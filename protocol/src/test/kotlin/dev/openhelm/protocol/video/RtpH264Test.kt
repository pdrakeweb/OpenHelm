package dev.openhelm.protocol.video

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtpH264Test {

    private fun rtp(
        seq: Int,
        ts: Long,
        marker: Boolean,
        payload: ByteArray,
        pt: Int = 96,
    ): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte() // V=2
        header[1] = ((if (marker) 0x80 else 0) or pt).toByte()
        header[2] = (seq shr 8).toByte()
        header[3] = seq.toByte()
        header[4] = (ts shr 24).toByte()
        header[5] = (ts shr 16).toByte()
        header[6] = (ts shr 8).toByte()
        header[7] = ts.toByte()
        // ssrc left zero
        return header + payload
    }

    @Test
    fun `parses a plain rtp header`() {
        val p = assertNotNull(RtpPacket.parse(rtp(seq = 7, ts = 1234, marker = true, payload = byteArrayOf(0x41, 1, 2))))
        assertEquals(7, p.sequence)
        assertEquals(1234, p.timestamp)
        assertTrue(p.marker)
        assertEquals(96, p.payloadType)
        assertContentEquals(byteArrayOf(0x41, 1, 2), p.payload)
    }

    @Test
    fun `rejects non-rtp garbage`() {
        assertNull(RtpPacket.parse(byteArrayOf(1, 2, 3)))
        assertNull(RtpPacket.parse(ByteArray(20))) // version 0
    }

    @Test
    fun `single nal with marker becomes one access unit`() {
        val d = RtpH264Depacketizer()
        val nal = byteArrayOf(0x65, 10, 20, 30) // IDR slice
        val au = assertNotNull(d.feed(RtpPacket.parse(rtp(1, 100, true, nal))!!))
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + nal, au)
        assertTrue(containsIdr(au))
    }

    @Test
    fun `fu-a fragments reassemble into the original nal`() {
        val d = RtpH264Depacketizer()
        // Original NAL: type 5 (IDR), header byte 0x65, body 1..6
        val fuIndicator = 0x7C.toByte() // NRI of 0x65 | type 28
        val start = byteArrayOf(fuIndicator, 0x85.toByte(), 1, 2)  // S=1, type 5
        val mid = byteArrayOf(fuIndicator, 0x05, 3, 4)
        val end = byteArrayOf(fuIndicator, 0x45, 5, 6)             // E=1

        assertNull(d.feed(RtpPacket.parse(rtp(1, 200, false, start))!!))
        assertNull(d.feed(RtpPacket.parse(rtp(2, 200, false, mid))!!))
        val au = assertNotNull(d.feed(RtpPacket.parse(rtp(3, 200, true, end))!!))
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3, 4, 5, 6), au)
    }

    @Test
    fun `stap-a expands to several nals`() {
        val d = RtpH264Depacketizer()
        val sps = byteArrayOf(0x67, 9)
        val pps = byteArrayOf(0x68, 8)
        val stap = byteArrayOf(24, 0, 2) + sps + byteArrayOf(0, 2) + pps
        val au = assertNotNull(d.feed(RtpPacket.parse(rtp(1, 300, true, stap))!!))
        assertContentEquals(
            byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps,
            au,
        )
        assertTrue(containsSps(au))
    }

    @Test
    fun `sequence gap drops the assembling unit and reports discontinuity`() {
        var discontinuities = 0
        val d = RtpH264Depacketizer { discontinuities++ }
        val fuIndicator = 0x7C.toByte()
        assertNull(d.feed(RtpPacket.parse(rtp(1, 400, false, byteArrayOf(fuIndicator, 0x85.toByte(), 1)))!!))
        // seq 2 lost; seq 3 arrives with the end fragment
        assertNull(d.feed(RtpPacket.parse(rtp(3, 400, true, byteArrayOf(fuIndicator, 0x45, 3)))!!))
        assertTrue(discontinuities > 0)

        // The next complete frame still comes out clean.
        val nal = byteArrayOf(0x65, 42)
        val au = assertNotNull(d.feed(RtpPacket.parse(rtp(4, 500, true, nal))!!))
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + nal, au)
    }

    @Test
    fun `a server that never sets the marker bit still produces access units`() {
        // The failure this pins cost a sea trial. RFC 6184 says the marker SHOULD be set on the
        // last packet of an access unit — and a real display's payloader does not. Treating the
        // marker as the only frame boundary meant every completed frame was discarded when the
        // next timestamp arrived, and counted as a discontinuity: hundreds of "gaps", zero
        // decoded frames, on a link that was otherwise perfectly healthy.
        var discontinuities = 0
        val d = RtpH264Depacketizer { discontinuities++ }
        val out = mutableListOf<ByteArray>()

        // Several frames, two packets each, marker NEVER set — enough packets for the
        // diagnostics to be able to call it.
        var seq = 1
        for (frame in 0 until 6) {
            val ts = 1000L + frame * 3000
            listOf(byteArrayOf(0x67, frame.toByte()), byteArrayOf(0x65, frame.toByte())).forEach { nal ->
                d.feed(RtpPacket.parse(rtp(seq++, ts, marker = false, payload = nal))!!)?.let(out::add)
            }
        }
        // A fourth frame's first packet is what closes frame 3.
        d.feed(RtpPacket.parse(rtp(seq, 10_000, marker = false, payload = byteArrayOf(0x67, 9)))!!)
            ?.let(out::add)

        assertEquals(6, out.size, "expected one access unit per completed frame")
        assertEquals(0, discontinuities, "a frame boundary is not a discontinuity")
        out.forEach { assertTrue(containsIdr(it), "each access unit should carry its IDR slice") }

        val diag = d.diagnostics()
        assertEquals(0L, diag.markers)
        assertEquals(6L, diag.unmarkedFrames)
        assertEquals(0L, diag.markerTerminated)
        assertTrue(diag.serverOmitsMarker, "diagnostics should name the cause")
    }

    @Test
    fun `marker-terminated streams are unaffected and still emit immediately`() {
        var discontinuities = 0
        val d = RtpH264Depacketizer { discontinuities++ }
        val nal = byteArrayOf(0x65, 1, 2)
        val au = assertNotNull(d.feed(RtpPacket.parse(rtp(1, 100, marker = true, payload = nal))!!))
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + nal, au)
        assertEquals(0, discontinuities)
        val diag = d.diagnostics()
        assertEquals(1L, diag.markerTerminated)
        assertEquals(0L, diag.unmarkedFrames)
        assertTrue(!diag.serverOmitsMarker, "a marked stream must not be reported as unmarked")
    }

    @Test
    fun `a frame whose marker never arrives is emitted at the next timestamp, not discarded`() {
        // This case used to be treated as loss: the unmarked frame was thrown away and counted as
        // a discontinuity. It is not loss — the sequence numbers are unbroken and the frame is
        // whole. The next timestamp is simply where it ends.
        var discontinuities = 0
        val d = RtpH264Depacketizer { discontinuities++ }

        // Frame at ts=600 whose marker packet never arrives (seq continuity intact).
        assertNull(d.feed(RtpPacket.parse(rtp(1, 600, false, byteArrayOf(0x41, 1)))!!))
        // The next frame's first packet is what closes it.
        val au = d.feed(RtpPacket.parse(rtp(2, 700, true, byteArrayOf(0x41, 2)))!!)
        assertNotNull(au)
        assertEquals(0, discontinuities, "an unmarked frame boundary is not a discontinuity")
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x41, 1), au, "the ts=600 frame should come out")
    }
}
