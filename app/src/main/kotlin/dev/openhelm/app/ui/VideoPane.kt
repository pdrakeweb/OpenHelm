package dev.openhelm.app.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhelm.app.BuildConfig
import dev.openhelm.app.video.RtpTransport
import dev.openhelm.app.video.VideoState

/**
 * The display mirror: a letterboxed 5:3 [TextureView] with a connect spinner until the first
 * frame is actually on the glass (a prepared pipeline and a visible picture differ by seconds,
 * and the spinner must not lie), and a stats overlay for keeping the latency budget honest.
 *
 * Two gesture paths, kept strictly apart:
 *
 * - **One finger** talks to the display: tap places the cursor, drag pans the chart — the touch
 *   opcode, with positions normalised across the video area.
 * - **Two fingers** zoom and pan the *local view only*, via [TextureView.setTransform]. A pinch
 *   must never leak to the display as touches. Holding to that costs a small deferral: the
 *   one-finger DOWN is withheld for [PINCH_GRACE_MS] so a gesture that turns out to be a pinch
 *   sends nothing at all, and a gesture that ends inside the window is emitted whole on lift.
 *
 * While zoomed, one-finger touches are mapped through the inverse transform, so the cursor lands
 * on the chart feature under the finger, not at the untransformed position.
 */
@Composable
fun VideoPane(viewModel: MainViewModel, palette: HelmPalette, modifier: Modifier = Modifier) {
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val stats by viewModel.videoStats.collectAsStateWithLifecycle()

    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Latches the first time real video reaches the glass, and never clears. After that moment a
    // decoded frame is stuck on the TextureView for as long as the pane lives, which is what makes
    // every later non-streaming state a safety case rather than a loading case.
    var everStreamed by remember { mutableStateOf(false) }
    LaunchedEffect(videoState) {
        if (videoState == VideoState.Streaming) everStreamed = true
    }

    // Push the local zoom/pan into the TextureView whenever it changes.
    LaunchedEffect(textureView, scale, pan) {
        val view = textureView ?: return@LaunchedEffect
        if (view.width == 0 || view.height == 0) return@LaunchedEffect
        val m = Matrix()
        m.postScale(scale, scale, view.width / 2f, view.height / 2f)
        m.postTranslate(pan.x, pan.y)
        view.setTransform(m)
        view.invalidate()
    }

    BoxWithConstraints(
        // The letterbox surround takes the palette's page colour rather than being black
        // everywhere. A black bar beside a white interface reads as a fault rather than as a
        // margin, and in bright sun a black bar is washed to grey anyway — the contrast it was
        // supposed to provide is the first thing to go.
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Box(letterboxModifier(maxWidth, maxHeight)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).apply {
                        // Keeping the screen awake is deliberately NOT done here — it is a
                        // property of being in a session, and the keypad-only mode has no
                        // TextureView. See KeepScreenOn(), held by the remote screen itself.
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                viewModel.onVideoSurfaceReady(Surface(st))
                            }

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                viewModel.onVideoSurfaceDestroyed()
                                return true
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                        textureView = this
                    }
                },
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        videoTouchGestures(
                            scaleOf = { scale },
                            panOf = { pan },
                            onTransform = { s, p -> scale = s; pan = p },
                            onDown = { x, y, size -> viewModel.videoTouchDown(x, y, size.width, size.height) },
                            onMove = { x, y, size -> viewModel.videoTouchMove(x, y, size.width, size.height) },
                            onUp = { x, y, size -> viewModel.videoTouchUp(x, y, size.width, size.height) },
                        )
                    },
            )
        }

        NightDim(palette)

        when (val s = videoState) {
            VideoState.Streaming -> {}

            is VideoState.Failed -> StaleVideoOverlay(reason = friendlyReason(s.reason))

            // Before the first frame there is nothing behind this but black, so a spinner is the
            // honest treatment. Afterwards there is a frozen chart, and every non-streaming state
            // has to be scrimmed — not just Failed. The reconnect loop cycles
            // Failed → Connecting → Failed on a roughly 18-second period, and scrimming only
            // Failed left that frozen chart at full brightness for most of each cycle.
            is VideoState.Connecting, VideoState.Idle ->
                if (everStreamed) StaleVideoOverlay(reason = null)
                else CircularProgressIndicator(Modifier.size(48.dp))
        }

        // The overlay that keeps us honest: if these numbers regress, the build is broken.
        // Debug-build only — it is diagnostic instrumentation, not something to show at a helm.
        if (BuildConfig.DEBUG) {
            Text(
                text = buildString {
                    append(stats.fps).append(" fps · q").append(stats.queueDepth)
                    append(" · dec ").append(stats.decodeMs).append(" ms")
                    append(" · drop ").append(stats.dropped)
                    append(" · gap ").append(stats.discontinuities)
                    if (scale > 1f) append(" · ×%.1f".format(scale))
                    if (stats.transport == RtpTransport.TCP_INTERLEAVED) append(" · TCP (sim)")
                },
                color = Color(0xCCE8EEF4),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0x66000000)),
            )
        }
    }
}

/**
 * What the user sees when the video link drops.
 *
 * **This is a safety treatment, not a status message.** When the stream fails the last decoded
 * frame stays on the `TextureView` — a chart, at full brightness, looking exactly like live video.
 * Someone glancing at a mounted phone mid-manoeuvre could act on a position that is now minutes
 * stale. So the frozen picture is deliberately buried: a heavy scrim knocks it back, and a
 * high-contrast banner states plainly that it is not live.
 *
 * It does not auto-dismiss, and it is deliberately **not** a Snackbar — a transient message that
 * clears itself is precisely the wrong pattern for a condition that is still true after it fades.
 */
@Composable
private fun StaleVideoOverlay(reason: String?) {
    Box(
        Modifier
            .fillMaxSize()
            // Heavy enough that the frozen chart underneath cannot be mistaken for live video.
            .background(Color(0xD9000000))
            // The scrim must stop touches as well as light. Drawing over the frozen chart does
            // not stop a tap reaching the forwarding layer beneath it, and that tap would be sent
            // to the display as a real touch at a chart position the user believes is current —
            // the exact mistake the scrim exists to prevent, committed through the scrim.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "VIDEO NOT LIVE",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (reason != null) {
                    "The picture behind this is frozen — $reason. Reconnecting…"
                } else {
                    "The picture behind this is frozen. Reconnecting…"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
