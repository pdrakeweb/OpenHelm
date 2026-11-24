package dev.openhelm.app.ui

/**
 * When the "delayed by" readout appears over the picture.
 *
 * The readout answers a question the picture itself cannot: whether what you are looking at is
 * live. That matters most when the answer is *no*, which is why the default is [WHEN_DELAYED] —
 * an indicator that is always lit is one nobody reads, and a chart that is running late needs to
 * say so at the moment it starts, not blend into furniture that was there all along.
 */
enum class DelayNotification(val label: String, val detail: String) {
    /** Show the delay at all times, whatever it is. */
    ALWAYS("Always", "The delay is shown constantly, however small it is."),

    /** Show it only once the delay reaches the threshold. The default. */
    WHEN_DELAYED("When late", "Nothing is shown until the picture falls behind."),

    /** Never show it. */
    OFF("Off", "The delay is never shown."),
}

/** What a fresh install does: stay quiet, speak up when the picture falls behind. */
val DefaultDelayNotification: DelayNotification = DelayNotification.WHEN_DELAYED

/**
 * How late the picture has to be before it is worth saying so, in seconds.
 *
 * Three by default. Below about a second is ordinary pipeline latency and saying so would be
 * noise; by three the chart is far enough behind that a boat under way has moved meaningfully
 * since the frame was drawn, which is the point at which the number stops being trivia.
 */
val DelayThresholdChoices: List<Int> = listOf(1, 2, 3, 5, 10)

const val DefaultDelayThresholdSeconds: Int = 3

/**
 * Read a persisted mode name, tolerating anything unexpected.
 *
 * Stored by name rather than ordinal so reordering the enum cannot silently change what a user
 * gets — the same rule the palette follows, for the same reason.
 */
internal fun parseDelayNotification(saved: String?): DelayNotification =
    DelayNotification.entries.firstOrNull { it.name == saved } ?: DefaultDelayNotification

/** Clamp a persisted threshold to something offered, so a stale value cannot strand the UI. */
internal fun parseDelayThreshold(saved: Int?): Int =
    saved?.takeIf { it in DelayThresholdChoices } ?: DefaultDelayThresholdSeconds

/**
 * How old a held picture is, for the banner over it: `45 s`, `2 min 05 s`, `1 h 03 min`.
 *
 * Whole seconds, and never rounded *down* across a unit — a picture 119 seconds old reads
 * `1 min 59 s`, not "1 min" — because this number is what tells someone how far the boat has moved
 * since the chart in front of them was true.
 */
internal fun formatPictureAge(ageMs: Long): String {
    val total = (ageMs.coerceAtLeast(0L) / 1_000L)
    val h = total / 3_600
    val m = (total % 3_600) / 60
    val sec = total % 60
    return when {
        h > 0 -> "%d h %02d min".format(java.util.Locale.ROOT, h, m)
        m > 0 -> "%d min %02d s".format(java.util.Locale.ROOT, m, sec)
        else -> "$sec s"
    }
}
