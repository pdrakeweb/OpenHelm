package dev.openhelm.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoveryTest {

    @Test
    fun `version string is parsed as hex from characters two to four`() {
        // The counter-intuitive one, and the reason this function exists at all:
        // "1.10" -> "10" -> 0x10 -> 16.  Not 1, not 10.  Measured against a real device.
        assertEquals(0x10, parseRrcVersion("1.10"))
        assertEquals(0x01, parseRrcVersion("1.01"))
        assertEquals(0xFF, parseRrcVersion("1.ff"))
    }

    @Test
    fun `unparsable versions fall back rather than throwing`() {
        // Discovery must survive a device that advertises something unexpected.
        assertEquals(Rrc.DEFAULT_VERSION, parseRrcVersion(null))
        assertEquals(Rrc.DEFAULT_VERSION, parseRrcVersion(""))
        assertEquals(Rrc.DEFAULT_VERSION, parseRrcVersion("1.z"))
        assertEquals(Rrc.DEFAULT_VERSION, parseRrcVersion("abc"))
    }

    @Test
    fun `endpoint builds the stream url the device expects`() {
        val mfd = MfdEndpoint("192.168.131.1", 8554, 50000, "RAYMARINEMFD", 0x10)
        assertEquals("rtsp://192.168.131.1:8554/RAYMARINEMFD", mfd.rtspUrl)
    }

    @Test
    fun `manual address line parses`() {
        val mfd = MfdEndpoint.parse("192.168.131.1:8554:50000:RAYMARINEMFD:10")!!
        assertEquals("192.168.131.1", mfd.host)
        assertEquals(8554, mfd.rtspPort)
        assertEquals(50000, mfd.rrcPort)
        assertEquals("RAYMARINEMFD", mfd.rtspPath)
        assertEquals(0x10, mfd.rrcVersion) // hex, consistently with the TXT record
    }

    @Test
    fun `manual address tolerates a missing version`() {
        val mfd = MfdEndpoint.parse("10.0.2.2:8555:50000:stream")!!
        assertEquals(Rrc.DEFAULT_VERSION, mfd.rrcVersion)
    }

    @Test
    fun `malformed manual addresses are rejected, not guessed at`() {
        assertNull(MfdEndpoint.parse(""))
        assertNull(MfdEndpoint.parse("192.168.1.1"))
        assertNull(MfdEndpoint.parse("192.168.1.1:8554:50000"))     // no path
        assertNull(MfdEndpoint.parse("192.168.1.1:notaport:50000:x"))
        assertNull(MfdEndpoint.parse(":8554:50000:path"))           // no host
    }

    @Test
    fun `out-of-range ports are rejected at the parse, not deep in a socket call`() {
        // InetSocketAddress throws an *unchecked* IllegalArgumentException for a bad port; an
        // endpoint carrying one would detonate inside a connection loop at every retry. The
        // parse is the trust boundary, so the parse is where these die.
        assertNull(MfdEndpoint.parse("192.168.1.1:99999:50000:x"))  // rtsp port too large
        assertNull(MfdEndpoint.parse("192.168.1.1:8554:99999:x"))   // rrc port too large
        assertNull(MfdEndpoint.parse("192.168.1.1:0:50000:x"))      // zero is not connectable
        assertNull(MfdEndpoint.parse("192.168.1.1:-1:50000:x"))
    }

    @Test
    fun `manual address ignores surrounding whitespace`() {
        // It is typed by hand on a boat, and pasted from a config file.
        assertEquals("10.0.2.2", MfdEndpoint.parse("  10.0.2.2:8555:50000:stream:01  ")!!.host)
    }
}
