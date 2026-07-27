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
 * What a fresh install shows until the user chooses otherwise.
 *
 * [DUSK] regardless of the phone's own light/dark setting. The system setting describes a living
 * room, not a cockpit, and it is wrong in both directions here: a phone in light mode dragged into
 * [DAY] is glaring below decks, and [DAY] is the palette most likely to be wrong at the moment the
 * app is first opened, which is rarely in direct sun. Dusk is legible in every condition the other
 * two are designed for, which makes it the right thing to be wrong with.
 */
val DefaultPalette: HelmPalette = HelmPalette.DUSK

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
 * Note how many roles are named. Material's tonal and segmented buttons — every *Done*, *Back*,
 * *Exit simulation* and the Mirror/Remote switch — draw from `secondaryContainer`, the destructive
 * variant from `errorContainer`, and switch tracks from `surfaceContainerHighest`. Leaving one
 * unset does not derive it from the palette: the scheme builder fills it from Material's baseline,
 * which is purple in the dark schemes and near-white in the light one. That produced a row of
 * lavender buttons on every screen, and in night mode a bright non-red control is exactly what the
 * palette exists to remove. If a role is used anywhere, it is named in all three schemes.
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
    outline = Color(0xFF4B6076),
)

/**
 * Direct sun. Legibility beats elegance.
 *
 * The containers are deliberately several shades darker than a conventional light theme's, and the
 * text and outlines on them darker again. Material's light palette is tuned for a screen indoors,
 * where a near-white button on a white page still reads. Outdoors it does not: sunlight washes the
 * top of the range flat, so the pale end of a light scheme collapses towards the page and the
 * buttons stop having edges. Pulling the containers down into the mid-tones keeps that separation
 * once the highlights are gone, and near-black content on them holds up when a polarised lens or a
 * low sun takes another slice of the contrast.
 */
private val DayColors = lightColorScheme(
    primary = Color(0xFF073A63),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9BBFE0),
    onPrimaryContainer = Color(0xFF04203A),
    secondary = Color(0xFF12564E),
    onSecondary = Color(0xFFFFFFFF),
    // The tonal-button container: Mirror/Remote, Done, Back, Exit simulation. Mid-tone, so the
    // button still has an edge when the page is blown out.
    secondaryContainer = Color(0xFFA5BCD1),
    onSecondaryContainer = Color(0xFF05223A),
    tertiary = Color(0xFF6A4C0C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDFC98C),
    onTertiaryContainer = Color(0xFF2B1E00),
    background = Color(0xFFEBEFF4),
    onBackground = Color(0xFF07121C),
    surface = Color(0xFFFAFCFE),
    onSurface = Color(0xFF07121C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFE2E9F0),
    surfaceContainer = Color(0xFFD2DCE6),
    surfaceContainerHigh = Color(0xFFC2D0DE),
    // Switch tracks and other "highest" surfaces. Left unset, Material fills this from its light
    // baseline, which is near-white — the simulation switch vanished into the card behind it.
    surfaceContainerHighest = Color(0xFFAEC0D1),
    // Keypad keys, the dial's hub and ring, and the settings cards all draw from this.
    surfaceVariant = Color(0xFFB6C7D7),
    onSurfaceVariant = Color(0xFF1E3145),
    error = Color(0xFF8F1710),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE3B3AD),
    onErrorContainer = Color(0xFF360705),
    outline = Color(0xFF3F5266),
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
    // Done, Back, Exit simulation and the mode switch are drawn from.
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
