package dev.openhelm.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A screen-level navigation action — Back, Done, Exit — as an actual button.
 *
 * These were bare text links. On a phone they were small, easy to miss, and easy to mis-hit; the
 * design review called them out specifically. A tonal button gives them a hit area, a visible
 * edge, and a consistent shape across every screen that has one, and the icon makes the action
 * recognisable before the word is read.
 */
@Composable
fun NavActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .height(MinHelmTarget)
            .widthIn(min = 112.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // the adjacent label already names the action
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
