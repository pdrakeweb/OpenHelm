package dev.openhelm.app.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * A TextureView rather than a SurfaceView so that phase 3 can pinch-zoom the *local* view with
 * `setTransform` — that transform must never turn into touch messages to the display.
 */
@Composable
fun VideoPane(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val stats by viewModel.videoStats.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                // The stream is 800×480 — 5:3. Letterbox: a stretched chart is a wrong chart.
                .aspectRatio(5f / 3f),
            factory = { context ->
                TextureView(context).apply {
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
                }
            },
        )

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
