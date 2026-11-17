#!/bin/bash
# 폰에서 음성 인식 벤치마크를 돌리고 결과를 bench/results/stt-<시각>/ 으로 가져온다.
# 사용: bench/stt/run.sh [모델 id ...]      (생략하면 engines.json 의 기본 모델 모두)
#       THREADS=4 bench/stt/run.sh ...      (스레드 수를 바꿔서 재기)
#       LIMIT=20 bench/stt/run.sh ...       (발화 20개만 재서 빨리 훑어보기)
# 먼저 python3 bench/stt/prepare_audio.py 로 시험 음성을 만들어 둔다. 폰은 잠금이 풀려 있어야 한다.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/env.sh

BUILD=bench/build/stt
[ -f "$BUILD/input.jsonl" ] || die "시험 음성이 없습니다. 먼저 python3 bench/stt/prepare_audio.py 를 실행하세요"
adb get-state >/dev/null 2>&1 || die "adb에 연결된 기기가 없습니다"

OPTIONS=()
[ -z "${THREADS:-}" ] || OPTIONS+=(--threads "$THREADS")
[ -z "${LIMIT:-}" ] || OPTIONS+=(--limit "$LIMIT")
python3 bench/stt/fetch_models.py "$@" ${OPTIONS[@]+"${OPTIONS[@]}"}

OUT="bench/results/stt-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
cp "$BUILD/input.jsonl" "$BUILD/engines.json" "$OUT/"
adb shell getprop ro.product.model > "$OUT/device.txt"

./gradlew -q :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null

ACTIVITY="$APP_ID/.bench.SttBenchActivity"
REMOTE="/sdcard/Android/data/$APP_ID/files/bench-stt"
MODELS="$HOME/.cache/local-trans-bench/stt-models"
adb shell am force-stop "$APP_ID"
# 앱 전용 폴더는 앱이 만들어야 한다.
if ! adb shell "[ -d $REMOTE/models ] && [ -d $REMOTE/audio ]"; then
  adb shell am start -n "$ACTIVITY" >/dev/null
  sleep 4
  adb shell am force-stop "$APP_ID"
fi

# 크기가 같은 파일이 이미 있으면 다시 보내지 않는다.
push_if_changed() {
  local src="$1" dest="$2" have
  have=$(adb shell "stat -c %s '$dest' 2>/dev/null" < /dev/null | tr -d '\r' || true)
  [ "$have" = "$(stat -f %z "$src")" ] && return 0
  adb push "$src" "$dest" >/dev/null 2>&1 < /dev/null || die "폰으로 보내지 못했습니다: $src"
}

echo "== 시험 음성 보내는 중"
adb shell "rm -f $REMOTE/done-stt.json $REMOTE/output-*.jsonl"
for wav in "$BUILD"/audio/*.wav; do
  push_if_changed "$wav" "$REMOTE/audio/$(basename "$wav")"
done
adb push "$BUILD/input.jsonl" "$REMOTE/input.jsonl" >/dev/null 2>&1

echo "== 모델 보내는 중"
# 모델마다 실행 목록을 하나씩 만들고(run/<id>.json), 보낼 파일 목록을 적는다.
rm -rf "$BUILD/run"
mkdir -p "$BUILD/run"
python3 - "$BUILD" <<'EOF' > "$BUILD/model-files.txt"
import json, sys
from pathlib import Path
build = Path(sys.argv[1])
for engine in json.loads((build / "engines.json").read_text("utf-8")):
    (build / "run" / f"{engine['id']}.json").write_text(json.dumps([engine], ensure_ascii=False), "utf-8")
    for name in engine["files"].values():
        print(engine["id"], name)
EOF
while read -r id name; do
  push_if_changed "$MODELS/$id/$name" "$REMOTE/models/$id--$name" < /dev/null
done < "$BUILD/model-files.txt"

# 모델 하나를 새 앱 프로세스에서 돌린다 (메모리 사용량이 다른 모델과 섞이지 않게).
run_one() {
  local id="$1" waited=0 alive
  echo "== $id"
  adb shell am force-stop "$APP_ID"
  adb shell rm -f "$REMOTE/done-stt.json"
  adb push "$BUILD/run/$id.json" "$REMOTE/engines.json" >/dev/null 2>&1
  adb logcat -c
  adb shell am start -n "$ACTIVITY" >/dev/null
  sleep 5  # 앱 프로세스가 뜰 때까지 기다린다
  until adb shell "[ -f $REMOTE/done-stt.json ]" 2>/dev/null; do
    # USB 연결이 잠깐 끊겨도 폰에서는 계속 돌므로, 폰이 "앱이 없다"고 답할 때만 그만둔다.
    alive=$(adb shell "pidof $APP_ID || echo gone" 2>/dev/null | tr -d '\r' || true)
    [ "$alive" != gone ] || die "$id: 앱이 끝나기 전에 종료되었습니다 (adb logcat -s LocalTransBench 확인)"
    [ "$waited" -lt 7200 ] || die "$id: 2시간 안에 끝나지 않았습니다"
    if [ $((waited % 60)) -eq 50 ]; then
      adb logcat -d -s LocalTransBench:I 2>/dev/null | tail -1 | sed 's/.*LocalTransBench: /   /' || true
    fi
    sleep 10
    waited=$((waited + 10))
  done
  adb pull "$REMOTE/done-stt.json" "$OUT/done-$id.json" >/dev/null
  adb pull "$REMOTE/output-$id.jsonl" "$OUT/" >/dev/null 2>&1 || echo "   결과 없음: $id"
}

while read -r id; do
  run_one "$id" < /dev/null
done < <(cut -d' ' -f1 "$BUILD/model-files.txt" | uniq)
adb shell am force-stop "$APP_ID"

# 모델별 완료 기록을 하나로 합친다.
python3 - "$OUT" <<'EOF'
import json, sys
from pathlib import Path
out = Path(sys.argv[1])
engines = []
for engine in json.loads((out / "engines.json").read_text("utf-8")):
    done = json.loads((out / f"done-{engine['id']}.json").read_text("utf-8"))
    engines += done.get("engines") or [{"id": engine["id"], "error": done.get("error", "?")}]
(out / "done-stt.json").write_text(json.dumps({"engines": engines}, ensure_ascii=False), "utf-8")
EOF

echo "결과: $OUT"
echo "채점: python3 bench/stt/score.py $OUT"
