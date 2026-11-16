# 다른 스크립트가 source 해서 쓰는 공통 환경. 직접 실행하지 않습니다.
export JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
[ -n "$JAVA_HOME" ] || { echo "JDK 21을 찾을 수 없습니다" >&2; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
APP_ID="dev.localtrans.app"
die() { echo "오류: $*" >&2; exit 1; }
