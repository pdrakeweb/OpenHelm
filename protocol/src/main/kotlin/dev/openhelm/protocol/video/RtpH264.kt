package dev.openhelm.protocol.video

import java.io.ByteArrayOutputStream

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

    // A growable buffer rather than `fuBuffer = fuBuffer + fragment`: that spelling re-copies
    // everything accumulated so far on every continuation, which is O(k²) bytes for a k-fragment
    // NAL — the reassembly of every keyframe, on the hottest path in the pipeline.
    private var fu: ByteArrayOutputStream? = null

    /**
     * Feed one packet; returns a complete access unit, or null while one is still assembling.
     *
     * An access unit is complete at **either** boundary RFC 3550 gives us:
     *
     * - the **marker bit**, which RFC 6184 says SHOULD be set on the last packet of an access
     *   unit — the fast path, emitting the moment the frame ends; or
     * - a **change of RTP timestamp**, which is definitive: every packet of one access unit
     *   carries the same timestamp, so a new one means the previous frame is finished.
     *
     * Relying on the marker alone was a real defect, not a theoretical one. A display whose
     * payloader does not set it produced a stream where every completed frame was discarded at
     * the next timestamp and counted as a discontinuity — 388 of them and not one frame decoded,
     * on a link that was otherwise perfectly healthy. "SHOULD" is not "MUST", and a client that
     * treats it as MUST silently decodes nothing.
     */
    public fun feed(packet: RtpPacket): ByteArray? {
        packetsFed++
        if (packet.marker) markersSeen++

        var completed: ByteArray? = null

        expectedSeq?.let { expected ->
            if (packet.sequence != expected) {
                // Loss or reorder: either way the AU in progress is unusable.
                sequenceGaps++
                dropInProgress()
                onDiscontinuity()
            }
        }
        expectedSeq = (packet.sequence + 1) and 0xFFFF

        if (packet.timestamp != currentTimestamp) {
            if (currentTimestamp != -1L && nals.isNotEmpty()) {
                if (fu != null) {
                    // A fragment run was cut off part-way through: this frame really is torn, and
                    // emitting it would decode a broken picture.
                    dropInProgress()
                    onDiscontinuity()
                } else {
                    // A whole frame that simply never carried a marker. Emit it.
                    unmarkedFrames++
                    completed = assembleAccessUnit()
                }
            }
            fu = null
            currentTimestamp = packet.timestamp
        }

        val p = packet.payload
        if (p.isEmpty()) return completed
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
                if (p.size < 2) return completed
                val fuHeader = p[1].toInt() and 0xFF
                val start = fuHeader and 0x80 != 0
                val end = fuHeader and 0x40 != 0
                if (start) {
                    val reconstructed = (p[0].toInt() and 0xE0) or (fuHeader and 0x1F)
                    fu = ByteArrayOutputStream(p.size + 128).apply {
                        write(START_CODE, 0, START_CODE.size)
                        write(reconstructed)
                        write(p, 2, p.size - 2)
                    }
                } else {
                    val buf = fu
                        ?: return completed // continuation without a start: mid-loss, skip
                    buf.write(p, 2, p.size - 2)
                }
                if (end) {
                    fu?.let { nals.add(it.toByteArray()) }
                    fu = null
                }
            }

            else -> return completed // FU-B, MTAP: not emitted by any server this talks to
        }

        // A frame completed by the timestamp boundary above takes precedence: it is the older of
        // the two and must be delivered in order. The packet just collected stays in `nals` and
        // leaves at the next boundary. This can only arise from a server that sets the marker
        // intermittently, and costs that frame one packet interval rather than losing it.
        if (completed != null) return completed

        if (!packet.marker) return null
        if (fu != null) {
            // Marker inside an unfinished fragment run: broken frame.
            dropInProgress()
            onDiscontinuity()
            return null
        }
        if (nals.isEmpty()) return null

        markerTerminated++
        return assembleAccessUnit()
    }

    /** Concatenate the collected NAL units into one access unit and reset for the next frame. */
    private fun assembleAccessUnit(): ByteArray {
        val total = nals.sumOf { it.size }
        val au = ByteArray(total)
        var pos = 0
        for (nal in nals) {
            nal.copyInto(au, pos)
            pos += nal.size
        }
        nals.clear()
        accessUnitsEmitted++
        return au
    }

    // ---- diagnostics --------------------------------------------------------------------
    //
    // These exist because "no video" was, for one whole sea trial, indistinguishable from "video
    // still starting": the pipeline was healthy at every layer anyone could see, and the only
    // symptom of the marker-bit assumption was a counter going up. Whether a server marks its
    // access units is now something the app can *report*, not something a human has to infer.

    private var packetsFed = 0L
    private var markersSeen = 0L
    private var sequenceGaps = 0L
    private var markerTerminated = 0L
    private var unmarkedFrames = 0L
    private var accessUnitsEmitted = 0L

    /** A snapshot of how the far end is behaving, for logging. */
    public data class Diagnostics(
        val packets: Long,
        val markers: Long,
        val sequenceGaps: Long,
        val accessUnits: Long,
        val markerTerminated: Long,
        val unmarkedFrames: Long,
    ) {
        /**
         * True when packets are arriving and the server is not marking access-unit ends.
         *
         * Ten packets is several frames' worth — a marking server sets one per frame, so a run
         * that long with none is conclusive enough for a log line.
         */
        public val serverOmitsMarker: Boolean get() = packets >= 10 && markers == 0L

        override fun toString(): String = "packets=$packets markers=$markers seqGaps=$sequenceGaps " +
            "aus=$accessUnits (byMarker=$markerTerminated byTimestamp=$unmarkedFrames)" +
            if (serverOmitsMarker) " [server sets no RTP marker — using timestamp boundaries]" else ""
    }

    public fun diagnostics(): Diagnostics = Diagnostics(
        packetsFed, markersSeen, sequenceGaps, accessUnitsEmitted, markerTerminated, unmarkedFrames,
    )

    private fun dropInProgress() {
        nals.clear()
        fu = null
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
