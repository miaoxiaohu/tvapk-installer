<#
  一键把 release APK 推到电视上安装。

      powershell -ExecutionPolicy Bypass -File tools\push-to-tv.ps1 -Device <电视IP> -Port 10000

  上传后电视会自己弹系统安装框（autoInstall=true），需要在电视上按确认。
  电视上 App 的服务没起来时，本脚本会直接告诉你，不会干等。
#>
[CmdletBinding()]
param(
    # Mandatory on purpose: this used to default to one particular TV on my network.
    [Parameter(Mandatory = $true)]
    [string]$Device,
    [int]$Port = 10000,
    [string]$Apk,
    [int]$WaitSeconds = 45
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path $root 'app\build\outputs\apk\release\TvApkInstaller-release-1.0.0.apk' }
if (-not (Test-Path $Apk)) { Write-Host "找不到 APK: $Apk" -ForegroundColor Red; exit 1 }

$base = "http://${Device}:$Port"
$size = (Get-Item $Apk).Length
Write-Host "目标 : $base" -ForegroundColor Cyan
Write-Host "APK  : $Apk  ($('{0:N0}' -f $size) bytes)`n" -ForegroundColor Cyan

# --- 先探活，电视上没开服务就直接说清楚，别干等超时 -----------------------------
$info = $null
try {
    $info = & curl.exe -s --max-time 6 "$base/api/info" | ConvertFrom-Json
} catch { }

if (-not $info) {
    Write-Host "电视上 App 的服务没有响应（端口 $Port）。" -ForegroundColor Yellow
    Write-Host "请先在电视上打开「电视推装」，它会自动把服务拉起来，然后重跑本脚本。`n" -ForegroundColor Yellow
    Write-Host "排查：电视是否开机 → 是否和本机同一 Wi-Fi → App 里是否显示「服务已停止」" -ForegroundColor DarkYellow
    exit 2
}

Write-Host "设备 : $($info.device)  $($info.statusText)  验证码=$($info.pinRequired)  待装=$($info.pendingCount)"

# --- 上传：带时间戳文件名，避免和同名旧文件冲突（电视端会自动加 -1 后缀） -------
$name = "TvApkInstaller-$(Get-Date -Format 'MMdd-HHmm').apk"
Write-Host "`n上传 $name ..." -ForegroundColor Cyan
$resp = & curl.exe -s -X POST --data-binary "@$Apk" -H 'Content-Type: application/octet-stream' "$base/api/upload?filename=$name"
Write-Host "  $resp"

$ok = ($resp | ConvertFrom-Json).ok
if (-not $ok) { Write-Host "上传失败" -ForegroundColor Red; exit 3 }

# --- 等服务重启（安装确认后进程会被替换，START_STICKY 会拉回来）----------------
Write-Host "`n请在电视上确认安装…" -ForegroundColor Yellow
$sw = [Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt $WaitSeconds) {
    Start-Sleep -Seconds 3
    try {
        $i = & curl.exe -s --max-time 6 "$base/api/info" | ConvertFrom-Json
        if ($i.uptimeMs -lt 60000) { Write-Host "服务已重启（uptime $([int]($i.uptimeMs/1000))s），安装完成。"; exit 0 }
    } catch { }
}
Write-Host "等不到服务重启，请确认电视上是否点了「安装」。" -ForegroundColor Yellow
exit 4