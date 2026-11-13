<#
.SYNOPSIS
  One command for Windows: tools -> Pleos emulator -> unit tests + build -> install -> verify the app on real vehicle data.

.DESCRIPTION
  STATUS: not verified on a real Windows PC (written and checked on macOS).

  Run from the repository root:
    powershell -ExecutionPolicy Bypass -File scripts\bootstrap.ps1
    powershell -ExecutionPolicy Bypass -File scripts\bootstrap.ps1 -DryRun

  Every install step asks first (Y/n). -Yes answers only OUR prompts; license prompts from
  sdkmanager / winget are never answered for you.
  Emulator state is changed only on a local emulator-* serial running a Pleos AVD (Battery SOC only,
  restored afterwards).

  The end-to-end scenario check (scripts/e2e-scenario-b.sh) and the demo driver are bash scripts and
  run on macOS/Linux only for now.

.PARAMETER Yes        Answer our own Y/n prompts with yes.
.PARAMETER DryRun     Detect and print the plan; install, build and run nothing.
.PARAMETER NoVerify   Stop after installing and launching the app.
.PARAMETER Avd        Use this AVD instead of auto-detecting a Pleos AVD.
.PARAMETER NoBrowser  Do not open the Virtual Console at the end.
#>
param(
  [switch]$Yes,
  [switch]$DryRun,
  [switch]$NoVerify,
  [string]$Avd = "",
  [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8   # adb prints UTF-8 (Korean app logs)
$ProgressPreference = 'SilentlyContinue'                    # Invoke-WebRequest is very slow with progress on PS 5.1

$Root       = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$StateDir   = Join-Path $Root 'build\bootstrap'
$Serial     = if ($env:ZZAN_SERIAL) { $env:ZZAN_SERIAL } else { 'emulator-5554' }
$ConsoleUrl = 'http://127.0.0.1:8088'
$AppId      = 'com.zzandrive.app'
$DefaultAvd = 'Pleos_Connect_v2.1.0'
$PleosDevice = 'Pleos_Connect_v2_0'
$PleosRepo  = 'https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml'
$ImagePkg   = 'system-images;android-34;pleos_car_emulator;x86_64'
$ImageDir   = 'system-images\android-34\pleos_car_emulator\x86_64'
# cmdline-tools 20.0, pinned from https://dl.google.com/android/repository/repository2-3.xml.
# Not 23.0: its sdkmanager runs on the new "Android CLI" and ignores user update sites, so the Pleos image
# never shows up (tested on macOS: 20.0 lists pleos_car_emulator, 23.0 does not).
$CmdlineVersion = '20.0'
$CmdlineUrl  = 'https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip'
$CmdlineSha1 = '16b3f45ddb3d85ea6bbe6a1c0b47146daf0db450'
$SdkPackages = @('platform-tools', 'emulator', 'platforms;android-35', 'build-tools;35.0.0')
$BootTimeout = 300

New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

# ------------------------------------------------------------------ output helpers
function Step($m) { Write-Host ""; Write-Host "==> $m" -ForegroundColor White }
function Info($m) { Write-Host "    $m" }
function Ok($m)   { Write-Host "    OK  $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    !   $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host ""; Write-Host "X  $m" -ForegroundColor Red; exit 1 }

function Confirm-Step($question) {
  if ($DryRun) { Info "[dry-run] would ask: $question"; return $true }
  if ($Yes)    { Info "$question -> yes (-Yes)"; return $true }
  $ans = Read-Host "    ? $question [Y/n]"
  return ($ans -eq '' -or $ans -match '^(y|yes)$')
}

# Run a native exe, never throw on stderr, return combined output as one string.
function Invoke-Native([string]$Exe, [string[]]$Arguments) {
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $out = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $old }
  $script:NativeRc = $LASTEXITCODE
  return (@($out) -join "`n")
}

# Run a .bat (sdkmanager/avdmanager/gradlew) through cmd so ';' inside arguments survives.
# Output goes straight to this console so license prompts are visible and answered by the user.
function Invoke-Bat([string]$Bat, [string[]]$Arguments, [string]$Redirect = "", [string]$StdinText = "") {
  $quoted = ($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
  $line = '"' + $Bat + '" ' + $quoted
  if ($Redirect) { $line += ' > "' + $Redirect + '" 2>&1' }
  if ($StdinText) { $line = 'echo ' + $StdinText + '| ' + $line }
  if ($DryRun) { Info "[dry-run] $line"; return 0 }
  # Start-Process keeps stdin/stdout on this console (interactive license prompts) and returns only the exit code.
  $p = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/s', '/c', ('"' + $line + '"')) -NoNewWindow -Wait -PassThru
  return $p.ExitCode
}

# ------------------------------------------------------------------ 0. preflight
Write-Host "ZZAN DRIVE bootstrap (Windows, unverified) - $Root" -ForegroundColor White
if ($DryRun) { Write-Host "DRY RUN: nothing will be installed or changed" -ForegroundColor Yellow }

Step "0/8 Preflight"
if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
  Die "Pleos publishes the Windows emulator image for x86_64 only (this PC: $($env:PROCESSOR_ARCHITECTURE))."
}
Ok "Windows x64 -> image ABI x86_64"
try { Invoke-WebRequest -UseBasicParsing -Method Head -Uri $PleosRepo -TimeoutSec 10 | Out-Null; Ok "Pleos repository reachable" }
catch { Warn "Cannot reach nexus-playground.pleos.ai (needed on first run)." }

$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$UserHome = if ($env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME } else { Join-Path $env:USERPROFILE '.android' }
$AvdHome  = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $UserHome 'avd' }
$SdkManager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$AvdManager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$Adb        = Join-Path $Sdk 'platform-tools\adb.exe'
$EmulatorExe = Join-Path $Sdk 'emulator\emulator.exe'
$env:ANDROID_HOME = $Sdk; $env:ANDROID_SDK_ROOT = $Sdk

# ------------------------------------------------------------------ 1. JDK
function Get-JavaInfo([string]$JavaHome) {
  # [IO.Path]::Combine instead of Join-Path: Join-Path throws when the candidate's drive does not exist.
  try { $java = [System.IO.Path]::Combine($JavaHome, 'bin\java.exe') } catch { return $null }
  if (-not (Test-Path -LiteralPath $java)) { return $null }
  $v = Invoke-Native $java @('-XshowSettings:properties', '-version')
  $major = $null
  if ($v -match 'version "1\.(\d+)') { $major = [int]$Matches[1] } elseif ($v -match 'version "(\d+)') { $major = [int]$Matches[1] }
  $arch = if ($v -match 'os\.arch = (\S+)') { $Matches[1] } else { '' }
  return @{ Home = $JavaHome; Major = $major; Arch = $arch }
}

function Find-Jdk {
  $cands = @()
  if ($env:ZZAN_JAVA_HOME) { $cands += $env:ZZAN_JAVA_HOME }
  if ($env:JAVA_HOME) { $cands += $env:JAVA_HOME }
  foreach ($pattern in @('C:\Program Files\Microsoft\jdk-17*', 'C:\Program Files\Eclipse Adoptium\jdk-17*',
                         'C:\Program Files\Java\jdk-17*', 'C:\Program Files\Zulu\zulu-17*')) {
    # No -Directory: it is a FileSystem dynamic parameter and errors when the drive does not resolve.
    $cands += @(Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer } | Sort-Object Name -Descending | ForEach-Object { $_.FullName })
  }
  $cands += 'C:\Program Files\Android\Android Studio\jbr'
  $best = $null
  foreach ($c in $cands) {
    $i = Get-JavaInfo $c
    if (-not $i -or -not $i.Major) { continue }
    if ($i.Major -ne 17 -and $i.Major -ne 21) { continue }   # AGP 8.8 + Kotlin 2.0.0: JDK 17 or 21 (Android Studio JBR)
    if ($i.Arch -notin @('amd64', 'x86_64')) { continue }
    if ($i.Major -eq 17) { return $i }
    if (-not $best) { $best = $i }
  }
  return $best
}

