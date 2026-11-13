#!/usr/bin/env bash
# Shared helpers for the bash scripts in scripts/.
# Source only. Written for macOS's bundled bash 3.2 (no associative arrays, no mapfile).
#
# Safety rules:
#   - Never accept a license on the user's behalf (no `yes |` into sdkmanager).
#   - Every install step asks first (Y/n). --yes only answers OUR prompts, never license prompts.
#   - Emulator state is only changed on a local emulator-* serial that runs a Pleos AVD.

ZZAN_ROOT="${ZZAN_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
ZZAN_STATE_DIR="${ZZAN_STATE_DIR:-$ZZAN_ROOT/build/bootstrap}"
ZZAN_SERIAL="${ZZAN_SERIAL:-emulator-5554}"
ZZAN_CONSOLE_PORT="${ZZAN_CONSOLE_PORT:-8088}"
ZZAN_CONSOLE_URL="http://127.0.0.1:${ZZAN_CONSOLE_PORT}"
ZZAN_APP_ID="${ZZAN_APP_ID:-com.zzandrive.app}"
ZZAN_DEFAULT_AVD="Pleos_Connect_v2.1.0"      # default AVD name (searched first, used when creating one)
ZZAN_PLEOS_DEVICE="Pleos_Connect_v2_0"       # device profile shipped in the image's devices.xml
ZZAN_BOOT_TIMEOUT="${ZZAN_BOOT_TIMEOUT:-300}"
DRY_RUN="${DRY_RUN:-0}"
ASSUME_YES="${ASSUME_YES:-0}"

# cmdline-tools 20.0, pinned from https://dl.google.com/android/repository/repository2-3.xml.
# Not 23.0: its sdkmanager runs on the new "Android CLI" and ignores user update sites in repositories.cfg,
# so the Pleos image never shows up (tested: 20.0 lists pleos_car_emulator, 23.0 does not).
CMDLINE_VERSION=20.0
CMDLINE_BUILD=14742923
CMDLINE_SHA1_MAC=cc27cca4b84bfdbc7df17e3d0a01d0c640d8ee71
CMDLINE_SHA1_LINUX=48833c34b761c10cb20bcd16582129395d121b27
SDK_PACKAGES="platform-tools emulator platforms;android-35 build-tools;35.0.0"

# ------------------------------------------------------------------ output

if [ -t 1 ]; then C_B=$'\033[1m'; C_G=$'\033[32m'; C_Y=$'\033[33m'; C_R=$'\033[31m'; C_0=$'\033[0m'
else C_B=""; C_G=""; C_Y=""; C_R=""; C_0=""; fi
step() { printf '\n%s==> %s%s\n' "$C_B" "$*" "$C_0"; }
info() { printf '    %s\n' "$*"; }
ok()   { printf '    %s✓%s %s\n' "$C_G" "$C_0" "$*"; }
warn() { printf '    %s!%s %s\n' "$C_Y" "$C_0" "$*" >&2; }
die()  { printf '\n%s✗ %s%s\n' "$C_R" "$*" "$C_0" >&2; exit 1; }

# run CMD...: execute, or only print under --dry-run.
run() {
  if [ "$DRY_RUN" = 1 ]; then printf '    [dry-run] %s\n' "$*"; return 0; fi
  "$@"
}

