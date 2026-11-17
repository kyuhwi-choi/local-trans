"""음성 인식 벤치마크 입력(bench/build/stt/)을 만든다.

세트
  ami-near           회의 대화, 머리에 쓴 마이크 (hf-audio/open-asr-leaderboard 의 ami)
  ami-far            회의 대화, 탁자 위 마이크 한 개 (edinburghcstr/ami 의 sdm). ami-near 와 같은 발화는 아니다.
  earnings22         여러 나라 억양의 실적 발표 통화 (hf-audio/open-asr-leaderboard)
  librispeech-other  낭독 음성 중 어려운 쪽 (hf-audio/open-asr-leaderboard)
  conversation-tts   bench/data/conversation.jsonl 의 문장을 macOS 합성 음성으로 읽은 것

공개 세트는 Hugging Face datasets-server 에서 필요한 발화만 받아 ~/.cache/local-trans-bench/stt-audio 에 둔다.
모든 음성은 16kHz 모노 16비트 wav 로 바꾼다 (macOS 의 afconvert, say 사용).

사용: python3 bench/stt/prepare_audio.py [--per-set 50]
"""
import argparse
import json
import random
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "build" / "stt"
CACHE = Path.home() / ".cache" / "local-trans-bench" / "stt-audio"
API = "https://datasets-server.huggingface.co/rows"
SEED = 20261117
MIN_SEC, MAX_SEC, MIN_WORDS = 3.0, 12.0, 5

# (세트, 데이터셋, config, split, 살펴볼 시작 위치들)
# open-asr-leaderboard 는 긴 발화부터 정렬되어 있어, 3~12초 발화가 있는 위치를 골랐다.
SOURCES = [
    ("ami-near", "hf-audio/open-asr-leaderboard", "ami", "test", [300, 900, 1800]),
    ("ami-far", "edinburghcstr/ami", "sdm", "test", [700, 2900, 5000, 7300, 9600, 11800]),
    ("earnings22", "hf-audio/open-asr-leaderboard", "earnings22", "test", [500, 1000, 1500]),
    ("librispeech-other", "hf-audio/open-asr-leaderboard", "librispeech", "test.other", [600, 1200, 1800]),
]
# 합성 음성 세트에 돌려 쓰는 목소리 (설치된 것만 쓴다)
VOICES = ["Samantha", "Daniel", "Karen", "Rishi", "Moira", "Tessa"]


def fetch_json(url, tries=5):
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=180) as response:
                return json.load(response)
        except Exception as error:  # 서버가 가끔 5xx 를 낸다
            last = error
            time.sleep(3 * (attempt + 1))
    raise SystemExit(f"받지 못했습니다: {url}\n{last}")


def download(url, dest, tries=4):
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=180) as response:
                dest.write_bytes(response.read())
            return True
        except Exception:
            time.sleep(2 * (attempt + 1))
    return False


def to_wav(src, dest):
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(src), str(dest)], check=True)


def wav_seconds(path):
    return max(0, path.stat().st_size - 44) / (16000 * 2)


def candidates(dataset, config, split, offsets):
    """조건에 맞는 발화 후보를 (원래 id, 기준 문장, 음성 주소, 확장자) 로 돌려준다."""
    found = []
    for offset in offsets:
        query = urllib.parse.urlencode(
            {"dataset": dataset, "config": config, "split": split, "offset": offset, "length": 100})
        for item in fetch_json(f"{API}?{query}").get("rows", []):
            row = item["row"]
            text = row["text"].strip()
            if "audio_length_s" in row:
                seconds = row["audio_length_s"]
            else:
                seconds = row["end_time"] - row["begin_time"]
            if not (MIN_SEC <= seconds <= MAX_SEC) or len(text.split()) < MIN_WORDS:
                continue
            audio = row["audio"][0]
            found.append({
                "source_id": row.get("id") or row.get("audio_id"),
                "ref": text,
                "url": audio["src"],
                "ext": "flac" if "flac" in audio["type"] else "wav",
            })
        print(f"  {config} offset {offset}: 후보 누적 {len(found)}")
    return found


