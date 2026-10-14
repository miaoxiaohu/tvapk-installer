package com.miaoxiaohu.tvinstall.http

import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import kotlin.math.min

/**
 * Caps reads at a fixed length. Without this a handler that ignores the request body
 * would read straight past it and corrupt the next request on a keep-alive connection.
 */
class LimitedInputStream(
    private val source: InputStream,
    val limit: Long
) : InputStream() {

    var consumed: Long = 0L
        private set

    private fun remaining(): Long = limit - consumed

    override fun read(): Int {
        if (remaining() <= 0L) return -1
        val b = source.read()
        if (b >= 0) consumed++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining() <= 0L) return -1
        val n = source.read(b, off, min(len.toLong(), remaining()).toInt())
        if (n > 0) consumed += n
        return n
    }

    override fun available(): Int = min(source.available().toLong(), remaining()).toInt().coerceAtLeast(0)

    /** Throws away whatever the handler left unread so the socket stays usable. */
    fun drain() {
        val buf = ByteArray(16 * 1024)
        while (read(buf, 0, buf.size) > 0) {
            // discarded on purpose
        }
    }
}

class HttpRequest internal constructor(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>,
    val remoteAddress: String,
    val body: LimitedInputStream
) {
    val path: String = decodePath(target)
    val query: Map<String, String> = parseQuery(target)
    val cookies: Map<String, String> = parseCookies(headers["cookie"])

    val contentType: String get() = headers["content-type"] ?: ""

    fun header(name: String): String? = headers[name.lowercase()]

    /** Access key from header, query string or cookie - whichever the client found convenient. */
    fun authToken(): String? =
        (headers["x-auth-k"] ?: query["k"] ?: cookies["tvapk"])?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

        fun decodePath(target: String): String = decode(target.substringBefore('?'))

        fun parseQuery(target: String): Map<String, String> {
            val queryString = target.substringAfter('?', "")
            if (queryString.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in queryString.split('&')) {
                if (pair.isEmpty()) continue
                out[decode(pair.substringBefore('='))] = decode(pair.substringAfter('=', ""))
            }
            return out
        }

        fun parseCookies(header: String?): Map<String, String> {
            if (header.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in header.split(';')) {
                val key = part.substringBefore('=').trim()
                if (key.isNotEmpty()) out[key] = part.substringAfter('=', "").trim()
            }
            return out
        }
    }
}

class HttpResponse private constructor(
    private val status: Int,
    private val headers: MutableMap<String, String>,
    private val payload: ByteArray?,
    private val declaredLength: Long,
    private val writer: ((OutputStream) -> Unit)?
) {

    fun writeTo(out: OutputStream, headOnly: Boolean) {
        val head = StringBuilder(160)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        for ((key, value) in headers) {
            head.append(key).append(": ").append(value).append("\r\n")
        }
        head.append("Content-Length: ").append(declaredLength).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) {
            if (writer != null) writer.invoke(out) else payload?.let { out.write(it) }
        }
    }

    companion object {
        fun text(
            status: Int,
            contentType: String,
            body: String,
            extra: Map<String, String> = emptyMap()
        ): HttpResponse = bytes(status, contentType, body.toByteArray(Charsets.UTF_8), extra)

        fun bytes(
            status: Int,
            contentType: String,
            body: ByteArray,
            extra: Map<String, String> = emptyMap()
        ): HttpResponse = HttpResponse(
            status,
            headersOf(contentType, extra),
            body,
            body.size.toLong(),
            null
        )

        fun json(
            status: Int,
            body: String,
            extra: Map<String, String> = emptyMap()
        ): HttpResponse = text(
            status,
            "application/json; charset=utf-8",
            body,
            mapOf("Cache-Control" to "no-store") + extra
        )

        /** For bodies that must not be held in memory - uploaded APKs, the web page itself. */
        fun stream(
            status: Int,
            contentType: String,
            length: Long,
            writer: (OutputStream) -> Unit,
            extra: Map<String, String> = emptyMap()
        ): HttpResponse = HttpResponse(status, headersOf(contentType, extra), null, length, writer)

        fun empty(status: Int, extra: Map<String, String> = emptyMap()): HttpResponse =
            HttpResponse(status, headersOf("text/plain; charset=utf-8", extra), ByteArray(0), 0L, null)

        private fun headersOf(contentType: String, extra: Map<String, String>): MutableMap<String, String> {
            val headers = LinkedHashMap<String, String>()
            headers["Content-Type"] = contentType
            headers["X-Content-Type-Options"] = "nosniff"
            headers["Connection"] = "keep-alive"
            headers.putAll(extra)
            return headers
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            416 -> "Range Not Satisfiable"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}