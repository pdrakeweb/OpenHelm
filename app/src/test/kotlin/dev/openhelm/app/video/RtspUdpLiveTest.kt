package dev.openhelm.app.video

import dev.openhelm.protocol.video.RtpH264Depacketizer
import dev.openhelm.protocol.video.RtpPacket
import dev.openhelm.protocol.video.containsIdr
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * **The test the AVD cannot be:** a real RTSP/UDP session against a live server, on the host,
 * where inbound UDP actually works.
 *
 * The shipping video path had never once been exercised over real UDP. An AVD's SLIRP NAT drops
 * inbound UDP — that is the entire reason the simulator-only TCP-interleaved transport exists — so
 * every previous "video works" result came from a transport no real display serves. A UDP-only
 * defect could therefore survive every test in the suite, and one did.
 *
 * This drives the shipping [RtspSession] and [RtpH264Depacketizer] — the same code the app runs —
 * against the project's MFD emulator over UDP, and asserts that complete H.264 access units come
 * out. Everything downstream of that is `MediaCodec`, which needs a device.
 *
 * **Skips (rather than fails) when the emulator is not running**, so it never breaks a normal
 * build. Start it with:
 * ```
 * cd emulator && python -m mfd_emulator --no-discovery --no-console
 * ```
 */
class RtspUdpLiveTest {

    private val host = System.getProperty("openhelm.test.host") ?: "127.0.0.1"
    private val port = (System.getProperty("openhelm.test.rtspPort") ?: "8555").toInt()
    private val path = System.getProperty("openhelm.test.rtspPath") ?: "RAYMARINEMFD"

    private data class Result(
        val packets: Int,
        val accessUnits: Int,
        val idrs: Int,
        val discontinuities: Int,
        val seconds: Double,
        val mismatchedSource: InetAddress?,
    ) {
        override fun toString() = "packets=$packets aus=$accessUnits idrs=$idrs " +
            "gaps=$discontinuities over ${"%.1f".format(seconds)}s " +
            "(${"%.1f".format(if (seconds > 0) accessUnits / seconds else 0.0)} AU/s) " +
            "sourceMismatch=${mismatchedSource ?: "none"}"
    }

    private fun serverIsUp(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 1500) }
        true
    } catch (e: IOException) {
        false
    }

    /**
     * One full RTSP/UDP session. [closeRtcpAfterPlay] reproduces exactly what the app's pipeline
     * does, so the two can be compared against the same server in the same run.
     */
    private fun runSession(closeRtcpAfterPlay: Boolean, label: String): Result {
        val url = "rtsp://$host:$port/$path"
        val control = Socket()
        control.connect(InetSocketAddress(host, port), 5_000)
        control.tcpNoDelay = true
        control.soTimeout = 15_000

        var rtp = DatagramSocket(null).apply { reuseAddress = false; bind(InetSocketAddress(0)) }
        if (rtp.localPort % 2 != 0) {
            val even = rtp.localPort + 1
            rtp.close()
            rtp = DatagramSocket(null).apply { bind(InetSocketAddress(even)) }
        }
        var rtcp: DatagramSocket? = DatagramSocket(null).apply { bind(InetSocketAddress(rtp.localPort + 1)) }

        var packets = 0
        var aus = 0
        var idrs = 0
        var discontinuities = 0
        var mismatched: InetAddress? = null
        val started: Long

        try {
            val session = RtspSession(control, url)
            val track = session.describe()
            println("[$label] DESCRIBE ok: pt=${track.payloadType} control=${track.control} " +
                "sps=${track.sps?.size ?: 0}B pps=${track.pps?.size ?: 0}B")

            session.setupUdp(track.control, rtp.localPort)
            println("[$label] SETUP ok: session=${session.sessionId} timeout=${session.timeoutSeconds}s " +
                "clientPorts=${rtp.localPort}-${rtp.localPort + 1}")

            session.play()
            if (closeRtcpAfterPlay) {
                rtcp?.close()
                rtcp = null
                println("[$label] PLAY ok — RTCP socket CLOSED (as the app's pipeline does)")
            } else {
                println("[$label] PLAY ok — RTCP socket held open")
            }

            val expectedSource = control.inetAddress
            val depacketizer = RtpH264Depacketizer { discontinuities++ }
            val buffer = ByteArray(65_536)
            val datagram = DatagramPacket(buffer, buffer.size)
            rtp.soTimeout = 10_000

            // Run past a whole GOP (30 frames at 15 fps = 2 s), or no IDR proves nothing.
            started = System.currentTimeMillis()
            val deadline = started + 12_000
            while (System.currentTimeMillis() < deadline && (aus < 60 || idrs == 0)) {
                // DatagramPacket keeps the PREVIOUS receive's length, so without resetting it each
                // receive can take no more bytes than the last one did and the usable buffer
                // shrinks toward the smallest packet seen — silently truncating everything after.
                datagram.setLength(buffer.size)
                try {
                    rtp.receive(datagram)
                } catch (e: SocketTimeoutException) {
                    break
                }
                packets++
                if (datagram.address != expectedSource && mismatched == null) mismatched = datagram.address
                val packet = RtpPacket.parse(datagram.data, datagram.length) ?: continue
                if (packet.payloadType != track.payloadType) continue
                depacketizer.feed(packet)?.let {
                    aus++
                    if (containsIdr(it)) idrs++
                }
            }
            runCatching { session.teardown() }
        } finally {
            runCatching { control.close() }
            rtp.close()
            rtcp?.close()
        }

        return Result(packets, aus, idrs, discontinuities,
            (System.currentTimeMillis() - started) / 1000.0, mismatched)
    }

    @Test
    fun `a real UDP session delivers complete access units`() {
        assumeTrue("MFD emulator not running on $host:$port — skipping", serverIsUp())
        val r = runSession(closeRtcpAfterPlay = false, label = "rtcp-open")
        println("[udp-test] rtcp-open  -> $r")
        assertTrue("no RTP packets arrived at all over UDP", r.packets > 0)
        assertTrue("RTP arrived but no complete access unit was assembled", r.accessUnits > 0)
        assertTrue("no IDR seen — a decoder would never have a clean entry point", r.idrs > 0)
        assertTrue("RTP came from an unexpected source: ${r.mismatchedSource}", r.mismatchedSource == null)
    }

    /**
     * The pipeline closed its RTCP socket the moment PLAY returned, reasoning that nothing reads
     * receiver reports on a one-hop LAN. That reasoning is incomplete in a way worth testing: RTCP
     * is also where the **server** sends its sender reports, and a closed port answers those with
     * ICMP port-unreachable. What a server does with that is server-specific — exactly the sort of
     * thing that works against one implementation and stalls against another.
     */
    @Test
    fun `closing the RTCP socket after PLAY does not stop the stream`() {
        assumeTrue("MFD emulator not running on $host:$port — skipping", serverIsUp())
        val r = runSession(closeRtcpAfterPlay = true, label = "rtcp-closed")
        println("[udp-test] rtcp-closed -> $r")
        assertTrue(
            "closing RTCP after PLAY stopped the stream (${r.accessUnits} access units) — the " +
                "shipping pipeline does exactly this",
            r.accessUnits > 0,
        )
    }
}
