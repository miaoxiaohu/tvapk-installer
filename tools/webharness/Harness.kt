package com.miaoxiaohu.tvinstall.harness

import com.miaoxiaohu.tvinstall.http.HttpRequest
import com.miaoxiaohu.tvinstall.http.HttpResponse
import com.miaoxiaohu.tvinstall.http.MiniHttpServer
import com.miaoxiaohu.tvinstall.http.Multipart
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Desktop stand-in for the TV app.
 *
 * The emulator cannot run on this machine (it is itself a QEMU guest, so there is no
 * hardware virtualisation left for the emulator to use and software x86 emulation
 * hard-hangs). That does not stop us from testing the part that carries most of the
 * risk: the hand-rolled HTTP server plus the real index.html.
 *
 * This runs the REAL MiniHttpServer and serves the REAL res/raw/index.html, so what
 * you click in the browser is the same code the TV serves. The Android-specific
 * pieces (FileProvider, PackageInstaller, the foreground service, D-pad focus) are
 * stubbed and therefore NOT covered here.
 *
 * Run:  java -cp <classes>;<kotlin-stdlib> com.miaoxiaohu.tvinstall.harness.HarnessKt [port]
 */
object Harness {

    // ------------------------------------------------------------ fake state

    @Volatile var pinEnabled = true
    @Volatile var pin = "824193"
    @Volatile var autoInstall = true
    @Volatile var removeAfterInstall = false
    @Volatile var autoStart = true

    val startedAt = System.currentTimeMillis()
    private val uploads = AtomicLong(0)

    /** Files the "TV" has received. */
    val inbox: File = File(System.getProperty("java.io.tmpdir"), "tvapk-harness-inbox")
        .apply { deleteRecursively(); mkdirs() }

    /** The two switches the web page drives; every write is echoed to stdout. */
    val events = StringBuilder()

    private fun note(message: String) {
        println("  $message")
    }

    // ---------------------------------------------------------------- routing

    fun handle(request: HttpRequest): HttpResponse = try {
        route(request)
    } catch (t: Throwable) {
        t.printStackTrace()
        json(500, """{"ok":false,"error":"服务器内部错误"}""")
    }

