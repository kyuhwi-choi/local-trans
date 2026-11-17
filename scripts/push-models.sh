#!/bin/bash
# 모델을 PC에 받아 폰의 앱 전용 폴더로 넣습니다. 최초 1회만 하면 되고 앱 업데이트 후에도 유지됩니다.
#   scripts/push-models.sh          # 모두
#   scripts/push-models.sh stt      # 실시간 자막용 스트리밍 인식 모델만 (다운로드 약 110MB)
#   scripts/push-models.sh stt2     # 구간을 다시 인식하는 2단계 모델만 (약 110MB). 없어도 앱은 동작합니다.
#   scripts/push-models.sh llm      # Gemma 4 E2B만 (약 2.6GB)
# 다른 모델을 넣으려면 STT_MODEL / STT2_MODEL 에 sherpa-onnx 배포 이름을 적습니다.
#   예: STT2_MODEL=sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8 scripts/push-models.sh stt2
#       (다운로드 약 480MB, 더 정확하지만 메모리를 약 0.6GB 더 쓴다. bench/stt/RESULTS.md 참고)
# 먼저 폰에 앱을 설치하고 한 번 실행해 두세요(모델 폴더를 앱이 만들어야 합니다).
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

WHAT="${1:-all}"
CACHE="${MODEL_CACHE:-$HOME/.cache/local-trans-models}"
STT_MODEL="${STT_MODEL:-sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8}"
STT2_MODEL="${STT2_MODEL:-sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8}"
LLM_FILE="${LLM_FILE:-gemma-4-E2B-it.litertlm}"
LLM_URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$LLM_FILE"
REMOTE="/sdcard/Android/data/$APP_ID/files/models"

adb get-state >/dev/null 2>&1 || die "adb에 연결된 기기가 없습니다"
mkdir -p "$CACHE"

# 큰 파일은 연결이 중간에 끊기거나 멈추는 경우가 있어 HTTP/1.1 + 이어받기 + 재시도를 쓴다.
download() {
  curl -L --fail --http1.1 --retry 8 --retry-delay 3 --retry-all-errors \
    --speed-limit 20000 --speed-time 30 -C - -o "$2.part" "$1"
  mv "$2.part" "$2"
}

# pick <폴더> <encoder|decoder|joiner> <int8|plain> : 역할에 맞는 파일을 고른다 (세 번째 인자 쪽을 먼저 찾는다)
pick() {
  local f
  shopt -s nullglob
  if [ "$3" = plain ]; then
    for f in "$1"/"$2"*.onnx; do
      case "$f" in *int8*) ;; *) echo "$f"; return 0 ;; esac
    done
  fi
  for f in "$1"/"$2"*int8.onnx "$1"/"$2"*.onnx; do
    echo "$f"
    return 0
  done
  return 1
}

# push_stt <sherpa-onnx 모델 이름> <폰의 폴더 이름>
push_stt() {
  local name="$1" folder="$2" dir="$CACHE/$1" enc dec joi
  adb shell "[ -d $REMOTE/$folder ]" || die "폰에서 최신 앱을 한 번 실행한 뒤 다시 시도하세요 ($REMOTE/$folder 없음)"
  if [ ! -d "$dir" ]; then
    download "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$name.tar.bz2" "$CACHE/$name.tar.bz2"
    tar -xjf "$CACHE/$name.tar.bz2" -C "$CACHE"
    rm "$CACHE/$name.tar.bz2"
  fi
  # encoder·joiner 는 양자화본(int8)을, 크기가 작은 decoder 는 원본을 먼저 고른다.
  enc=$(pick "$dir" encoder int8) || die "encoder 모델을 찾지 못했습니다: $dir"
  dec=$(pick "$dir" decoder plain) || die "decoder 모델을 찾지 못했습니다: $dir"
  joi=$(pick "$dir" joiner int8) || die "joiner 모델을 찾지 못했습니다: $dir"
  [ -f "$dir/tokens.txt" ] || die "tokens.txt 가 없습니다: $dir"
  adb push "$enc" "$REMOTE/$folder/encoder.onnx"
  adb push "$dec" "$REMOTE/$folder/decoder.onnx"
  adb push "$joi" "$REMOTE/$folder/joiner.onnx"
  adb push "$dir/tokens.txt" "$REMOTE/$folder/tokens.txt"
}

if [ "$WHAT" = all ] || [ "$WHAT" = stt ]; then
  push_stt "$STT_MODEL" stt
fi

if [ "$WHAT" = all ] || [ "$WHAT" = stt2 ]; then
  push_stt "$STT2_MODEL" stt2
fi

if [ "$WHAT" = all ] || [ "$WHAT" = llm ]; then
  [ -f "$CACHE/$LLM_FILE" ] || download "$LLM_URL" "$CACHE/$LLM_FILE"
  adb push "$CACHE/$LLM_FILE" "$REMOTE/$LLM_FILE"
fi

adb shell "ls -la $REMOTE $REMOTE/stt $REMOTE/stt2" || true
echo "완료. 앱에서 모델을 내렸다가 다시 올리면(또는 '다시 확인') 새 모델을 씁니다."
