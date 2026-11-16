# 번역 벤치마크

이 앱의 번역(Gemma 4 E2B)과 구글 번역 오프라인 모드를 같은 폰, 같은 문장으로 비교한다.

구글 번역 앱은 밖에서 호출할 방법이 없어서, Google이 "구글 번역 오프라인 모드와 같은 모델"이라고 밝힌
[ML Kit 온디바이스 번역](https://developers.google.com/ml-kit/language/translation?hl=ko)을 대신 잰다.
ML Kit는 별도 앱(`bench/mlkit`, `dev.localtrans.benchmlkit`)으로 돌리므로 본 앱에는 의존성이 들어가지 않는다.

## 재는 것

| 세트 | 내용 | 문장 수 |
|---|---|---|
| conversation | 길 찾기·식당·회의·잡담·여행·병원/쇼핑 대화 (`data/conversation.jsonl`) | 60 |
| asr | 같은 문장을 음성 인식 결과처럼 구두점 없이 바꾼 것 | 60 |
| flores | 공개 평가 세트 FLORES-200 devtest 영→한 (뉴스·위키 문장) | 150 (조절 가능) |

- 품질: 기준 번역 대비 chrF2, 그리고 어느 엔진인지 가린 블라인드 평가
- 속도: 문장당 번역 시간 (Gemma는 첫 글자가 나올 때까지의 시간도)
- 자원: 준비 시간, 실행 중 메모리 최대값, 모델 크기

Gemma는 앱과 같은 설정·프롬프트로 돌리고, 대화 세트에서는 앱처럼 같은 대화의 직전 두 문장을 맥락으로 준다.
ML Kit는 맥락을 받지 않는다.

## 돌리는 법

폰을 USB로 연결하고 잠금을 푼 뒤:

```bash
python3 bench/prepare_data.py --flores 150
```

```bash
bench/run.sh
```

```bash
python3 bench/score.py
```

- `run.sh` 는 두 앱을 설치하고 차례로 실행한 뒤 결과를 `bench/results/<시각>/` 으로 가져온다.
  ML Kit 앱은 처음 한 번 한국어 모델(약 30MB)을 내려받으므로 그때만 인터넷이 필요하다.
- Gemma 쪽은 본 앱의 디버그 빌드에 들어 있는 실행기(`BenchActivity`)가 돌린다. 실행 중에는 본 앱이 종료된다.
- `score.py` 는 sacrebleu 가 있으면 그것으로 chrF2 를 계산한다 (`pip install sacrebleu`). 없으면 내장 구현을 쓴다.

블라인드 평가:

```bash
python3 bench/score.py --judge-sheet
```

`blind.md` 를 보고 문항마다 A/B/비슷함을 골라 `{"id": "...", "winner": "A"}` 형식의 줄로 적은 뒤:

```bash
python3 bench/score.py --judge-result judge-output.jsonl --judge "평가자 이름"
```

## 읽을 때 주의할 점

- ML Kit는 구글 번역 앱과 같은 계열의 모델이지만, 앱에 들어 있는 버전과 똑같다는 보장은 없다.
- chrF2 는 기준 번역과 글자가 얼마나 겹치는지를 본다. 뜻이 같아도 표현이 다르면 낮게 나오므로
  두 엔진의 상대 비교로만 읽고, 블라인드 평가와 함께 본다.
- 대화 세트의 기준 번역은 직접 쓴 존댓말 번역이다. 말투가 다른 번역은 점수가 깎인다.
- asr 세트는 구두점만 뺀 것이라 실제 음성 인식 오류(단어가 빠지거나 틀리는 것)는 들어 있지 않다.
- Gemma는 앱 설정(`llm.translateMaxTokens`)대로 출력 길이가 제한되어, 긴 FLORES 문장은 번역이 잘릴 수 있다.
- 음성까지 포함한 전체 통역 지연은 이 벤치마크의 범위가 아니다.
