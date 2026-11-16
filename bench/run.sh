#!/bin/bash
# 폰에서 번역 벤치마크를 돌리고 결과를 bench/results/<시각>/ 으로 가져온다.
# 사용: bench/run.sh [all|gemma|mlkit]
# 먼저 python3 bench/prepare_data.py 로 입력 파일을 만들어 둔다. 폰은 잠금이 풀려 있어야 한다.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

WHAT="${1:-all}"
INPUT=bench/build/input.jsonl
[ -f "$INPUT" ] || die "입력 파일이 없습니다. 먼저 python3 bench/prepare_data.py 를 실행하세요"
adb get-state >/dev/null 2>&1 || die "adb에 연결된 기기가 없습니다"

OUT="bench/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
cp "$INPUT" "$OUT/input.jsonl"
adb shell getprop ro.product.model > "$OUT/device.txt"

./gradlew -q :app:assembleDebug :benchmlkit:assembleDebug

# run_engine <이름> <패키지> <액티비티> <apk>
run_engine() {
  local name="$1" pkg="$2" activity="$3" apk="$4"
  local remote="/sdcard/Android/data/$pkg/files/bench"
  echo "== $name"
  adb install -r "$apk" >/dev/null
  adb shell am force-stop "$pkg"
  # 앱 전용 폴더는 앱이 한 번 실행되어야 생긴다.
  if ! adb shell "[ -d $remote ]"; then
    adb shell am start -n "$pkg/$activity" >/dev/null
    sleep 4
    adb shell am force-stop "$pkg"
  fi
  adb shell rm -f "$remote/done-$name.json" "$remote/output-$name.jsonl"
  adb push "$INPUT" "$remote/input.jsonl" >/dev/null
  adb shell am start -n "$pkg/$activity" >/dev/null

  # 끝날 때까지 기다리면서 메모리 사용량을 5초마다 기록한다 (최대 60분).
  local waited=0
  : > "$OUT/mem-$name.txt"
  until adb shell "[ -f $remote/done-$name.json ]"; do
    adb shell dumpsys meminfo "$pkg" | grep "TOTAL PSS" >> "$OUT/mem-$name.txt" || true
    adb shell pidof "$pkg" >/dev/null || die "$name: 앱이 끝나기 전에 종료되었습니다 (adb logcat -s LocalTransBench 확인)"
    [ "$waited" -lt 3600 ] || die "$name: 60분 안에 끝나지 않았습니다"
    sleep 5
    waited=$((waited + 5))
  done
  adb pull "$remote/done-$name.json" "$OUT/" >/dev/null
  adb pull "$remote/output-$name.jsonl" "$OUT/" >/dev/null 2>&1 || true
  adb shell am force-stop "$pkg"
  cat "$OUT/done-$name.json"
  echo
}

if [ "$WHAT" = all ] || [ "$WHAT" = mlkit ]; then
  run_engine mlkit dev.localtrans.benchmlkit .BenchActivity bench/mlkit/build/outputs/apk/debug/benchmlkit-debug.apk
fi
if [ "$WHAT" = all ] || [ "$WHAT" = gemma ]; then
  run_engine gemma "$APP_ID" .bench.BenchActivity app/build/outputs/apk/debug/app-debug.apk
fi

echo "결과: $OUT"
echo "채점: python3 bench/score.py $OUT"
