package dev.openhelm.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address policy for anything the app contacts on its own initiative.
 *
 * Written as two halves that matter differently. A false negative — rejecting a real display — is
 * an annoyance: discovery misses it and the user types the address. A false positive is a packet
 * to the internet from an app that promised not to send one, triggered by an advertisement from a
 * stranger's device on a marina network. The rejection cases are the ones with teeth.
 */
class LocalAddressesTest {

    private fun assertLocal(host: String) =
        assertTrue("$host should be treated as local", LocalAddresses.isLocal(host))

    private fun assertNotLocal(host: String) =
        assertFalse("$host must NOT be treated as local", LocalAddresses.isLocal(host))

    // ---- what a display actually looks like ------------------------------------------------

    @Test
    fun `the private ranges are allowed`() {
        assertLocal("192.168.131.1")   // the address on the reference unit
        assertLocal("192.168.0.1")
        assertLocal("10.0.0.1")
        assertLocal("10.255.255.254")
        assertLocal("172.16.0.1")
        assertLocal("172.31.255.254")
    }

    @Test
    fun `link-local is allowed, since a display with no DHCP lands there`() {
        assertLocal("169.254.1.1")
    }

    @Test
    fun `loopback is allowed, for the simulator on the same machine`() {
        assertLocal("127.0.0.1")
        assertLocal("10.0.2.2") // the emulator's view of its host
        assertLocal("::1")
    }

    @Test
    fun `IPv6 local ranges are allowed`() {
        assertLocal("fe80::1")
        assertLocal("fe80::1%wlan0")        // zone id
        assertLocal("[fe80::1]")            // bracketed
        assertLocal("feb0::1")              // top of fe80::/10
        assertLocal("fd00::1")              // unique local
        assertLocal("fc00::1")
        assertLocal("::ffff:192.168.1.7")   // IPv4-mapped
    }

    // ---- what must never be contacted unprompted -------------------------------------------

    @Test
    fun `public addresses are rejected`() {
        assertNotLocal("8.8.8.8")
        assertNotLocal("1.1.1.1")
        assertNotLocal("93.184.216.34")
        assertNotLocal("172.15.0.1")   // just below the private block
        assertNotLocal("172.32.0.1")   // just above it
        assertNotLocal("192.169.0.1")  // one off 192.168
        assertNotLocal("11.0.0.1")     // one off 10/8
    }

    @Test
    fun `carrier-grade NAT is rejected`() {
        // Not the public internet, but not a boat's LAN either. Too strict costs a manual connect;
        // too loose costs a packet.
        assertNotLocal("100.64.0.1")
        assertNotLocal("100.127.255.254")
    }

    @Test
    fun `hostnames are rejected, including local-looking ones`() {
        // Deciding by name would mean resolving it, and sending that query is the thing being
        // avoided. mDNS hands over a literal, so nothing real is lost.
        assertNotLocal("raymarine.local")
        assertNotLocal("mfd.local.")
        assertNotLocal("example.com")
        assertNotLocal("localhost")
    }

    @Test
    fun `public IPv6 is rejected`() {
        assertNotLocal("2001:4860:4860::8888")
        assertNotLocal("::ffff:8.8.8.8")   // IPv4-mapped public
        assertNotLocal("fec0::1")          // deprecated site-local, outside fe80::/10
    }

    // ---- inputs designed to slip past a lazy parser -----------------------------------------

    @Test
    fun `octal-looking octets are rejected rather than reinterpreted`() {
        // Some resolvers read a leading zero as octal, so 0177.0.0.1 is 127.0.0.1 to them and
        // something else to a naive parser. Neither reading is accepted.
        assertNotLocal("0177.0.0.1")
        assertNotLocal("010.0.0.1")
        assertNotLocal("192.0168.0.1")
    }

    @Test
    fun `malformed input is rejected rather than guessed at`() {
        assertNotLocal("")
        assertNotLocal("   ")
        assertNotLocal("192.168.1")
        assertNotLocal("192.168.1.1.1")
        assertNotLocal("192.168.1.256")
        assertNotLocal("192.168.1.-1")
        assertNotLocal("192.168.1.+1")
        assertNotLocal("192.168.1.a")
        assertNotLocal("192. 168.1.1")   // space *inside* an octet, which trimming does not reach
        assertNotLocal("192.168.1. 1")
        assertNotLocal("1921681.1")
    }

    @Test
    fun `surrounding whitespace does not change the decision`() {
        assertLocal("  192.168.1.7  ")
        assertNotLocal("  8.8.8.8  ")
    }
}
