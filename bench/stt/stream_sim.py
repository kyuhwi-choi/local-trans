"""앱의 인식 흐름을 Mac 에서 그대로 흉내 내어, 연속으로 이어지는 음성에서 구간이 어떻게 끊기는지 잰다.

run.sh 는 발화를 하나씩 따로 인식해 정확도와 속도를 재지만, 실제 앱은 끊이지 않는 음성에서 구간을 스스로 끊는다.
이 스크립트는 시험 발화를 무음을 사이에 두고 한 줄로 이어 붙인 뒤 앱과 같은 방식(0.1초 조각, 같은 구간 끊기 규칙)으로
흘려보내고, 끊긴 구간마다 2단계 모델로 다시 인식한다. 시간은 모두 음성 속 시각이라 Mac 에서 돌려도 폰과 같다.

재는 것
  구간 끊김  발화가 끝난 뒤 다음 발화가 시작되기 전에 구간이 끊긴 비율, 그때까지 걸린 시간
  놓친 발화  어느 구간에도 글자가 잡히지 않은 발화
  오류율     스트리밍 결과와 2단계 결과를 각각 이어 붙여 전체 기준 문장과 비교한 WER

사용: python3 bench/stt/stream_sim.py [스트리밍 모델 id ...] [--second parakeet-110m] [--per-set 20]
      (pip install sherpa-onnx numpy whisper-normalizer 필요, 모델은 fetch_models.py 가 받아 둔 것을 쓴다)
"""
import argparse
import json
import random
import statistics
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

from score import errors, make_normalizer

HERE = Path(__file__).resolve().parent
BUILD = HERE.parent / "build" / "stt"
MODELS = Path.home() / ".cache" / "local-trans-bench" / "stt-models"
RATE = 16000
CHUNK = RATE // 10
SEED = 20261117
# 앱 설정(remote/config.json 의 stt)과 같은 값
ENDPOINT_SILENCE, IDLE_SILENCE, MAX_UTTERANCE = 0.6, 2.4, 8.0
# 글자 없이 끊긴 구간에서도 마지막 이만큼은 남겨 다음 구간 앞에 붙인다 (앱의 StreamingStt.KEEP_ON_EMPTY_SEC).
KEEP_ON_EMPTY = 1.5


def load_engines():
    return {e["id"]: e for e in json.loads((HERE / "engines.json").read_text("utf-8"))}


def model_path(engine, role):
    return str(MODELS / engine["id"] / engine["files"][role])


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (RATE, 1, 2), path
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def build_stream(per_set, gap_min, gap_max):
    """발화를 세트를 번갈아 가며 고르고, 사이에 무음을 넣어 한 줄로 잇는다. (음성, [(시작, 끝, 기준 문장)])"""
    items = [json.loads(line) for line in (BUILD / "input.jsonl").read_text("utf-8").splitlines() if line.strip()]
    by_set = {}
    for item in items:
        by_set.setdefault(item["set"], []).append(item)
    chosen = [group[i] for i in range(per_set) for group in by_set.values() if i < len(group)]
    rng = random.Random(SEED)
    parts, clips, pos = [np.zeros(RATE, dtype=np.float32)], [], RATE
    for item in chosen:
        audio = read_wav(BUILD / "audio" / f"{item['id']}.wav")
        clips.append((pos / RATE, (pos + len(audio)) / RATE, item["ref"]))
        gap = np.zeros(int(rng.uniform(gap_min, gap_max) * RATE), dtype=np.float32)
        parts += [audio, gap]
        pos += len(audio) + len(gap)
    return np.concatenate(parts), clips


def simulate(engine, audio, second, keep_on_empty):
    """앱의 StreamingStt 와 같은 순서로 넣고 끊는다. [(구간 시작, 끊긴 시각, 스트리밍 문장, 2단계 문장)]"""
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=model_path(engine, "tokens"), encoder=model_path(engine, "encoder"),
        decoder=model_path(engine, "decoder"), joiner=model_path(engine, "joiner"),
        num_threads=2, sample_rate=RATE, feature_dim=80, decoding_method="greedy_search",
        enable_endpoint_detection=True, rule1_min_trailing_silence=IDLE_SILENCE,
        rule2_min_trailing_silence=ENDPOINT_SILENCE, rule3_min_utterance_length=MAX_UTTERANCE,
        model_type=engine.get("modelType", ""),
    )
    stream = recognizer.create_stream()
    segments, segment_start = [], 0
    for pos in range(0, len(audio), CHUNK):
        stream.accept_waveform(RATE, audio[pos:pos + CHUNK])
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        if recognizer.is_endpoint(stream):
            text = recognizer.get_result(stream).strip()
            recognizer.reset(stream)
            end = min(pos + CHUNK, len(audio))
            if text:
                refined = ""
                if second is not None:
                    offline = second.create_stream()
                    offline.accept_waveform(RATE, audio[segment_start:end])
                    second.decode_stream(offline)
                    refined = offline.result.text.strip()
                segments.append((segment_start / RATE, end / RATE, text, refined))
                segment_start = end
            else:
                # 말이 막 시작될 때 글자 없이 끊기는 일이 있어, 버리면 다음 구간의 첫 단어를 잃는다.
                segment_start = max(segment_start, end - int(keep_on_empty * RATE))
    return segments


