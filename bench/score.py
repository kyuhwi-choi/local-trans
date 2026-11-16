"""벤치마크 결과를 채점해 요약(summary.md)을 만든다.

사용:
  python3 bench/score.py [결과 폴더]                  # 생략하면 가장 최근 결과
  python3 bench/score.py [결과 폴더] --judge-sheet    # 블라인드 평가용 파일 만들기
  python3 bench/score.py [결과 폴더] --judge-result judge-output.jsonl   # 블라인드 평가 집계

채점 기준
  chrF2   기준 번역과 글자 단위로 얼마나 겹치는지 (0~100, 높을수록 좋음). 띄어쓰기·형태소 분석에
          좌우되지 않아 한국어에 쓰기 좋지만, 같은 뜻의 다른 표현은 낮게 나온다.
  시간    문장 하나를 번역하는 데 걸린 시간 (중앙값, 90% 지점)
  블라인드 어느 엔진인지 가린 두 번역 중 더 나은 쪽을 고른 결과
sacrebleu 가 설치되어 있으면 그것으로, 없으면 같은 방식의 내장 구현으로 chrF2 를 계산한다.
"""
import argparse
import json
import random
import statistics
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
ENGINES = {"gemma": "Gemma 4 E2B (이 앱)", "mlkit": "ML Kit (구글 번역 오프라인 모델)"}
SETS = {
    "conversation": "대화체 문장",
    "asr": "음성 인식 형태 (구두점 없음)",
    "flores": "FLORES-200 (뉴스·위키 문장)",
}
JUDGED_SETS = ("conversation", "asr")
SEED = 20261116


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text("utf-8").splitlines() if line.strip()]


def chrf(hyps, refs):
    try:
        import sacrebleu
        return sacrebleu.corpus_chrf(hyps, [refs]).score, f"sacrebleu {sacrebleu.__version__}"
    except ImportError:
        return builtin_chrf(hyps, refs), "내장 구현"


def builtin_chrf(hyps, refs, order=6, beta=2.0):
    """sacrebleu 의 chrF2 와 같은 방식: 공백을 뺀 글자 n-그램(1~6), 차수별 F 점수의 평균."""
    def grams(text, n):
        chars = "".join(text.split())
        return Counter(chars[i:i + n] for i in range(len(chars) - n + 1))

    total, effective = 0.0, 0
    for n in range(1, order + 1):
        match = hyp_len = ref_len = 0
        for hyp, ref in zip(hyps, refs):
            h, r = grams(hyp, n), grams(ref, n)
            match += sum((h & r).values())
            hyp_len += sum(h.values())
            ref_len += sum(r.values())
        if hyp_len == 0 or ref_len == 0:
            continue
        effective += 1
        precision, recall = match / hyp_len, match / ref_len
        if precision + recall > 0:
            total += (1 + beta ** 2) * precision * recall / (beta ** 2 * precision + recall)
    return 100 * total / effective if effective else 0.0


def percentile(values, q):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(q * (len(ordered) - 1))))]


def peak_mb(path):
    if not path.is_file():
        return None
    values = [int(line.split()[2]) for line in path.read_text().splitlines() if "TOTAL PSS" in line]
    return max(values) / 1024 if values else None


def load(result_dir):
    items = read_jsonl(result_dir / "input.jsonl")
    outputs, meta = {}, {}
    for engine in ENGINES:
        out = result_dir / f"output-{engine}.jsonl"
        done = result_dir / f"done-{engine}.json"
        if out.is_file():
            outputs[engine] = {row["id"]: row for row in read_jsonl(out)}
        if done.is_file():
            meta[engine] = json.loads(done.read_text("utf-8"))
    return items, outputs, meta