Step "1/8 JDK (17 preferred)"
$jdk = Find-Jdk
if (-not $jdk) {
  Warn "No JDK 17 or 21 (x64) found."
  if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    if ($DryRun) { Info "[dry-run] winget not found; you would need to install JDK 17 by hand" }
    else { Die "Install JDK 17 (https://learn.microsoft.com/java/openjdk/download) and re-run." }
  }
  elseif (-not (Confirm-Step "Install Microsoft OpenJDK 17 with winget (winget shows its own agreement prompt)")) { Die "JDK 17 is required." }
  elseif (-not $DryRun) {
    & winget install --id Microsoft.OpenJDK.17 -e
    $jdk = Find-Jdk
    if (-not $jdk) { Die "JDK installed but not found. Open a new terminal and re-run." }
  }
}
if ($jdk) {
  $env:JAVA_HOME = $jdk.Home
  Ok "JAVA_HOME=$($jdk.Home) ($($jdk.Major), $($jdk.Arch))"
}
$env:Path = "$($env:JAVA_HOME)\bin;$Sdk\platform-tools;$Sdk\emulator;$Sdk\cmdline-tools\latest\bin;$($env:Path)"

# ------------------------------------------------------------------ 2. Android SDK
Step "2/8 Android SDK at $Sdk"
function Use-CmdlineTools([string]$Dir) {
  $script:SdkManager = Join-Path $Dir 'bin\sdkmanager.bat'
  $script:AvdManager = Join-Path $Dir 'bin\avdmanager.bat'
  $env:Path = "$Dir\bin;$($env:Path)"
}
# The 23.0+ sdkmanager prints "... (Android CLI)" for --version and cannot see the Pleos update site.
function Test-AndroidCli([string]$Dir) { return ((Invoke-Native (Join-Path $Dir 'bin\sdkmanager.bat') @('--version')) -match 'Android CLI') }