# confirm "question": 0 = yes. Reads the terminal directly so piped stdin cannot answer it.
confirm() {
  if [ "$DRY_RUN" = 1 ]; then printf '    [dry-run] would ask: %s\n' "$1"; return 0; fi
  if [ "$ASSUME_YES" = 1 ]; then info "$1 → yes (--yes)"; return 0; fi
  if [ ! -r /dev/tty ]; then warn "No terminal to ask: $1"; return 1; fi
  local ans
  printf '    %s? %s [Y/n] ' "$C_Y" "$1" > /dev/tty
  read -r ans < /dev/tty || return 1
  case "$ans" in ""|y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

need_cmd() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not found. $2"; }

# ------------------------------------------------------------------ platform

detect_platform() {
  HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
  HOST_ARCH="$(uname -m)"
  case "$HOST_ARCH" in aarch64) HOST_ARCH=arm64 ;; amd64) HOST_ARCH=x86_64 ;; esac
  # macOS under Rosetta reports x86_64 from uname; the hardware flag tells the truth.
  if [ "$HOST_OS" = darwin ] && [ "$(sysctl -n hw.optional.arm64 2>/dev/null)" = 1 ]; then HOST_ARCH=arm64; fi
  if [ "$HOST_ARCH" = arm64 ]; then
    PLEOS_ABI=arm64-v8a
    PLEOS_REPO_URL="https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml"
  else
    PLEOS_ABI=x86_64
    PLEOS_REPO_URL="https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml"
  fi
  PLEOS_IMAGE_PKG="system-images;android-34;pleos_car_emulator;${PLEOS_ABI}"
  PLEOS_IMAGE_DIR_REL="system-images/android-34/pleos_car_emulator/${PLEOS_ABI}"
  JAVA_ARCH="$([ "$HOST_ARCH" = arm64 ] && echo aarch64 || echo x86_64)"
  case "$HOST_OS" in
    darwin) SDK_DEFAULT="$HOME/Library/Android/sdk" ;;
    linux)  SDK_DEFAULT="$HOME/Android/Sdk" ;;
    *) die "Unsupported OS '$HOST_OS'. On Windows run: powershell -ExecutionPolicy Bypass -File scripts\\bootstrap.ps1" ;;
  esac
  ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"
  ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}"
}

free_gb() { # free_gb PATH -> integer GiB available on the volume holding PATH (or its nearest parent)
  local p="$1"; while [ ! -e "$p" ] && [ "$p" != / ]; do p="$(dirname "$p")"; done
  df -Pk "$p" | awk 'NR==2 {print int($4/1048576)}'
}

# ------------------------------------------------------------------ JDK

