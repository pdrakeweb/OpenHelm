package dev.openhelm.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The light conditions OpenHelm is used in.
 *
 * Chartplotters have had these for decades because the requirement is real: the same screen is read
 * in direct sun and then, hours later, by someone whose night vision is the difference between
 * seeing an unlit hazard and not.
 *
 * All three are reachable from the status bar while a session is live — the moment they are needed
 * is the moment when leaving the remote to hunt through settings is least acceptable. Declaration
 * order is the cycle order that control steps through, brightest first, so it is not free to change.
 */
enum class HelmPalette { HIGH_CONTRAST, DARK, NIGHT }

/**
 * What a fresh install shows until the user chooses otherwise.
 *
 * [DARK] regardless of the phone's own light/dark setting. That setting describes a living room
 * rather than a cockpit, and it is wrong in both directions here: a phone in light mode dragged
 * into [HIGH_CONTRAST] is glaring below decks, and high contrast is the palette least likely to be
 * right at the moment the app is first opened, which is rarely in direct sun.
 */
val DefaultPalette: HelmPalette = HelmPalette.DARK

/**
 * Colours for the remote's own controls: keypad keys, the dial, and the panel.
 *
 * These are deliberately **not** Material colour roles. `surfaceVariant` was carrying the keys, the
 * settings cards and the scan indicator's track at once, and those want opposite things in a
 * high-contrast palette: a key should be a dark slab with white content, while a card must stay
 * light so the black text on it reads. One role cannot be both, and the attempt produced either
 * washed-out keys or invisible card text depending on which way it was pushed.
 *
 * Splitting them out also means the contrast of the controls — the part of the app someone is
 * actually looking at on a wet afternoon — can be stated per palette and checked, which is what
 * `PaletteContrastTest` does.
 */
@Immutable
data class HelmControlColors(
    /** Resting key, dial hub and dial ring. */
    val keyFill: Color,
    /** Glyphs and labels on a resting key. */
    val keyContent: Color,
    /** A key under the finger. */
    val keyPressedFill: Color,
    /** Glyphs and labels on a pressed key. */
    val keyPressedContent: Color,
    /** Drawn around every key. Zero width where the fill alone already separates it. */
    val keyBorder: Color,
    val keyBorderWidth: Dp,
)

val LocalHelmControls = staticCompositionLocalOf {
    // Never used: OpenHelmTheme always provides. Present so previews fail loudly rather than oddly.
    HelmControlColors(
        keyFill = Color.Magenta,
        keyContent = Color.Black,
        keyPressedFill = Color.Magenta,
        keyPressedContent = Color.Black,
        keyBorder = Color.Transparent,
        keyBorderWidth = 0.dp,
    )
}

/**
 * Shared container tones for [DarkColors].
 *
 * Four subtly different navies had accumulated across side-panel keys, keypad tiles, the recents
 * buttons and settings cards, with no single source. These are that source.
 */
private object Navy {
    val Background = Color(0xFF0B1620)
    val Surface = Color(0xFF122334)
    val SurfaceContainer = Color(0xFF16293C)
    val SurfaceVariant = Color(0xFF1B3247)
    val OnSurface = Color(0xFFDDE7EE)
    val OnSurfaceVariant = Color(0xFFB8C7D4)
}

