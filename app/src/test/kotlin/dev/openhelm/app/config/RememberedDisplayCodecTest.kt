package dev.openhelm.app.config

import dev.openhelm.protocol.MfdEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remembered-displays record format.
 *
 * Three of the fields (`rtspPath`, `model`, `serial`) are copied verbatim out of mDNS TXT records,
 * so their contents are chosen by whatever is advertising on the Wi-Fi. The separator escaping is
 * what stops one advertisement from rewriting a different, trusted entry.
 */
class RememberedDisplayCodecTest {

    private fun endpoint(
        host: String = "192.168.1.10",
        path: String = "/stream",
        model: String? = "E9",
        serial: String? = "E70021",
    ) = MfdEndpoint(
        host = host,
        rtspPort = 554,
        rrcPort = 2054,
        rtspPath = path,
        rrcVersion = 0x10,
        model = model,
        serial = serial,
    )

    @Test
    fun `an ordinary record round-trips`() {
        val d = RememberedDisplay(endpoint(), name = "Helm E95")
        assertEquals(d, EndpointStore.decode(EndpointStore.encode(d)))
    }

    @Test
    fun `a tab in an advertised field cannot shift the other fields`() {
        // Without escaping this path would consume the version column and everything after it.
        val d = RememberedDisplay(endpoint(path = "/stream\t554\t2054\tevil"))
        val back = EndpointStore.decode(EndpointStore.encode(d))
        assertEquals(d, back)
        assertEquals(0x10, back!!.endpoint.rrcVersion)
    }

    @Test
    fun `a newline in an advertised field cannot forge a second record`() {
        val forged = "\nevil.example\t554\t2054\t/x\t16\t\t\tHelm E95"
        val d = RememberedDisplay(endpoint(model = "E9$forged"))
        val line = EndpointStore.encode(d)

        // One physical line, so writeList's newline separator still means one record per line.
        assertEquals(1, line.lines().size)
        assertEquals(listOf(d), EndpointStore.readList(line))
    }

    @Test
    fun `a literal backslash in a name survives intact`() {
        val d = RememberedDisplay(endpoint(), name = """C:\helm\tab""")
        assertEquals(d, EndpointStore.decode(EndpointStore.encode(d)))
    }

    @Test
    fun `records written before escaping existed still decode`() {
        // The pre-escaping format, byte for byte. Nothing in it contains a backslash, which is
        // why the upgrade needs no migration.
        val legacy = "192.168.1.10\t554\t2054\t/stream\t16\tE9\tE70021\tHelm E95"
        val back = EndpointStore.decode(legacy)!!
        assertEquals("192.168.1.10", back.endpoint.host)
        assertEquals("/stream", back.endpoint.rtspPath)
        assertEquals("Helm E95", back.name)
    }

    @Test
    fun `records written before naming existed still decode, with no name`() {
        val old = "192.168.1.10\t554\t2054\t/stream\t16\tE9\tE70021"
        assertNull(EndpointStore.decode(old)!!.name)
    }

    @Test
    fun `a truncated or malformed line is dropped rather than half-read`() {
        assertNull(EndpointStore.decode("192.168.1.10\t554"))
        assertNull(EndpointStore.decode("192.168.1.10\tnotaport\t2054\t/stream\t16"))
        assertEquals(emptyList<RememberedDisplay>(), EndpointStore.readList(null))
    }

    @Test
    fun `a record with an impossible port is dropped, not probed`() {
        // Remembered displays are probed automatically at launch, and an out-of-range port makes
        // InetSocketAddress throw an unchecked exception. A corrupt record decodes to nothing.
        assertNull(EndpointStore.decode("192.168.1.10\t99999\t2054\t/stream\t16"))
        assertNull(EndpointStore.decode("192.168.1.10\t554\t99999\t/stream\t16"))
        assertNull(EndpointStore.decode("192.168.1.10\t0\t2054\t/stream\t16"))
    }

    @Test
    fun `the label prefers the user's name, then the model, then the address`() {
        assertEquals("Helm E95", RememberedDisplay(endpoint(), "Helm E95").label)
        assertEquals("E9", RememberedDisplay(endpoint()).label)
        assertEquals("192.168.1.10", RememberedDisplay(endpoint(model = null)).label)
    }
}
