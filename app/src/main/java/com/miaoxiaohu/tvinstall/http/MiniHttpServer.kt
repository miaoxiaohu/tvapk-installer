package com.miaoxiaohu.tvinstall.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small blocking HTTP/1.1 server.
 *
 * The app has no third-party dependencies, so this is the whole web tier. It supports
 * exactly what the upload page needs: keep-alive, bounded headers, Content-Length bodies
 * and streamed responses. Chunked encoding is refused rather than half-supported,
 * because a silently mis-parsed upload would corrupt an APK.
 */
class MiniHttpServer(
    private val handler: (HttpRequest) -> HttpResponse,
    private val onLog: (String) -> Unit = {}
) {

    companion object {
        private const val LF = 10
        private const val CR = 13

        private const val MAX_REQUEST_LINE = 8 * 1024
        private const val MAX_HEADER_LINE = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 3L * 1024 * 1024 * 1024
        private const val MAX_DRAIN_BYTES = 4L * 1024 * 1024
        private const val IDLE_TIMEOUT_MS = 30_000
        private const val UPLOAD_SOCKET_TIMEOUT_MS = 300_000
        private const val MAX_CONCURRENT = 48
        private const val BACKLOG = 64
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    private val slots = Semaphore(MAX_CONCURRENT)
    private val workerIds = AtomicInteger(0)

    val boundPort: Int get() = serverSocket?.localPort ?: 0

    /** Binds and starts accepting. Returns the port actually bound (useful when [port] is 0). */
    fun start(port: Int, bindAddress: String = "0.0.0.0"): Int {
        check(!isRunning) { "server already running" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), BACKLOG)
        serverSocket = socket
        isRunning = true
        Thread({ acceptLoop(socket) }, "tvapk-accept").apply {
            isDaemon = true
            start()
        }.also { acceptThread = it }
        return socket.localPort
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
            // closing is the intent; the accept thread unblocks on it
        }
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            } catch (e: IOException) {
                if (!isRunning) break else continue
            }
            if (!slots.tryAcquire()) {
                reject(client)
                continue
            }
            Thread({
                try {
                    handle(client)
                } finally {
                    slots.release()
                }
            }, "tvapk-worker-${workerIds.incrementAndGet()}").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun reject(client: Socket) {
        try {
            BufferedOutputStream(client.getOutputStream()).use { out ->
                HttpResponse.text(503, "text/plain; charset=utf-8", "server busy").writeTo(out, false)
                out.flush()
            }
        } catch (ignored: Exception) {
            // client already gone
        } finally {
            try {
                client.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = IDLE_TIMEOUT_MS
            serve(
                socket,
                BufferedInputStream(socket.getInputStream(), 16 * 1024),
                BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            )
        } catch (e: Exception) {
            // Peer disconnected or the client misbehaved. Nothing to recover here.
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun serve(socket: Socket, input: InputStream, output: OutputStream) {
        while (true) {
            val requestLine = try {
                readLine(input, MAX_REQUEST_LINE)
            } catch (e: LineTooLongException) {
                refuse(output, "request line too long")
                return
            } ?: return
            if (requestLine.isBlank()) continue

            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                HttpResponse.text(400, "text/plain; charset=utf-8", "malformed request line")
                    .writeTo(output, false)
                output.flush()
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            val version = parts[2]

            val headers = LinkedHashMap<String, String>()
            var headerBytes = 0
            while (true) {
                val line = try {
                    readLine(input, MAX_HEADER_LINE)
                } catch (e: LineTooLongException) {
                    refuse(output, "header line too long")
                    return
                } ?: return
                if (line.isEmpty()) break
                headerBytes += line.length + 2
                if (headerBytes > MAX_HEADER_BYTES) {
                    HttpResponse.text(431, "text/plain; charset=utf-8", "headers too large")
                        .writeTo(output, false)
                    output.flush()
                    return
                }
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                HttpResponse.text(
                    411, "text/plain; charset=utf-8",
                    "chunked encoding is not supported; send Content-Length"
                ).writeTo(output, false)
                output.flush()
                return
            }

            val declaredHeader = headers["content-length"]?.trim()
            val declared = if (declaredHeader == null) 0L else declaredHeader.toLongOrNull()
            if (declared == null || declared < 0L) {
                HttpResponse.text(400, "text/plain; charset=utf-8", "bad Content-Length")
                    .writeTo(output, false)
                output.flush()
                return
            }
            if (declared > MAX_BODY_BYTES) {
                HttpResponse.text(413, "text/plain; charset=utf-8", "body too large")
                    .writeTo(output, false)
                output.flush()
                return
            }

            val keepAlive = !version.equals("HTTP/1.0", true) &&
                !headers["connection"].equals("close", ignoreCase = true)

            val body = LimitedInputStream(input, declared)
            // Big bodies are almost always uploads; give them a much longer leash on Wi-Fi.
            socket.soTimeout =
                if (declared > 1024L * 1024L) UPLOAD_SOCKET_TIMEOUT_MS else IDLE_TIMEOUT_MS

            val request = HttpRequest(
                method, target, version, headers,
                socket.remoteSocketAddress?.toString().orEmpty(), body
            )
            val response = try {
                handler(request)
            } catch (t: Throwable) {
                onLog("handler failed on $method $target: $t")
                HttpResponse.json(500, """{"ok":false,"error":"internal error"}""")
            }

            response.writeTo(output, method == "HEAD")
            output.flush()

            if (!keepAlive) return
            val leftover = declared - body.consumed
            when {
                leftover <= 0L -> continue
                leftover <= MAX_DRAIN_BYTES -> {
                    body.drain()
                    continue
                }
                else -> return   // too much unread body to bother with; hang up
            }
        }
    }

    private fun refuse(output: OutputStream, reason: String) {
        try {
            HttpResponse.text(431, "text/plain; charset=utf-8", reason).writeTo(output, false)
            output.flush()
        } catch (ignored: Exception) {
            // peer is already gone; nothing left to say
        }
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val builder = StringBuilder(128)
        while (true) {
            val c = input.read()
            if (c < 0) return if (builder.isEmpty()) null else builder.toString()
            if (c == LF) {
                if (builder.isNotEmpty() && builder[builder.length - 1].code == CR) {
                    builder.setLength(builder.length - 1)
                }
                return builder.toString()
            }
            if (c == CR) continue
            builder.append(c.toChar())
            if (builder.length > max) throw LineTooLongException()
        }
    }

    private class LineTooLongException : IOException("line exceeds the configured limit")
}