def report(label, clips, segments, normalize, use_second):
    refs = [word for _, _, ref in clips for word in normalize(ref)]
    cut_delays, missed = [], 0
    for index, (start, end, _) in enumerate(clips):
        next_start = clips[index + 1][0] if index + 1 < len(clips) else float("inf")
        # 발화가 끝난 뒤, 다음 발화가 시작되기 전에 끊긴 구간이 있는가
        cuts = [seg_end for _, seg_end, _, _ in segments if end <= seg_end <= next_start]
        if cuts:
            cut_delays.append(min(cuts) - end)
        if not any(seg_start < end and seg_end > start for seg_start, seg_end, _, _ in segments):
            missed += 1
    first = [word for _, _, text, _ in segments for word in normalize(text)]
    row = [label, f"{len(segments)}", f"{100 * len(cut_delays) / len(clips):.0f}%",
           f"{statistics.median(cut_delays):.1f}초 / {sorted(cut_delays)[int(0.9 * (len(cut_delays) - 1))]:.1f}초"
           if cut_delays else "-",
           f"{missed}", f"{100 * errors(refs, first) / len(refs):.1f}"]
    if use_second:
        refined = [word for _, _, text, better in segments for word in normalize(better or text)]
        row.append(f"{100 * errors(refs, refined) / len(refs):.1f}")
    return "| " + " | ".join(row) + " |"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ids", nargs="*", help="스트리밍 모델 id (생략하면 engines.json 의 스트리밍 모델 모두)")
    parser.add_argument("--second", default="parakeet-110m", help="2단계 모델 id (none 이면 쓰지 않음)")
    parser.add_argument("--per-set", type=int, default=20, help="세트마다 쓸 발화 수")
    parser.add_argument("--gap", type=float, nargs=2, default=[1.2, 2.5], help="발화 사이 무음 길이 범위(초)")
    parser.add_argument("--show", action="store_true", help="구간별 문장도 출력")
    parser.add_argument("--keep-on-empty", type=float, default=KEEP_ON_EMPTY,
                        help="글자 없이 끊긴 구간에서 남길 음성 길이(초). 0 이면 모두 버린다")
    args = parser.parse_args()

    engines = load_engines()
    ids = args.ids or [i for i, e in engines.items() if e["mode"] == "streaming" and (MODELS / i).is_dir()]
    second = None
    if args.second != "none":
        spec = engines[args.second]
        second = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=model_path(spec, "encoder"), decoder=model_path(spec, "decoder"),
            joiner=model_path(spec, "joiner"), tokens=model_path(spec, "tokens"),
            num_threads=2, sample_rate=RATE, feature_dim=80, decoding_method="greedy_search",
            model_type=spec.get("modelType", ""),
        )
    audio, clips = build_stream(args.per_set, *args.gap)
    normalize, _ = make_normalizer()
    print(f"발화 {len(clips)}개, {len(audio) / RATE / 60:.1f}분, 발화 사이 무음 {args.gap[0]}~{args.gap[1]}초")
    header = ["스트리밍 모델", "구간 수", "제때 끊김", "끊길 때까지 (중앙값 / 90%)", "놓친 발화", "스트리밍 WER"]
    if second is not None:
        header.append(f"2단계 WER ({args.second})")
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * len(header))
    for engine_id in ids:
        segments = simulate(engines[engine_id], audio, second, args.keep_on_empty)
        print(report(engines[engine_id]["label"], clips, segments, normalize, second is not None), flush=True)
        if args.show:
            for start, end, text, refined in segments:
                print(f"    {start:7.1f}~{end:6.1f}  {text}  =>  {refined}")


if __name__ == "__main__":
    main()
