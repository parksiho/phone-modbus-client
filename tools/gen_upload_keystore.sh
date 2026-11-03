#!/usr/bin/env bash
# Play 앱 서명에 쓸 업로드 키스토어와 gitignore 된 keystore.properties 를 만든다.
# 비밀번호는 커밋하지 않는다. 예제 형식은 keystore.properties.example.
#
#   tools/gen_upload_keystore.sh
#   KS_CN="홍길동" KS_ORG="내 회사" KS_C=KR tools/gen_upload_keystore.sh
#
# 이미 환경변수 KEYSTORE_PASSWORD, KEY_PASSWORD 가 있으면 그 값을 쓰고 다시 묻지 않는다.
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/upload-keystore.jks}"
ALIAS="${KEY_ALIAS:-upload}"
VALIDITY_DAYS="${KEY_VALIDITY_DAYS:-10000}"

if [[ -e "$OUT" && "${FORCE:-}" != 1 ]]; then
  echo "이미 있습니다: $OUT" >&2
  echo "덮어쓰려면 FORCE=1 을 붙이세요. 기존 업로드 키를 지우면 Play 앱을 업데이트할 수 없습니다." >&2
  exit 1
fi

prompt() {
  local name="$1" text="$2"
  if [[ -n "${!name:-}" ]]; then
    return 0
  fi
  local value
  read -r -p "$text: " value
  if [[ -z "$value" ]]; then
    echo "$text 가 비어 있습니다." >&2
    exit 1
  fi
  printf -v "$name" '%s' "$value"
}

prompt_secret() {
  local name="$1" text="$2" again
  if [[ -n "${!name:-}" ]]; then
    return 0
  fi
  read -r -s -p "$text: " "$name"
  echo
  read -r -s -p "$text (다시): " again
  echo
  if [[ -z "${!name}" || "${!name}" != "$again" ]]; then
    echo "비밀번호가 비어 있거나 서로 다릅니다." >&2
    exit 1
  fi
}

prompt KS_CN "이름 (인증서 CN)"
prompt KS_ORG "조직 (인증서 O). 개인이면 본인 이름"
prompt KS_C "국가 코드 두 글자 (예: KR)"
if [[ ! "$KS_C" =~ ^[A-Za-z]{2}$ ]]; then
  echo "국가 코드는 두 글자여야 합니다." >&2
  exit 1
fi
prompt_secret KEYSTORE_PASSWORD "스토어 비밀번호"
prompt_secret KEY_PASSWORD "키 비밀번호"

mkdir -p "$(dirname "$OUT")"
keytool -genkeypair -v \
  -keystore "$OUT" \
  -storetype JKS \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity "$VALIDITY_DAYS" \
  -dname "CN=${KS_CN}, O=${KS_ORG}, C=${KS_C}" \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEY_PASSWORD"
chmod 600 "$OUT"

REL="$OUT"
if [[ "$OUT" == "$ROOT/"* ]]; then
  REL="${OUT#"$ROOT/"}"
fi

PROPS="$ROOT/keystore.properties"
umask 077
cat > "$PROPS" <<EOF
storeFile=${REL}
storePassword=${KEYSTORE_PASSWORD}
keyAlias=${ALIAS}
keyPassword=${KEY_PASSWORD}
EOF
chmod 600 "$PROPS"

echo "키스토어: $OUT"
echo "서명 설정: $PROPS (gitignore)"
echo "이 파일들은 커밋하지 마세요. 업로드 키를 잃으면 Play 앱 서명을 등록한 뒤에도 업데이트 업로드가 막힐 수 있습니다."
echo "백업은 비밀번호 관리자나 오프라인 저장소에 두세요."
