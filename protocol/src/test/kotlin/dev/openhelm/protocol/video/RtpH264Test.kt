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
    fun `marker lost between frames starts the next frame clean`() {
        var discontinuities = 0
        val d = RtpH264Depacketizer { discontinuities++ }
        // Frame at ts=600 whose marker packet never arrives (seq continuity intact).
        assertNull(d.feed(RtpPacket.parse(rtp(1, 600, false, byteArrayOf(0x41, 1)))!!))
        // Next frame begins at ts=700.
        val au = d.feed(RtpPacket.parse(rtp(2, 700, true, byteArrayOf(0x41, 2)))!!)
        assertNotNull(au)
        assertEquals(1, discontinuities)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x41, 2), au)
    }
}
