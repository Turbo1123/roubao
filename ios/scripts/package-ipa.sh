#!/bin/bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APP=${1:-$ROOT/.build/DerivedData/Build/Products/Release-iphoneos/iPhoneUse.app}
OUT=${2:-$ROOT/output/肉包-iOS-实验版-未签名.ipa}
[[ -d "$APP" ]] || { echo 'Build the iOS app first.' >&2; exit 1; }
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/Payload" "$(dirname "$OUT")"
ditto "$APP" "$STAGE/Payload/iPhoneUse.app"
(cd "$STAGE" && /usr/bin/zip -qry "$OUT" Payload)
echo "IPA packaged. Signing status must be checked before installation."
