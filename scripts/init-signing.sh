#!/bin/bash
# 앱 서명 키를 한 번만 만듭니다. 이 키가 바뀌면 기존 설치본을 업데이트할 수 없으므로
# signing/ 폴더를 반드시 별도로 백업하세요. (저장소에는 올라가지 않습니다)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
[ ! -e signing/release.jks ] || die "signing/release.jks 가 이미 있습니다. 덮어쓰지 않습니다."
mkdir -p signing
PASS="$(openssl rand -hex 16)"
keytool -genkeypair -keystore signing/release.jks -storetype PKCS12 \
  -alias localtrans -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=local-trans" -storepass "$PASS" -keypass "$PASS" >/dev/null
umask 077
cat > signing/keystore.properties <<PROPS
storeFile=signing/release.jks
storePassword=$PASS
keyAlias=localtrans
keyPassword=$PASS
PROPS
chmod 600 signing/release.jks signing/keystore.properties
echo "서명 키를 만들었습니다: signing/ (백업 필수)"
