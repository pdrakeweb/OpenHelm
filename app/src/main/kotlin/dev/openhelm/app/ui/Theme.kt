package dev.openhelm.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The light conditions OpenHelm is used in.
 *
 * Chartplotters have had day/dusk/night palettes for decades because the requirement is real: the
 * same screen is read in direct sun and then, hours later, by someone whose night vision is the
 * difference between seeing an unlit hazard and not.
 *
 * All three are reachable from the status bar while a session is live — the moment they are needed
 * is the moment when leaving the remote to hunt through settings is least acceptable. Declaration
 * order is the cycle order that control steps through, brightest first, so it is not free to
 * change.
 */
enum class HelmPalette { DAY, DUSK, NIGHT }

/**
 * What to show before the user has ever chosen: the system's own light/dark setting.
 *
 * Dark maps to [DUSK] rather than [NIGHT]. Android's dark mode means "it is dark here"; [NIGHT] is
 * the far stronger claim that red-shifted, heavily dimmed output is wanted, which costs real
 * legibility and should only ever be entered deliberately.
 */
fun defaultPaletteFor(systemInDarkTheme: Boolean): HelmPalette =
    if (systemInDarkTheme) HelmPalette.DUSK else HelmPalette.DAY

/**
 * Shared container tones.
 *
 * Four subtly different navies had accumulated across side-panel keys, keypad tiles, the recents
 * buttons and settings cards, with no single source. These are that source; every surface in the
 * app pulls from here.
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
 * Overcast, twilight, below decks — the conventional dark theme.
 *
 * Note how many `*Container` roles are set. Material's tonal buttons — every *Done*, *Back*,
 * *Video off* and *Exit simulation* in this app — take their colour from `secondaryContainer`, and
 * the destructive variant from `errorContainer`. Leaving those unset does **not** derive them from
 * the palette: `darkColorScheme()` fills them from Material's baseline, which is purple. Every
 * screen therefore carried a row of lavender buttons that ignored the palette entirely, and in
 * night mode that is not a cosmetic problem — a bright non-red button is exactly the thing night
 * mode exists to remove. If a role is used anywhere, it is named here.
 */
private val DuskColors = darkColorScheme(
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
    surfaceContainer = Navy.SurfaceContainer,
    surfaceContainerHigh = Navy.SurfaceVariant,
    surfaceVariant = Navy.SurfaceVariant,
    onSurfaceVariant = Navy.OnSurfaceVariant,
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF5A1D1D),
    onErrorContainer = Color(0xFFFBD5D5),
    outline = Color(0xFF4B6076),
)

/** Direct sun. Legibility beats elegance: near-white surfaces, dark text, maximum contrast. */
private val DayColors = lightColorScheme(
    primary = Color(0xFF0B4F86),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE3F5),
    onPrimaryContainer = Color(0xFF06263F),
    secondary = Color(0xFF1D6F66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E4F0),
    onSecondaryContainer = Color(0xFF0B3350),
    tertiary = Color(0xFF7A5A12),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E7BE),
    onTertiaryContainer = Color(0xFF3A2900),
    background = Color(0xFFF3F6F9),
    onBackground = Color(0xFF0B1620),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1620),
    surfaceContainer = Color(0xFFE7EDF3),
    surfaceContainerHigh = Color(0xFFDDE5EC),
    surfaceVariant = Color(0xFFDDE5EC),
    onSurfaceVariant = Color(0xFF33475A),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF6B7C8C),
)

/**
 * After dark, underway. Red-dominant and very low luminance: rod cells are least sensitive to long
 * wavelengths, so a red-forward display costs the least dark adaptation. Nothing here is white and
 * nothing approaches full brightness.
 */
private val NightColors = darkColorScheme(
    primary = Color(0xFFB2453A),
    onPrimary = Color(0xFF1A0603),
    primaryContainer = Color(0xFF3A1610),
    onPrimaryContainer = Color(0xFFC26550),
    secondary = Color(0xFF8C4A3A),
    onSecondary = Color(0xFF1A0603),
    // The tonal-button pair. Barely above the surface, and red like everything else — this is what
    // Done, Back, Video off and Exit simulation are drawn from.
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
    surfaceContainer = Color(0xFF170907),
    surfaceContainerHigh = Color(0xFF1F0C08),
    surfaceVariant = Color(0xFF1F0C08),
    onSurfaceVariant = Color(0xFF8E4B3C),
    error = Color(0xFFCF6151),
    onError = Color(0xFF1A0603),
    // Destructive actions stay distinguishable without going bright: warmer and slightly lighter
    // than the ordinary container, which is as far as night mode allows.
    errorContainer = Color(0xFF3A140F),
    onErrorContainer = Color(0xFFD97C68),
    outline = Color(0xFF5A2A20),
)

@Composable
fun OpenHelmTheme(
    palette: HelmPalette = HelmPalette.DUSK,
    content: @Composable () -> Unit,
) {
    val colors = when (palette) {
        HelmPalette.DAY -> DayColors
        HelmPalette.DUSK -> DuskColors
        HelmPalette.NIGHT -> NightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
