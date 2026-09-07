package dev.openhelm.app.ambient

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.openhelm.app.di.AppScope
import dev.openhelm.app.ui.HelmPalette
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Picks the display's colour mode from ambient light, so the phone does what a physical light
 * sensor on a chartplotter helm would: bright/day is [HelmPalette.HIGH_CONTRAST], dusk is
 * [HelmPalette.DARK], and full dark is [HelmPalette.NIGHT] — the red mode, kept last because rod
 * cells are least sensitive to long wavelengths and switching to it costs the least of a
 * navigator's night vision.
 *
 * **Primary input** is the phone's ambient light sensor, when the device has one. **Fallback** is
 * [Twilight] — the sun's geometric altitude for the date, assuming
 * [Twilight.DEFAULT_LATITUDE_DEGREES] — used both when there is no light sensor at all and
 * whenever the sensor is currently reporting [SensorManager.SENSOR_STATUS_UNRELIABLE].
 *
 * **Hysteresis**, so a reading sitting right at a boundary does not chatter between two modes:
 * entering and leaving each extreme band uses different lux thresholds ([ENTER_BRIGHT_LUX] vs.
 * [EXIT_BRIGHT_LUX], [ENTER_DARK_LUX] vs. [EXIT_DARK_LUX]).
 *
 * **Dwell**, so a hand or a dodger's shadow crossing the sensor for a moment does not flip the
 * whole interface: even a reading that has crossed a threshold must persist for [DWELL_MS] before
 * it is actually applied. The one exception is the very first classification after [start] —
 * applied immediately, with no dwell, so nobody stares at a white screen for five seconds after
 * opening the app (or unlocking the phone) at night while the debounce settles.
 */
@Singleton
class AmbientPaletteMonitor @Inject constructor(
    @ApplicationContext context: Context,
    @AppScope private val scope: CoroutineScope,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val _palette = MutableStateFlow(HelmPalette.DARK)

    /** The mode this monitor currently recommends. Only kept up to date while [start] is active. */
    val palette: StateFlow<HelmPalette> = _palette.asStateFlow()

    private var tickJob: Job? = null
    private var dwellJob: Job? = null

    /** False until the very first classification has been applied — see the class doc's rule. */
    private var appliedFirstReading = false

    @Volatile private var unreliable = false
    private var lastCommitted: HelmPalette? = null
    private var pendingCandidate: HelmPalette? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (unreliable) return
            event.values.firstOrNull()?.let { onClassified(classifyLux(it)) }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            unreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        }
    }

    /**
     * Begin recommending a mode. Registers the light sensor if the device has one; either way,
     * starts a once-a-minute [Twilight] tick that only acts when there is no sensor or it is
     * currently unreliable — cheap to run unconditionally, and it is what seeds the very first
     * classification on a device with no sensor at all.
     *
     * Safe to call repeatedly; a second call while already running does nothing.
     */
    fun start() {
        if (tickJob != null) return
        appliedFirstReading = false
        lastCommitted = null
        pendingCandidate = null
        unreliable = false
        lightSensor?.let { sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        tickJob = scope.launch {
            while (true) {
                if (lightSensor == null || unreliable) {
                    onClassified(Twilight.classify(ZonedDateTime.now()))
                }
                delay(TWILIGHT_POLL_MS)
            }
        }
    }

    /** Stop recommending a mode and release the sensor. [palette] keeps its last value. */
    fun stop() {
        sensorManager?.unregisterListener(listener)
        tickJob?.cancel()
        tickJob = null
        dwellJob?.cancel()
        dwellJob = null
    }

    /**
     * Which band [lux] falls in, given the currently committed mode — the hysteresis. A mode that
     * is already active only gives it up once the reading clears the *wider* of the two thresholds
     * at that edge; from the middle band either edge uses its normal (narrower) entry threshold.
     */
    private fun classifyLux(lux: Float): HelmPalette = when (lastCommitted) {
        HelmPalette.HIGH_CONTRAST -> if (lux >= EXIT_BRIGHT_LUX) HelmPalette.HIGH_CONTRAST else freshBand(lux)
        HelmPalette.NIGHT -> if (lux <= EXIT_DARK_LUX) HelmPalette.NIGHT else freshBand(lux)
        HelmPalette.DARK, null -> freshBand(lux)
    }

    private fun freshBand(lux: Float): HelmPalette = when {
        lux >= ENTER_BRIGHT_LUX -> HelmPalette.HIGH_CONTRAST
        lux <= ENTER_DARK_LUX -> HelmPalette.NIGHT
        else -> HelmPalette.DARK
    }

    private fun onClassified(candidate: HelmPalette) {
        if (!appliedFirstReading) {
            appliedFirstReading = true
            commit(candidate)
            return
        }
        if (candidate == lastCommitted) {
            dwellJob?.cancel()
            dwellJob = null
            pendingCandidate = null
            return
        }
        if (candidate == pendingCandidate) return // already waiting this one out
        dwellJob?.cancel()
        pendingCandidate = candidate
        dwellJob = scope.launch {
            delay(DWELL_MS)
            commit(candidate)
        }
    }

    private fun commit(mode: HelmPalette) {
        lastCommitted = mode
        pendingCandidate = null
        _palette.value = mode
    }

    private companion object {
        /** How long a candidate mode must persist before it is actually applied. */
        const val DWELL_MS = 5_000L
        const val TWILIGHT_POLL_MS = 60_000L

        // Round numbers, not a calibrated light meter: picked to land day/dusk/night the way a
        // person would call them from a phone's own ambient sensor, which is coarse and reads low
        // behind glass. Each extreme has an entry threshold and a looser exit threshold, so a
        // reading has to travel further to leave a band than it did to enter it.
        const val ENTER_BRIGHT_LUX = 80.0
        const val EXIT_BRIGHT_LUX = 40.0
        const val ENTER_DARK_LUX = 1.0
        const val EXIT_DARK_LUX = 3.0
    }
}
