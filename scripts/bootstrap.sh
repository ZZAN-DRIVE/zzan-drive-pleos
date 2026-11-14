#!/usr/bin/env bash
# One command: tools → Pleos emulator → unit tests + build → install → verify the app on real vehicle data.
#
#   ./scripts/bootstrap.sh              # asks before every install step
#   ./scripts/bootstrap.sh --dry-run    # only print what would happen
#
# Options:
#   --yes          answer our own Y/n prompts with yes (license prompts still need YOU)
#   --dry-run      detect and print the plan; install/build/run nothing
#   --no-verify    stop after installing and launching the app
#   --e2e          also run 시나리오 B end to end on real vehicle data (scripts/e2e-scenario-b.sh, ~2 min;
#                  replaces the app's own savings record with the scenario's starting point)
#   --avd NAME     use this AVD instead of auto-detecting a Pleos AVD
#   --no-browser   do not open the Virtual Console at the end
#
# Windows: powershell -ExecutionPolicy Bypass -File scripts\bootstrap.ps1
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

VERIFY=1; BROWSER=1; E2E=0
usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; }
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y)     ASSUME_YES=1 ;;
    --dry-run)    DRY_RUN=1 ;;
    --no-verify)  VERIFY=0 ;;
    --e2e)        E2E=1 ;;
    --avd)        shift; ZZAN_AVD="${1:?--avd needs a name}" ;;
    --no-browser) BROWSER=0 ;;
    -h|--help)    usage; exit 0 ;;
    *) usage; die "Unknown option: $1" ;;
  esac
  shift
done
export DRY_RUN ASSUME_YES

cd "$ZZAN_ROOT"
mkdir -p "$ZZAN_STATE_DIR"
printf '%s짠드라이브 bootstrap%s (%s)%s\n' "$C_B" "$C_0" "$ZZAN_ROOT" "$([ "$DRY_RUN" = 1 ] && echo ' — DRY RUN')"

step "0/8 Preflight"
detect_platform
need_cmd curl "Install curl and re-run."
need_cmd unzip "Install unzip and re-run."
ok "Host $HOST_OS/$HOST_ARCH → image ABI $PLEOS_ABI"
if curl -fsS -o /dev/null --max-time 10 "$PLEOS_REPO_URL"; then ok "Pleos repository reachable"
else warn "Cannot reach nexus-playground.pleos.ai (needed for the image and SDK libraries on first run)."; fi
resolve_sdk_root

ensure_jdk
ensure_cmdline_tools
ensure_sdk_packages
ensure_pleos_repo
ensure_pleos_image
ensure_avd
start_emulator

