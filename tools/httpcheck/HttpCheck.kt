package com.miaoxiaohu.tvinstall.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Standalone JVM exercise for the HTTP layer. MiniHttpServer / Multipart / HttpTypes
 * have no Android imports, so the riskiest parsing code is verified without a device.
 *
 * Run with the compiled classes of app/src/main/java/com/miaoxiaohu/tvinstall/http.
 */

private var passed = 0
private var failed = 0

private fun report(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("  PASS  $name")
    } else {
        failed++
        println("  FAIL  $name" + if (detail.isEmpty()) "" else "   [$detail]")
    }
}

private class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

private fun readResponse(input: InputStream): Resp? {
    val head = ByteArrayOutputStream()
    while (true) {
        val c = input.read()
        if (c < 0) return null
        head.write(c)
        val b = head.toByteArray()
        val n = b.size
        if (n >= 4 && b[n - 4] == 13.toByte() && b[n - 3] == 10.toByte() &&
            b[n - 2] == 13.toByte() && b[n - 1] == 10.toByte()
        ) break
    }
    val headText = String(head.toByteArray(), Charsets.ISO_8859_1)
    val lines = headText.split("\r\n")
    val status = lines[0].split(' ').getOrNull(1)?.toIntOrNull() ?: -1
    val headers = lines.drop(1).filter { it.contains(':') }.associate {
        it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
    }
    val len = headers["content-length"]?.toIntOrNull() ?: 0
    val body = ByteArray(len)
    var got = 0
    while (got < len) {
        val k = input.read(body, got, len - got)
        if (k < 0) break
        got += k
    }
    return Resp(status, headers, body.copyOf(got))
}

/** Sends everything [write] produces, then reads however many responses come back. */
private fun exchange(port: Int, write: (OutputStream) -> Unit): List<Resp> {
    Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 20000
        s.tcpNoDelay = true
        val out = s.getOutputStream()
        write(out)
        out.flush()
        val input = s.getInputStream()
        val outList = ArrayList<Resp>()
        while (true) {
            val r = readResponse(input) ?: break
            outList.add(r)
            if (r.headers["connection"]?.equals("close", ignoreCase = true) == true) break
        }
        return outList
    }
}

private fun get(port: Int, path: String, headers: String = ""): Resp =
    exchange(port) { o ->
        o.write("GET $path HTTP/1.1\r\nHost: t\r\nConnection: close\r\n$headers\r\n".toByteArray())
    }.first()

private fun post(port: Int, path: String, contentType: String, body: ByteArray, extra: String = ""): Resp {
    val head = buildString {
        append("POST $path HTTP/1.1\r\nHost: t\r\nConnection: close\r\n")
        append("Content-Type: $contentType\r\nContent-Length: ${body.size}\r\n")
        if (extra.isNotEmpty()) append("$extra\r\n")
        append("\r\n")
    }
    return exchange(port) { o ->
        o.write(head.toByteArray())
        o.write(body)
    }.first()
}

