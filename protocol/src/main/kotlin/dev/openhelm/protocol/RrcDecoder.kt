package dev.openhelm.protocol

/** A decoded frame. [Unknown] exists so an unmapped opcode is reported, never fatal. */
public sealed interface RrcFrame {
    public val version: Int

    public data class Button(val key: MfdKey?, val rawKey: Int, val action: KeyAction?, override val version: Int) : RrcFrame
    public data class Zoom(val x: Int, val y: Int, override val version: Int) : RrcFrame
    public data class Touch(val action: TouchAction?, val seq: Int, val x: Int, val y: Int, override val version: Int) : RrcFrame {
        /** Position as a 0f..1f fraction of the video area. */
        public val fraction: Pair<Float, Float>
            get() = (x.toFloat() / TOUCH_MAX) to (y.toFloat() / TOUCH_MAX)
    }

    /** A well-framed message whose opcode we do not know. Surfaced, not thrown. */
    public data class Unknown(val opcode: Int, val payload: ByteArray, override val version: Int) : RrcFrame {
        // Hand-written because the generated ones would compare ByteArray by identity.
        override fun equals(other: Any?): Boolean = other is Unknown &&
            opcode == other.opcode && version == other.version && payload.contentEquals(other.payload)

        override fun hashCode(): Int = (opcode * 31 + version) * 31 + payload.contentHashCode()
    }
}

/**
 * Reassembles RRC frames from a TCP byte stream.
 *
 * TCP gives no message boundaries, so this tolerates frames split across reads and several frames
 * arriving in one read. On a corrupt stream it resynchronises by dropping a byte and rescanning for
 * the magic, so bad input can slow it down but cannot wedge it.
 *
 * The app itself never decodes RRC — the display never speaks on the control socket. This class is
 * the *receiving* half of the protocol, here so that simulators, desktop tooling and the tests can
 * verify what the encoder emits. The boxed `ArrayDeque<Byte>` buffer is deliberate simplicity: at
 * control-channel rates (tens of bytes per user action) it costs nothing; swap it for a ByteArray
 * ring before feeding it anything video-rate.
 *
 * Not thread-safe; own it from one coroutine.
 */
public class RrcDecoder {
    private val buf = ArrayDeque<Byte>()

    public fun feed(data: ByteArray): List<RrcFrame> {
        data.forEach(buf::addLast)
        val out = mutableListOf<RrcFrame>()

        while (true) {
            if (buf.size < Rrc.HEADER_LEN) break

            if (!magicAt0()) {
                buf.removeFirst() // resync
                continue
            }

            val payloadLen = (buf.elementAt(7).toInt() and 0xFF) or ((buf.elementAt(8).toInt() and 0xFF) shl 8)
            val total = Rrc.HEADER_LEN + payloadLen
            if (buf.size < total) break // wait for the rest

            val frame = ByteArray(total) { buf.removeFirst() }
            out += decode(frame)
        }
        return out
    }

    private fun magicAt0(): Boolean =
        (0..3).all { buf.elementAt(it) == Rrc.MAGIC[it] }

    private fun decode(frame: ByteArray): RrcFrame {
        val version = frame[5].toInt() and 0xFF
        val opcode = frame[6].toInt() and 0xFF
        val p = frame.copyOfRange(Rrc.HEADER_LEN, frame.size)

        fun le16(i: Int): Int {
            val v = (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)
            return if (v > 0x7FFF) v - 0x10000 else v // the field is a signed int16
        }

        return when {
            opcode == Rrc.OP_BUTTON && p.size >= 2 -> {
                val raw = p[0].toInt() and 0xFF
                RrcFrame.Button(
                    key = MfdKey.fromCode(raw),
                    rawKey = raw,
                    action = KeyAction.entries.firstOrNull { it.code == (p[1].toInt() and 0xFF) },
                    version = version,
                )
            }

            opcode == Rrc.OP_ZOOM && p.size >= 4 -> RrcFrame.Zoom(le16(0), le16(2), version)

            opcode == Rrc.OP_TOUCH && p.size >= 6 -> RrcFrame.Touch(
                action = TouchAction.entries.firstOrNull { it.code == (p[0].toInt() and 0xFF) },
                seq = p[1].toInt() and 0xFF,
                // Touch coordinates span 0..65535; the field is signed, so the top half arrives
                // negative and has to be folded back before it means anything.
                x = le16(2).let { if (it < 0) it + 0x10000 else it },
                y = le16(4).let { if (it < 0) it + 0x10000 else it },
                version = version,
            )

            else -> RrcFrame.Unknown(opcode, p, version)
        }
    }
}