def public_set(name, dataset, config, split, offsets, count):
    folder = CACHE / name
    folder.mkdir(parents=True, exist_ok=True)
    manifest = folder / "manifest.json"
    if manifest.is_file():
        rows = json.loads(manifest.read_text("utf-8"))
        if len(rows) >= count and all((folder / r["file"]).is_file() for r in rows[:count]):
            return rows[:count]

    print(f"{name}: 발화 목록 받는 중")
    pool = candidates(dataset, config, split, offsets)
    random.Random(SEED).shuffle(pool)
    rows = []
    for item in pool:
        if len(rows) >= count:
            break
        index = len(rows) + 1
        raw = folder / f"{index:03d}.{item['ext']}"
        wav = folder / f"{index:03d}.wav"
        if not download(item["url"], raw):
            continue
        if raw != wav:
            to_wav(raw, wav)
            raw.unlink()
        else:
            fixed = folder / f"{index:03d}.tmp.wav"
            to_wav(raw, fixed)
            fixed.replace(wav)
        rows.append({"file": wav.name, "ref": item["ref"], "source_id": item["source_id"]})
    if len(rows) < count:
        print(f"  경고: {name} 은 {len(rows)}개만 구했습니다 (목표 {count})")
    manifest.write_text(json.dumps(rows, ensure_ascii=False, indent=1), "utf-8")
    return rows


def tts_set():
    folder = CACHE / "conversation-tts"
    folder.mkdir(parents=True, exist_ok=True)
    installed = subprocess.run(["say", "-v", "?"], capture_output=True, text=True).stdout
    voices = [v for v in VOICES if any(line.startswith(v + " ") for line in installed.splitlines())]
    if not voices:
        print("  경고: 쓸 수 있는 영어 합성 음성이 없어 conversation-tts 세트를 건너뜁니다")
        return []
    lines = (HERE.parent / "data" / "conversation.jsonl").read_text("utf-8").splitlines()
    rows = []
    for index, line in enumerate(filter(None, lines), start=1):
        item = json.loads(line)
        voice = voices[(index - 1) % len(voices)]
        wav = folder / f"{index:03d}-{voice}.wav"
        if not wav.is_file():
            aiff = folder / f"{index:03d}.aiff"
            subprocess.run(["say", "-v", voice, "-o", str(aiff), item["en"]], check=True)
            to_wav(aiff, wav)
            aiff.unlink()
        rows.append({"file": wav.name, "ref": item["en"], "source_id": f"{item['id']} ({voice})"})
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--per-set", type=int, default=50, help="공개 세트마다 쓸 발화 수")
    args = parser.parse_args()

    sets = {name: public_set(name, dataset, config, split, offsets, args.per_set)
            for name, dataset, config, split, offsets in SOURCES}
    sets["conversation-tts"] = tts_set()

    audio_dir = OUT / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)
    for old in audio_dir.glob("*.wav"):
        old.unlink()
    items = []
    for name, rows in sets.items():
        total = 0.0
        for index, row in enumerate(rows, start=1):
            item_id = f"{name}-{index:03d}"
            source = CACHE / name / row["file"]
            (audio_dir / f"{item_id}.wav").write_bytes(source.read_bytes())
            seconds = wav_seconds(source)
            total += seconds
            items.append({"id": item_id, "set": name, "ref": row["ref"], "sec": round(seconds, 2),
                          "source_id": row["source_id"]})
        print(f"{name}: {len(rows)}개, {total / 60:.1f}분")
    (OUT / "input.jsonl").write_text("".join(json.dumps(i, ensure_ascii=False) + "\n" for i in items), "utf-8")
    print(f"{OUT / 'input.jsonl'} — 모두 {len(items)}개")


if __name__ == "__main__":
    main()
