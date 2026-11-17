# 음성 인식 벤치마크

앱이 쓰는 스트리밍 음성 인식 모델과 바꿔 볼 만한 후보 모델을 같은 폰, 같은 음성으로 비교한다.
모든 모델은 앱과 같은 라이브러리(sherpa-onnx)로 CPU 에서 돌린다.

## 재는 것

| 세트 | 내용 | 발화 수 |
|---|---|---|
| conversation-tts | 번역 벤치마크의 대화 문장을 macOS 합성 음성 여러 개로 읽은 것 | 60 |
| librispeech-other | 낭독 음성 중 어려운 쪽 (LibriSpeech test-other) | 50 |
| ami-near | 회의 대화, 머리에 쓴 마이크 (AMI) | 50 |
| ami-far | 회의 대화, 탁자 위 마이크 한 개 (AMI). ami-near 와 같은 발화는 아니다 | 50 |
| earnings22 | 여러 나라 억양의 실적 발표 통화 (Earnings-22) | 50 |

- 정확도: 기준 문장 대비 단어 오류율(WER)
- 속도: 음성 1초를 처리하는 데 걸리는 시간, 문장 단위 모델은 발화 하나를 인식하는 시간
- 자원: 모델을 올리는 시간, 메모리 증가, 모델 크기

모델 목록은 `engines.json` 에 있다. 스트리밍 모델은 앱과 같은 방식(0.1초 조각, 같은 구간 끊기 규칙)으로,
문장 단위 모델은 발화 하나를 통째로 넣어 잰다. 시험 음성은 말이 곧바로 시작해서 스트리밍 모델이 첫 단어를
놓치기 쉬우므로, 스트리밍 모델에는 실제 사용처럼 앞에 0.5초, 뒤에 1초의 무음을 붙여 넣는다. 문장 단위 모델은 실시간 자막에는 쓸 수 없고,
스트리밍 모델이 구간을 끊은 뒤 그 구간을 다시 인식하는 용도로 본다.

## 돌리는 법

폰을 USB로 연결하고 잠금을 푼 뒤:

```bash
python3 bench/stt/prepare_audio.py
```

```bash
bench/stt/run.sh
```

```bash
python3 bench/stt/score.py
```

- `prepare_audio.py` 는 공개 세트에서 3~12초 길이 발화를 골라 받고(약 50MB), 합성 음성 세트를 만든다. macOS 전용이다(`afconvert`, `say`).
- `run.sh` 는 모델을 Mac 에 받고(모두 약 3.5GB) 폰으로 보낸 뒤 차례로 실행한다. 모델 id 를 적으면 그 모델만 돌린다.
  `THREADS=4 bench/stt/run.sh` 처럼 스레드 수를 바꿔 잴 수 있다 (기본은 앱 설정 `stt.numThreads`).
- 처음 20개 발화에서 처리 시간이 음성 길이보다 긴 모델은 실제로 쓸 수 없다고 보고 50개(세트마다 10개)만 재고 넘어간다.
- `engines.json` 에서 `optional` 로 표시한 모델(Whisper)은 id 를 직접 적어야 돌린다.
- `score.py` 는 whisper-normalizer 가 있으면 그것으로 문장을 다듬는다 (`pip install whisper-normalizer`).
  없으면 내장 규칙을 쓰는데, 숫자 표기를 맞추지 못해 숫자가 많은 세트의 오류율이 높게 나온다.
- 폰에 보낸 모델과 음성은 `/sdcard/Android/data/dev.localtrans.app/files/bench-stt/` 에 남는다. 다 쓰고 나면 지운다.

연속 음성에서 구간 끊김까지 보려면 (Mac 에서 실행, `pip install sherpa-onnx numpy whisper-normalizer` 필요):

```bash
python3 bench/stt/stream_sim.py zipformer-kroko-2025 fastconformer-480ms --second parakeet-110m
```

발화를 무음을 사이에 두고 이어 붙여 앱과 같은 규칙으로 흘려보내고, 구간이 제때 끊기는지와 2단계 인식 후 오류율을 잰다.
모델은 `run.sh` (또는 `fetch_models.py`) 가 받아 둔 것을 쓴다.

앱의 "미리 번역"(말이 잠깐 멈추면 구간이 끝나기 전에 2단계 인식과 번역을 시작)도 같은 규칙으로 흉내 내어,
이어 쓴 구간 수와 앞당긴 시간, 버린 횟수를 함께 보여 준다. 침묵은 앱처럼 Silero VAD 로 잰다
(`~/.cache/local-trans-bench/stt-models/silero-vad/silero_vad.onnx`, 앱 빌드용으로 `scripts/fetch-deps.sh` 가 받는 것과 같은 파일).
`--speculate 0.4` 처럼 기준을 바꾸거나 `--speculate 0` 으로 끌 수 있다. 실제 폰에서의 지연은 `bench/e2e/` 로 잰다.

## 읽을 때 주의할 점

- 세트마다 발화가 50~60개(단어 수백 개)라서 오류율 1~2%p 차이는 우연일 수 있다.
- 폰 마이크로 녹음한 음성이 아니다. 먼 마이크 세트가 탁자 위 폰과 가장 비슷하지만 같은 조건은 아니다.
- 속도는 음성 인식만 돌릴 때의 값이다. 앱에서는 번역 모델이 함께 돌므로 더 느려질 수 있다.
- 오래 돌리면 폰이 뜨거워져 뒤쪽 모델이 느리게 나올 수 있다.
- 합성 음성은 실제 사람 목소리보다 인식하기 쉽다.
