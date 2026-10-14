# 电视推装 (TV APK Installer)

在电视上开一个 HTTP 端口，手机 / 电脑连同一个 Wi-Fi，**扫电视上的二维码**打开网页，
把 APK 拖进去就能推到这台电视安装。交互方式对齐沙发管家的「远程推装」。

<p align="center">
  <img src="docs/tv-screen.png" width="320" alt="电视端界面">
  <img src="docs/web.png" width="320" alt="网页推装页">
</p>

## 它能做什么

| 功能 | 说明 |
| --- | --- |
| 二维码推装 | 电视上显示二维码，手机相机扫一下直接进推装页，不用手敲 IP |
| 网页上传 | 拖拽 / 点选 / 粘贴直链，支持多文件，带上传进度 |
| 一键安装 | 上传完自动弹系统安装界面（可关闭自动安装，手动点「安装」） |
| 待装管理 | 电视和网页都能看待装列表，解析出应用名 / 版本 / 包名 / 体积 |
| 应用管理 | 电视上直接列出已装第三方应用，遥控器就能启动 / 卸载 |
| 6 位验证码 | 默认开启，同网段的人不扫码进不来；可在两端关闭 |
| 端口可改 | 默认 10000，电视上点端口数字就能改 |
| 开机自启 | 电视重启后服务自动拉起来 |

## 安装

1. 把 `TvApkInstaller-release-1.0.0.apk` 传到电视上（U 盘 / adb / 任意方式）。
2. 用「文件管理器」或系统安装器打开，允许「安装未知应用」。
3. 打开「电视推装」应用，电视上会显示地址和二维码。
4. 手机连**同一个 Wi-Fi**，相机扫码 → 拖 APK 进去 → 电视上确认安装。

> 没有网络 ADB 的电视（很多投影仪、盒子）就走 U 盘：APK 改后缀成 `.apk1` 放进 U 盘，
> 在「资源管理器」里长按 → 选择「软件包安装程序」。

## 命令行推装

服务开着的时候，电脑上也可以直接推：

```bash
# 原始字节流上传（推荐，文件名走 query 参数）
curl --data-binary @app.apk "http://192.168.1.100:10000/api/upload?filename=app.apk&k=123456"

# multipart 表单也支持
curl -F "file=@app.apk" "http://192.168.1.100:10000/api/upload?filename=app.apk&k=123456"

# 触发安装
curl -X POST -H 'Content-Type: application/json' \
     -d '{"name":"app.apk"}' "http://192.168.1.100:10000/api/install?k=123456"
```

## HTTP 接口

除 `GET /`、`GET /api/ping` 外，所有接口都需要验证码：`?k=`、请求头 `X-Auth-K`、
或 Cookie `tvapk`，三者任选其一。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 推装网页（免鉴权，否则页面没法先加载出来） |
| GET | `/api/ping` | 心跳 |
| GET | `/api/info` | 设备名、地址、端口、验证码、状态、已授权安装未知应用 |
| GET | `/api/apks` | 待装 APK 列表（含解析结果） |
| GET | `/api/installed` | 已装第三方应用列表 |
| POST | `/api/upload` | 上传 APK，`?filename=` 给名字；支持 raw body 和 multipart |
| POST | `/api/install` | `{"name":"app.apk"}` 触发安装 |
| POST | `/api/delete` | `{"name":"app.apk"}` 删除待装文件 |
| POST | `/api/uninstall` | `{"package":"com.x"}` 卸载单个应用 |
| POST | `/api/settings` | `autoInstall` / `pinEnabled` / `removeAfterInstall` / `autoStart` / `port` |

上传体积上限 3 GB。端口被占用会在电视上直接显示「端口 N 已被占用」。

> 批量卸载是**故意不提供**的。这个服务在局域网上可访问，而「一次卸光所有第三方应用」没有撤销路径、
> 误触也无法挽回，因此两个界面都不放这个入口，服务端也不留这个接口。卸载只能一个个来。

## 自己编译

依赖：JDK 17、Android SDK（platform 34 + build-tools 34）。

```powershell
.\tools\setup-toolchain.ps1     # 装 SDK 到 D:\android-sdk 和 Gradle 到 D:\gradle（可选）
.\tools\build-apk.ps1            # 出 release + debug APK
```

或者直接用 Android Studio 打开本目录。

> ⚠️ **`local.properties` 里的 SDK 路径必须用正斜杠。**
> 这个文件是 `java.util.Properties` 解析的，里面 `\` 是转义符：
> `sdk.dir=D:\android-sdk` 会被读成 `D:android-sdk`（一个相对路径），
> AGP 随后在 `getCanonicalFile()` 上抛一句毫无线索的
> `IOException: 文件名、目录名或卷标语法不正确`。写成 `sdk.dir=D:/android-sdk` 即可。

release 签名走仓库根目录的 `keystore.properties`，内容形如：

```properties
storeFile=tvapk-release.jks
storePassword=<你的口令>
keyAlias=tvapk
keyPassword=<你的口令>
```

**这个文件和 `.jks` 私钥都不在仓库里**（见 `.gitignore`），需要自己生成：

```powershell
keytool -genkeypair -v -keystore tvapk-release.jks -keyalg RSA -keysize 2048 `
        -validity 10000 -alias tvapk
```

没有 keystore 时构建脚本会自动跳过 release 签名（`hasReleaseKeystore` 判断），
所以 clone 下来照样能编译，只是产出的 release APK 未签名。
**这是个人自用签名，不要拿去上架应用市场。**

## 验证情况

