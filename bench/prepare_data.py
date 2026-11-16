"""벤치마크 입력 파일(bench/build/input.jsonl)을 만든다.

세 가지 세트를 한 파일에 담는다.
  conversation  bench/data/conversation.jsonl 의 대화체 문장 (같은 대화의 직전 두 문장을 맥락으로 붙임)
  asr           같은 문장을 음성 인식 결과처럼 구두점 없이 바꾼 것
  flores        공개 평가 세트 FLORES-200 devtest 영→한 앞쪽 N문장 (--flores N, 0 이면 생략)

사용: python3 bench/prepare_data.py [--flores 150]
"""
import argparse
import json
import re
import tarfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = Path.home() / ".cache" / "local-trans-bench" / "flores"
FLORES_URL = "https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz"
FLORES_FILES = {
    "en": "./flores200_dataset/devtest/eng_Latn.devtest",
    "ko": "./flores200_dataset/devtest/kor_Hang.devtest",
}
CONTEXT_TURNS = 2  # 앱의 llm.contextUtterances 기본값과 같다


def asr_style(text: str) -> str:
    """앱의 음성 인식 출력처럼 만든다: 구두점 없음, 첫 글자만 대문자 (I 는 유지)."""
    words = re.sub(r"[^A-Za-z0-9' ]+", " ", text).split()
    words = [w if w == "I" or w.startswith("I'") else w.lower() for w in words]
    line = " ".join(words)
    return line[:1].upper() + line[1:]


def conversation():
    rows = [json.loads(line) for line in (HERE / "data" / "conversation.jsonl").read_text("utf-8").splitlines() if line]
    out = []
    for style in ("conversation", "asr"):
        history = {}
        for row in rows:
            english = row["en"] if style == "conversation" else asr_style(row["en"])
            previous = history.setdefault(row["dialogue"], [])
            out.append({
                "id": row["id"] if style == "conversation" else row["id"].replace("conv", "asr"),
                "set": style,
                "en": english,
                "ko_ref": row["ko_ref"],
                "context": previous[-CONTEXT_TURNS:],
            })
            previous.append(english)
    return out


def flores(count: int):
    CACHE.mkdir(parents=True, exist_ok=True)
    paths = {lang: CACHE / Path(member).name for lang, member in FLORES_FILES.items()}
    if not all(p.is_file() for p in paths.values()):
        archive = CACHE / "flores200_dataset.tar.gz"
        if not archive.is_file():
            print("FLORES-200 받는 중 (약 25MB)…")
            urllib.request.urlretrieve(FLORES_URL, archive)
        with tarfile.open(archive) as tar:
            for lang, member in FLORES_FILES.items():
                # 필요한 두 파일만 내용으로 꺼내 쓴다 (압축 안의 경로를 그대로 풀지 않는다).
                paths[lang].write_bytes(tar.extractfile(member).read())
    english = paths["en"].read_text("utf-8").splitlines()
    korean = paths["ko"].read_text("utf-8").splitlines()
    assert len(english) == len(korean), "FLORES 영어·한국어 줄 수가 다릅니다"
    return [
        {"id": "flores-%04d" % (i + 1), "set": "flores", "en": english[i], "ko_ref": korean[i], "context": []}
        for i in range(min(count, len(english)))
    ]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--flores", type=int, default=150, help="FLORES 문장 수 (0 이면 넣지 않음)")
    args = parser.parse_args()

    rows = conversation()
    if args.flores > 0:
        rows += flores(args.flores)
    out = HERE / "build" / "input.jsonl"
    out.parent.mkdir(exist_ok=True)
    out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), "utf-8")
    counts = {}
    for r in rows:
        counts[r["set"]] = counts.get(r["set"], 0) + 1
    print(f"{out} — {counts}")


if __name__ == "__main__":
    main()