fun main() {
    val received = ConcurrentHashMap<String, ByteArray>()
    val server = MiniHttpServer(
        handler = { req ->
            when {
                req.path == "/hello" && req.method == "GET" ->
                    HttpResponse.text(200, "text/plain; charset=utf-8", "hello ${req.authToken() ?: "-"}")

                req.path == "/raw" && req.method == "POST" -> {
                    val buf = ByteArrayOutputStream()
                    req.body.copyTo(buf)
                    received[req.query["id"] ?: "raw"] = buf.toByteArray()
                    HttpResponse.json(200, """{"ok":true,"n":${buf.size()}}""")
                }

                req.path == "/mp" && req.method == "POST" -> {
                    val boundary = Multipart.boundaryOf(req.contentType)
                    if (boundary == null) {
                        HttpResponse.json(400, """{"ok":false}""")
                    } else {
                        val buf = ByteArrayOutputStream()
                        val name = Multipart.extractFirstFile(req.body, buf, boundary)
                        received["mp"] = buf.toByteArray()
                        HttpResponse.json(200, """{"ok":true,"n":${buf.size()},"name":"$name"}""")
                    }
                }

                // Never touches the body: proves the server drains it and keeps the socket clean.
                req.path == "/ignore" && req.method == "POST" ->
                    HttpResponse.json(200, """{"ok":true}""")

                else -> HttpResponse.json(404, """{"ok":false,"error":"nope"}""")
            }
        },
        onLog = { println("  [server] $it") }
    )
    val port = server.start(0)
    println("MiniHttpServer listening on 127.0.0.1:$port\n")

    try {
        // ---------------------------------------------------------- basics
        println("basics")
        val hello = get(port, "/hello")
        report("GET returns 200", hello.status == 200, "got ${hello.status}")
        report("GET body echoes no-token as '-'", hello.text == "hello -", hello.text)
        report("unknown path -> 404", get(port, "/nope").status == 404)

        // ------------------------------------------------------------- auth
        println("\nauth plumbing")
        report("token from query string", get(port, "/hello?k=135790").text == "hello 135790")
        report(
            "token from X-Auth-K header",
            get(port, "/hello", "X-Auth-K: 246800\r\n").text == "hello 246800"
        )
        report(
            "token from cookie",
            exchange(port) { o ->
                o.write(
                    ("GET /hello HTTP/1.1\r\nHost: t\r\nCookie: tvapk=112233; other=x\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
            }.first().text == "hello 112233"
        )

        // ------------------------------------------------------- raw upload
        println("\nraw body upload")
        val binary = ByteArray(200_000) { (it % 256).toByte() }
        val rawResp = post(port, "/raw", "application/octet-stream", binary)
        report("raw upload -> 200", rawResp.status == 200, rawResp.text)
        report("raw upload byte count matches", received["raw"]?.size == binary.size,
            "${received["raw"]?.size} vs ${binary.size}")
        report("raw upload content is byte-identical", received["raw"]?.contentEquals(binary) == true)

        val zeros = ByteArray(300_000)
        post(port, "/raw", "application/octet-stream", zeros)
        report("300 KB of 0x00 survives intact", received["raw"]?.contentEquals(zeros) == true)

        // -------------------------------------------------- multipart upload
        println("\nmultipart upload")
        val boundary = "----tvinstallBoundary9x"
        val tricky = ByteArrayBuilder().apply {
            repeat(80_000) { i ->
                // Sprinkle in the literal boundary text so the scanner must not be fooled.
                append((if (i % 5000 == 0) "----tvinstallBoundary" else "payload-$i-").toByteArray())
            }
        }.toByteArray()

        val mpBody = ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"file\"; filename=\"demo.apk\"\r\n".toByteArray())
            write("Content-Type: application/vnd.android.package-archive\r\n\r\n".toByteArray())
            write(tricky)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val mpResp = post(port, "/mp", "multipart/form-data; boundary=$boundary", mpBody)
        report("multipart -> 200", mpResp.status == 200, mpResp.text)
        report("multipart filename extracted", mpResp.text.contains("\"name\":\"demo.apk\""), mpResp.text)
        report(
            "multipart body byte-identical (incl. fake boundaries)",
            received["mp"]?.contentEquals(tricky) == true,
            "${received["mp"]?.size} vs ${tricky.size}"
        )

        // A body whose real boundary straddles the 64 KB internal buffer edge.
        val straddle = ByteArray(65_536 - 12) { 0x5A }
        val straddleBody = ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"file\"; filename=\"edge.apk\"\r\n\r\n".toByteArray())
            write(straddle)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        post(port, "/mp", "multipart/form-data; boundary=$boundary", straddleBody)
        report("boundary straddling 64 KB buffer is not found inside the file",
            received["mp"]?.contentEquals(straddle) == true,
            "${received["mp"]?.size} vs ${straddle.size}")

        report("multipart without boundary -> 400",
            post(port, "/mp", "multipart/form-data", ByteArray(4)).status == 400)

        // -------------------------------------------------------- robustness
        println("\nprotocol robustness")
        report("chunked encoding refused with 411", exchange(port) { o ->
            o.write(("POST /raw HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n5\r\nhello\r\n0\r\n\r\n").toByteArray())
        }.first().status == 411)

        report("malformed request line -> 400", exchange(port) { o ->
            o.write("GARBAGE\r\n\r\n".toByteArray())
        }.first().status == 400)

        report("oversized header -> 431", exchange(port) { o ->
            o.write("GET /hello HTTP/1.1\r\nHost: t\r\nConnection: close\r\n".toByteArray())
            o.write(("X-Big: " + "a".repeat(40_000) + "\r\n\r\n").toByteArray())
        }.first().status == 431)

        report("negative Content-Length -> 400", exchange(port) { o ->
            o.write(("POST /raw HTTP/1.1\r\nHost: t\r\nContent-Length: -5\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
        }.first().status == 400)

        // ------------------------------------------------------- keep-alive
        println("\nkeep-alive")
        val ka = exchange(port) { o ->
            o.write("GET /hello HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
            o.flush()
            o.write("GET /hello?k=000111 HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n".toByteArray())
        }
        report("two requests on one connection", ka.size == 2, "got ${ka.size}")
        report("first keep-alive response correct", ka.getOrNull(0)?.text == "hello -")
        report("second response on same socket correct", ka.getOrNull(1)?.text == "hello 000111")

        val drain = exchange(port) { o ->
            o.write(("POST /ignore HTTP/1.1\r\nHost: t\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: 5000\r\n\r\n").toByteArray())
            o.write(ByteArray(5000) { 0x7F })
            o.write("GET /hello HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n".toByteArray())
        }
        report("unread body is drained, next request still served",
            drain.size == 2 && drain[1].text == "hello -", "got ${drain.size}")

        // ---------------------------------------------- concurrent clients
        println("\nconcurrency")
        var mismatches = 0
        val threads = (1..24).map { i ->
            Thread {
                val payload = ByteArray(120_000) { (it + i).toByte() }
                post(port, "/raw?id=w$i", "application/octet-stream", payload)
                if (received["w$i"]?.contentEquals(payload) != true) {
                    mismatches++
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30000) }
        report("24 concurrent uploads all served", threads.none { it.isAlive })
        report("24 concurrent uploads all byte-identical (no cross-talk)", mismatches == 0,
            "$mismatches mismatched")

        val random = Random(42)
        val fuzz = ByteArray(250_000) { random.nextInt(256).toByte() }
        post(port, "/raw", "application/octet-stream", fuzz)
        report("250 KB of uniform random bytes is byte-identical",
            received["raw"]?.contentEquals(fuzz) == true)
    } finally {
        server.stop()
    }

    println("\n--------------------------------------------")
    println("passed: $passed   failed: $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

private class ByteArrayBuilder {
    private val out = ByteArrayOutputStream()
    fun append(value: ByteArray): ByteArrayBuilder { out.write(value); return this }
    fun toByteArray(): ByteArray = out.toByteArray()
}