| 项目 | 结果 |
| --- | --- |
| HTTP 层 JVM 自测 | **26/26 通过**（`tools/httpcheck/HttpCheck.kt`） |
| 网页无头冒烟 | **10/10 通过**（`tools/webharness/webcheck.mjs`，真实 index.html） |
| 接口契约 curl | 9/9 通过（鉴权、上传、列表、开关、404） |
| 二维码编码器 | 与 Python `qrcode` 库 **170/170 载荷逐位一致** |
| APK 清单 | 10 项权限、leanback + 普通双入口、四个组件齐全 |
| 签名 | release 用仓库 keystore，debug 用默认 debug key |

### 在电脑上跑真网页（不用模拟器）

`tools/webharness/` 起一套桌面替身：**真的 `MiniHttpServer` + 真的 `res/raw/index.html`**，
只有 Android 那层（FileProvider / PackageInstaller / 前台服务 / 遥控器焦点）是桩。
改完网页或 HTTP 层，不装 APK 就能回归。

```powershell
# 1) 编译（HTTP 层是纯 JVM 代码，本来就零依赖）
#    编译器用 Gradle 缓存里的 kotlin-compiler-embeddable，依赖还要带 trove4j + annotations
# 2) 起服务（端口自己挑，见下方避坑）
java -cp "<classes>;<kotlin-stdlib.jar>" com.miaoxiaohu.tvinstall.harness.HarnessKt 23456
#    验证码固定 824193，打开 http://127.0.0.1:23456/?k=824193

# 3) 无头 Chrome 跑冒烟（需要 node，脚本零 npm 依赖，直连 CDP）
node tools/webharness/webcheck.mjs http://127.0.0.1:23456/ shot.png 824193
```

⚠️ **端口别选 Chrome 的不安全端口**：`8080`、`10080`、`6666-6669` 都在 Chromium 黑名单里，
浏览器会直接返回 `net::ERR_UNSAFE_PORT` 落到错误页 —— 但 curl 一切正常，
排查起来很费时间。用 `23456` 这种高位端口。App 默认的 `10000` 不在名单里。

⚠️ 跑测试前记得清 localStorage：网页会把验证码记在本地，不清的话第二次跑会跳过验证码门，
让第 1 项「验证码门出现」假通过。脚本已经用 `Storage.clearDataForOrigin` 做了隔离。

HTTP 自测覆盖：keep-alive 多请求复用、未读请求体自动 drain、
raw/multipart 双通道上传逐字节比对（含正文里混入假 boundary、
以及真实 boundary 正好跨 64 KB 缓冲区边界）、
chunked 拒绝返回 411、超长头返回 431、负数 Content-Length 返回 400、
验证码三种传法（query / header / cookie）、24 路并发上传零串包。

跑法（需要一个 kotlinc，比如 `kotlin-compiler-embeddable`）：

```powershell
$jars = (Get-ChildItem <kotlinc-jars> -Filter *.jar).FullName -join ';'
$stdlib = '<kotlin-stdlib.jar>'
java -cp $jars org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect `
     -cp $stdlib -d lib app/src/main/java/com/miaoxiaohu/tvinstall/http/*.kt
java -cp $jars org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect `
     -cp "lib;$stdlib" -d out tools/httpcheck/HttpCheck.kt
java -cp "out;lib;$stdlib" com.miaoxiaohu.tvinstall.http.HttpCheckKt
```

## 代码结构

零第三方依赖，HTTP 服务器是自己写的，所以 APK 只有 ~150 KB。

```
app/src/main/java/com/miaoxiaohu/tvinstall/
├── MainActivity.kt        电视端界面：地址 / 二维码 / 验证码 / 待装 / 已装
├── ServerService.kt       前台服务，持有端口，通知里带「停止」按钮
├── ApiHandler.kt          路由 + 鉴权 + 上传落盘
├── ApkRepository.kt       待装目录与已装包的读写、APK 元数据解析
├── ApkInstaller.kt        交给系统安装器，失败时退回 PackageInstaller 会话
├── ApkFileProvider.kt     给系统安装器提供 content:// URI（自己实现，避开 androidx）
├── InstallEvents.kt       安装/卸载广播 → 电视和网页同步刷新
├── NetUtil.kt             挑出真正能用的局域网地址
├── http/
│   ├── MiniHttpServer.kt  HTTP/1.1 + keep-alive + 流式响应
│   ├── Multipart.kt       multipart/form-data 流式解析
│   └── HttpTypes.kt       请求 / 响应 / 限长输入流
└── qr/QrEncoder.kt        QR 编码（byte mode，EC M，v1–v7）
```

## 几个实现上的选择

- **不用 androidx**。`FileProvider` 只有几十行，自己写一个，APK 就不用背 1 MB 依赖。
- **安装走 `ACTION_VIEW` + content URI**，不是 `PackageInstaller`。
  前者是所有电视固件都认的标准安装入口，会自动处理「允许来自此来源」的弹窗。
- **HTTP 服务器拒绝 chunked encoding**，直接返回 411。
  半支持 chunked 只会让上传悄悄损坏，APK 坏了比报错更难查。
- **接口永远要 `Content-Length`**。200MB 的 APK 传两分钟，手机和 AP 都会重传。

## 已知限制

- 只能装「已装第三方应用」列表里操作，系统应用不显示（免得误删）。
- 电视没装任何「未知来源安装器」的固件上，装不上——那是系统层面的限制。
- 批量卸载走 `PackageInstaller`，个别应用会要求电视上确认一次。