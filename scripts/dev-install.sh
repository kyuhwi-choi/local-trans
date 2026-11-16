#!/bin/bash
# 개발 중 빠른 설치: 디버그 빌드를 USB/무선 adb로 덮어씁니다. (앱 데이터와 모델은 유지)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
./gradlew -q :app:assembleDebug "$@"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
