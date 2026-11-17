"""음성 인식 벤치마크 결과를 채점해 요약(summary.md)을 만든다.

사용: python3 bench/stt/score.py [결과 폴더]      # 생략하면 가장 최근의 stt-* 결과

채점 기준
  오류율(WER)  기준 문장과 비교해 틀리거나 빠지거나 더 들어간 단어의 비율 (낮을수록 좋음).
               세트 전체의 오류 단어 수를 기준 단어 수로 나눈다.
  처리 속도    음성 1초를 처리하는 데 걸린 시간(초). 1보다 작아야 실시간으로 따라간다.
비교 전에 양쪽 문장을 같은 규칙으로 다듬는다 (대소문자·구두점 제거, 숫자 표기 통일, uh/um 같은 군말 제거).
whisper-normalizer 가 설치되어 있으면 그것으로(pip install whisper-normalizer), 없으면 내장된 간단한 규칙으로 한다.
내장 규칙은 숫자 표기(twenty / 20)를 맞추지 못하므로 숫자가 많은 세트의 오류율이 높게 나온다.
"""
import argparse
import json
import re
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SETS = {
    "conversation-tts": "대화 문장 (합성 음성)",
    "librispeech-other": "낭독",
    "ami-near": "회의, 가까운 마이크",
    "ami-far": "회의, 먼 마이크",
    "earnings22": "여러 억양 통화",
}
MODES = {"streaming": "스트리밍", "offline": "문장 단위"}
PAD_MS = 1500  # 실행기가 스트리밍 모델에 앞뒤로 붙여 넣는 무음 길이 (앞 0.5초, 뒤 1초)


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text("utf-8").splitlines() if line.strip()]


def make_normalizer():
    try:
        from whisper_normalizer.english import EnglishTextNormalizer
        normalizer = EnglishTextNormalizer()
        return (lambda text: normalizer(text).split()), "whisper-normalizer"
    except ImportError:
        fillers = {"uh", "um", "hmm", "mm", "mhm", "ah", "eh", "er"}

        def basic(text):
            text = text.lower().replace("-", " ")
            words = re.sub(r"[^a-z0-9' ]+", " ", text).split()
            return [w.strip("'") for w in words if w.strip("'") and w not in fillers]
        return basic, "내장 규칙 (숫자 표기는 맞추지 못함)"


def errors(ref, hyp):
    """단어 단위 편집 거리."""
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, start=1):
        previous, row[0] = row[0], i
        for j, h in enumerate(hyp, start=1):
            current = row[j]
            row[j] = min(row[j] + 1, row[j - 1] + 1, previous + (r != h))
            previous = current
    return row[-1]


def percentile(values, q):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(q * (len(ordered) - 1))))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", nargs="?")
    args = parser.parse_args()
    if args.result_dir:
        result_dir = Path(args.result_dir)
    else:
        candidates = sorted(p for p in (HERE.parent / "results").glob("stt-*") if p.is_dir())
        if not candidates:
            sys.exit("bench/results 에 stt 결과가 없습니다. 먼저 bench/stt/run.sh 를 실행하세요")
        result_dir = candidates[-1]

    items = read_jsonl(result_dir / "input.jsonl")
    engines = json.loads((result_dir / "engines.json").read_text("utf-8"))
    done = json.loads((result_dir / "done-stt.json").read_text("utf-8"))
    if "error" in done:
        sys.exit(f"실행 실패: {done['error']}")
    meta = {m["id"]: m for m in done["engines"]}
    normalize, method = make_normalizer()
    refs = {it["id"]: normalize(it["ref"]) for it in items}
    set_names = [s for s in SETS if any(it["set"] == s for it in items)]

    device = (result_dir / "device.txt").read_text().strip() if (result_dir / "device.txt").is_file() else "?"
    lines = [f"# 음성 인식 벤치마크 결과 ({result_dir.name}, {device})", ""]
    counts = ", ".join(f"{SETS[s]} {sum(1 for it in items if it['set'] == s)}개" for s in set_names)
    lines += [f"시험 음성: {counts}", "", "## 오류율 (WER %, 낮을수록 좋음)", "",
              "| 모델 | 방식 | " + " | ".join(SETS[s] for s in set_names) + " | 전체 |",
              "|---|---|" + "---|" * (len(set_names) + 1)]
    speed = ["## 속도와 자원", "",
             "| 모델 | 음성 1초당 처리 시간 (중앙값 / 90%) | 발화당 시간 중앙값 | 0.1초 조각 최대 처리 시간 (95%) "
             "| 올리는 시간 | 메모리 증가 | 모델 크기 | 스레드 |",
             "|---|---|---|---|---|---|---|---|"]
    for engine in engines:
        label, mode = engine["label"], engine["mode"]
        info = meta.get(engine["id"], {})
        output = result_dir / f"output-{engine['id']}.jsonl"
        if "error" in info or not output.is_file():
            lines.append(f"| {label} | {MODES[mode]} | 실패: {info.get('error', '결과 없음')} |")
            continue
        rows = {row["id"]: row for row in read_jsonl(output)}
        cells, all_errors, all_words = [], 0, 0
        for name in set_names:
            wrong = words = 0
            for it in items:
                if it["set"] == name and it["id"] in rows:
                    wrong += errors(refs[it["id"]], normalize(rows[it["id"]]["out"]))
                    words += len(refs[it["id"]])
            cells.append(f"{100 * wrong / words:.1f}" if words else "-")
            all_errors += wrong
            all_words += words
        if info.get("stopped_slow"):
            label += f" — 너무 느려 {len(rows)}개만 잼"
        lines.append(f"| {label} | {MODES[mode]} | " + " | ".join(cells) + f" | {100 * all_errors / all_words:.1f} |")

        tail = PAD_MS if mode == "streaming" else 0
        factors = [row["ms"] / (row["audio_ms"] + tail) for row in rows.values()]
        step = f"{percentile([row['step_max_ms'] for row in rows.values()], 0.95)}ms" if mode == "streaming" else "-"
        speed.append(
            f"| {label} | {statistics.median(factors):.2f}초 / {percentile(factors, 0.9):.2f}초 "
            f"| {statistics.median(row['ms'] for row in rows.values()):.0f}ms | {step} "
            f"| {info['load_ms'] / 1000:.1f}초 | {(info['peak_pss_kb'] - info['base_pss_kb']) / 1024:.0f}MB "
            f"| {engine.get('bytes', 0) / 1e6:.0f}MB | {info['threads']} |")
    lines += ["", f"문장 다듬기: {method}", ""] + speed + [""]

    text = "\n".join(lines)
    (result_dir / "summary.md").write_text(text, "utf-8")
    print(text)


if __name__ == "__main__":
    main()
