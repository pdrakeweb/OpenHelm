package dev.openhelm.app.video

import dev.openhelm.protocol.video.SdpVideoTrack
import dev.openhelm.protocol.video.parseSdpVideoTrack
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket

class RtspException(message: String) : IOException(message)

/**
 * A deliberately small RTSP/1.0 client: DESCRIBE → SETUP → PLAY → keepalive → TEARDOWN, no
 * authentication, one video track — which is exactly what the MFD's stock RTSP server offers.
 *
 * **Transport rule:** real displays get RTP over **UDP** ([setupUdp]). A TCP-interleaved SETUP
 * ([setupInterleaved]) makes the MFD's server accept the session and then never deliver a single
 * packet, indefinitely — so interleaving exists here *only* to let the simulator exercise the
 * decode path on an Android emulator, whose NAT cannot pass inbound UDP.
 *
 * Not thread-safe; drive it from the one connection coroutine. In interleaved mode the caller owns
 * the read loop via [readInterleaved] after PLAY, and keepalive responses are consumed there.
 */
class RtspSession(private val socket: Socket, private val url: String) {

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private var cseq = 1
    private var contentBase: String = url
    private var interleavedStarted = false

    var sessionId: String? = null
        private set

    /** Session timeout advertised by the server; keepalives should run well inside it. */
    var timeoutSeconds: Int = 60
        private set

    fun describe(): SdpVideoTrack {
        val response = request("DESCRIBE", url, "Accept: application/sdp")
        response.headers["content-base"]?.let { contentBase = it.trimEnd() }
        val track = parseSdpVideoTrack(response.body)
            ?: throw RtspException("No H.264 video track in DESCRIBE answer")
        return track
    }

    private fun setupUrl(control: String?): String = when {
        control == null || control == "*" -> contentBase
        control.startsWith("rtsp://", ignoreCase = true) -> control
        else -> contentBase.trimEnd('/') + "/" + control
    }

    /** SETUP with RTP/UDP — the only transport a real display serves. */
    fun setupUdp(control: String?, rtpPort: Int) {
        val response = request(
            "SETUP", setupUrl(control),
            "Transport: RTP/AVP;unicast;client_port=$rtpPort-${rtpPort + 1}",
        )
        rememberSession(response)
    }

    /** SETUP with TCP interleaving — simulator-only; hangs a real display. */
    fun setupInterleaved(control: String?) {
        val response = request(
            "SETUP", setupUrl(control),
            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1",
        )
        rememberSession(response)
    }

    fun play() {
        request("PLAY", contentBase, "Range: npt=0.000-")
    }

    /**
     * Refresh the server's session timer. In interleaved mode the response is consumed by
     * [readInterleaved]'s loop instead of here.
     */
    fun keepalive() {
        if (interleavedStarted) {
            sendRequest("GET_PARAMETER", contentBase, emptyArray())
        } else {
            request("GET_PARAMETER", contentBase)
        }
    }

    fun teardown() {
        try {
            if (interleavedStarted) sendRequest("TEARDOWN", contentBase, emptyArray())
            else request("TEARDOWN", contentBase)
        } catch (_: IOException) {
            // The point of TEARDOWN is politeness; the socket is closing either way.
        }
    }

    /**
     * Interleaved read loop: demultiplexes `$`-framed binary from in-band RTSP responses. Channel
     * 0 payloads (RTP) go to [onRtp]; channel 1 (RTCP) and response text are consumed and
     * discarded. Runs until the socket dies or the caller cancels by closing it.
     */
    fun readInterleaved(onRtp: (ByteArray) -> Unit) {
        interleavedStarted = true
        while (true) {
            val first = input.read()
            if (first == -1) throw RtspException("Stream closed by server")
            if (first == '$'.code) {
                val channel = input.read()
                val hi = input.read()
                val lo = input.read()
                if (channel == -1 || hi == -1 || lo == -1) throw RtspException("Truncated interleaved frame")
                val len = (hi shl 8) or lo
                val payload = readFully(len)
                if (channel == 0) onRtp(payload)
            } else {
                // An RTSP response line (to a keepalive): consume headers and body, discard.
                skipTextResponse(firstByte = first)
            }
        }
    }

    // ---- wire plumbing -------------------------------------------------------------------

    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private fun request(method: String, target: String, vararg extraHeaders: String): Response {
        sendRequest(method, target, extraHeaders)
        val response = readResponse()
        if (response.status != 200) {
            throw RtspException("$method rejected: RTSP ${response.status}")
        }
        return response
    }

    private fun sendRequest(method: String, target: String, extraHeaders: Array<out String>) {
        val lines = StringBuilder()
            .append(method).append(' ').append(target).append(" RTSP/1.0\r\n")
            .append("CSeq: ").append(cseq++).append("\r\n")
            .append("User-Agent: OpenHelm\r\n")
        sessionId?.let { lines.append("Session: ").append(it).append("\r\n") }
        for (h in extraHeaders) lines.append(h).append("\r\n")
        lines.append("\r\n")
        output.write(lines.toString().toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun readResponse(): Response {
        val statusLine = readLine() ?: throw RtspException("Connection closed before response")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw RtspException("Malformed status line: $statusLine")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine() ?: throw RtspException("Connection closed inside headers")
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val body = headers["content-length"]?.toIntOrNull()?.takeIf { it > 0 }
            ?.let { String(readFully(it), Charsets.UTF_8) } ?: ""
        return Response(status, headers, body)
    }

    private fun rememberSession(response: Response) {
        val session = response.headers["session"] ?: return
        sessionId = session.substringBefore(';').trim()
        session.substringAfter("timeout=", "").takeIf { it.isNotEmpty() }
            ?.substringBefore(';')?.trim()?.toIntOrNull()?.let { timeoutSeconds = it }
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            when {
                c == -1 -> return if (sb.isEmpty()) null else sb.toString()
                c == '\n'.code -> return sb.toString().trimEnd('\r')
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun readFully(len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n == -1) throw RtspException("Connection closed mid-payload")
            off += n
        }
        return buf
    }

    private fun skipTextResponse(firstByte: Int) {
        // We already consumed the first byte of the status line; finish the line, the headers,
        // and any body, then return to the binary loop.
        val firstLine = StringBuilder().append(firstByte.toChar())
        while (true) {
            val c = input.read()
            if (c == -1) throw RtspException("Stream closed inside response")
            if (c == '\n'.code) break
            if (c != '\r'.code) firstLine.append(c.toChar())
        }
        var contentLength = 0
        while (true) {
            val line = readLine() ?: throw RtspException("Stream closed inside headers")
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals("content-length", ignoreCase = true)) {
                contentLength = line.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > 0) readFully(contentLength)
    }
}