    private fun route(request: HttpRequest): HttpResponse {
        val path = request.path.trimEnd('/').ifEmpty { "/" }
        return when {
            path == "/" -> index()
            path == "/favicon.ico" -> HttpResponse.empty(204)
            path == "/api/ping" -> json(200, """{"ok":true,"time":${System.currentTimeMillis()}}""")
            path.startsWith("/api/") ->
                if (!authorized(request)) json(401, """{"ok":false,"error":"验证码错误"}""")
                else api(request, path)
            else -> HttpResponse.text(404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun api(request: HttpRequest, path: String): HttpResponse = when (path) {
        "/api/info" -> info()
        "/api/apks" -> pending()
        "/api/installed" -> installed()
        "/api/upload" -> upload(request)
        "/api/install" -> install(request)
        "/api/delete" -> delete(request)
        "/api/uninstall" -> {
            val pkg = fieldOf(request, "package")
            request.body.drain()
            note("卸载请求 -> $pkg")
            json(200, """{"ok":true,"package":"$pkg"}""")
        }
        "/api/settings" -> settings(request)
        else -> json(404, """{"ok":false,"error":"接口不存在"}""")
    }

    // --------------------------------------------------------------- handlers

    /** The real file the TV serves, straight out of res/raw. */
    private fun index(): HttpResponse {
        val file = File(INDEX_SOURCE)
        if (!file.isFile) return HttpResponse.text(500, "text/plain; charset=utf-8", "index.html 不在 $INDEX_SOURCE")
        return HttpResponse.bytes(
            200, "text/html; charset=utf-8", file.readBytes(),
            mapOf("Cache-Control" to "no-store")
        )
    }

    private fun info(): HttpResponse = json(
        200,
        """
        {"ok":true,"device":"JVM 测试台","address":"127.0.0.1","addresses":["127.0.0.1"],
         "port":$port,"baseUrl":"http://127.0.0.1:$port","url":"http://127.0.0.1:$port/?k=$pin",
         "pin":"$pin","pinRequired":$pinEnabled,"autoInstall":$autoInstall,
         "removeAfterInstall":$removeAfterInstall,"status":2,"statusText":"运行中","error":null,
         "uploaded":${uploads.get()},"hasNetwork":true,"isWifi":true,"canInstallPackages":true,
         "pendingCount":${inbox.listFiles()?.size ?: 0},
         "uptimeMs":${System.currentTimeMillis() - startedAt},"lastEvent":null,"autoStart":$autoStart}
        """.trimIndent().replace("\n", "")
    )

    private fun pending(): HttpResponse {
        val files = inbox.listFiles { f: File -> f.isFile && f.name.endsWith(".apk", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val items = files.joinToString(",") { f ->
            """{"name":"${f.name}","size":${f.length()},"modifiedAt":${f.lastModified()},
               "package":null,"label":"${f.nameWithoutExtension}","versionName":null,
               "versionCode":0,"error":null,"installed":false,"installedVersion":null}"""
        }
        return json(200, """{"ok":true,"items":[$items]}""")
    }

    /** Stand-ins so the installed-apps list has something to render and uninstall. */
    private fun installed(): HttpResponse {
        val items = listOf(
            "com.miaoxiaohu.tvinstall|电视推装|1.0.0" to "把自己排除掉了",
            "org.smarttube.stable|SmartTube|32.56" to "",
            "com.android.settings|系统设置|14" to ""
        )
        val json = items.joinToString(",") { (triple, _) ->
            val (pkg, label, version) = triple.split("|")
            """{"package":"$pkg","label":"$label","versionName":"$version",
               "versionCode":1,"enabled":true,"firstInstallAt":0}"""
        }
        return HttpResponse.json(200, """{"ok":true,"items":[$json]}""")
    }

    private fun upload(request: HttpRequest): HttpResponse {
        val rawName = request.query["filename"] ?: request.header("x-filename")
        if (rawName.isNullOrBlank()) {
            request.body.drain()
            return json(400, """{"ok":false,"error":"缺少 filename 参数"}""")
        }
        val contentType = request.contentType
        val isMultipart = contentType.startsWith("multipart/form-data", ignoreCase = true)
        val boundary = if (isMultipart) Multipart.boundaryOf(contentType) else null
        if (isMultipart && boundary == null) {
            request.body.drain()
            return json(400, """{"ok":false,"error":"multipart 请求缺少 boundary"}""")
        }

        val target = File(inbox, ApkNameSanitizer.sanitize(rawName))
        var failure: String? = null
        FileOutputStream(target).use { out ->
            if (isMultipart) {
                if (Multipart.extractFirstFile(request.body, out, boundary!!) == null) failure = "请求里没有文件字段"
            } else {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = request.body.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
        if (failure != null || target.length() == 0L) {
            target.delete()
            request.body.drain()
            return json(400, """{"ok":false,"error":"${failure ?: "上传内容为空"}"}""")
        }

        uploads.incrementAndGet()
        note("上传 ${target.name}  ${target.length()} bytes  multipart=$isMultipart")
        if (autoInstall) note("autoInstall=true -> TV 侧会弹系统安装界面")
        return json(
            200,
            """{"ok":true,"name":"${target.name}","size":${target.length()},"autoInstall":$autoInstall}"""
        )
    }

    private fun install(request: HttpRequest): HttpResponse {
        val name = fieldOf(request, "name")
        request.body.drain()
        if (name.isBlank()) return json(400, """{"ok":false,"error":"缺少 name"}""")
        if (!File(inbox, name).isFile) return json(404, """{"ok":false,"error":"找不到 $name"}""")
        note("安装请求 -> $name")
        return json(200, """{"ok":true,"name":"$name"}""")
    }

    private fun delete(request: HttpRequest): HttpResponse {
        val name = fieldOf(request, "name")
        request.body.drain()
        val file = File(inbox, name)
        val removed = file.isFile && file.delete()
        note("删除 $name -> $removed")
        return if (removed) json(200, """{"ok":true,"name":"$name","removed":true}""")
        else json(404, """{"ok":true,"name":"$name","removed":false}""")
    }

    private fun settings(request: HttpRequest): HttpResponse {
        val body = request.body.readBytes().toString(Charsets.UTF_8)
        if (body.contains("\"autoInstall\"")) {
            autoInstall = !body.contains("\"autoInstall\":false")
            note("settings -> autoInstall=$autoInstall")
        }
        if (body.contains("\"pinEnabled\"")) {
            pinEnabled = !body.contains("\"pinEnabled\":false")
            note("settings -> pinEnabled=$pinEnabled")
        }
        return info()
    }

    // ---------------------------------------------------------------- helpers

    private fun authorized(request: HttpRequest): Boolean {
        if (!pinEnabled) return true
        val token = request.authToken() ?: return false
        if (token.length != pin.length) return false
        var diff = 0
        for (i in token.indices) diff = diff or (token[i].code xor pin[i].code)
        return diff == 0
    }

    /** Minimal field extraction; the harness only ever receives flat JSON objects. */
    private fun fieldOf(request: HttpRequest, field: String): String {
        val body = runCatching { request.body.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
        val m = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(body) ?: return ""
        return m.groupValues[1]
    }

    private fun json(status: Int, body: String): HttpResponse = HttpResponse.json(status, body)

    var port: Int = 10000
    val INDEX_SOURCE = "D:\\document\\apk_install\\app\\src\\main\\res\\raw\\index.html"
}

/** Mirrors ApkFileProvider.sanitize so uploads land the same way they do on the TV. */
object ApkNameSanitizer {
    fun sanitize(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_', '-')
        val safe = if (cleaned.isBlank()) "app.apk" else cleaned
        return if (safe.endsWith(".apk", true)) safe else "$safe.apk"
    }
}

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 10000
    Harness.port = port
    Harness.inbox.mkdirs()

    println("电视推装 · JVM 测试台")
    println("  真实 HTTP 服务器 : com.miaoxiaohu.tvinstall.http.MiniHttpServer")
    println("  真实网页         : ${Harness.INDEX_SOURCE}")
    println("  收件箱           : ${Harness.inbox}")
    println("  验证码           : ${Harness.pin}")
    println("  地址             : http://127.0.0.1:$port/?k=${Harness.pin}")
    println()

    val server = MiniHttpServer(
        handler = { request -> Harness.handle(request) },
        onLog = { }
    )
    val bound = server.start(port)
    println("已启动，监听 $bound。按 Ctrl+C 结束。")
    Thread.currentThread().join()
}