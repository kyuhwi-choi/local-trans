"""통역 전 구간 벤치마크(bench/e2e/run.sh)의 결과를 채점한다.

사용: python3 bench/e2e/score.py [결과 폴더 ...]     # 생략하면 가장 최근의 e2e-* 결과. 여러 개를 적으면 나란히 보여 준다

재는 것 (시각은 모두 음성이 시작된 뒤로 흐른 시간이라, 음성 속 발화 위치와 바로 견줄 수 있다)
  말 끝 → 확정      발화가 끝난 뒤 구간이 끊길 때까지. 기준 발화 위치(.ref.jsonl)가 있을 때만 잰다.
  말 끝 → 번역 끝   발화가 끝난 뒤 그 번역이 다 나올 때까지. 듣는 사람이 느끼는 지연이다.
  확정 → 번역 끝    구간이 끊긴 뒤 번역이 다 나올 때까지 (2단계 인식 + 번역). 미리 번역한 구간은 짧게 나온다.
  제때 끊김         발화가 끝난 뒤 다음 발화가 시작되기 전에 구간이 끊긴 비율
  WER               스트리밍 결과와 2단계 결과를 각각 이어 붙여 기준 문장 전체와 비교한 단어 오류율
  chrF2             기준 한국어 번역이 있는 발화(대화 문장 세트)의 번역 품질. bench/README.md 의 주의점 참고
"""
import argparse
import json
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "stt"))
from score import errors, make_normalizer  # noqa: E402  (bench/stt/score.py)

RESULTS = HERE.parent / "results"


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text("utf-8").splitlines() if line.strip()]


def chrf(hyps, refs):
    """bench/score.py 의 chrF2 (sacrebleu 가 있으면 그것으로)."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("translation_score", HERE.parent / "score.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.chrf(hyps, refs)


def spread(values):
    if not values:
        return "-"
    ordered = sorted(values)
    p90 = ordered[min(len(ordered) - 1, int(round(0.9 * (len(ordered) - 1))))]
    return f"{statistics.median(ordered) / 1000:.1f}초 / {p90 / 1000:.1f}초"


def score_run(log_path, normalize):
    rows = read_jsonl(log_path)
    session = next((row for row in rows if row["type"] == "session"), {})
    utterances = [row for row in rows if row["type"] == "utterance"]
    stem = log_path.with_suffix("")
    result = {
        "name": log_path.stem,
        "speculate": session.get("stt", {}).get("speculateSilenceSec", "?"),
        "count": len(utterances),
        "speculated": sum(1 for u in utterances if u.get("speculated")),
        "translate": [u["ms"] - u["finalMs"] for u in utterances],
        "cut": [], "heard": [], "timely": None, "wer": None, "chrf": None,
    }

    clips = read_jsonl(f"{stem}.ref.jsonl") if Path(f"{stem}.ref.jsonl").is_file() else []
    ref_words = [word for clip in clips for word in normalize(clip["ref"])]
    if not clips and Path(f"{stem}.ref.txt").is_file():
        ref_words = normalize(Path(f"{stem}.ref.txt").read_text("utf-8"))
    if ref_words:
        streaming = [word for u in utterances for word in normalize(u["streaming"])]
        refined = [word for u in utterances for word in normalize(u["english"])]
        result["wer"] = (100 * errors(ref_words, streaming) / len(ref_words),
                         100 * errors(ref_words, refined) / len(ref_words))

    for index, clip in enumerate(clips):
        next_start = clips[index + 1]["start"] if index + 1 < len(clips) else float("inf")
        # 발화가 끝난 뒤, 다음 발화가 시작되기 전에 끊긴 첫 구간
        after = [u for u in utterances if clip["end"] * 1000 <= u["finalMs"] <= next_start * 1000]
        if after:
            first = min(after, key=lambda u: u["finalMs"])
            result["cut"].append(first["finalMs"] - clip["end"] * 1000)
            result["heard"].append(first["ms"] - clip["end"] * 1000)
    if clips:
        result["timely"] = 100 * len(result["cut"]) / len(clips)

    # 번역 품질: 구간을 음성 속 위치가 가장 많이 겹치는 발화에 붙이고, 기준 번역이 있는 발화만 본다.
    korean = {}
    for u in utterances:
        end = u["finalMs"] / 1000
        start = end - u.get("audioSec", 0)
        overlap = lambda clip: min(end, clip["end"]) - max(start, clip["start"])
        best = max(clips, key=overlap, default=None)
        if best is not None and overlap(best) > 0:
            korean.setdefault(best["id"], []).append(u["korean"])
    with_ref = [clip for clip in clips if "ko_ref" in clip]
    if with_ref:
        result["chrf"] = chrf([" ".join(korean.get(clip["id"], [])) for clip in with_ref],
                              [clip["ko_ref"] for clip in with_ref])
        result["chrf_count"] = len(with_ref)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dirs", nargs="*", type=Path)
    args = parser.parse_args()
    dirs = args.dirs or sorted(RESULTS.glob("e2e-*"))[-1:]
    if not dirs:
        sys.exit("결과 폴더가 없습니다. 먼저 bench/e2e/run.sh 를 실행하세요")
    normalize, normalizer_name = make_normalizer()

    header = ["실행", "음성", "미리 번역 기준", "구간 수", "미리 번역한 구간", "제때 끊김", "말 끝 → 확정 (중앙값 / 90%)",
              "말 끝 → 번역 끝", "확정 → 번역 끝", "스트리밍 WER", "2단계 WER", "chrF2"]
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    chrf_method = ""
    for result_dir in dirs:
        logs = sorted(p for p in result_dir.glob("*.jsonl") if not p.name.endswith(".ref.jsonl"))
        if not logs:
            sys.exit(f"{result_dir} 에 통역 기록이 없습니다")
        for log_path in logs:
            r = score_run(log_path, normalize)
            wer = [f"{r['wer'][0]:.1f}", f"{r['wer'][1]:.1f}"] if r["wer"] else ["-", "-"]
            quality = "-"
            if r["chrf"]:
                quality, chrf_method = f"{r['chrf'][0]:.1f} ({r['chrf_count']}문장)", r["chrf"][1]
            lines.append("| " + " | ".join([
                result_dir.name, r["name"],
                "끔" if r["speculate"] == 0 else f"{r['speculate']}초",
                str(r["count"]), f"{r['speculated']}",
                "-" if r["timely"] is None else f"{r['timely']:.0f}%",
                spread(r["cut"]), spread(r["heard"]), spread(r["translate"]), *wer, quality,
            ]) + " |")
    lines += ["", f"문장 다듬기: {normalizer_name}" + (f", chrF2 계산: {chrf_method}" if chrf_method else "")]
    text = "\n".join(lines) + "\n"
    print(text)
    if len(dirs) == 1:
        (dirs[0] / "summary.md").write_text(text, "utf-8")


if __name__ == "__main__":
    main()
