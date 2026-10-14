package com.miaoxiaohu.tvinstall.http

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.URLDecoder

/**
 * Reader for `multipart/form-data`, limited to what actually matters here: pull the one
 * part that carries a filename and stream its bytes straight to disk.
 *
 * Browsers and `curl -F file=@app.apk` both work. Nothing else is supported on purpose -
 * a hand-rolled general multipart implementation is where upload corruption comes from.
 */
object Multipart {

    private const val CR = 13
    private const val LF = 10
    private const val MAX_HEADER_LINE = 8 * 1024

    private val STAR_FILENAME =
        Regex("""filename\*\s*=\s*([^']*)'([^']*)'([^;]+)""", RegexOption.IGNORE_CASE)
    private val PLAIN_FILENAME =
        Regex("""filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)

    fun boundaryOf(contentType: String): String? {
        for (part in contentType.split(';').drop(1)) {
            val trimmed = part.trim()
            if (!trimmed.startsWith("boundary=", ignoreCase = true)) continue
            var value = trimmed.substringAfter('=').trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            if (value.isNotBlank()) return value
        }
        return null
    }

    /** Streams the first file part into [out]. Returns its filename, or null if none had one. */
    fun extractFirstFile(source: InputStream, out: OutputStream, boundary: String): String? {
        val pushback = PushbackInputStream(source, 256 * 1024)
        val reader = DelimReader(pushback, "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1))
        val opening = "--$boundary"

        // Skip any preamble to the opening delimiter.
        var sawOpening = false
        while (!sawOpening) {
            val line = reader.readLine() ?: return null
            if (line == opening) {
                sawOpening = true
            } else if (line == "$opening--") {
                return null
            }
        }

        while (true) {
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: return null
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            val filename = filenameOf(headers["content-disposition"].orEmpty())
            reader.copyUntilDelimiter(if (filename != null) out else null)
            if (filename != null) return filename

            // Skip this part and move to the next one, if there is one.
            if (reader.readLine() != opening) return null
        }
    }

    private fun filenameOf(disposition: String): String? {
        val star = STAR_FILENAME.find(disposition)
        if (star != null) {
            val charset = star.groupValues[1].ifBlank { "UTF-8" }
            val raw = star.groupValues[3].trim()
            return runCatching { URLDecoder.decode(raw, charset) }.getOrDefault(raw).ifBlank { null }
        }
        val plain = PLAIN_FILENAME.find(disposition) ?: return null
        val value = if (plain.groupValues[1].isNotEmpty()) plain.groupValues[1] else plain.groupValues[2]
        return value.trim().ifBlank { null }
    }

    /** Streaming part reader that can hand a boundary-delimited blob to a sink. */
    private class DelimReader(
        private val input: PushbackInputStream,
        private val delimiter: ByteArray
    ) {

        fun readLine(): String? {
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
                if (builder.length > MAX_HEADER_LINE) throw IOException("multipart header line too long")
            }
        }

        /**
         * Streams up to (but not including) the next delimiter and leaves the stream
         * positioned exactly on it, so the caller can read the delimiter line next.
         */
        fun copyUntilDelimiter(sink: OutputStream?): Long {
            val buffer = ByteArray(64 * 1024 + delimiter.size)
            var filled = 0
            var total = 0L

            while (true) {
                val read = input.read(buffer, filled, buffer.size - filled)
                if (read < 0) return total
                filled += read

                while (true) {
                    val at = indexOf(buffer, filled, delimiter, 0)
                    if (at >= 0) {
                        if (sink != null) sink.write(buffer, 0, at)
                        total += at
                        input.unread(buffer, at, filled - at)
                        return total
                    }
                    if (filled <= delimiter.size) break
                    // Hold back just enough bytes to span a delimiter split across reads.
                    val keep = delimiter.size - 1
                    val flush = filled - keep
                    if (sink != null) sink.write(buffer, 0, flush)
                    total += flush
                    System.arraycopy(buffer, flush, buffer, 0, keep)
                    filled = keep
                }
            }
        }

        private fun indexOf(haystack: ByteArray, length: Int, needle: ByteArray, from: Int): Int {
            var i = from
            while (i + needle.size <= length) {
                var j = 0
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
                i++
            }
            return -1
        }
    }
}