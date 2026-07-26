package dev.openhelm.protocol.video

import java.util.Base64

/**
 * The slice of SDP (RFC 8866) that an MFD's RTSP DESCRIBE answer actually uses: one H.264 video
 * track, its payload type, its control attribute, and the out-of-band SPS/PPS from
 * `sprop-parameter-sets`.
 *
 * Deliberately not a general SDP parser — unknown lines are ignored, and only the first video
 * media section is considered.
 */
public data class SdpVideoTrack(
    /** RTP payload type from `m=video`, e.g. 96. */
    public val payloadType: Int,
    /** The `a=control:` value, to be resolved against the DESCRIBE URL for SETUP. */
    public val control: String?,
    /** Sequence parameter set with `00 00 00 01` start code, if the SDP carried one. */
    public val sps: ByteArray?,
    /** Picture parameter set with start code, if carried. */
    public val pps: ByteArray?,
    /** RTP clock rate, from `a=rtpmap`; H.264 is always 90000. */
    public val clockRate: Int,
)

/** Parse the video track description out of an SDP body. Null when there is no H.264 video. */
public fun parseSdpVideoTrack(sdp: String): SdpVideoTrack? {
    var inVideo = false
    var payloadType = -1
    var control: String? = null
    var clockRate = 90_000
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    var isH264 = false

    for (raw in sdp.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("m=") -> {
                if (inVideo) break // second media section: done with the first video one
                if (line.startsWith("m=video")) {
                    inVideo = true
                    payloadType = line.split(' ').lastOrNull()?.toIntOrNull() ?: -1
                }
            }

            !inVideo -> {} // session-level lines before the video section

            line.startsWith("a=rtpmap:") -> {
                // a=rtpmap:96 H264/90000
                val rest = line.removePrefix("a=rtpmap:")
                val pt = rest.substringBefore(' ').toIntOrNull()
                if (pt == payloadType) {
                    val encoding = rest.substringAfter(' ', "")
                    isH264 = encoding.startsWith("H264", ignoreCase = true)
                    clockRate = encoding.substringAfter('/', "90000").substringBefore('/')
                        .toIntOrNull() ?: 90_000
                }
            }

            line.startsWith("a=control:") -> control = line.removePrefix("a=control:")

            line.startsWith("a=fmtp:") -> {
                val rest = line.removePrefix("a=fmtp:")
                if (rest.substringBefore(' ').toIntOrNull() == payloadType) {
                    val params = rest.substringAfter(' ', "").split(';')
                    val sprop = params.map { it.trim() }
                        .firstOrNull { it.startsWith("sprop-parameter-sets=") }
                        ?.removePrefix("sprop-parameter-sets=")
                    if (sprop != null) {
                        val sets = sprop.split(',').mapNotNull { b64 ->
                            try {
                                Base64.getDecoder().decode(b64)
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        }
                        // By convention the SPS (NAL type 7) comes first, but check rather than
                        // assume — some servers order them the other way.
                        for (nal in sets) {
                            if (nal.isEmpty()) continue
                            when (nal[0].toInt() and 0x1F) {
                                7 -> sps = START_CODE + nal
                                8 -> pps = START_CODE + nal
                            }
                        }
                    }
                }
            }
        }
    }

    if (!inVideo || payloadType < 0 || !isH264) return null
    return SdpVideoTrack(payloadType, control, sps, pps, clockRate)
}

internal val START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)
