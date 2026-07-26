package dev.openhelm.protocol.video

/**
 * One parsed RTP packet (RFC 3550 header already validated and stripped).
 */
public data class RtpPacket(
    public val sequence: Int,
    public val timestamp: Long,
    public val marker: Boolean,
    public val payloadType: Int,
    public val payload: ByteArray,
) {
    public companion object {
        public const val MIN_HEADER: Int = 12

        /** Parse a datagram. Returns null for anything that is not a well-formed RTP v2 packet. */
        public fun parse(datagram: ByteArray, length: Int = datagram.size): RtpPacket? {
            if (length < MIN_HEADER) return null
            val b0 = datagram[0].toInt() and 0xFF
            if (b0 shr 6 != 2) return null // RTP version 2
            val hasPadding = b0 and 0x20 != 0
            val hasExtension = b0 and 0x10 != 0
            val csrcCount = b0 and 0x0F
            val b1 = datagram[1].toInt() and 0xFF
            val marker = b1 and 0x80 != 0
            val payloadType = b1 and 0x7F
            val sequence = ((datagram[2].toInt() and 0xFF) shl 8) or (datagram[3].toInt() and 0xFF)
            val timestamp = ((datagram[4].toLong() and 0xFF) shl 24) or
                ((datagram[5].toLong() and 0xFF) shl 16) or
                ((datagram[6].toLong() and 0xFF) shl 8) or
                (datagram[7].toLong() and 0xFF)

            var offset = MIN_HEADER + csrcCount * 4
            if (offset > length) return null
            if (hasExtension) {
                if (offset + 4 > length) return null
                val extWords = ((datagram[offset + 2].toInt() and 0xFF) shl 8) or
                    (datagram[offset + 3].toInt() and 0xFF)
                offset += 4 + extWords * 4
                if (offset > length) return null
            }
            var end = length
            if (hasPadding) {
                val pad = datagram[length - 1].toInt() and 0xFF
                if (pad == 0 || pad > end - offset) return null
                end -= pad
            }
            return RtpPacket(sequence, timestamp, marker, payloadType, datagram.copyOfRange(offset, end))
        }
    }
}

/**
 * Reassembles H.264 access units from RTP payloads (RFC 6184).
 *
 * Understands single NAL units (types 1–23), FU-A fragments (28) and STAP-A aggregates (24) —
 * which is everything the MFD's stock RTSP server, and every general-purpose H.264 packetizer,
 * actually emits. Output NAL units carry `00 00 00 01` start codes, concatenated per access unit,
 * ready for a decoder.
 *
 * **Loss policy, per the latency-first design:** there is no reorder buffer and no retry. A
 * sequence gap or a broken fragment run discards the access unit under assembly and reports a
 * discontinuity — the caller should skip forward to the next IDR rather than display a corrupt
 * frame. On the one-hop LAN this protocol runs over, packets arrive in order or not at all; a
 * smoothing buffer would buy nothing and cost latency.
 *
 * Not thread-safe: feed it from the single receive loop.
 */
public class RtpH264Depacketizer(private val onDiscontinuity: () -> Unit = {}) {

    private var expectedSeq: Int? = null
    private var currentTimestamp: Long = -1
    private val nals = ArrayList<ByteArray>()
    private var fuBuffer: ByteArray? = null

    /**
     * Feed one packet; returns a complete access unit (marker-terminated), or null while one is
     * still assembling.
     */
    public fun feed(packet: RtpPacket): ByteArray? {
        expectedSeq?.let { expected ->
            if (packet.sequence != expected) {
                // Loss or reorder: either way the AU in progress is unusable.
                dropInProgress()
                onDiscontinuity()
            }
        }
        expectedSeq = (packet.sequence + 1) and 0xFFFF

        if (packet.timestamp != currentTimestamp) {
            // New frame began without a marker on the old one (or after loss): flush nothing,
            // start clean. Emitting a partial AU risks decoding a torn picture.
            if (currentTimestamp != -1L && nals.isNotEmpty()) {
                nals.clear()
                onDiscontinuity()
            }
            fuBuffer = null
            currentTimestamp = packet.timestamp
        }

        val p = packet.payload
        if (p.isEmpty()) return null
        when (val nalType = p[0].toInt() and 0x1F) {
            in 1..23 -> nals.add(START_CODE + p)

            24 -> { // STAP-A: [u16 size][NAL] repeated
                var i = 1
                while (i + 2 <= p.size) {
                    val size = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                    i += 2
                    if (size == 0 || i + size > p.size) break
                    nals.add(START_CODE + p.copyOfRange(i, i + size))
                    i += size
                }
            }

            28 -> { // FU-A
                if (p.size < 2) return null
                val fuHeader = p[1].toInt() and 0xFF
                val start = fuHeader and 0x80 != 0
                val end = fuHeader and 0x40 != 0
                if (start) {
                    val reconstructed = ((p[0].toInt() and 0xE0) or (fuHeader and 0x1F)).toByte()
                    fuBuffer = START_CODE + byteArrayOf(reconstructed) + p.copyOfRange(2, p.size)
                } else {
                    val buf = fuBuffer
                        ?: return null // continuation without a start: mid-loss, skip
                    fuBuffer = buf + p.copyOfRange(2, p.size)
                }
                if (end) {
                    fuBuffer?.let { nals.add(it) }
                    fuBuffer = null
                }
            }

            else -> return null // FU-B, MTAP: not emitted by any server this talks to
        }

        if (!packet.marker) return null
        if (fuBuffer != null) {
            // Marker inside an unfinished fragment run: broken frame.
            dropInProgress()
            onDiscontinuity()
            return null
        }
        if (nals.isEmpty()) return null

        val total = nals.sumOf { it.size }
        val au = ByteArray(total)
        var pos = 0
        for (nal in nals) {
            nal.copyInto(au, pos)
            pos += nal.size
        }
        nals.clear()
        currentTimestamp = -1
        return au
    }

    private fun dropInProgress() {
        nals.clear()
        fuBuffer = null
        currentTimestamp = -1
    }
}

/** True when the access unit contains an IDR slice (NAL type 5) — a clean decoder entry point. */
public fun containsIdr(accessUnit: ByteArray): Boolean = containsNalType(accessUnit, 5)

/** True when the access unit contains an SPS (NAL type 7). */
public fun containsSps(accessUnit: ByteArray): Boolean = containsNalType(accessUnit, 7)

private fun containsNalType(au: ByteArray, wanted: Int): Boolean {
    var i = 0
    while (i + 4 < au.size) {
        // Find a start code (00 00 01, optionally preceded by an extra 00).
        if (au[i].toInt() == 0 && au[i + 1].toInt() == 0) {
            val skip = when {
                au[i + 2].toInt() == 1 -> 3
                au[i + 2].toInt() == 0 && i + 3 < au.size && au[i + 3].toInt() == 1 -> 4
                else -> 0
            }
            if (skip > 0 && i + skip < au.size) {
                if (au[i + skip].toInt() and 0x1F == wanted) return true
                i += skip
                continue
            }
        }
        i++
    }
    return false
}
