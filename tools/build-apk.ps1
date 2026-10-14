#requires -Version 5.1
# Build the release APK. ASCII-only on purpose: PS 5.1 reads .ps1 as ANSI without a BOM.

$ErrorActionPreference = 'Stop'

# Derived from this script's own location so the repo is portable.
$root = Split-Path -Parent $PSScriptRoot
$jdk  = $env:JAVA_HOME
$gradle = (Get-Command gradle -ErrorAction SilentlyContinue).Source
if (-not $gradle) { $gradle = (Get-Command gradle.bat -ErrorAction SilentlyContinue).Source }

if (-not $gradle -or -not (Test-Path $gradle)) {
    throw "Gradle not found on PATH - run tools\setup-toolchain.ps1 first"
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    throw "JAVA_HOME is not set to a JDK 17 install"
}

$sdkDir = 'D:\android-sdk'
if (-not (Test-Path $sdkDir)) { throw "Android SDK not found at $sdkDir - run tools\setup-toolchain.ps1 first" }

# These two are present but EMPTY in this machine's environment block, which makes
# AGP's SdkLocator call File("") and blow up with an opaque IOException. Pin them.
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir

# NOTE: forward slashes on purpose. local.properties is parsed by java.util.Properties,
# where '\' is an escape character - "sdk.dir=D:\android-sdk" loads as the relative
# path "D:android-sdk" and AGP then dies with an opaque IOException from getCanonicalFile().
[System.IO.File]::WriteAllText(
    (Join-Path $root 'local.properties'),
    "sdk.dir=$($sdkDir -replace '\\','/')`n",
    (New-Object System.Text.ASCIIEncoding)
)

Set-Location $root

$env:JAVA_HOME = $jdk
$gradleArgs = @(
    '--no-daemon',
    '--stacktrace',
    ':app:assembleRelease',
    ':app:assembleDebug'
)

& $gradle @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "gradle build failed with exit code $LASTEXITCODE" }

Write-Output ''
Write-Output '=== APK output ==='

# AGP's own variant naming is verbose; give the deliverables stable, readable names.
# The version comes from output-metadata.json rather than being hardcoded - a hardcoded
# map silently mislabels every build after the first version bump.
$apkRoot = Join-Path $root 'app\build\outputs\apk'
Get-ChildItem -Path $apkRoot -Recurse -Filter '*.apk' | ForEach-Object {
    $kind = if ($_.FullName -match '\\release\\') { 'release' } else { 'debug' }

    $version = $null
    $meta = Join-Path $_.DirectoryName 'output-metadata.json'
    if (Test-Path $meta) {
        try {
            $version = (Get-Content $meta -Raw | ConvertFrom-Json).elements[0].versionName
        } catch {
            $version = $null
        }
    }
    if (-not $version) {
        Write-Warning "no versionName in $meta - leaving $($_.Name) as-is"
        return
    }

    $target = Join-Path $_.DirectoryName "TvApkInstaller-$kind-$version.apk"
    if ($_.FullName -ne $target) { Move-Item -Path $_.FullName -Destination $target -Force }
}

Get-ChildItem -Path $apkRoot -Recurse -Filter '*.apk' |
    Select-Object @{n='Path';e={$_.FullName}}, @{n='SizeKB';e={[math]::Round($_.Length / 1KB, 1)}} |
    Format-Table -AutoSize