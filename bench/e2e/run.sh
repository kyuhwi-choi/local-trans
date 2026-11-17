#!/bin/bash
# 음성 파일을 마이크 대신 앱에 넣어 통역 전체(인식 → 2단계 인식 → 번역)를 실제 속도로 돌리고,
# 구간마다의 결과와 시각을 적은 통역 기록을 bench/results/e2e-<시각>/ 으로 가져온다.
# 사용: bench/e2e/run.sh [음성.wav ...]            (생략하면 bench/build/e2e/stream.wav)
#       CONFIG=설정.json bench/e2e/run.sh ...      (시험할 설정. 생략하면 저장소의 remote/config.json)
#       LABEL=미리번역-끔 bench/e2e/run.sh ...     (결과 폴더 이름 뒤에 붙는다)
# 음성은 16kHz 모노 16비트 wav. 같은 이름의 .ref.jsonl (build_stream.py) 이나 .ref.txt (받아 적은 영어) 가 옆에 있으면
# 채점에 쓴다. 폰은 잠금이 풀려 있어야 하고, 음성 길이만큼 시간이 걸린다.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/env.sh

[ $# -gt 0 ] || set -- bench/build/e2e/stream.wav
for wav in "$@"; do
  [ -f "$wav" ] || die "음성 파일이 없습니다: $wav (python3 bench/e2e/build_stream.py 로 만들 수 있습니다)"
done
CONFIG="${CONFIG:-remote/config.json}"
[ -f "$CONFIG" ] || die "설정 파일이 없습니다: $CONFIG"
adb get-state >/dev/null 2>&1 || die "adb에 연결된 기기가 없습니다"

OUT="bench/results/e2e-$(date +%Y%m%d-%H%M%S)${LABEL:+-$LABEL}"
mkdir -p "$OUT"
cp "$CONFIG" "$OUT/config.json"
adb shell getprop ro.product.model > "$OUT/device.txt"

./gradlew -q :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null

FILES="/sdcard/Android/data/$APP_ID/files"
SESSION=bench-e2e
LOG="$FILES/recordings/$SESSION.jsonl"
# 시험이 끝나면(도중에 멈춰도) 넣어 둔 음성과 설정을 지워, 앱이 평소대로 마이크와 원격 설정을 쓰게 한다.
cleanup() {
  adb shell am force-stop "$APP_ID" || true
  adb shell rm -f "$FILES/debug-input.wav" "$FILES/debug-config.json" "$LOG" || true
}
trap cleanup EXIT

adb shell am force-stop "$APP_ID"
adb shell input keyevent KEYCODE_WAKEUP
adb push "$CONFIG" "$FILES/debug-config.json" >/dev/null 2>&1 || die "폰에 앱 폴더가 없습니다. 앱을 한 번 실행해 두세요"

for wav in "$@"; do
  name="$(basename "$wav" .wav)"
  seconds=$(python3 -c 'import sys, wave; w = wave.open(sys.argv[1]); print(w.getnframes() // w.getframerate())' "$wav")
  echo "== $name (${seconds}초)"
  adb shell rm -f "$LOG"
  adb push "$wav" "$FILES/debug-input.wav" >/dev/null 2>&1
  # 앱이 이미 떠 있으면 올라가 있는 모델을 그대로 쓴다.
  adb shell am start -n "$APP_ID/.MainActivity" --es autostart "$SESSION" >/dev/null 2>&1
  waited=0
  until adb shell "grep -q '\"type\":\"end\"' $LOG 2>/dev/null" < /dev/null; do
    alive=$(adb shell "pidof $APP_ID || echo gone" 2>/dev/null < /dev/null | tr -d '\r' || true)
    # 막 띄운 직후에는 프로세스가 아직 이름을 얻지 못해 없는 것처럼 보일 수 있다.
    [ "$waited" -lt 10 ] || [ "$alive" != gone ] || die "$name: 앱이 끝나기 전에 종료되었습니다 (adb logcat -s LocalTrans 확인)"
    if [ "$waited" -ge 90 ] && ! adb shell "[ -f $LOG ]" < /dev/null; then
      die "$name: 통역이 시작되지 않았습니다 (모델이 폰에 있는지, 화면 잠금이 풀려 있는지 확인)"
    fi
    [ "$waited" -lt $((seconds + 300)) ] || die "$name: 끝나지 않았습니다"
    sleep 5
    waited=$((waited + 5))
  done
  adb pull "$LOG" "$OUT/$name.jsonl" >/dev/null
  for ref in "${wav%.wav}.ref.jsonl" "${wav%.wav}.ref.txt"; do
    [ ! -f "$ref" ] || cp "$ref" "$OUT/"
  done
done

echo "결과: $OUT"
echo "채점: python3 bench/e2e/score.py $OUT"
