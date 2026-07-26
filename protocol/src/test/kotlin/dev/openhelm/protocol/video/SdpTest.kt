package dev.openhelm.protocol.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdpTest {

    // The shape a stock GStreamer RTSP server answers with; values representative, not copied.
    private val sdp = """
        v=0
        o=- 16053254550796 1 IN IP4 192.168.131.1
        s=Session streamed with GStreamer
        t=0 0
        a=tools:GStreamer
        a=type:broadcast
        a=control:rtsp://192.168.131.1:8554/RAYMARINEMFD
        m=video 0 RTP/AVP 96
        c=IN IP4 0.0.0.0
        a=rtpmap:96 H264/90000
        a=fmtp:96 packetization-mode=1;profile-level-id=640028;sprop-parameter-sets=Z2QAKKzZQMg9WQ==,aOvssiw=
        a=control:stream=0
    """.trimIndent()

    @Test
    fun `parses payload type control and clock rate`() {
        val track = assertNotNull(parseSdpVideoTrack(sdp))
        assertEquals(96, track.payloadType)
        assertEquals("stream=0", track.control)
        assertEquals(90_000, track.clockRate)
    }

    @Test
    fun `extracts sps and pps with start codes`() {
        val track = assertNotNull(parseSdpVideoTrack(sdp))
        val sps = assertNotNull(track.sps)
        val pps = assertNotNull(track.pps)
        assertTrue(sps.take(4) == listOf<Byte>(0, 0, 0, 1))
        assertEquals(7, sps[4].toInt() and 0x1F)
        assertEquals(8, pps[4].toInt() and 0x1F)
    }

    @Test
    fun `audio-only sdp yields null`() {
        val audio = """
            v=0
            m=audio 0 RTP/AVP 0
            a=rtpmap:0 PCMU/8000
        """.trimIndent()
        assertNull(parseSdpVideoTrack(audio))
    }

    @Test
    fun `non-h264 video yields null`() {
        val mjpeg = """
            v=0
            m=video 0 RTP/AVP 26
            a=rtpmap:26 JPEG/90000
        """.trimIndent()
        assertNull(parseSdpVideoTrack(mjpeg))
    }

    @Test
    fun `missing sprop still parses the track`() {
        val bare = """
            v=0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
        """.trimIndent()
        val track = assertNotNull(parseSdpVideoTrack(bare))
        assertNull(track.sps)
        assertNull(track.pps)
    }
}