$latestTools = Join-Path $Sdk 'cmdline-tools\latest'
$pinnedTools = Join-Path $Sdk "cmdline-tools\$CmdlineVersion"
$toolsDest = $null
if ((Test-Path (Join-Path $latestTools 'bin\sdkmanager.bat')) -and -not (Test-AndroidCli $latestTools)) { Use-CmdlineTools $latestTools; Ok "cmdline-tools present" }
elseif (Test-Path (Join-Path $pinnedTools 'bin\sdkmanager.bat')) { Use-CmdlineTools $pinnedTools; Ok "cmdline-tools $CmdlineVersion present" }
elseif (Test-Path (Join-Path $latestTools 'bin\sdkmanager.bat')) {
  Warn "cmdline-tools\latest is the new Android CLI, which cannot see the Pleos update site. Adding $CmdlineVersion next to it."
  $toolsDest = $pinnedTools
}
else { Warn "Android command-line tools not found."; $toolsDest = $latestTools }
if ($toolsDest) {
  if (-not (Confirm-Step "Download Android cmdline-tools $CmdlineVersion (~150 MB) into $toolsDest")) { Die "cmdline-tools $CmdlineVersion are required for the Pleos image." }
  if ($DryRun) { Info "[dry-run] download $CmdlineUrl; verify SHA1 $CmdlineSha1; extract" }
  else {
    $tmp = Join-Path $env:TEMP ("zzan-cmdline-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $zip = Join-Path $tmp 'cmdline-tools.zip'
    Invoke-WebRequest -UseBasicParsing -Uri $CmdlineUrl -OutFile $zip
    $sha = (Get-FileHash -Algorithm SHA1 -Path $zip).Hash.ToLower()
    if ($sha -ne $CmdlineSha1) { Die "Checksum mismatch for $CmdlineUrl ($sha)" }
    Expand-Archive -Path $zip -DestinationPath (Join-Path $tmp 'x') -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $Sdk 'cmdline-tools') | Out-Null
    if (Test-Path $toolsDest) { Die "$toolsDest already exists; remove it or fix it by hand." }
    Move-Item (Join-Path $tmp 'x\cmdline-tools') $toolsDest
    Remove-Item -Recurse -Force $tmp
    Use-CmdlineTools $toolsDest
    Ok "cmdline-tools $CmdlineVersion installed in $toolsDest"
  }
}

