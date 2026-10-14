package com.miaoxiaohu.tvinstall

import android.content.Context
import com.miaoxiaohu.tvinstall.http.HttpRequest
import com.miaoxiaohu.tvinstall.http.HttpResponse
import com.miaoxiaohu.tvinstall.http.Multipart
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream

/** Callbacks the request router needs from the service that hosts it. */
interface InstallHost {
    fun requestInstall(fileName: String)
    fun requestUninstall(packageName: String)
    fun setBusy(active: Boolean)
    fun requestRestart()
}

class ApiHandler(
    private val context: Context,
    private val config: AppConfig,
    private val repo: ApkRepository,
    private val host: InstallHost,
    private val onLog: (String) -> Unit
) {

    fun handle(request: HttpRequest): HttpResponse = try {
        route(request)
    } catch (t: Throwable) {
        onLog("${request.method} ${request.path} failed: $t")
        json(500, error("服务器内部错误"))
    }

    private fun route(request: HttpRequest): HttpResponse {
        val path = request.path.trimEnd('/').ifEmpty { "/" }
        return when {
            path == "/" -> if (request.method == "GET" || request.method == "HEAD") index() else methodNotAllowed()
            path == "/favicon.ico" -> HttpResponse.empty(204)
            path == "/api/ping" && request.method == "GET" ->
                HttpResponse.json(200, ok().put("time", System.currentTimeMillis()).toString())

            path.startsWith("/api/") -> {
                if (!authorized(request)) unauthorized()
                else api(request, path)
            }

            else -> HttpResponse.text(404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun api(request: HttpRequest, path: String): HttpResponse = when (path) {
        "/api/info" -> ifGet(request) { info() }
        "/api/apks" -> ifGet(request) { pending() }
        "/api/installed" -> ifGet(request) { installed() }
        "/api/upload" -> ifPost(request) { upload(request) }
        "/api/install" -> ifPost(request) { install(request) }
        "/api/delete" -> ifPost(request) { delete(request) }
        "/api/uninstall" -> ifPost(request) { uninstall(request) }
        // No bulk endpoint: uninstalling every third-party app has no undo and is a
        // foot-gun on a LAN-reachable service, so it is deliberately not exposed.
        "/api/settings" -> ifPost(request) { settings(request) }
        else -> json(404, error("接口不存在"))
    }

    // ------------------------------------------------------------------ pages

    private fun index(): HttpResponse = try {
        // Unauthenticated on purpose: the page has to load before it can ask for a PIN.
        context.resources.openRawResource(R.raw.index).use { input ->
            HttpResponse.bytes(
                200,
                "text/html; charset=utf-8",
                input.readBytes(),
                mapOf("Cache-Control" to "no-store")
            )
        }
    } catch (t: Throwable) {
        onLog("index.html missing: $t")
        HttpResponse.text(500, "text/plain; charset=utf-8", "页面资源缺失")
    }

    // ------------------------------------------------------------------- api

    private fun info(): HttpResponse {
        val addresses = NetUtil.lanAddresses()
        val address = addresses.firstOrNull() ?: "127.0.0.1"
        val port = if (ServerState.status == ServerState.RUNNING) ServerState.port else config.port
        val base = "http://$address:$port"
        val json = JSONObject()
            .put("ok", true)
            .put("device", NetUtil.deviceLabel(context))
            .put("address", address)
            .put("addresses", JSONArray(addresses))
            .put("port", port)
            .put("baseUrl", base)
            .put("url", "$base/?k=${config.pin}")
            .put("pin", config.pin)
            .put("pinRequired", config.pinEnabled)
            .put("autoInstall", config.autoInstall)
            .put("removeAfterInstall", config.removeAfterInstall)
            .put("status", ServerState.status)
            .put("statusText", statusText())
            .put("error", ServerState.error ?: JSONObject.NULL)
            .put("uploaded", ServerState.uploadedCount)
            .put("hasNetwork", NetUtil.hasNetwork(context))
            .put("isWifi", NetUtil.isWifi(context))
            .put("canInstallPackages", ApkInstaller.hasInstallPermission(context))
            .put("pendingCount", repo.directory.listFiles()?.size ?: 0)
            .put(
                "uptimeMs",
                if (ServerState.startedAt > 0) System.currentTimeMillis() - ServerState.startedAt else 0L
            )
            .put("lastEvent", InstallEvents.lastMessage ?: JSONObject.NULL)
            .put("autoStart", config.autoStart)
            .put("system", SystemProbe.toJson(context))
        return HttpResponse.json(200, json.toString())
    }

    private fun pending(): HttpResponse {
        val items = JSONArray()
        for (entry in repo.listPending()) {
            items.put(
                JSONObject()
                    .put("name", entry.fileName)
                    .put("size", entry.sizeBytes)
                    .put("modifiedAt", entry.modifiedAt)
                    .put("package", entry.packageName ?: JSONObject.NULL)
                    .put("label", entry.label)
                    .put("versionName", entry.versionName ?: JSONObject.NULL)
                    .put("versionCode", entry.versionCode)
                    .put("error", entry.parseError ?: JSONObject.NULL)
                    .put("installed", entry.installed)
                    .put("installedVersion", entry.installedVersion ?: JSONObject.NULL)
            )
        }
        return HttpResponse.json(200, ok().put("items", items).toString())
    }

    private fun installed(): HttpResponse {
        val items = JSONArray()
        for (app in repo.installedApps(includeSystem = false)) {
            items.put(
                JSONObject()
                    .put("package", app.packageName)
                    .put("label", app.label)
                    .put("versionName", app.versionName)
                    .put("versionCode", app.versionCode)
                    .put("enabled", app.enabled)
                    .put("firstInstallAt", app.firstInstallAt)
            )
        }
        return HttpResponse.json(200, ok().put("items", items).toString())
    }

    // ---------------------------------------------------------------- upload

    private fun upload(request: HttpRequest): HttpResponse {
        val rawName = request.query["filename"] ?: request.header("x-filename")
        if (rawName.isNullOrBlank()) {
            request.body.drain()
            return json(400, error("缺少 filename 参数"))
        }

        val contentType = request.contentType
        val isMultipart = contentType.startsWith("multipart/form-data", ignoreCase = true)
        val boundary = if (isMultipart) Multipart.boundaryOf(contentType) else null
        if (isMultipart && boundary == null) {
            request.body.drain()
            return json(400, error("multipart 请求缺少 boundary"))
        }

        val target = repo.allocateFile(rawName)
        host.setBusy(true)
        var failure: String? = null
        try {
            FileOutputStream(target).use { out ->
                if (isMultipart) {
                    if (Multipart.extractFirstFile(request.body, out, boundary!!) == null) {
                        failure = "请求里没有文件字段"
                    }
                } else {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = request.body.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            failure = t.message ?: "写入失败"
            onLog("upload of $rawName failed: $t")
        } finally {
            host.setBusy(false)
        }

        if (failure != null || target.length() == 0L) {
            target.delete()
            request.body.drain()
            return json(400, error(failure ?: "上传内容为空"))
        }

        ServerState.noteUpload()
        ServerState.notifyListeners()
        if (config.autoInstall) host.requestInstall(target.name)

        return HttpResponse.json(
            200,
            ok()
                .put("name", target.name)
                .put("size", target.length())
                .put("autoInstall", config.autoInstall)
                .toString()
        )
    }

    private fun install(request: HttpRequest): HttpResponse {
        val name = readJson(request).optString("name")
        request.body.drain()
        if (name.isBlank()) return json(400, error("缺少 name"))
        val file = repo.fileFor(name) ?: return json(404, error("找不到 $name"))

        if (!ApkInstaller.hasInstallPermission(context)) {
            return HttpResponse.json(
                403,
                error("电视还没有授权「安装未知应用」")
                    .put("needsPermission", true)
                    .toString()
            )
        }
        host.requestInstall(file.name)
        return HttpResponse.json(200, ok().put("name", file.name).toString())
    }

    private fun delete(request: HttpRequest): HttpResponse {
        val name = readJson(request).optString("name")
        request.body.drain()
        if (name.isBlank()) return json(400, error("缺少 name"))
        val removed = repo.delete(name)
        ServerState.notifyListeners()
        return HttpResponse.json(
            if (removed) 200 else 404,
            ok().put("name", name).put("removed", removed).toString()
        )
    }

    private fun uninstall(request: HttpRequest): HttpResponse {
        val pkg = readJson(request).optString("package")
        request.body.drain()
        if (pkg.isBlank()) return json(400, error("缺少 package"))
        host.requestUninstall(pkg)
        return HttpResponse.json(200, ok().put("package", pkg).toString())
    }

    private fun settings(request: HttpRequest): HttpResponse {
        val json = readJson(request)
        var restartNeeded = false
        if (json.has("autoInstall")) config.autoInstall = json.optBoolean("autoInstall", true)
        if (json.has("removeAfterInstall")) {
            config.removeAfterInstall = json.optBoolean("removeAfterInstall", false)
        }
        if (json.has("pinEnabled")) config.pinEnabled = json.optBoolean("pinEnabled", true)
        if (json.has("autoStart")) config.autoStart = json.optBoolean("autoStart", true)
        if (json.has("port")) {
            val requested = json.optInt("port", config.port)
            if (requested != config.port) {
                config.port = requested
                restartNeeded = true
            }
        }
        if (restartNeeded) host.requestRestart()
        ServerState.notifyListeners()
        return info()
    }

    // -------------------------------------------------------------- helpers

    private fun authorized(request: HttpRequest): Boolean {
        if (!config.pinEnabled) return true
        val token = request.authToken() ?: return false
        return constantTimeEquals(token, config.pin)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun unauthorized(): HttpResponse = HttpResponse.json(
        401,
        error("验证码不正确").put("needsPin", true).toString(),
        mapOf("WWW-Authenticate" to "tvapk")
    )

    private fun methodNotAllowed(): HttpResponse =
        json(405, error("只支持 GET 和 POST"))

    private fun ifGet(request: HttpRequest, block: () -> HttpResponse): HttpResponse =
        if (request.method == "GET" || request.method == "HEAD") block() else methodNotAllowed()

    private fun ifPost(request: HttpRequest, block: () -> HttpResponse): HttpResponse =
        if (request.method == "POST") block() else methodNotAllowed()

    private fun readJson(request: HttpRequest): JSONObject {
        val raw = try {
            String(request.body.readBytes(), Charsets.UTF_8)
        } catch (ignored: Exception) {
            ""
        }
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (ignored: Exception) {
            JSONObject()
        }
    }

    private fun ok(): JSONObject = JSONObject().put("ok", true)

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    /** [HttpResponse.json] speaks strings; this keeps every call site chainable. */
    private fun json(status: Int, body: JSONObject): HttpResponse =
        HttpResponse.json(status, body.toString())

    private fun statusText(): String = when (ServerState.status) {
        ServerState.RUNNING -> "运行中"
        ServerState.STARTING -> "启动中"
        ServerState.FAILED -> "启动失败"
        else -> "已停止"
    }
}