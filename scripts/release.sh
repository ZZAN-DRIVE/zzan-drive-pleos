#!/usr/bin/env bash
# Builds the contest release APK, checks it, and puts it where the contest rules want it.
#
#   scripts/release.sh               # unit tests + release build → apk/ZZANDRIVE_ZzanDrive_release.apk
#   scripts/release.sh --install     # also install it on the Pleos emulator WITHOUT pre-granted permissions
#   scripts/release.sh --out FILE    # write somewhere else (for example a trial with a throwaway key)
#
# Signing (one of; never commit either):
#   keystore.properties at the repository root (git-ignored):
#     storeFile=/absolute/path/to/zzandrive-release.jks
#     storePassword=...
#     keyAlias=zzandrive
#     keyPassword=...
#   or the environment variables ZZAN_KEYSTORE, ZZAN_KEYSTORE_PASSWORD, ZZAN_KEY_ALIAS, ZZAN_KEY_PASSWORD.
# Make the team key once and keep it outside the repository. Blind judging: the certificate may carry
# the team name only (no person, e-mail or school):
#   keytool -genkeypair -keystore ~/zzandrive-release.jks -alias zzandrive -keyalg RSA -keysize 4096 \
#           -validity 10000 -dname "CN=ZZAN-DRIVE"
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; }
INSTALL=0
OUT="${ZZAN_APK_OUT:-apk/ZZANDRIVE_ZzanDrive_release.apk}"
while [ $# -gt 0 ]; do
  case "$1" in
    --install) INSTALL=1 ;;
    --out)     shift; OUT="${1:?--out needs a file name}" ;;
    --yes|-y)  ASSUME_YES=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "Unknown option: $1" ;;
  esac
  shift
done
export ASSUME_YES   # read by confirm() in lib/common.sh
cd "$ZZAN_ROOT"
mkdir -p "$ZZAN_STATE_DIR"
detect_platform; resolve_sdk_root
resolve_jdk || die "No usable JDK 17/21. Run ./scripts/bootstrap.sh first."

step "1/4 Unit tests + release build (log: $ZZAN_STATE_DIR/gradle-release.log)"
if ! ./gradlew --console=plain :core:test :data:source-mock:test :app:assembleRelease > "$ZZAN_STATE_DIR/gradle-release.log" 2>&1; then
  tail -30 "$ZZAN_STATE_DIR/gradle-release.log" >&2
  die "Release build failed. Full log: $ZZAN_STATE_DIR/gradle-release.log"
fi
APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  [ -f app/build/outputs/apk/release/app-release-unsigned.apk ] &&
    die "The release APK is unsigned: no keystore.properties or ZZAN_KEYSTORE. See the top of this script."
  die "No release APK found in app/build/outputs/apk/release/"
fi
ok "built $APK"

step "2/4 Signature (APK Signature Scheme v2/v3) and certificate"
BT="$(ls -d "$ANDROID_SDK_ROOT"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] && [ -x "${BT}apksigner" ] || die "apksigner not found under $ANDROID_SDK_ROOT/build-tools (run ./scripts/bootstrap.sh)"
# apksigner reports a scheme only when the checked platform range needs it. v3 alone covers our
# minSdk 28, so ask for Android 7.0 (24) and up to confirm the v2 block is present and valid too.
SIG="$("${BT}apksigner" verify --min-sdk-version 24 --verbose --print-certs "$APK" 2>&1)" || { printf '%s\n' "$SIG" >&2; die "apksigner verify failed"; }
for scheme in v2 v3; do
  if grep -qE "Verified using $scheme scheme .*: true" <<<"$SIG"; then ok "signed with scheme $scheme"; else die "not signed with scheme $scheme"; fi
done
DN="$(sed -nE 's/^(Signer #1|V3\.0 Signer:) certificate DN: //p' <<<"$SIG" | head -1)"
SHA="$(sed -nE 's/^(Signer #1|V3\.0 Signer:) certificate SHA-256 digest: //p' <<<"$SIG" | head -1)"
case "$DN" in
  *"Android Debug"*) die "Signed with the Android debug key. Use the team key (see the top of this script)." ;;
  *@*|*EMAILADDRESS*) die "The certificate DN contains an e-mail address (blind judging): $DN" ;;
esac
ok "certificate DN: $DN"
warn "Blind judging: make sure this DN names the team only, never a person or school."
info "certificate SHA-256: $SHA"

step "3/4 Contents"
AAPT2="${BT}aapt2"
[ -x "$AAPT2" ] || die "aapt2 not found in $BT"
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null || true)"
case "$BADGING" in *application-debuggable*) die "The release APK is debuggable" ;; esac
ok "not debuggable (debug commands are off)"
# The diagnostics screen must not be reachable from outside in release builds.
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" | awk '
  /E: activity/ { inact = 1; name = ""; ex = ""; next }
  /E: / { inact = 0 }
  inact && /android:name\(/ { name = $0 }
  inact && /android:exported\(/ { ex = $0 }
  inact && name ~ /DiagnosticsActivity/ && ex != "" { if (ex ~ /=true/ || ex ~ /0xffffffff/) bad = 1; inact = 0 }
  END { exit bad }' || die "DiagnosticsActivity is exported in the release APK"
ok "diagnostics screen not exported"
if unzip -l "$APK" | awk '{print $4}' | grep -iE '(^|/)(keystore\.properties|local\.properties|secrets?\.[a-z]+|\.env[^/]*|[^/]*\.(jks|keystore|pem|p12))$'; then
  die "The APK contains a file that looks like a secret (listed above)"
fi
ok "no keystore, properties or secret files inside"
SIZE="$(wc -c < "$APK" | tr -d ' ')"
[ "$SIZE" -lt $((100 * 1024 * 1024)) ] || die "APK is larger than 100 MB ($SIZE bytes)"
ok "size $(awk -v s="$SIZE" 'BEGIN { printf "%.1f MB", s / 1048576 }')"

step "4/4 Output"
mkdir -p "$(dirname "$OUT")"
cp "$APK" "$OUT"
ok "$OUT"
info "SHA-256 $(shasum -a 256 "$OUT" | cut -d' ' -f1)"

if [ "$INSTALL" = 1 ]; then
  step "Install on the emulator without pre-granted permissions"
  require_pleos_emulator
  out="$(adb_s install -r "$OUT" 2>&1 || true)"
  if case "$out" in *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) true ;; *) false ;; esac; then
    confirm "A differently signed 짠드라이브 (e.g. the debug build) is installed. Remove it, with its saved data" ||
      die "Kept the installed app; the release APK was not installed."
    adb_s uninstall "$ZZAN_APP_ID" >/dev/null
    out="$(adb_s install "$OUT" 2>&1 || true)"
  fi
  case "$out" in *Success*) ;; *) printf '%s\n' "$out" >&2; die "adb install failed" ;; esac
  adb_s shell am start -n "$ZZAN_APP_ID/.MainActivity" >/dev/null
  ok "installed and launched. The app asks for the location permission on first launch;"
  info "the 시연 B · 충전 / 시연 A · 주유 buttons work without it and without the Virtual Console."
  info "Back to the debug build for the scripts: ./scripts/bootstrap.sh (asks before replacing this one)."
fi
