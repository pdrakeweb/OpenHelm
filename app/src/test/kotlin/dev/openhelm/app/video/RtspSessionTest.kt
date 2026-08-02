package dev.openhelm.app.video

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RTSP response parsing and interleaved demux, against canned bytes.
 *
 * This wire logic used to be welded to a [java.net.Socket] and therefore untested — in a codebase
 * where every *other* parser has a golden-vector suite. The streams constructor exists for exactly
 * this file. The response shapes are representative of a stock GStreamer RTSP server, values
 * invented.
 */
class RtspSessionTest {

    private fun session(response: String, out: ByteArrayOutputStream = ByteArrayOutputStream()) =
        RtspSession(ByteArrayInputStream(response.toByteArray(Charsets.US_ASCII)), out, "rtsp://h/x") to out

    private fun response(status: String, headers: List<String> = emptyList(), body: String = ""): String =
        buildString {
            append("RTSP/1.0 ").append(status).append("\r\n")
            append("CSeq: 1\r\n")
            headers.forEach { append(it).append("\r\n") }
            if (body.isNotEmpty()) append("Content-Length: ").append(body.length).append("\r\n")
            append("\r\n")
            append(body)
        }

    // ---- DESCRIBE ---------------------------------------------------------------------------

    private val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:stream=0\r\n"

    @Test
    fun `describe parses the video track out of the response body`() {
        val (s, out) = session(response("200 OK", body = sdp))
        val track = s.describe()
        assertEquals(96, track.payloadType)
        assertEquals("stream=0", track.control)
        val sent = out.toString("US-ASCII")
        assertTrue("request line missing: $sent", sent.startsWith("DESCRIBE rtsp://h/x RTSP/1.0\r\n"))
        assertTrue("Accept header missing: $sent", "Accept: application/sdp\r\n" in sent)
    }

    @Test
    fun `content-base from DESCRIBE is what SETUP resolves its control against`() {
        val describe = response("200 OK", headers = listOf("Content-Base: rtsp://h/base/"), body = sdp)
        val setup = response("200 OK", headers = listOf("Session: ABC123;timeout=30"))
        val (s, out) = session(describe + setup)
        val track = s.describe()
        s.setupUdp(track.control, 5000)
        val sent = out.toString("US-ASCII")
        assertTrue("SETUP did not resolve against content-base: $sent", "SETUP rtsp://h/base/stream=0 RTSP/1.0" in sent)
        assertEquals("ABC123", s.sessionId)
        assertEquals(30, s.timeoutSeconds)
    }

    @Test
    fun `a non-200 answer to a required request throws rather than limping on`() {
        val (s, _) = session(response("454 Session Not Found"))
        assertThrows(RtspException::class.java) { s.describe() }
    }

    @Test
    fun `a malformed status line throws rather than being misread`() {
        val (s, _) = session("garbage\r\n\r\n")
        assertThrows(RtspException::class.java) { s.describe() }
    }

    @Test
    fun `headers are matched case-insensitively`() {
        val (s, _) = session(response("200 OK", headers = listOf("CONTENT-BASE: rtsp://h/CB/"), body = sdp))
        assertNotNull(s.describe())
        // The only observable effect of content-base is the SETUP target; asserted above. Here it
        // is enough that the upper-cased header did not break body reading (Content-Length path).
    }

    // ---- keepalive fallback -----------------------------------------------------------------

    @Test
    fun `keepalive falls back to OPTIONS after a server rejects GET_PARAMETER`() {
        // First keepalive answered 501, second must switch method. Neither throws: any request
        // carrying the Session header refreshes the server's timer, and tearing the stream down
        // once per timeout window over a 501 would be a rhythmic, miserable failure.
        val (s, out) = session(response("501 Not Implemented") + response("200 OK"))
        s.keepalive()
        s.keepalive()
        val sent = out.toString("US-ASCII")
        assertTrue("first keepalive should be GET_PARAMETER: $sent", "GET_PARAMETER" in sent)
        assertTrue("second keepalive should fall back to OPTIONS: $sent", "OPTIONS" in sent)
    }

    // ---- interleaved demux ------------------------------------------------------------------

    @Test
    fun `readInterleaved passes channel-0 payloads through and discards responses and RTCP`() {
        val payloadA = byteArrayOf(1, 2, 3)
        val payloadB = byteArrayOf(9, 8)
        val rtcp = byteArrayOf(7, 7, 7, 7)
        val keepaliveAnswer = response("200 OK", body = "xy")
        val wire = ByteArrayOutputStream().apply {
            write('$'.code); write(0); write(0); write(payloadA.size); write(payloadA)
            write('$'.code); write(1); write(0); write(rtcp.size); write(rtcp) // RTCP: dropped
            write(keepaliveAnswer.toByteArray(Charsets.US_ASCII)) // in-band response: consumed
            write('$'.code); write(0); write(0); write(payloadB.size); write(payloadB)
        }
        val s = RtspSession(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(), "rtsp://h/x")
        val got = mutableListOf<ByteArray>()
        // The loop runs until the stream ends, which it reports as an exception — expected here.
        assertThrows(RtspException::class.java) { s.readInterleaved { got.add(it) } }
        assertEquals(2, got.size)
        assertTrue(payloadA.contentEquals(got[0]))
        assertTrue(payloadB.contentEquals(got[1]))
    }

    @Test
    fun `a truncated interleaved frame is an error, not a hang or a misparse`() {
        val wire = byteArrayOf('$'.code.toByte(), 0) // header cut off
        val s = RtspSession(ByteArrayInputStream(wire), ByteArrayOutputStream(), "rtsp://h/x")
        assertThrows(RtspException::class.java) { s.readInterleaved { } }
    }
}