function Test-SdkPackage([string]$Pkg) {
  switch ($Pkg) {
    'platform-tools' { return (Test-Path $Adb) }
    'emulator'       { return (Test-Path $EmulatorExe) }
    default          { return (Test-Path (Join-Path $Sdk (($Pkg -replace ';', '\') + '\package.xml'))) }
  }
}
$missing = @($SdkPackages | Where-Object { -not (Test-SdkPackage $_) })
if ($missing.Count -eq 0) { Ok "SDK packages present: $($SdkPackages -join ' ')" }
else {
  Warn "Missing SDK packages: $($missing -join ' ')"
  if (-not (Confirm-Step "Install them with sdkmanager (Google's license prompt is yours to answer)")) { Die "SDK packages are required." }
  $rc = Invoke-Bat $SdkManager (@('--install') + $missing)
  if ($rc -ne 0) { Die "sdkmanager failed. Accept the license (type y) and re-run." }
}

# ------------------------------------------------------------------ 3. Pleos repository + image + AVD
Step "3/8 Pleos emulator image ($ImagePkg)"
$cfg = Join-Path $UserHome 'repositories.cfg'
if ((Test-Path $cfg) -and ((Get-Content -Raw $cfg) -like "*$PleosRepo*")) { Ok "Pleos update site registered in $cfg" }
else {
  if (-not (Confirm-Step "Register the Pleos update site in $cfg (a backup is kept)")) { Die "The Pleos update site is required." }
  if (-not $DryRun) {
    New-Item -ItemType Directory -Force -Path $UserHome | Out-Null
    $lines = @(); $count = 0
    if (Test-Path $cfg) {
      Copy-Item $cfg ("$cfg.bak." + (Get-Date -Format 'yyyyMMddHHmmss'))
      $lines = @(Get-Content $cfg | Where-Object { $_ -notmatch '^count=' -and $_.Trim() -ne '' })
      $c = Get-Content $cfg | Where-Object { $_ -match '^count=(\d+)' } | Select-Object -First 1
      if ($c -and ($c -match '^count=(\d+)')) { $count = [int]$Matches[1] }
    }
    # Two-digit keys (src00); sdkmanager ignores "src0".
    $idx = '{0:D2}' -f $count
    $lines += "src$idx=$PleosRepo"; $lines += "disp$idx=Pleos Connect System Image"; $lines += "enabled$idx=true"
    $text = "count=$($count + 1)`r`n`r`n" + ($lines -join "`r`n") + "`r`n"
    [System.IO.File]::WriteAllText($cfg, $text, (New-Object System.Text.UTF8Encoding($false)))
    Ok "Registered as src$idx"
  }
}

if (Test-Path (Join-Path $Sdk "$ImageDir\system.img")) { Ok "Pleos image installed" }
else {
  $drive = (Get-Item ($(if (Test-Path $Sdk) { $Sdk } else { $env:LOCALAPPDATA }))).PSDrive
  $freeGb = [math]::Floor($drive.Free / 1GB)
  Warn "Pleos image not installed. Download ~4.1 GB, much more after unpacking. Free on $($drive.Name): $freeGb GB."
  if ($freeGb -lt 25 -and -not $DryRun) { Die "Need at least 25 GB free on the SDK drive." }
  Info "sdkmanager will show the 42dot Pleos Connect SDK License. Read it and answer it yourself."
  if (-not (Confirm-Step "Install $ImagePkg now (takes several minutes)")) { Die "The Pleos image is required." }
  $rc = Invoke-Bat $SdkManager @('--install', $ImagePkg)
  if ($rc -ne 0) { Die "Image install failed. Accept the license (type y) and re-run." }
}

function Test-PleosAvd([string]$Name) {
  $c = Join-Path $AvdHome "$Name.avd\config.ini"
  if (-not (Test-Path $c)) { return $false }
  return [bool](Select-String -Path $c -Pattern '^image\.sysdir\.1=.*pleos_car_emulator' -Quiet)
}
function Find-PleosAvd {
  if ($Avd) { if (Test-PleosAvd $Avd) { return $Avd } else { return $null } }
  if (Test-PleosAvd $DefaultAvd) { return $DefaultAvd }
  foreach ($ini in @(Get-ChildItem -Path (Join-Path $AvdHome '*.ini') -ErrorAction SilentlyContinue)) {
    if (Test-PleosAvd $ini.BaseName) { return $ini.BaseName }
  }
  return $null
}

$AvdName = Find-PleosAvd
if ($AvdName) { Ok "AVD: $AvdName" }
else {
  $AvdName = if ($Avd) { $Avd } else { $DefaultAvd }
  if (-not (Confirm-Step "Create AVD '$AvdName' (Pleos Connect 2.1.0, 4 GB RAM, cold boot)")) { Die "A Pleos AVD is required." }
  $rc = Invoke-Bat $AvdManager @('create', 'avd', '-n', $AvdName, '-k', $ImagePkg, '-d', $PleosDevice) -StdinText 'no'
  if ($rc -ne 0) { Die "avdmanager could not create '$AvdName'." }
  if (-not $DryRun) {
    $c = Join-Path $AvdHome "$AvdName.avd\config.ini"
    $ini = @(Get-Content $c)
    $set = [ordered]@{ 'hw.ramSize' = '4096'; 'fastboot.forceColdBoot' = 'yes'; 'fastboot.forceFastBoot' = 'no';
                       'hw.gpu.enabled' = 'yes'; 'hw.gpu.mode' = 'host'; 'disk.dataPartition.size' = '6G';
                       'PlayStore.enabled' = 'no'; 'hw.audioInput' = 'yes' }
    foreach ($k in $set.Keys) {
      $found = $false
      for ($i = 0; $i -lt $ini.Count; $i++) { if ($ini[$i] -like "$k=*") { $ini[$i] = "$k=$($set[$k])"; $found = $true } }
      if (-not $found) { $ini += "$k=$($set[$k])" }
    }
    [System.IO.File]::WriteAllText($c, (($ini -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
    Ok "Created AVD $AvdName"
  }
}

# ------------------------------------------------------------------ 4. emulator
function AdbS([string[]]$A) { return (Invoke-Native $Adb (@('-s', $Serial) + $A)) }
function Test-Online { return ((AdbS @('get-state')).Trim() -eq 'device') }
function Get-RunningAvd { return ((AdbS @('emu', 'avd', 'name')) -split "`n")[0].Trim() }

Step "4/8 Emulator $Serial"
if (-not $DryRun) {
  $accel = Invoke-Native $EmulatorExe @('-accel-check')
  if ($accel -notmatch 'is installed and usable') {
    Write-Host $accel
    Die ("Hardware acceleration is not available. Enable 'Windows Hypervisor Platform' (Windows Features, needs admin and a reboot), " +
         "or install the Android Emulator hypervisor driver, then re-run.")
  }
  Ok "Hardware acceleration usable"
  Invoke-Native $Adb @('start-server') | Out-Null
}
if ($DryRun) { Info "[dry-run] emulator -avd $AvdName -port 5554 -no-snapshot -gpu host" }
elseif (Test-Online) {
  $running = Get-RunningAvd
  if (-not (Test-PleosAvd $running)) { Die "$Serial is already used by '$running' (not Pleos). Stop it first." }
  $AvdName = $running; Ok "Already running: $running"
}
else {
  $dup = Get-CimInstance Win32_Process -Filter "Name LIKE 'qemu-system%'" -ErrorAction SilentlyContinue |
         Where-Object { $_.CommandLine -match ("-avd\s+" + [regex]::Escape($AvdName) + "(\s|$)") }
  if ($dup) { Die "$AvdName is already running on another port (e.g. from Android Studio). Close it first." }
  Info "Starting $AvdName (window opens; log: $StateDir\emulator.log)"
  Start-Process -FilePath $EmulatorExe -ArgumentList @('-avd', $AvdName, '-port', $Serial.Replace('emulator-', ''), '-no-snapshot', '-gpu', 'host') `
    -RedirectStandardOutput (Join-Path $StateDir 'emulator.log') -RedirectStandardError (Join-Path $StateDir 'emulator.err.log') | Out-Null
}
if (-not $DryRun) {
  $t = 0
  Info "Waiting for boot (timeout ${BootTimeout}s)"
  while ($t -lt $BootTimeout) {
    if ((Test-Online) -and ((AdbS @('shell', 'getprop', 'sys.boot_completed')).Trim() -eq '1') -and ((AdbS @('shell', 'pm', 'path', 'android')) -match 'package:')) { break }
    Start-Sleep -Seconds 3; $t += 3
  }
  if ($t -ge $BootTimeout) { Die "Boot timed out. Log: $StateDir\emulator.log" }
  $u = 0
  while ($u -lt 60 -and (AdbS @('shell', 'am', 'get-current-user')).Trim() -eq '0') { Start-Sleep -Seconds 2; $u += 2 }
  Ok "Booted (current user $((AdbS @('shell', 'am', 'get-current-user')).Trim()))"
}

# ------------------------------------------------------------------ 5-6. build, install
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$GradleTasks = @(':core:test', ':data:source-mock:test', ':app:assembleDebug')
# Sum of tests and failures over the JUnit XML results of the unit-test modules.
function Get-TestTotals {
  $t = 0; $f = 0
  foreach ($rel in @('core\build\test-results\test', 'data\source-mock\build\test-results\test')) {
    $dir = [IO.Path]::Combine($Root, $rel)
    if (-not (Test-Path $dir)) { continue }
    foreach ($x in @(Get-ChildItem -Path $dir -Filter '*.xml')) {
      [xml]$doc = [IO.File]::ReadAllText($x.FullName)
      $t += [int]$doc.testsuite.tests
      $f += [int]$doc.testsuite.failures + [int]$doc.testsuite.errors
    }
  }
  return @($t, $f)
}
Step "5/8 Unit tests + build (log: $StateDir\gradle.log)"
Push-Location $Root
try {
  $rc = Invoke-Bat (Join-Path $Root 'gradlew.bat') (@('--console=plain') + $GradleTasks) -Redirect (Join-Path $StateDir 'gradle.log')
} finally { Pop-Location }
if (-not $DryRun) {
  if ($rc -ne 0) { Get-Content (Join-Path $StateDir 'gradle.log') -Tail 30; Die "Unit tests or build failed. Full log: $StateDir\gradle.log (test reports: core\build\reports\tests\test\index.html)" }
  $totals = Get-TestTotals
  if ($totals[1] -ne 0) { Die "$($totals[1]) unit test(s) failed. Reports: core\build\reports\tests\test\index.html" }
  Ok "Unit tests: $($totals[0]) passed (core + source-mock)"
  Ok "$Apk ($([math]::Round((Get-Item $Apk).Length / 1MB)) MB)"
}

Step "6/8 Install and launch on $Serial"
if ($DryRun) { Info "[dry-run] adb install -r -g; pm grant --user 10; adb forward tcp:8088" }
else {
  if (-not ($Serial -like 'emulator-*')) { Die "Only a local emulator serial is allowed." }
  if (-not (Test-PleosAvd (Get-RunningAvd))) { Die "$Serial is not running a Pleos AVD. Refusing." }
  $out = AdbS @('install', '-r', '-g', $Apk)
  if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    # e.g. the release APK (other signing key) is installed; Android cannot update across keys.
    if (-not (Confirm-Step "A differently signed app is installed (e.g. the release APK). Remove it, with its saved data")) { Die "Kept the installed app. Remove it (adb uninstall $AppId) and re-run." }
    AdbS @('uninstall', $AppId) | Out-Null
    $out = AdbS @('install', '-r', '-g', $Apk)
  }
  if ($out -notmatch 'Success') { Write-Host $out; Die "adb install failed." }
  AdbS @('shell', 'pm', 'grant', '--user', '10', $AppId, 'pleos.car.permission.FUSED_LOCATION') | Out-Null
  AdbS @('forward', 'tcp:8088', 'tcp:8088') | Out-Null
  Ok "Installed $AppId"
}

# ------------------------------------------------------------------ 7. verify
$Rows = New-Object System.Collections.ArrayList
function Add-Row($s, $c, $d) { [void]$Rows.Add([pscustomobject]@{ status = $s; check = $c; detail = $d }) }
function Get-TagLog($tag) { return @((AdbS @('logcat', '-d', '-v', 'raw', '-s', "${tag}:I")) -split "`n" | ForEach-Object { $_.TrimEnd("`r") }) }
function Get-AppLog { return (Get-TagLog 'JjanShell') }
function Count-Lines($re) { return @(Get-AppLog | Where-Object { $_ -match $re }).Count }
function Wait-Line($re, $timeout, $min = 1) {
  for ($i = 0; $i -lt $timeout; $i++) { if ((Count-Lines $re) -ge $min) { return $true }; Start-Sleep -Seconds 1 }
  return $false
}
function Last-Line($re) { $l = @(Get-AppLog | Where-Object { $_ -match $re }); if ($l.Count) { return $l[-1] } else { return '' } }
function Console-Get($path) { return (Invoke-RestMethod -Uri ($ConsoleUrl + $path) -TimeoutSec 5) }
function Console-Put($path) { Invoke-RestMethod -Method Put -Uri ($ConsoleUrl + $path) -TimeoutSec 5 | Out-Null }

$hb = $null; $client = "zzan-bootstrap-ps-$PID"; $origSoc = $null; $socUrl = $null
function Stop-Session {
  if ($script:origSoc -ne $null -and $script:socUrl) { try { Console-Put ($script:socUrl + $script:origSoc) } catch { Warn "Could not restore SOC to $($script:origSoc)." }; $script:origSoc = $null }
  if ($script:hb) {
    Stop-Job $script:hb -ErrorAction SilentlyContinue; Remove-Job $script:hb -Force -ErrorAction SilentlyContinue; $script:hb = $null
    try { Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$ConsoleUrl/browser/session?client=$client&closing=1" -TimeoutSec 2 | Out-Null } catch {}
  }
}

Step "7/8 Verify SDK integration"
if ($DryRun) { Info "[dry-run] console session, SOC round trip, SDK log checks" }
elseif ($NoVerify) { AdbS @('shell', 'am', 'start', '-n', "$AppId/.MainActivity") | Out-Null; Info "Skipped (-NoVerify); app launched." }
else {
  try {
    $code = 0
    try { $code = (Invoke-WebRequest -UseBasicParsing -Uri "$ConsoleUrl/" -TimeoutSec 5).StatusCode } catch {}
    if ($code -eq 200) { Add-Row PASS 'console' 'HTTP 200' } else { Add-Row FAIL 'console' "HTTP $code" }

    $session = $false
    if ($code -eq 200) {
      # The console accepts writes only while a browser session sends heartbeats (3 s timeout).
      $hb = Start-Job -ArgumentList "$ConsoleUrl/browser/session?client=$client" -ScriptBlock {
        param($u) while ($true) { try { Invoke-WebRequest -UseBasicParsing -Method Post -Uri $u -TimeoutSec 2 | Out-Null } catch {}; Start-Sleep -Seconds 1 }
      }
      for ($i = 0; $i -lt 20; $i++) { try { if ((Console-Get '/vehicle/state').controls.emulator_active) { $session = $true; break } } catch {}; Start-Sleep -Seconds 1 }
      if ($session) { Add-Row PASS 'console session' 'heartbeat accepted' } else { Add-Row FAIL 'console session' 'emulator_active stayed false' }
    }

    AdbS @('shell', 'am', 'force-stop', $AppId) | Out-Null
    AdbS @('logcat', '-c') | Out-Null
    AdbS @('shell', 'am', 'start', '-W', '-n', "$AppId/.diag.DiagnosticsActivity", '--ez', 'zzan.selftest', 'true') | Out-Null
    if (Wait-Line '^App: \uC2E4\uD589\uB428' 20) { Add-Row PASS 'app launch' (Last-Line '^App: ') } else { Add-Row FAIL 'app launch' 'no launch log in 20 s' }

    [void](Wait-Line '^Vehicle: .*\uBC30\uD130\uB9AC [0-9]+%' 30)
    $l = Last-Line '^Vehicle: .*\uBC30\uD130\uB9AC'
    if ($l -match '\uBC30\uD130\uB9AC [0-9]+%') { Add-Row PASS 'Vehicle read' $l } else { Add-Row FAIL 'Vehicle read' $l }

    if (Wait-Line '^Vehicle \uBC30\uD130\uB9AC \uAD6C\uB3C5: \uB4F1\uB85D \uD638\uCD9C \uBC18\uD658' 15) {
      $feature = $null
      if ($session) { try { $feature = @((Console-Get '/vehicle/catalog').features | Where-Object { $_.id -eq 'ev-battery-soc' })[0] } catch {} }
      if ($feature) {
        $socUrl = "$($feature.write_endpoint)?field=$($feature.control.params.field)&$($feature.control.field)="
        $st = Console-Get '/vehicle/state'
        $gear = $null; if ($st.gear.actual) { $gear = $st.gear.actual.value }
        if ([double]$st.controls.speed_kph -ne 0 -or ($gear -and $gear -ne 'Park')) { Add-Row WARN 'SOC round trip' "skipped: not parked (gear=$gear)" }
        else {
          $soc = [int][math]::Floor([double]$st.ev_battery.state_of_charge)
          $target = if ($soc -eq 42) { 58 } else { 42 }
          $origSoc = $soc
          $before = Count-Lines ": ${target}%$"
          Console-Put ($socUrl + $target)
          if (Wait-Line ": ${target}%$" 20 ($before + 1)) {
            $before = Count-Lines ": ${soc}%$"
            Console-Put ($socUrl + $soc)
            if (Wait-Line ": ${soc}%$" 20 ($before + 1)) { Add-Row PASS 'SOC round trip' "console $soc->$target->$soc%, app callback received both"; $origSoc = $null }
            else { Add-Row FAIL 'SOC round trip' "restore callback ($soc%) not received" }
          } else { Add-Row FAIL 'SOC round trip' "console set $target% but no app callback in 20 s" }
        }
      } else { Add-Row WARN 'SOC round trip' 'skipped: no console session or SOC feature in /vehicle/catalog' }
    } else { Add-Row FAIL 'SOC subscription' 'register did not return' }

    if (Wait-Line '^Navi: \uC11C\uBE44\uC2A4 \uC5F0\uACB0 \uCF5C\uBC31 \uC218\uC2E0' 20) { [void](Wait-Line '^Navi \uC704\uCE58:' 10); Add-Row PASS 'NaviHelper' ("connected " + (Last-Line '^Navi \uC704\uCE58:')) }
    else { Add-Row FAIL 'NaviHelper' (Last-Line '^Navi:') }

    if (Wait-Line '^TTS: ON_DEVICE \uCD08\uAE30\uD654 \uC131\uACF5' 20) {
      if (Wait-Line '^TTS \uC774\uBCA4\uD2B8: onDone' 25) { Add-Row PASS 'TextToSpeech' 'ON_DEVICE init + speak -> onDone' }
      else { Add-Row WARN 'TextToSpeech' ("init OK, no onDone: " + (Last-Line '^TTS \uC774\uBCA4\uD2B8:')) }
    } else { Add-Row FAIL 'TextToSpeech' (Last-Line '^TTS:') }

    if (Wait-Line '^STT: ON_DEVICE \uCD08\uAE30\uD654 \uC131\uACF5' 20) {
      if (Wait-Line '^STT \uC774\uBCA4\uD2B8: onReady' 25) { Add-Row PASS 'SpeechToText' 'ON_DEVICE init + request -> onReady' }
      else { Add-Row WARN 'SpeechToText' 'init OK, no onReady' }
    } else { Add-Row FAIL 'SpeechToText' (Last-Line '^STT:') }

    if (Wait-Line '^Location (\uAD6C\uB3C5|\uB9C8\uC9C0\uB9C9 \uC704\uCE58): -?[0-9]' 20) { Add-Row PASS 'FusedLocation' (Last-Line '^Location (\uAD6C\uB3C5|\uB9C8\uC9C0\uB9C9 \uC704\uCE58): -?[0-9]') }
    elseif ((Count-Lines 'isSupported\(\) = false') -gt 0) { Add-Row WARN 'FusedLocation' 'not supported on this image' }
    else { Add-Row FAIL 'FusedLocation' (Last-Line '^Location') }

    if (Wait-Line '^UX \uC81C\uD55C:' 10) {
      $l = Last-Line '^UX \uC81C\uD55C:'
      if ($l -match '\uC815\uCC28') { Add-Row PASS 'UX restriction' $l } else { Add-Row WARN 'UX restriction' $l }
    } else { Add-Row WARN 'UX restriction' 'no CarUxRestrictions callback' }

  } finally { Stop-Session }

  (Get-AppLog) -join "`n" | Set-Content -Encoding UTF8 (Join-Path $StateDir 'app-logcat.txt')

  # Product screen: the app's own runtime (recommendation, detection, settlement) on real vehicle data.
  # Log tag ZzanApp; the line "DATA first ..." appears once per start.
  Step "Launch $AppId product screen (runtime on real vehicle data)"
  AdbS @('shell', 'am', 'force-stop', $AppId) | Out-Null
  AdbS @('logcat', '-c') | Out-Null
  AdbS @('shell', 'am', 'start', '-W', '-n', "$AppId/.MainActivity") | Out-Null
  $data = $null
  for ($i = 0; $i -lt 20 -and -not $data; $i++) {
    $data = @(Get-TagLog 'ZzanApp' | Where-Object { $_ -match '^DATA first .*origin=PLEOS_SDK' }) | Select-Object -Last 1
    if (-not $data) { Start-Sleep -Seconds 1 }
  }
  $ready = @(Get-TagLog 'ZzanApp' | Where-Object { $_ -match '^READY ' }) | Select-Object -Last 1
  if ($ready -and $data) { Add-Row PASS 'product runtime' ("$ready".Substring(6) + ' / ' + "$data".Substring(11)) }
  elseif ($ready) { Add-Row FAIL 'product runtime' 'started, but no vehicle data from the Pleos SDK within 20 s' }
  else { Add-Row FAIL 'product runtime' 'no READY log from MainActivity within 20 s' }

  $fail = @($Rows | Where-Object { $_.status -eq 'FAIL' }).Count
  $warn = @($Rows | Where-Object { $_.status -eq 'WARN' }).Count
  $report = [ordered]@{ date = (Get-Date -Format 's'); app = $AppId; serial = $Serial; avd = $AvdName; host = 'windows-x86_64';
                        checks = $Rows; fail = $fail; warn = $warn; app_log = (Join-Path $StateDir 'app-logcat.txt') }
  [System.IO.File]::WriteAllText((Join-Path $StateDir 'report.json'), ($report | ConvertTo-Json -Depth 4), (New-Object System.Text.UTF8Encoding($false)))
  Step "Result"
  foreach ($r in $Rows) {
    $color = switch ($r.status) { 'PASS' { 'Green' } 'WARN' { 'Yellow' } default { 'Red' } }
    Write-Host ("    {0,-4}  {1,-18} {2}" -f $r.status, $r.check, $r.detail) -ForegroundColor $color
  }
  Info "Report: $StateDir\report.json"
}

Step "8/8 Done"
if ($DryRun) { Ok "Dry run complete. Nothing was installed or changed."; exit 0 }
Info "Vehicle values: $ConsoleUrl/ (keep the tab open to change values by hand)"
Info "Try it:         in the app, the orange demo buttons (B: charging, A: fueling) play the plan's scenarios without the console"
Info "App log:        adb -s $Serial logcat -s ZzanApp ZzanDemo   (diagnostics screen: JjanShell)"
if (-not $NoBrowser) { Start-Process "$ConsoleUrl/" | Out-Null }
if (-not $NoVerify -and @($Rows | Where-Object { $_.status -eq 'FAIL' }).Count -gt 0) { exit 1 }
exit 0
