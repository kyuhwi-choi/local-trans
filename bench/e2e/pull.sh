#!/bin/bash
# 폰에 남긴 통역 녹음(디버그 빌드에서 "음성 저장"을 켜고 통역한 것)을 bench/recordings/ 로 가져온다.
# 음성(.wav)과 그때의 통역 기록(.jsonl)이 한 쌍이다. 실제 대화가 담기므로 bench/recordings/ 는 저장소에 올리지 않는다.
# 사용: bench/e2e/pull.sh            (가져오기만)
#       bench/e2e/pull.sh --delete   (가져온 뒤 폰에서 지운다)
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/env.sh

adb get-state >/dev/null 2>&1 || die "adb에 연결된 기기가 없습니다"
REMOTE="/sdcard/Android/data/$APP_ID/files/recordings"
adb shell "[ -d $REMOTE ]" || die "폰에 녹음이 없습니다"
mkdir -p bench/recordings
adb pull "$REMOTE/." bench/recordings/ | tail -1
if [ "${1:-}" = --delete ]; then
  adb shell "rm -f $REMOTE/*.wav $REMOTE/*.jsonl"
  echo "폰의 녹음을 지웠습니다"
fi
ls -lh bench/recordings | tail -n +2
echo "다시 돌려 보기: bench/e2e/run.sh bench/recordings/<이름>.wav"
