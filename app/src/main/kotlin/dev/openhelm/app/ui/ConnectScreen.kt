package dev.openhelm.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhelm.app.config.RememberedDisplay
import dev.openhelm.app.ui.icons.MfdIcons

/**
 * The connect screen: the app's front door, and deliberately almost empty.
 *
 * Connecting is automatic — remembered displays are tried first, then the network is swept, and
 * whatever answers is connected to without the user tapping anything. These networks carry exactly
 * one display, so presenting a chooser would be ceremony. What the user sees is therefore a large,
 * unambiguous **Scanning** state; the recent displays are shortcuts, not a required step, and they
 * show only the display's name because an address is not something anyone reads at a helm.
 *
 * Everything fiddly — typing an address, the video transport, managing saved displays — lives
 * behind the overflow menu, off this screen.
 */
@Composable
fun ConnectScreen(viewModel: MainViewModel) {
    val recents by viewModel.recentShortlist.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val probing by viewModel.probingRecents.collectAsStateWithLifecycle()
    val timedOut by viewModel.discoveryTimedOut.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.startAutoConnect()
        onDispose { viewModel.stopDiscovery() }
    }

    Box(Modifier.fillMaxSize()) {
        OverflowMenu(
            onManual = viewModel::openManual,
            onSettings = viewModel::openSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The wheel appears on the launcher icon and the splash and then, previously, never
            // again. Pairing it with the wordmark makes the app's one piece of branding actually
            // part of the app.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MfdIcons.Wheel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "OpenHelm",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Light,
                )
            }

            Spacer(Modifier.height(48.dp))

            ScanningIndicator(
                active = searching || probing,
                timedOut = timedOut,
                idle = viewModel.autoConnectSuppressed,
                onScan = viewModel::resumeAutoConnect,
            )

            if (recents.isNotEmpty()) {
                Spacer(Modifier.height(40.dp))
                RecentButtons(recents, onConnect = { viewModel.connect(it.endpoint) })
            }
        }
    }
}

/**
 * The dominant element on the screen.
 *
 * While a scan is running it is a slowly sweeping ring with one word inside it. When the sweep has
 * run its window without finding anything, **the ring becomes the button**: it fills in, says
 * *Scan again*, and the outcome moves underneath it.
 *
 * That is the arrangement rather than a separate retry button below because the ring is already the
 * thing being looked at and is already 132dp across — far and away the easiest target on the
 * screen, which matters when the answer to "no display found" is almost always "try again". A
 * second, smaller button below it competed with the ring for attention while being harder to hit.
 *
 * The outcome sits under the ring rather than inside it: it is a sentence that changes, and the
 * middle of a circle is the one place on the screen where a longer line has nowhere to go.
 */
@Composable
private fun ScanningIndicator(
    active: Boolean,
    timedOut: Boolean,
    idle: Boolean,
    onScan: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    val transition = rememberInfiniteTransition(label = "scan")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "sweep",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ScanRingSize)
            .then(
                if (active) {
                    Modifier
                } else {
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onScan)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Scan again for displays"
                        }
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Idle, the whole ring takes the accent and thickens: it has to read as something to
            // press, not as the leftover track of an animation that stopped.
            drawCircle(
                color = if (active) track else accent,
                style = Stroke(width = (if (active) 3.dp else 4.dp).toPx()),
            )
            if (active) {
                rotate(angle) {
                    drawArc(
                        color = accent,
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx()),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                    )
                }
            }
        }

        Text(
            text = if (active) "Scanning" else "Scan again",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.onBackground else accent,
        )
    }

    val outcome = when {
        active -> null
        idle -> "Disconnected"
        timedOut -> "No MFD found"
        else -> null
    }

    if (outcome != null) {
        Spacer(Modifier.height(20.dp))
        Text(
            outcome,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        if (timedOut && !idle) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Some boat networks block automatic discovery.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Large enough to be the obvious target on the screen, and to hold "Scan again" on one line. */
private val ScanRingSize = 132.dp

/** Recent displays as plain name buttons — no address, no ports, nothing to decode at a glance. */
@Composable
private fun RecentButtons(
    recents: List<RememberedDisplay>,
    onConnect: (RememberedDisplay) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        recents.forEach { display ->
            Button(
                onClick = { onConnect(display) },
                modifier = Modifier.height(56.dp).widthIn(min = 120.dp, max = 200.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(
                    display.label,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The kebab. Drawn rather than imported, like every other glyph here. */
@Composable
private fun OverflowMenu(
    onManual: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.size(MinHelmTarget)) {
            Canvas(Modifier.size(20.dp)) {
                val r = 2.dp.toPx()
                val cx = size.width / 2f
                listOf(0.18f, 0.5f, 0.82f).forEach { fraction ->
                    drawCircle(color = tint, radius = r, center = Offset(cx, size.height * fraction))
                }
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // Menus otherwise render on Material's default surface, a flat grey that belongs to no
            // part of this app's palette.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = { Text("Manual connect") },
                leadingIcon = {
                    Icon(MfdIcons.ManualEntry, contentDescription = null)
                },
                onClick = {
                    open = false
                    onManual()
                },
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = {
                    Icon(MfdIcons.Settings, contentDescription = null)
                },
                onClick = {
                    open = false
                    onSettings()
                },
            )
        }
    }
}
