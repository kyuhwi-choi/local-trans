#!/bin/bash
# 새 버전을 빌드해 GitHub Releases 에 올립니다. 폰의 앱은 다음 실행 때 이 릴리스를 발견합니다.
#   scripts/release.sh "변경 내용 한 줄"
#   scripts/release.sh --local "..."   # 올리지 않고 dist/ 에만 만든다 (APK_BASE_URL 로 주소 지정)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

LOCAL=0
if [ "${1:-}" = "--local" ]; then LOCAL=1; shift; fi
NOTES="${1:-}"

[ -f signing/keystore.properties ] || die "서명 키가 없습니다. scripts/init-signing.sh 를 먼저 실행하세요."
REPO="$(grep '^update.repo=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')"
if [ "$LOCAL" = 0 ]; then
  [ -n "$REPO" ] || die "gradle.properties 의 update.repo 에 GitHub 저장소(owner/name)를 적어 주세요."
  gh auth status >/dev/null 2>&1 || die "gh 로그인이 필요합니다 (gh auth login)"
fi

# 버전 코드는 항상 1씩 올린다. 빌드가 실패하면 되돌린다.
cp version.properties version.properties.bak
trap 'mv -f version.properties.bak version.properties 2>/dev/null || true' ERR
CODE=$(( $(grep '^versionCode=' version.properties | cut -d= -f2) + 1 ))
NAME="${VERSION_NAME:-0.1.$CODE}"
printf 'versionCode=%s\nversionName=%s\n' "$CODE" "$NAME" > version.properties

./gradlew -q :app:testDebugUnitTest :app:assembleRelease

TAG="v$NAME"
APK_NAME="local-trans-$NAME.apk"
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/$APK_NAME"
BASE="${APK_BASE_URL:-https://github.com/$REPO/releases/download/$TAG}"
SHA="$(shasum -a 256 "dist/$APK_NAME" | cut -d' ' -f1)"
SIZE="$(stat -f%z "dist/$APK_NAME")"
CODE="$CODE" NAME="$NAME" URL="$BASE/$APK_NAME" SHA="$SHA" SIZE="$SIZE" NOTES="$NOTES" python3 - > dist/latest.json <<'PY'
import json, os
e = os.environ
print(json.dumps({
    "versionCode": int(e["CODE"]),
    "versionName": e["NAME"],
    "apkUrl": e["URL"],
    "sha256": e["SHA"],
    "sizeBytes": int(e["SIZE"]),
    "notes": e["NOTES"],
}, ensure_ascii=False, indent=2))
PY
rm -f version.properties.bak
trap - ERR

if [ "$LOCAL" = 1 ]; then
  echo "dist/$APK_NAME, dist/latest.json 생성 (업로드하지 않음)"
  exit 0
fi

gh release create "$TAG" "dist/$APK_NAME" dist/latest.json \
  --repo "$REPO" --title "$TAG" --notes "${NOTES:-$TAG}" --latest
echo "배포 완료: $TAG (versionCode $CODE)"
