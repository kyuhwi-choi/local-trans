#!/bin/bash
# 저장소에 넣지 않는 빌드 의존 바이너리(sherpa-onnx AAR, 약 38MB)를 받습니다.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="sherpa-onnx-static-link-onnxruntime-2.0.1.aar"
SHA="07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575"
DEST="app/libs/$NAME"
mkdir -p app/libs
if [ ! -f "$DEST" ]; then
  curl -L --fail -o "$DEST" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/$NAME"
fi
echo "$SHA  $DEST" | shasum -a 256 -c -
