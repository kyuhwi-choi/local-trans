#!/bin/bash
# 모델을 PC에 받아 폰의 앱 전용 폴더로 넣습니다. 최초 1회만 하면 되고 앱 업데이트 후에도 유지됩니다.
#   scripts/push-models.sh          # STT + LLM 모두
#   scripts/push-models.sh stt      # 음성 인식 모델만 (다운로드 약 310MB)
#   scripts/push-models.sh llm      # Gemma 4 E2B만 (약 2.6GB)
# 먼저 폰에 앱을 설치하고 한 번 실행해 두세요(모델 폴더를 앱이 만들어야 합니다).
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

WHAT="${1:-all}"
CACHE="${MODEL_CACHE:-$HOME/.cache/local-trans-models}"
STT_MODEL="${STT_MODEL:-sherpa-onnx-streaming-zipformer-en-2023-06-26}"
LLM_FILE="${LLM_FILE:-gemma-4-E2B-it.litertlm}"
LLM_URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$LLM_FILE"
REMOTE="/sdcard/Android/data/$APP_ID/files/models"

adb get-state >/dev/null 2>&1 || die "adb에 연결된 기기가 없습니다"
adb shell "[ -d $REMOTE/stt ]" || die "폰에서 앱을 한 번 실행한 뒤 다시 시도하세요 ($REMOTE 없음)"
mkdir -p "$CACHE"

# 여러 후보 중 첫 번째로 존재하는 파일을 고른다 (int8 양자화본 우선)
pick() { for f in "$@"; do [ -f "$f" ] && { echo "$f"; return; }; done; return 1; }

if [ "$WHAT" = all ] || [ "$WHAT" = stt ]; then
  DIR="$CACHE/$STT_MODEL"
  if [ ! -d "$DIR" ]; then
    curl -L --fail -o "$CACHE/$STT_MODEL.tar.bz2" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$STT_MODEL.tar.bz2"
    tar -xjf "$CACHE/$STT_MODEL.tar.bz2" -C "$CACHE"
    rm "$CACHE/$STT_MODEL.tar.bz2"
  fi
  shopt -s nullglob
  ENC=$(pick "$DIR"/encoder*int8.onnx "$DIR"/encoder*.onnx) || die "encoder 모델을 찾지 못했습니다: $DIR"
  DEC=$(pick $(ls "$DIR"/decoder*.onnx | grep -v int8) "$DIR"/decoder*.onnx) || die "decoder 모델을 찾지 못했습니다"
  JOI=$(pick "$DIR"/joiner*int8.onnx "$DIR"/joiner*.onnx) || die "joiner 모델을 찾지 못했습니다"
  [ -f "$DIR/tokens.txt" ] || die "tokens.txt 가 없습니다"
  adb push "$ENC" "$REMOTE/stt/encoder.onnx"
  adb push "$DEC" "$REMOTE/stt/decoder.onnx"
  adb push "$JOI" "$REMOTE/stt/joiner.onnx"
  adb push "$DIR/tokens.txt" "$REMOTE/stt/tokens.txt"
fi

if [ "$WHAT" = all ] || [ "$WHAT" = llm ]; then
  if [ ! -f "$CACHE/$LLM_FILE" ]; then
    # 큰 파일은 HTTP/2 연결이 중간에 끊기는 경우가 있어 HTTP/1.1 + 이어받기 + 재시도를 쓴다.
    curl -L --fail --http1.1 --retry 8 --retry-delay 3 --retry-all-errors -C - \
      -o "$CACHE/$LLM_FILE.part" "$LLM_URL"
    mv "$CACHE/$LLM_FILE.part" "$CACHE/$LLM_FILE"
  fi
  adb push "$CACHE/$LLM_FILE" "$REMOTE/$LLM_FILE"
fi

adb shell ls -la "$REMOTE" "$REMOTE/stt"
echo "완료. 앱에서 '다시 확인'을 누르세요."
