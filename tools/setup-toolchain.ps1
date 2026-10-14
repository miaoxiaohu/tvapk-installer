#requires -Version 5.1
# Provision Android SDK + Gradle so the APK can be built on this machine.
# ASCII-only on purpose: PS 5.1 reads .ps1 as ANSI when there is no BOM.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Derived from this script's own location so the repo is portable.
$root = Split-Path -Parent $PSScriptRoot

# Override these if you keep the toolchain somewhere else, or export
# ANDROID_HOME / ANDROID_SDK_ROOT / JAVA_HOME before calling this script.
$SDK   = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'android-sdk' }
$GDIR  = if ($env:GRADLE_HOME) { $env:GRADLE_HOME } else { Join-Path $env:LOCALAPPDATA 'gradle' }
$TMP   = Join-Path $root '.toolcache'
$JDK   = $env:JAVA_HOME

$env:JAVA_HOME = $JDK
$env:PATH = "$JDK\bin;$env:PATH"

function Step($m) { Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

New-Item -ItemType Directory -Force -Path $SDK, $GDIR, $TMP | Out-Null

# ---------------------------------------------------------------- cmdline-tools
$zipCandidates = @(
  'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
  'https://dl.google.com/android/repository/commandlinetools-win-10406996_latest.zip'
)
$zip = Join-Path $TMP 'cmdline-tools.zip'
$ok = $false
foreach ($u in $zipCandidates) {
  Step "downloading $u"
  & curl.exe -L --fail --retry 3 --retry-delay 2 -o $zip $u
  if ($LASTEXITCODE -eq 0 -and (Test-Path $zip) -and (Get-Item $zip).Length -gt 50MB) { $ok = $true; break }
}
if (-not $ok) { throw 'cmdline-tools download failed' }

$stage = Join-Path $TMP 'cmdline-stage'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Step 'expanding cmdline-tools'
Expand-Archive -Path $zip -DestinationPath $stage -Force

$latest = Join-Path $SDK 'cmdline-tools\latest'
if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
New-Item -ItemType Directory -Force -Path (Split-Path $latest) | Out-Null
Move-Item -Path (Join-Path $stage 'cmdline-tools') -Destination $latest
Write-Output 'cmdline-tools staged'

$sdkm = Join-Path $latest 'bin\sdkmanager.bat'
if (-not (Test-Path $sdkm)) { throw "sdkmanager not found at $sdkm" }

# ------------------------------------------------------------------- licenses
Step 'accepting licenses'
# NOTE: PowerShell 5.1 has no '<' stdin redirection operator - pipe the answers in.
$answers = 1..40 | ForEach-Object { 'y' }
$answers | & $sdkm --sdk_root="$SDK" --licenses
Write-Output ("licenses exit={0}" -f $LASTEXITCODE)

# --------------------------------------------------------------------- packages
$packages = @(
  'platform-tools',
  'platforms;android-34',
  'build-tools;34.0.0'
)
foreach ($p in $packages) {
  Step "installing $p"
  & $sdkm --sdk_root="$SDK" $p
  Write-Output ("  exit={0}" -f $LASTEXITCODE)
}

# ----------------------------------------------------------------------- gradle
$gver = '8.7'
$gzip = Join-Path $TMP "gradle-$gver-bin.zip"
$groot = Join-Path $GDIR "gradle-$gver"
if (-not (Test-Path (Join-Path $groot 'bin\gradle.bat'))) {
  Step "downloading gradle $gver"
  & curl.exe -L --fail --retry 3 --retry-delay 2 -o $gzip "https://services.gradle.org/distributions/gradle-$gver-bin.zip"
  if ($LASTEXITCODE -ne 0) { throw 'gradle download failed' }
  Step 'expanding gradle'
  Expand-Archive -Path $gzip -DestinationPath $GDIR -Force
}
Write-Output ("gradle present: {0}" -f (Test-Path (Join-Path $groot 'bin\gradle.bat')))

# ------------------------------------------------------------------- local.prop
# Forward slashes only: java.util.Properties treats '\' as an escape, so a Windows
# backslash path silently degrades into a relative path and AGP fails to locate the SDK.
$lp = Join-Path $root 'local.properties'
[System.IO.File]::WriteAllText($lp, "sdk.dir=$($SDK -replace '\\','/')`n", (New-Object System.Text.ASCIIEncoding))
Write-Output "wrote $lp"

Step 'DONE'
Get-ChildItem $SDK | Select-Object -ExpandProperty Name | ForEach-Object { "  sdk/$_" }