/**
 * Overcast, twilight, below decks. The conventional dark theme, and the default.
 *
 * Note how many roles are named. Material's tonal and segmented buttons — every *Done*, *Back*,
 * *End simulation* and the Mirror/Remote switch — draw from `secondaryContainer`, the destructive
 * variant from `errorContainer`, and switch tracks from `surfaceContainerHighest`. Leaving one
 * unset does not derive it from the palette: the scheme builder fills it from Material's baseline,
 * which is purple in the dark schemes and near-white in the light one. That produced a row of
 * lavender buttons on every screen. If a role is used anywhere, it is named in all three schemes.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF06263F),
    primaryContainer = Color(0xFF13405F),
    onPrimaryContainer = Color(0xFFCDE5FA),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF04312C),
    secondaryContainer = Color(0xFF1B3A4E),
    onSecondaryContainer = Color(0xFFCFE2F0),
    tertiary = Color(0xFFD9A441),
    onTertiary = Color(0xFF3A2900),
    tertiaryContainer = Color(0xFF4A3608),
    onTertiaryContainer = Color(0xFFF3DCA8),
    background = Navy.Background,
    onBackground = Navy.OnSurface,
    surface = Navy.Surface,
    onSurface = Navy.OnSurface,
    surfaceContainerLowest = Color(0xFF060E16),
    surfaceContainerLow = Color(0xFF0E1C28),
    surfaceContainer = Navy.SurfaceContainer,
    surfaceContainerHigh = Navy.SurfaceVariant,
    surfaceContainerHighest = Color(0xFF203A52),
    surfaceVariant = Navy.SurfaceVariant,
    onSurfaceVariant = Navy.OnSurfaceVariant,
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF5A1D1D),
    onErrorContainer = Color(0xFFFBD5D5),
    outline = Color(0xFF547A96),
)

private val DarkControls = HelmControlColors(
    // Lighter than the panel behind it by a real margin. This used to be Navy.SurfaceVariant, which
    // measured 1.39:1 against the background — the keys were very nearly the same tone as the panel
    // they sat on, and the boundary was doing no work at all.
    keyFill = Color(0xFF476881),
    keyContent = Navy.OnSurface,
    // Amber, as in high contrast. Blue would have been the palette-consistent choice and was the
    // first attempt, but a blue bright enough to read as "pressed" against a blue-grey key could
    // not also stay clear of it: the resting fill has to be light enough to separate from the
    // panel, which leaves too little room above it. Amber steps off the palette's hue entirely and
    // is unmistakable in peripheral vision, which is where a press confirmation is actually seen.
    keyPressedFill = Color(0xFFFFB300),
    keyPressedContent = Color(0xFF241A00),
    keyBorder = Color.Transparent,
    keyBorderWidth = 0.dp,
)

/**
 * Direct sun. Legibility is the only thing being optimised.
 *
 * Built to WCAG's AAA thresholds rather than by eye: 7:1 for body text, 4.5:1 for large text and
 * icons. Every pairing here clears those with room to spare, and `PaletteContrastTest` computes the
 * ratios so a later tweak that looks nicer but reads worse fails the build.
 *
 * Two decisions differ from a conventional light theme, both because sunlight compresses contrast
 * from the top down. Ambient light adds a roughly constant amount to everything on the glass, so
 * the pale end of a scheme collapses towards the page first — a near-white button on a white page
 * has no edge left once the highlights are gone. So the page is pure white, to keep the maximum
 * absolute luminance available, and everything meant to be *touched* is a dark slab on it rather
 * than a lighter tint of it. Nothing relies on a mid-tone separating from another mid-tone.
 *
 * Colour is used only where it distinguishes one thing from another (the pressed amber, the
 * destructive red); it never carries information on its own.
 */
private val HighContrastColors = lightColorScheme(
    primary = Color(0xFF00365F),
    onPrimary = Color(0xFFFFFFFF),
    // Selected states are dark slabs, not tints, so they hold their edge in glare.
    primaryContainer = Color(0xFF00365F),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF0A4A42),
    onSecondary = Color(0xFFFFFFFF),
    // The tonal-button container: Mirror/Remote, Done, Back, End simulation.
    secondaryContainer = Color(0xFF12314C),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF4A3400),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF4A3400),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F5F8),
    surfaceContainer = Color(0xFFE7ECF1),
    surfaceContainerHigh = Color(0xFFDBE2E9),
    surfaceContainerHighest = Color(0xFFCAD4DE),
    // Cards and the scan track. Light, because black text sits on these.
    surfaceVariant = Color(0xFFE7ECF1),
    // No de-emphasised text in this palette: "muted" is the first thing sunlight destroys.
    onSurfaceVariant = Color(0xFF14202B),
    error = Color(0xFF7A0F07),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF7A0F07),
    onErrorContainer = Color(0xFFFFFFFF),
    outline = Color(0xFF000000),
)

