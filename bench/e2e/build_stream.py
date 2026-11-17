"""전 구간 벤치마크에 넣을 연속 음성을 만든다.

음성 인식 벤치마크의 시험 발화(bench/build/stt, prepare_audio.py 가 만든 것)를 세트를 번갈아 가며 고르고,
사이에 무음을 넣어 한 파일로 잇는다. 발화마다 음성 속 시작·끝 시각과 기준 문장을 함께 적어 두어,
채점할 때 "말이 끝난 뒤 번역이 끝날 때까지" 걸린 시간과 오류율을 잴 수 있게 한다.
대화 문장 세트(conversation-tts)는 기준 한국어 번역(bench/data/conversation.jsonl)도 붙인다.

만드는 것: bench/build/e2e/<이름>.wav, <이름>.ref.jsonl

사용: python3 bench/e2e/build_stream.py [--per-set 6] [--sets ami-far earnings22 ...] [--name stream]
"""
import argparse
import json
import random
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
STT = HERE.parent / "build" / "stt"
OUT = HERE.parent / "build" / "e2e"
RATE = 16000
SEED = 20261117


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text("utf-8").splitlines() if line.strip()]


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (RATE, 1, 2), path
        return w.readframes(w.getnframes())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--per-set", type=int, default=6, help="세트마다 쓸 발화 수")
    parser.add_argument("--sets", nargs="*", help="쓸 세트 (생략하면 모두)")
    parser.add_argument("--gap", type=float, nargs=2, default=[1.2, 2.5], help="발화 사이 무음 길이 범위(초)")
    parser.add_argument("--name", default="stream")
    args = parser.parse_args()

    if not (STT / "input.jsonl").is_file():
        raise SystemExit("시험 음성이 없습니다. 먼저 python3 bench/stt/prepare_audio.py 를 실행하세요")
    korean = {row["id"]: row["ko_ref"] for row in read_jsonl(HERE.parent / "data" / "conversation.jsonl")}
    by_set = {}
    for item in read_jsonl(STT / "input.jsonl"):
        if not args.sets or item["set"] in args.sets:
            by_set.setdefault(item["set"], []).append(item)
    chosen = [group[i] for i in range(args.per_set) for group in by_set.values() if i < len(group)]
    if not chosen:
        raise SystemExit("고른 발화가 없습니다")

    rng = random.Random(SEED)
    silence = lambda sec: bytes(2 * int(sec * RATE))
    parts, refs, pos = [silence(1.0)], [], RATE
    for item in chosen:
        audio = read_wav(STT / "audio" / f"{item['id']}.wav")
        samples = len(audio) // 2
        ref = {"id": item["id"], "set": item["set"], "start": round(pos / RATE, 2),
               "end": round((pos + samples) / RATE, 2), "ref": item["ref"]}
        # 합성 음성의 source_id 는 "conv-001 (Samantha)" 꼴이다.
        source = item.get("source_id", "").split(" ")[0]
        if source in korean:
            ref["ko_ref"] = korean[source]
        refs.append(ref)
        gap = silence(rng.uniform(*args.gap))
        parts += [audio, gap]
        pos += samples + len(gap) // 2

    OUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT / f"{args.name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(parts))
    (OUT / f"{args.name}.ref.jsonl").write_text(
        "".join(json.dumps(ref, ensure_ascii=False) + "\n" for ref in refs), "utf-8")
    print(f"{OUT / (args.name + '.wav')}: 발화 {len(refs)}개, {pos / RATE / 60:.1f}분")


if __name__ == "__main__":
    main()
