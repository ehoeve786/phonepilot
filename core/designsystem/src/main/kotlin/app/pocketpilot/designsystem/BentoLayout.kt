package app.pocketpilot.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The gap between Bento tiles. */
val BentoGap: Dp = 12.dp

/**
 * Two tiles side by side with [startWeight] to 1 widths, so the pair is asymmetric, both as tall as the
 * taller one. Below [stackBelow] of width they stack instead, since a narrow tile cannot hold its text.
 */
@Composable
fun BentoPair(
    start: @Composable (Modifier) -> Unit,
    end: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    startWeight: Float = 1.3f,
    stackBelow: Dp = 340.dp,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < stackBelow) {
            Column(verticalArrangement = Arrangement.spacedBy(BentoGap)) {
                start(Modifier.fillMaxWidth())
                end(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(BentoGap)) {
                start(Modifier.weight(startWeight).fillMaxHeight())
                end(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
