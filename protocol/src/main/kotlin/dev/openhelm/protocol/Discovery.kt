package dev.openhelm.protocol

/**
 * Service discovery: the mDNS/DNS-SD facts, and the parsing that turns them into something usable.
 *
 * Deliberately free of Android types so it can be unit-tested and shared with desktop tooling.
 * The platform layer supplies resolved names, addresses and TXT maps; everything here is pure.
 */
public object Discovery {
    /** Video service. */
    public const val SERVICE_RTSP: String = "_rtsp._tcp"

    /** Remote-control service. */
    public const val SERVICE_RRC: String = "_rym_rrc._tcp"

    /** Camera pan/tilt. Known to exist; not supported. */
    public const val SERVICE_PAN_TILT: String = "_rym_pt._udp"

    public const val TXT_RTSP_PATH: String = "raymarine-mfd-rtsp-path"
    public const val TXT_MODEL: String = "raymarine-mfd-model"
    public const val TXT_SERIAL: String = "raymarine-mfd-serial"
    public const val TXT_RRC_VERSION: String = "raymarine-mfd-rrc-version"
}

/**
 * Turn the advertised version string into the byte that goes in a frame header.
 *
 * **This is not the obvious conversion, and getting it wrong is silent.** The device advertises a
 * string like `"1.10"`, and the wire format wants characters `[2,4)` of it interpreted as
 * **hexadecimal**:
 *
 * ```
 * "1.10"  ->  "10"  ->  0x10  ->  16      // not 1, and not 10
 * ```
 *
 * Reproduced deliberately: the encoding is what a device expects, so "fixing" it to a sane parse
 * would break interoperability. Falls back to [Rrc.DEFAULT_VERSION] for anything unparsable, which
 * is what the reference implementation does.
 */
public fun parseRrcVersion(advertised: String?): Int {
    if (advertised == null || advertised.length < 4) return Rrc.DEFAULT_VERSION
    return advertised.substring(2, 4).toIntOrNull(16) ?: Rrc.DEFAULT_VERSION
}

/**
 * A discovered (or manually entered) MFD.
 *
 * @param host address of the unit
 * @param rtspPort port from the `_rtsp._tcp` SRV record
 * @param rrcPort port from the `_rym_rrc._tcp` SRV record
 * @param rtspPath the [Discovery.TXT_RTSP_PATH] TXT value
 * @param rrcVersion header byte, already decoded by [parseRrcVersion]
 */
public data class MfdEndpoint(
    public val host: String,
    public val rtspPort: Int,
    public val rrcPort: Int,
    public val rtspPath: String,
    public val rrcVersion: Int = Rrc.DEFAULT_VERSION,
    public val model: String? = null,
    public val serial: String? = null,
) {
    /**
     * The stream URL, assembled the way the device expects.
     *
     * Use **UDP** for RTP when playing this. A TCP-interleaved SETUP hangs the MFD's RTSP server
     * indefinitely — the session appears to establish and then no media ever arrives.
     */
    public val rtspUrl: String get() = "rtsp://$host:$rtspPort/$rtspPath"

    public companion object {
        /**
         * Parse the compact manual-address form `host:rtspPort:rrcPort:rtspPath:versionHex`,
         * e.g. `192.168.131.1:8554:50000:RAYMARINEMFD:10`.
         *
         * Discovery over mDNS is unreliable on real boat Wi-Fi, so entering an address by hand is a
         * first-class path, not a debug affordance. Returns null on anything malformed.
         */
        public fun parse(line: String): MfdEndpoint? {
            val f = line.trim().split(":")
            if (f.size < 4) return null
            // Ports must be actual TCP ports. Anything else is rejected here, at the parse,
            // because `InetSocketAddress` throws an *unchecked* IllegalArgumentException for an
            // out-of-range port — deep inside a connection loop is the wrong place to find out.
            val rtspPort = f[1].toIntOrNull()?.takeIf { it in 1..0xFFFF } ?: return null
            val rrcPort = f[2].toIntOrNull()?.takeIf { it in 1..0xFFFF } ?: return null
            if (f[0].isBlank() || f[3].isBlank()) return null
            val version = f.getOrNull(4)?.toIntOrNull(16) ?: Rrc.DEFAULT_VERSION
            return MfdEndpoint(f[0], rtspPort, rrcPort, f[3], version)
        }
    }
}