private val HighContrastControls = HelmControlColors(
    // A near-black slab on a white page: the largest luminance step the display can produce, and
    // the one least affected by glare washing out the top of the range.
    keyFill = Color(0xFF0A1926),
    keyContent = Color(0xFFFFFFFF),
    // The press flash inverts the key rather than tinting it, so it registers peripherally while
    // the eyes are on the water. Amber because it is far from both the resting slab and the page.
    keyPressedFill = Color(0xFFFFC400),
    keyPressedContent = Color(0xFF000000),
    // Defines the pressed key against white; invisible against the resting slab, which needs none.
    keyBorder = Color(0xFF000000),
    keyBorderWidth = 2.dp,
)

/**
 * After dark, underway. Red-dominant and very low luminance: rod cells are least sensitive to long
 * wavelengths, so a red-forward display costs the least dark adaptation. Nothing here is white and
 * nothing approaches full brightness.
 *
 * This palette deliberately does not meet WCAG's ratios, and cannot. Those thresholds assume a
 * screen read indoors, where the only cost of more contrast is more contrast; here the cost is a
 * navigator's night vision, which takes twenty minutes to rebuild. The trade is made knowingly and
 * `PaletteContrastTest` holds it to a lower floor rather than pretending otherwise.
 */
private val NightColors = darkColorScheme(
    primary = Color(0xFFB2453A),
    onPrimary = Color(0xFF1A0603),
    primaryContainer = Color(0xFF3A1610),
    onPrimaryContainer = Color(0xFFC26550),
    secondary = Color(0xFF8C4A3A),
    onSecondary = Color(0xFF1A0603),
    secondaryContainer = Color(0xFF26100B),
    onSecondaryContainer = Color(0xFFB4604C),
    tertiary = Color(0xFF8A5A2A),
    onTertiary = Color(0xFF1A0603),
    tertiaryContainer = Color(0xFF25150A),
    onTertiaryContainer = Color(0xFFA8763C),
    background = Color(0xFF070402),
    onBackground = Color(0xFFA85A46),
    surface = Color(0xFF120705),
    onSurface = Color(0xFFA85A46),
    surfaceContainerLowest = Color(0xFF040201),
    surfaceContainerLow = Color(0xFF0D0503),
    surfaceContainer = Color(0xFF170907),
    surfaceContainerHigh = Color(0xFF1F0C08),
    surfaceContainerHighest = Color(0xFF2A120C),
    surfaceVariant = Color(0xFF1F0C08),
    onSurfaceVariant = Color(0xFF97503F),
    error = Color(0xFFCF6151),
    onError = Color(0xFF1A0603),
    errorContainer = Color(0xFF3A140F),
    onErrorContainer = Color(0xFFD97C68),
    outline = Color(0xFF6B342A),
)

private val NightControls = HelmControlColors(
    // Lifted well clear of the page. Matching the panel's own surfaceVariant put the keys at 1.08:1
    // against the background — on a dark bridge you could not find a key without feeling for it,
    // which is the opposite of what a night palette is for. Dim is the goal; invisible is a defect.
    keyFill = Color(0xFF522319),
    keyContent = Color(0xFFC4705A),
    keyPressedFill = Color(0xFFB2453A),
    keyPressedContent = Color(0xFF1A0603),
    keyBorder = Color.Transparent,
    keyBorderWidth = 0.dp,
)

/** The colour scheme and control colours for a palette. Exposed for the contrast test. */
internal fun schemeFor(palette: HelmPalette) = when (palette) {
    HelmPalette.HIGH_CONTRAST -> HighContrastColors
    HelmPalette.DARK -> DarkColors
    HelmPalette.NIGHT -> NightColors
}

internal fun controlsFor(palette: HelmPalette) = when (palette) {
    HelmPalette.HIGH_CONTRAST -> HighContrastControls
    HelmPalette.DARK -> DarkControls
    HelmPalette.NIGHT -> NightControls
}

@Composable
fun OpenHelmTheme(
    palette: HelmPalette = DefaultPalette,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalHelmControls provides controlsFor(palette)) {
        MaterialTheme(colorScheme = schemeFor(palette), content = content)
    }
}
