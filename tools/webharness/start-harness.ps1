<#
  一键起「电视推装 · JVM 测试台」。

  跑的是真的 MiniHttpServer + 真的 res/raw/index.html，所以改完网页或 HTTP 层
  不装 APK 就能回归。Android 那层（FileProvider / PackageInstaller / 前台服务 /
  遥控器焦点）是桩，这部分仍然只能靠真机。

  用法：
      powershell -ExecutionPolicy Bypass -File tools\webharness\start-harness.ps1
      powershell -ExecutionPolicy Bypass -File tools\webharness\start-harness.ps1 -Port 8888 -NoBrowser

  Ctrl+C 结束。
#>
[CmdletBinding()]
param(
    [int]$Port = 23456,
    [switch]$NoBrowser,
    [switch]$Recompile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # ...\apk_install
$out  = Join-Path $env:TEMP 'tvapk-harness'
$cls  = Join-Path $out 'classes'

# --- 端口自检：Chrome 会静默拦截一批「不安全端口」，必须在起服务前排除 ----------
function Test-PortFree([int]$p) {
    $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $p)
    try { $l.Start(); return $true } catch { return $false } finally { $l.Stop() }
}
# 与 Chromium net/base/port_util.cc 的 IsUnsafePort 对齐（调试端口常用子集）
$unsafe = @(22,23,25,37,42,43,53,69,77,79,87,95,101,102,103,104,109,110,111,113,
            115,117,119,123,135,137,139,143,161,179,389,427,465,512,513,514,515,
            526,530,531,532,540,548,554,556,563,587,601,636,989,990,993,995,1719,
            1720,1723,2049,3659,4045,5060,5061,6000,6566,6665,6666,6667,6668,6669,
            6679,6697,8080,10080)
if ($unsafe -contains $Port) {
    Write-Host "端口 $Port 在 Chrome 的「不安全端口」黑名单里。" -ForegroundColor Yellow
    Write-Host "curl 能通，但浏览器会直接 ERR_UNSAFE_PORT 打不开。换个高位端口，例如 -Port 23456" -ForegroundColor Yellow
    exit 2
}
if (-not (Test-PortFree $Port)) {
    Write-Host "端口 $Port 已被占用。换个端口，例如 -Port 8888" -ForegroundColor Yellow
    exit 2
}

# --- 定位 kotlinc（复用 Gradle 缓存，不额外装工具链）--------------------------
$g      = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
$cc     = (Get-ChildItem "$g\org.jetbrains.kotlin\kotlin-compiler-embeddable" -Recurse -Filter '*.jar' 2>$null |
           Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1).FullName
$st     = (Get-ChildItem "$g\org.jetbrains.kotlin\kotlin-stdlib" -Recurse -Filter 'kotlin-stdlib-1.9*.jar' 2>$null |
           Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1).FullName
$tr     = (Get-ChildItem "$g\org.jetbrains.intellij.deps\trove4j" -Recurse -Filter '*.jar' 2>$null |
           Select-Object -First 1).FullName
$ann    = (Get-ChildItem "$g\org.jetbrains\annotations" -Recurse -Filter '*.jar' 2>$null |
           Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1).FullName
if (-not ($cc -and $st)) {
    Write-Host "找不到 kotlin-compiler-embeddable / kotlin-stdlib。" -ForegroundColor Red
    Write-Host "先跑一次 tools\build-apk.ps1 把依赖拉进 Gradle 缓存。" -ForegroundColor Red
    exit 3
}

# --- 编译（默认增量：目录已存在就跳过）-----------------------------------------
if ($Recompile -or -not (Test-Path (Join-Path $cls 'com\miaoxiaohu\tvinstall\harness\HarnessKt.class'))) {
    New-Item -ItemType Directory -Force -Path $cls | Out-Null
    $src = @(
        (Join-Path $root 'app\src\main\java\com\miaoxiaohu\tvinstall\http\MiniHttpServer.kt')
        (Join-Path $root 'app\src\main\java\com\miaoxiaohu\tvinstall\http\HttpTypes.kt')
        (Join-Path $root 'app\src\main\java\com\miaoxiaohu\tvinstall\http\Multipart.kt')
        (Join-Path $root 'tools\webharness\Harness.kt')
    )
    Write-Host "编译中..."
    & java -cp "$cc;$st;$tr;$ann" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -no-stdlib -nowarn -cp $st -d $cls @src
    if ($LASTEXITCODE -ne 0) { Write-Host "编译失败" -ForegroundColor Red; exit 4 }
    Write-Host "编译完成。`n"
}

$url = "http://127.0.0.1:$Port/?k=824193"
Write-Host "  验证码 : 824193" -ForegroundColor Cyan
Write-Host "  地址   : $url`n" -ForegroundColor Cyan

if (-not $NoBrowser) { Start-Process $url }

# --- 前台运行（Ctrl+C 结束，不会像后台任务那样 1 小时被杀）------------------
$env:JAVA_TOOL_OPTIONS = ''
& java "-Dfile.encoding=UTF-8" -cp "$cls;$st" com.miaoxiaohu.tvinstall.harness.HarnessKt $Port