def summarize(result_dir):
    items, outputs, meta = load(result_dir)
    if not outputs:
        sys.exit(f"{result_dir} 에 엔진 출력이 없습니다")
    device = (result_dir / "device.txt").read_text().strip() if (result_dir / "device.txt").is_file() else "?"
    lines = [f"# 번역 벤치마크 결과 ({result_dir.name}, {device})", ""]

    method = ""
    lines += ["## 품질과 속도", "", "| 세트 | 엔진 | 문장 수 | chrF2 | 시간 중앙값 | 시간 90% | 빈 출력 |", "|---|---|---|---|---|---|---|"]
    for set_name, set_label in SETS.items():
        rows = [it for it in items if it["set"] == set_name]
        for engine, label in ENGINES.items():
            got = [(it, outputs[engine][it["id"]]) for it in rows if engine in outputs and it["id"] in outputs[engine]]
            if not got:
                continue
            score, method = chrf([o["out"] for _, o in got], [it["ko_ref"] for it, _ in got])
            times = [o["ms"] for _, o in got]
            empty = sum(1 for _, o in got if not o["out"].strip())
            lines.append(
                f"| {set_label} | {label} | {len(got)} | {score:.1f} | {statistics.median(times):.0f}ms "
                f"| {percentile(times, 0.9):.0f}ms | {empty} |"
            )
    lines += ["", f"chrF2 계산: {method}", ""]

    lines += ["## 자원", "", "| 엔진 | 준비 시간 | 메모리 최대 | 비고 |", "|---|---|---|---|"]
    for engine, label in ENGINES.items():
        if engine not in meta:
            continue
        m = meta[engine]
        if "error" in m:
            lines.append(f"| {label} | - | - | 실패: {m['error']} |")
            continue
        peak = peak_mb(result_dir / f"mem-{engine}.txt")
        note = []
        if "backend" in m:
            note.append(f"백엔드 {m['backend'].upper()}")
        if "model_bytes" in m:
            note.append(f"모델 {m['model_bytes'] / 1e9:.2f}GB")
        if engine == "gemma" and engine in outputs:
            firsts = [o["first_ms"] for o in outputs[engine].values() if o.get("first_ms", -1) >= 0]
            if firsts:
                note.append(f"첫 글자까지 중앙값 {statistics.median(firsts):.0f}ms")
        lines.append(
            f"| {label} | {m.get('load_ms', 0) / 1000:.1f}초 | "
            f"{f'{peak:.0f}MB' if peak else '-'} | {', '.join(note)} |"
        )
    lines.append("")

    judged = result_dir / "judge-tally.json"
    if judged.is_file():
        tally = json.loads(judged.read_text("utf-8"))
        lines += ["## 블라인드 평가", "", f"평가자: {tally.get('judge', '?')}", "",
                  "| 세트 | Gemma 우세 | ML Kit 우세 | 비슷함 |", "|---|---|---|---|"]
        for set_name in JUDGED_SETS:
            t = tally["sets"].get(set_name)
            if t:
                lines.append(f"| {SETS[set_name]} | {t.get('gemma', 0)} | {t.get('mlkit', 0)} | {t.get('tie', 0)} |")
        lines.append("")

    text = "\n".join(lines)
    (result_dir / "summary.md").write_text(text, "utf-8")
    print(text)


def judge_sheet(result_dir):
    items, outputs, _ = load(result_dir)
    if set(outputs) != set(ENGINES):
        sys.exit("블라인드 평가에는 두 엔진의 출력이 모두 필요합니다")
    rng = random.Random(SEED)
    sheet, key, readable = [], {}, ["# 블라인드 평가지", "", "각 문항에서 더 나은 번역을 고른다 (A / B / 비슷함).", ""]
    for it in items:
        if it["set"] not in JUDGED_SETS:
            continue
        order = ["gemma", "mlkit"]
        rng.shuffle(order)
        a, b = (outputs[e][it["id"]]["out"] for e in order)
        key[it["id"]] = {"A": order[0], "B": order[1]}
        sheet.append({"id": it["id"], "context": it["context"], "en": it["en"], "A": a, "B": b})
        readable += [f"### {it['id']}", ""]
        if it["context"]:
            readable += ["앞 문장: " + " / ".join(it["context"]), ""]
        readable += [f"영어: {it['en']}", "", f"- A: {a}", f"- B: {b}", ""]
    (result_dir / "judge-input.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in sheet), "utf-8")
    (result_dir / "judge-key.json").write_text(json.dumps(key, ensure_ascii=False, indent=1), "utf-8")
    (result_dir / "blind.md").write_text("\n".join(readable), "utf-8")
    print(f"{len(sheet)}문항: {result_dir / 'judge-input.jsonl'} (사람이 읽을 용도: blind.md, 정답표: judge-key.json)")


def judge_result(result_dir, path, judge):
    items = {it["id"]: it for it in read_jsonl(result_dir / "input.jsonl")}
    key = json.loads((result_dir / "judge-key.json").read_text("utf-8"))
    tally = {}
    for row in read_jsonl(path):
        winner = str(row.get("winner", "")).strip().upper()
        engine = key[row["id"]].get(winner, "tie")
        tally.setdefault(items[row["id"]]["set"], Counter())[engine] += 1
    out = {"judge": judge, "sets": {name: dict(counts) for name, counts in tally.items()}}
    (result_dir / "judge-tally.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), "utf-8")
    print(json.dumps(out, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", nargs="?")
    parser.add_argument("--judge-sheet", action="store_true")
    parser.add_argument("--judge-result")
    parser.add_argument("--judge", default="?", help="블라인드 평가자 (요약에 표시)")
    args = parser.parse_args()

    if args.result_dir:
        result_dir = Path(args.result_dir)
    else:
        candidates = sorted(p for p in (HERE / "results").glob("*") if p.is_dir())
        if not candidates:
            sys.exit("bench/results 에 결과가 없습니다. 먼저 bench/run.sh 를 실행하세요")
        result_dir = candidates[-1]

    if args.judge_sheet:
        judge_sheet(result_dir)
    elif args.judge_result:
        judge_result(result_dir, args.judge_result, args.judge)
        summarize(result_dir)
    else:
        summarize(result_dir)


if __name__ == "__main__":
    main()