java_major() { # java_major JAVA_HOME -> 17, 21, ... (empty when unusable)
  [ -x "$1/bin/java" ] || return 0
  "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit}'
}
java_arch() { "$1/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/os.arch/ {print $2; exit}'; }

# Accept JDK 17 or 21 with the host's native arch. AGP 8.8 + Kotlin 2.0.0 are built and tested on these;
# 21 covers Android Studio's bundled JBR. Newer JDKs (e.g. 23) are skipped on purpose.
jdk_usable() {
  local home="$1" major arch
  major="$(java_major "$home")"; [ -n "$major" ] || return 1
  [ "$major" = 17 ] || [ "$major" = 21 ] || return 1
  arch="$(java_arch "$home")"
  [ "$arch" = "$JAVA_ARCH" ] || { [ "$JAVA_ARCH" = x86_64 ] && [ "$arch" = amd64 ]; }
}

jdk_candidates() {
  [ -n "${ZZAN_JAVA_HOME:-}" ] && echo "$ZZAN_JAVA_HOME"
  [ -n "${JAVA_HOME:-}" ] && echo "$JAVA_HOME"
  if command -v brew >/dev/null 2>&1; then
    echo "$(brew --prefix 2>/dev/null)/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  fi
  echo "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  echo "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  if [ -x /usr/libexec/java_home ]; then
    # -V lists every registered JDK; java_home -v is not a strict filter, so check each one ourselves.
    /usr/libexec/java_home -V 2>&1 | awk -F'" ' '/^[[:space:]]+1?[0-9]/ {print $NF}' | sed 's/^[[:space:]]*//'
  fi
  echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ls -d /usr/lib/jvm/*17* /usr/lib/jvm/*21* 2>/dev/null
}

# resolve_jdk: export JAVA_HOME to the first usable candidate. Prefers 17, then 21 (Android Studio JBR).
resolve_jdk() {
  local c best=""
  while IFS= read -r c; do
    [ -n "$c" ] || continue
    jdk_usable "$c" || continue
    if [ "$(java_major "$c")" = 17 ]; then best="$c"; break; fi
    [ -z "$best" ] && best="$c"
  done <<EOF
$(jdk_candidates)
EOF
  [ -n "$best" ] || return 1
  export JAVA_HOME="$best"
}

ensure_jdk() {
  step "1/8 JDK (17 preferred, native $JAVA_ARCH)"
  if resolve_jdk; then ok "JAVA_HOME=$JAVA_HOME ($(java_major "$JAVA_HOME"), $(java_arch "$JAVA_HOME"))"; return; fi
  warn "No JDK 17 or 21 for $JAVA_ARCH found (x86_64 JDKs on Apple Silicon and JDK 22+ are skipped on purpose)."
  if [ "$HOST_OS" = darwin ] && command -v brew >/dev/null 2>&1; then
    confirm "Install OpenJDK 17 with Homebrew (brew install openjdk@17)" || die "JDK 17 is required."
    run brew install openjdk@17
    [ "$DRY_RUN" = 1 ] && { JAVA_HOME="(dry-run)"; return; }
    resolve_jdk || die "openjdk@17 installed but not usable. Check: brew info openjdk@17"
    ok "JAVA_HOME=$JAVA_HOME"
  else
    die "Install JDK 17 (macOS: https://brew.sh then 'brew install openjdk@17'; Linux: your package manager), then re-run."
  fi
}

# ------------------------------------------------------------------ Android SDK

resolve_sdk_root() {
  local from_props=""
  if [ -f "$ZZAN_ROOT/local.properties" ]; then
    from_props="$(sed -n 's/^sdk\.dir=//p' "$ZZAN_ROOT/local.properties" | tail -1)"
  fi
  ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${from_props:-$SDK_DEFAULT}}}"
  export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
  ADB="$ANDROID_HOME/platform-tools/adb"
  EMULATOR="$ANDROID_HOME/emulator/emulator"
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
}

cmdline_tools_url_sha1() { # prints "URL SHA1" for this host (20.0 ships one universal macOS zip)
  local name sha
  case "$HOST_OS" in
    darwin) name="commandlinetools-mac-${CMDLINE_BUILD}_latest.zip";   sha="$CMDLINE_SHA1_MAC" ;;
    *)      name="commandlinetools-linux-${CMDLINE_BUILD}_latest.zip"; sha="$CMDLINE_SHA1_LINUX" ;;
  esac
  echo "https://dl.google.com/android/repository/$name $sha"
}

# The 23.0+ sdkmanager prints "... (Android CLI)" for --version and cannot see the Pleos update site.
is_android_cli() { "$1/bin/sdkmanager" --version 2>&1 | grep -q 'Android CLI'; }

use_cmdline_tools() { # use_cmdline_tools DIR
  SDKMANAGER="$1/bin/sdkmanager"; AVDMANAGER="$1/bin/avdmanager"
  export PATH="$1/bin:$PATH"
}

sha1_of() { shasum -a 1 "$1" 2>/dev/null | awk '{print $1}' || sha1sum "$1" | awk '{print $1}'; }

# verify_sha1 FILE EXPECTED -> 0 when equal (EXPECTED empty = skip with warning)
verify_sha1() {
  [ -n "$2" ] || { warn "No pinned checksum for $(basename "$1"); skipping verification."; return 0; }
  [ "$(sha1_of "$1")" = "$2" ]
}

ensure_cmdline_tools() {
  step "2/8 Android SDK at $ANDROID_HOME"
  local latest="$ANDROID_HOME/cmdline-tools/latest" pinned="$ANDROID_HOME/cmdline-tools/$CMDLINE_VERSION" dest
  if [ -x "$latest/bin/sdkmanager" ] && ! is_android_cli "$latest"; then
    use_cmdline_tools "$latest"; ok "cmdline-tools present ($(sed -n 's/^Pkg.Revision=//p' "$latest/source.properties" 2>/dev/null))"; return
  fi
  if [ -x "$pinned/bin/sdkmanager" ]; then use_cmdline_tools "$pinned"; ok "cmdline-tools $CMDLINE_VERSION present"; return; fi
  if [ -x "$latest/bin/sdkmanager" ]; then
    # Keep the user's newer tools; add 20.0 next to them only for sdkmanager/avdmanager.
    warn "cmdline-tools/latest is the new Android CLI, which cannot see the Pleos update site."
    dest="$pinned"
  else
    [ -x "$latest/bin/sdkmanager" ] || warn "Android command-line tools not found."
    dest="$latest"
  fi
  local url sha tmp
  read -r url sha <<EOF
$(cmdline_tools_url_sha1)
EOF
  confirm "Download Android cmdline-tools $CMDLINE_VERSION (~150 MB) into $dest" \
    || die "cmdline-tools $CMDLINE_VERSION are required for the Pleos image."
  need_cmd curl; need_cmd unzip
  [ "$DRY_RUN" = 1 ] && { info "[dry-run] curl $url; verify sha1 $sha; unzip"; return; }
  tmp="$(mktemp -d)"
  curl -fL --progress-bar -o "$tmp/cmdline-tools.zip" "$url" || die "Download failed: $url"
  verify_sha1 "$tmp/cmdline-tools.zip" "$sha" || die "Checksum mismatch for $url"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/x"
  [ ! -e "$dest" ] || die "$dest already exists; remove it or fix it by hand."
  mv "$tmp/x/cmdline-tools" "$dest"
  rm -rf "$tmp"
  use_cmdline_tools "$dest"
  ok "cmdline-tools $CMDLINE_VERSION installed in $dest"
}

sdk_pkg_present() { # sdk_pkg_present "platforms;android-35"
  case "$1" in
    platform-tools) [ -x "$ANDROID_HOME/platform-tools/adb" ] ;;
    emulator)       [ -x "$ANDROID_HOME/emulator/emulator" ] ;;
    *)              [ -f "$ANDROID_HOME/$(echo "$1" | tr ';' '/')/package.xml" ] ;;
  esac
}

ensure_sdk_packages() {
  local missing="" p
  for p in $SDK_PACKAGES; do sdk_pkg_present "$p" || missing="$missing $p"; done
  if [ -z "$missing" ]; then ok "SDK packages present: $SDK_PACKAGES"; return; fi
  warn "Missing SDK packages:$missing"
  confirm "Install them with sdkmanager (Google's license prompt is yours to answer)" || die "SDK packages are required."
  # shellcheck disable=SC2086
  run "$SDKMANAGER" --install $missing || die "sdkmanager failed. Accept the license (type y) and re-run."
}

# ------------------------------------------------------------------ Pleos repository + image + AVD

repo_cfg() { echo "$ANDROID_USER_HOME/repositories.cfg"; }

# Adds the Pleos update site the same way Android Studio's "SDK Update Sites" does.
# Keys need two digits (src00); "src0" is ignored by sdkmanager.
ensure_pleos_repo() {
  step "3/8 Pleos emulator image ($PLEOS_IMAGE_PKG)"
  local cfg; cfg="$(repo_cfg)"
  if [ -f "$cfg" ] && grep -qF "$PLEOS_REPO_URL" "$cfg"; then ok "Pleos update site registered in $cfg"; return; fi
  confirm "Register the Pleos update site in $cfg (a backup is kept)" || die "The Pleos update site is required for the image."
  [ "$DRY_RUN" = 1 ] && { info "[dry-run] append src entry for $PLEOS_REPO_URL"; return; }
  mkdir -p "$ANDROID_USER_HOME"
  local count=0 idx
  if [ -f "$cfg" ]; then
    cp "$cfg" "$cfg.bak.$(date +%Y%m%d%H%M%S)"
    count="$(sed -n 's/^count=//p' "$cfg" | head -1)"; count="${count:-0}"
  fi
  idx="$(printf '%02d' "$count")"
  {
    if [ -f "$cfg" ]; then sed '/^count=/d' "$cfg"; fi
    printf 'src%s=%s\ndisp%s=Pleos Connect System Image\nenabled%s=true\n' "$idx" "$PLEOS_REPO_URL" "$idx" "$idx"
  } > "$cfg.new"
  { printf 'count=%s\n\n' "$((count + 1))"; grep -v '^[[:space:]]*$' "$cfg.new"; } > "$cfg"
  rm -f "$cfg.new"
  ok "Registered as src$idx in $cfg"
}

ensure_pleos_image() {
  if [ -f "$ANDROID_HOME/$PLEOS_IMAGE_DIR_REL/system.img" ]; then ok "Pleos image installed"; return; fi
  local gb; gb="$(free_gb "$ANDROID_HOME")"
  warn "Pleos image not installed. Download ~4.7 GB, ~19 GB after unpacking. Free space: ${gb} GiB."
  [ "$gb" -ge 25 ] || [ "$DRY_RUN" = 1 ] || die "Need at least 25 GiB free on the SDK volume (have ${gb} GiB)."
  info "sdkmanager will show the 42dot Pleos Connect SDK License. Read it and answer it yourself."
  confirm "Install $PLEOS_IMAGE_PKG now (takes several minutes)" || die "The Pleos image is required."
  run "$SDKMANAGER" --install "$PLEOS_IMAGE_PKG" || die "Image install failed. Accept the license (type y) and re-run."
}

avd_sysdir() { sed -n 's/^image\.sysdir\.1=//p' "$ANDROID_AVD_HOME/$1.avd/config.ini" 2>/dev/null | head -1; }
is_pleos_avd() { case "$(avd_sysdir "$1")" in *pleos_car_emulator*) return 0 ;; *) return 1 ;; esac; }

# find_pleos_avd: print one Pleos AVD name. Order: $ZZAN_AVD, $ZZAN_DEFAULT_AVD, then any other.
find_pleos_avd() {
  local n f
  if [ -n "${ZZAN_AVD:-}" ]; then is_pleos_avd "$ZZAN_AVD" && { echo "$ZZAN_AVD"; return 0; }; return 1; fi
  is_pleos_avd "$ZZAN_DEFAULT_AVD" && { echo "$ZZAN_DEFAULT_AVD"; return 0; }
  for f in "$ANDROID_AVD_HOME"/*.ini; do
    [ -f "$f" ] || continue
    n="$(basename "$f" .ini)"
    is_pleos_avd "$n" && { echo "$n"; return 0; }
  done
  return 1
}

set_ini() { # set_ini FILE KEY VALUE (replace or append)
  if grep -q "^$2=" "$1"; then sed -i.bak "s|^$2=.*|$2=$3|" "$1" && rm -f "$1.bak"; else printf '%s=%s\n' "$2" "$3" >> "$1"; fi
}

ensure_avd() {
  local name
  if name="$(find_pleos_avd)"; then ZZAN_AVD="$name"; ok "AVD: $ZZAN_AVD"; return; fi
  name="${ZZAN_AVD:-$ZZAN_DEFAULT_AVD}"
  confirm "Create AVD '$name' (Pleos Connect 2.1.0, 4 GB RAM, cold boot)" || die "A Pleos AVD is required."
  # "no" answers avdmanager's "custom hardware profile?" question; the device profile supplies the rest.
  if [ "$DRY_RUN" = 1 ]; then info "[dry-run] avdmanager create avd -n $name -k $PLEOS_IMAGE_PKG -d $ZZAN_PLEOS_DEVICE"; ZZAN_AVD="$name"; return; fi
  echo no | "$AVDMANAGER" create avd -n "$name" -k "$PLEOS_IMAGE_PKG" -d "$ZZAN_PLEOS_DEVICE" >/dev/null \
    || die "avdmanager could not create '$name'."
  local cfg="$ANDROID_AVD_HOME/$name.avd/config.ini" kv
  for kv in hw.ramSize=4096 fastboot.forceColdBoot=yes fastboot.forceFastBoot=no hw.gpu.enabled=yes \
            hw.gpu.mode=host disk.dataPartition.size=6G PlayStore.enabled=no hw.audioInput=yes; do
    set_ini "$cfg" "${kv%%=*}" "${kv#*=}"
  done
  ZZAN_AVD="$name"; ok "Created AVD $ZZAN_AVD"
}

# ------------------------------------------------------------------ emulator

adb_s() { "$ADB" -s "$ZZAN_SERIAL" "$@"; }
serial_online() { [ "$("$ADB" -s "$ZZAN_SERIAL" get-state 2>/dev/null)" = device ]; }
running_avd_name() { adb_s emu avd name 2>/dev/null | tr -d '\r' | head -1; }

# require_pleos_emulator: refuse anything but a local emulator running a Pleos AVD.
require_pleos_emulator() {
  case "$ZZAN_SERIAL" in emulator-*) ;; *) die "Only a local emulator serial is allowed (got $ZZAN_SERIAL)." ;; esac
  serial_online || die "$ZZAN_SERIAL is not online. Start it with scripts/bootstrap.sh or scripts/emulator.sh start."
  local avd; avd="$(running_avd_name)"
  is_pleos_avd "$avd" || die "$ZZAN_SERIAL runs AVD '$avd', which is not a Pleos Connect AVD. Refusing."
}

wait_for_boot() {
  local t=0
  info "Waiting for boot (timeout ${ZZAN_BOOT_TIMEOUT}s)"
  while [ $t -lt "$ZZAN_BOOT_TIMEOUT" ]; do
    if serial_online && [ "$(adb_s shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] \
       && adb_s shell pm path android >/dev/null 2>&1; then
      break
    fi
    sleep 3; t=$((t + 3))
  done
  [ $t -lt "$ZZAN_BOOT_TIMEOUT" ] || die "Boot timed out. Log: $ZZAN_STATE_DIR/emulator.log"
  # AAOS runs apps as the driver user (10). Wait until the system switched to it.
  local u=0
  while [ $u -lt 60 ]; do
    [ "$(adb_s shell am get-current-user 2>/dev/null | tr -d '\r')" != 0 ] && break
    sleep 2; u=$((u + 2))
  done
  ok "Booted in ~$((t + u))s (current user $(adb_s shell am get-current-user 2>/dev/null | tr -d '\r'))"
}

start_emulator() {
  step "4/8 Emulator $ZZAN_SERIAL"
  mkdir -p "$ZZAN_STATE_DIR"
  run "$ADB" start-server >/dev/null 2>&1 || true
  if serial_online; then
    local avd; avd="$(running_avd_name)"
    is_pleos_avd "$avd" || die "$ZZAN_SERIAL is already used by '$avd' (not Pleos). Stop it first."
    ok "Already running: $avd"; ZZAN_AVD="$avd"
    [ "$DRY_RUN" = 1 ] || wait_for_boot
    return
  fi
  if [ "$DRY_RUN" = 1 ]; then info "[dry-run] emulator -avd $ZZAN_AVD -port 5554 -no-snapshot -gpu host"; return; fi
  if pgrep -f "qemu-system.*-avd $ZZAN_AVD( |\$)" >/dev/null 2>&1; then
    die "$ZZAN_AVD is already running on another port (e.g. started from Android Studio). Close it, or set ZZAN_SERIAL=emulator-<port>."
  fi
  info "Starting $ZZAN_AVD (window opens; log: $ZZAN_STATE_DIR/emulator.log)"
  nohup "$EMULATOR" -avd "$ZZAN_AVD" -port "${ZZAN_SERIAL#emulator-}" -no-snapshot -gpu host \
    > "$ZZAN_STATE_DIR/emulator.log" 2>&1 &
  echo $! > "$ZZAN_STATE_DIR/emulator.pid"
  wait_for_boot
}

# ------------------------------------------------------------------ Virtual Console (127.0.0.1:8088)

console_forward() { adb_s forward "tcp:$ZZAN_CONSOLE_PORT" "tcp:$ZZAN_CONSOLE_PORT" >/dev/null; }
console_http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$ZZAN_CONSOLE_URL/" || true; }
console_get() { curl -fsS --max-time 5 "$ZZAN_CONSOLE_URL$1"; }
console_put() { curl -fsS --max-time 5 -X PUT "$ZZAN_CONSOLE_URL$1"; }

# json_get KEYPATH < json  (macOS plutil, else python3, else jq)
json_get() {
  if command -v plutil >/dev/null 2>&1 && [ "$(uname -s)" = Darwin ]; then
    plutil -extract "$1" raw -o - - 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    python3 -I -c 'import json,sys
v=json.load(sys.stdin)
for k in sys.argv[1].split("."): v=v[int(k)] if isinstance(v,list) else v[k]
print(str(v).lower() if isinstance(v,bool) else v)' "$1" 2>/dev/null
  else
    jq -r ".$1" 2>/dev/null
  fi
}

# The console only accepts writes while a browser session sends heartbeats (3 s timeout).
# Opening the page in a browser does this; here we send the same POST every second.
console_session_start() {
  CONSOLE_CLIENT="zzan-bootstrap-$$-$(date +%s)"
  ( while :; do curl -s -o /dev/null --max-time 2 -X POST "$ZZAN_CONSOLE_URL/browser/session?client=$CONSOLE_CLIENT"; sleep 1; done ) &
  CONSOLE_HB_PID=$!
  local t=0
  while [ $t -lt 15 ]; do
    [ "$(console_get /vehicle/state | json_get controls.emulator_active)" = true ] && return 0
    sleep 1; t=$((t + 1))
  done
  return 1
}

console_session_stop() {
  [ -n "${CONSOLE_HB_PID:-}" ] || return 0
  kill "$CONSOLE_HB_PID" 2>/dev/null || true
  wait "$CONSOLE_HB_PID" 2>/dev/null || true   # 143 (SIGTERM) is expected here
  curl -s -o /dev/null --max-time 2 -X POST "$ZZAN_CONSOLE_URL/browser/session?client=$CONSOLE_CLIENT&closing=1" || true
  CONSOLE_HB_PID=""
}