APK="app/build/outputs/apk/debug/app-debug.apk"
GRADLE_TASKS=":core:test :data:source-mock:test :app:assembleDebug"
# test_totals → "TESTS FAILED", summed over the JUnit XML results of the unit-test modules
test_totals() {
  cat core/build/test-results/test/*.xml data/source-mock/build/test-results/test/*.xml 2>/dev/null |
    awk -F'"' '/<testsuite / { for (i = 1; i < NF; i++) { if ($i ~ / tests=$/) t += $(i+1); if ($i ~ / (failures|errors)=$/) f += $(i+1) } }
               END { printf "%d %d\n", t, f }'
}
step "5/8 Unit tests + build (log: $ZZAN_STATE_DIR/gradle.log)"
if [ "$DRY_RUN" = 1 ]; then info "[dry-run] ./gradlew $GRADLE_TASKS"
else
  info "First run downloads Gradle and libraries (a few minutes)."
  # shellcheck disable=SC2086
  if ! ./gradlew --console=plain $GRADLE_TASKS > "$ZZAN_STATE_DIR/gradle.log" 2>&1; then
    tail -30 "$ZZAN_STATE_DIR/gradle.log" >&2
    die "Unit tests or build failed. Full log: $ZZAN_STATE_DIR/gradle.log (test reports: core/build/reports/tests/test/index.html)"
  fi
  TOTALS="$(test_totals)"; TESTS="${TOTALS% *}"; TEST_FAILS="${TOTALS#* }"
  [ "$TEST_FAILS" = 0 ] || die "$TEST_FAILS unit test(s) failed. Reports: core/build/reports/tests/test/index.html"
  ok "Unit tests: $TESTS passed (core: 금액·정산·감지·레벨·음성 문구 / source-mock: 시연 시나리오)"
  ok "$APK ($(du -h "$APK" | cut -f1))"
fi

step "6/8 Install and launch on $ZZAN_SERIAL"
if [ "$DRY_RUN" = 1 ]; then info "[dry-run] adb install -r -g $APK; pm grant --user 10 …; am start"
else
  require_pleos_emulator
  out="$(adb_s install -r -g "$APK" 2>&1 || true)"
  if case "$out" in *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) true ;; *) false ;; esac; then
    # e.g. the release APK (other signing key) is installed; Android cannot update across keys.
    confirm "A differently signed 짠드라이브 is installed (e.g. the release APK). Remove it, with its saved data" ||
      die "Kept the installed app. Remove it yourself (adb uninstall $ZZAN_APP_ID) and re-run."
    adb_s uninstall "$ZZAN_APP_ID" >/dev/null || true
    out="$(adb_s install -r -g "$APK" 2>&1 || true)"
  fi
  case "$out" in *Success*) ;; *) printf '%s\n' "$out" >&2; die "adb install failed." ;; esac
  # -g grants runtime permissions at install time; the grant below is a no-op safety net (driver user is 10).
  adb_s shell pm grant --user 10 "$ZZAN_APP_ID" pleos.car.permission.FUSED_LOCATION >/dev/null 2>&1 || true
  console_forward
  ok "Installed $ZZAN_APP_ID"
fi

step "7/8 Verify SDK integration"
if [ "$DRY_RUN" = 1 ]; then info "[dry-run] scripts/verify-app.sh"
elif [ "$VERIFY" = 1 ]; then
  VERIFY_RC=0; bash scripts/verify-app.sh || VERIFY_RC=$?
else
  adb_s shell am start -n "$ZZAN_APP_ID/.MainActivity" >/dev/null; info "Skipped (--no-verify); app launched."
fi

if [ "$E2E" = 1 ]; then
  step "시나리오 B end to end on real vehicle data (scripts/e2e-scenario-b.sh)"
  if [ "$DRY_RUN" = 1 ]; then info "[dry-run] scripts/e2e-scenario-b.sh"
  elif [ "${VERIFY_RC:-0}" != 0 ]; then warn "Skipped: the checks above failed."
  else E2E_RC=0; bash scripts/e2e-scenario-b.sh || E2E_RC=$?; fi
fi

step "8/8 Done"
if [ "$DRY_RUN" = 1 ]; then ok "Dry run complete. Nothing was installed or changed."; exit 0; fi
info "Vehicle values: $ZZAN_CONSOLE_URL/ (keep the tab open to change values by hand)"
info "Try it:         in the app, [시연 B · 충전] / [시연 A · 주유] play the plan's scenarios without the console"
info "Real data E2E:  scripts/e2e-scenario-b.sh (battery 25 % → recommendation → +40 kWh → 2,323원 → level 3)"
info "App log:        adb -s $ZZAN_SERIAL logcat -s ZzanApp ZzanDemo   (diagnostics screen: JjanShell)"
info "Stop emulator:  scripts/emulator.sh stop"
if [ "$BROWSER" = 1 ]; then
  case "$HOST_OS" in darwin) open "$ZZAN_CONSOLE_URL/" >/dev/null 2>&1 || true ;;
                     linux)  command -v xdg-open >/dev/null && xdg-open "$ZZAN_CONSOLE_URL/" >/dev/null 2>&1 || true ;; esac
fi
[ "${VERIFY_RC:-0}" = 0 ] || exit "$VERIFY_RC"
exit "${E2E_RC:-0}"
