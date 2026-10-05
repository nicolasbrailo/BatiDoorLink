package com.nicobrailo.batidoorlink.rtsp

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** What arrives on an RTSP connection: responses, interleaved data, and the odd server request. */
sealed class RtspMessage {
    data class Response(
        val status: Int,
        val reason: String,
        /** Header names in lower case. */
        val headers: Map<String, String>,
        val body: String,
    ) : RtspMessage() {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** RTP or RTCP sent over the connection itself (RFC 2326 10.12). */
    class Interleaved(val channel: Int, val data: ByteArray) : RtspMessage()

    /** A request from the server (GET_PARAMETER, ANNOUNCE...), which we don't act on. */
    data class Request(val requestLine: String, val headers: Map<String, String>) : RtspMessage()

    companion object {
        /** Reads the next message off [input]; throws [EOFException] when the connection closes. */
        fun read(input: InputStream): RtspMessage {
            val first = input.read()
            if (first < 0) throw EOFException()
            if (first == '$'.code) {
                val channel = readByte(input)
                val length = (readByte(input) shl 8) or readByte(input)
                val data = ByteArray(length)
                readFully(input, data)
                return Interleaved(channel, data)
            }
            val startLine = (first.toChar() + readLine(input)).trim()
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length).also { readFully(input, it) }
            if (!startLine.startsWith("RTSP/")) return Request(startLine, headers)
            // RTSP/1.0 200 OK
            val parts = startLine.split(' ', limit = 3)
            val status = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $startLine")
            return Response(status, parts.getOrElse(2) { "" }, headers, String(body, Charsets.UTF_8))
        }

        private fun readByte(input: InputStream): Int {
            val b = input.read()
            if (b < 0) throw EOFException()
            return b
        }

        private fun readFully(input: InputStream, into: ByteArray) {
            var off = 0
            while (off < into.size) {
                val n = input.read(into, off, into.size - off)
                if (n < 0) throw EOFException()
                off += n
            }
        }

        /** A line without its CRLF (or bare LF). Headers are ASCII. */
        private fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = readByte(input)
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("header line too long")
            }
            return sb.toString()
        }
    }
}
