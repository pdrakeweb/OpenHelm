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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 *   must never leak to the display as touches; the moment a second finger lands, any in-flight
 *   touch gesture is released.
 *
 * While zoomed, one-finger touches are mapped through the inverse transform, so the cursor lands
 * on the chart feature under the finger, not at the untransformed position.
 */
@Composable
fun VideoPane(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val stats by viewModel.videoStats.collectAsStateWithLifecycle()

    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

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

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // The stream is 800×480 — 5:3. Letterbox: a stretched chart is a wrong chart.
                .aspectRatio(5f / 3f),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).apply {
                        // A helm-mounted phone must not sleep mid-passage while showing the chart.
                        keepScreenOn = true
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
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            val viewSize = this.size
                            var touching = true
                            var pinching = false
                            var lastMs = 0L

                            fun content(p: Offset): Offset {
                                // Invert the local view transform: normalised coordinates must
                                // describe the *chart* under the finger.
                                val cx = viewSize.width / 2f
                                val cy = viewSize.height / 2f
                                return Offset(
                                    ((p.x - pan.x - cx) / scale + cx).coerceIn(0f, viewSize.width.toFloat()),
                                    ((p.y - pan.y - cy) / scale + cy).coerceIn(0f, viewSize.height.toFloat()),
                                )
                            }

                            var last = content(down.position)
                            viewModel.videoTouchDown(last.x, last.y, viewSize.width, viewSize.height)

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedChanges = event.changes.filter { it.pressed }

                                if (pressedChanges.size >= 2) {
                                    if (touching) {
                                        // Second finger down: this is now a local zoom, not a
                                        // conversation with the display.
                                        viewModel.videoTouchUp(last.x, last.y, viewSize.width, viewSize.height)
                                        touching = false
                                        pinching = true
                                    }
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
                                    val maxX = (scale - 1f) * viewSize.width / 2f
                                    val maxY = (scale - 1f) * viewSize.height / 2f
                                    pan = Offset(
                                        (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                        (pan.y + panChange.y).coerceIn(-maxY, maxY),
                                    )
                                    event.changes.forEach { it.consume() }
                                } else if (pressedChanges.size == 1 && touching) {
                                    val change = pressedChanges.first()
                                    val now = System.currentTimeMillis()
                                    if (now - lastMs >= MOVE_INTERVAL_MS) {
                                        lastMs = now
                                        last = content(change.position)
                                        viewModel.videoTouchMove(last.x, last.y, viewSize.width, viewSize.height)
                                    }
                                    change.consume()
                                } else if (pressedChanges.isEmpty()) {
                                    if (touching) {
                                        viewModel.videoTouchUp(last.x, last.y, viewSize.width, viewSize.height)
                                    }
                                    if (pinching && scale <= SNAP_BACK_BELOW) {
                                        // Near-1× is an accident of finger lift; snap clean.
                                        scale = 1f
                                        pan = Offset.Zero
                                    }
                                    break
                                }
                                // One finger remaining after a pinch: ignored until lift — a
                                // half-ended pinch must not start sending chart touches.
                            }
                        }
                    },
            )
        }

        when (val s = videoState) {
            is VideoState.Connecting, VideoState.Idle -> {
                CircularProgressIndicator(Modifier.size(48.dp))
            }

            is VideoState.Failed -> {
                Text(
                    "Video: ${s.reason} — retrying",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            VideoState.Streaming -> {}
        }

        // The overlay that keeps us honest: if these numbers regress, the build is broken.
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

private const val MAX_ZOOM = 4f
private const val SNAP_BACK_BELOW = 1.1f
private const val MOVE_INTERVAL_MS = 33L
