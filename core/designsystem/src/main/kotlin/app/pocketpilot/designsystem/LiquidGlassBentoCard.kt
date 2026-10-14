package app.pocketpilot.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The colours of one glass tile; [LiquidGlassDefaults.colors] picks them for the current theme. */
@Immutable
class LiquidGlassColors(
    /** Under the glass, so text keeps its contrast on any background. */
    val base: Color,
    val fillTop: Color,
    val fillBottom: Color,
    val borderStart: Color,
    val borderEnd: Color,
    val ambientFar: Color,
    val spotFar: Color,
    val ambientNear: Color,
    val spotNear: Color,
)

object LiquidGlassDefaults {
    val CornerRadius: Dp = 24.dp
    val Shape: Shape = RoundedCornerShape(CornerRadius)
    val ContentPadding = PaddingValues(16.dp)

    /** Light from the top left: a bright edge fading to a nearly clear fill, with a soft shadow below. */
    @Composable
    fun colors(dark: Boolean = MaterialTheme.colorScheme.background.luminance() < DARK_BELOW): LiquidGlassColors =
        if (dark) {
            LiquidGlassColors(
                base = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                fillTop = Color.White.copy(alpha = 0.12f),
                fillBottom = Color.White.copy(alpha = 0.03f),
                borderStart = Color.White.copy(alpha = 0.30f),
                borderEnd = Color.White.copy(alpha = 0.04f),
                ambientFar = Color.Black.copy(alpha = 0.30f),
                spotFar = Color.Black.copy(alpha = 0.45f),
                ambientNear = Color.Black.copy(alpha = 0.20f),
                spotNear = Color.Black.copy(alpha = 0.30f),
            )
        } else {
            // White glass would vanish on a light background, so the fill leans on a white base and a faint tint.
            LiquidGlassColors(
                base = Color.White.copy(alpha = 0.72f),
                fillTop = Color.White.copy(alpha = 0.60f),
                fillBottom = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                borderStart = Color.White.copy(alpha = 0.95f),
                borderEnd = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                ambientFar = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                spotFar = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ambientNear = Color.Black.copy(alpha = 0.05f),
                spotNear = Color.Black.copy(alpha = 0.10f),
            )
        }

    private const val DARK_BELOW = 0.5f
}

/**
 * A translucent "liquid glass" tile for the Bento grid: a clear gradient fill over a soft base, a gradient
 * edge, rounded corners and two stacked shadows, one wide and one tight. Content uses the theme's
 * on-surface colour and Material 3 typography. Pass [onClick] to make the whole tile a button, and [title]
 * for the small label most tiles carry.
 */
@Composable
fun LiquidGlassBentoCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    shape: Shape = LiquidGlassDefaults.Shape,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = LiquidGlassDefaults.ContentPadding,
    colors: LiquidGlassColors = LiquidGlassDefaults.colors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .shadow(FAR_ELEVATION, shape, clip = false, ambientColor = colors.ambientFar, spotColor = colors.spotFar)
            .shadow(NEAR_ELEVATION, shape, clip = false, ambientColor = colors.ambientNear, spotColor = colors.spotNear)
            .clip(shape)
            .background(colors.base)
            .background(Brush.linearGradient(listOf(colors.fillTop, colors.fillBottom)))
            .border(BORDER_WIDTH, Brush.linearGradient(listOf(colors.borderStart, colors.borderEnd)), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(contentPadding)) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(TITLE_GAP))
                }
                content()
            }
        }
    }
}

private val FAR_ELEVATION = 20.dp
private val NEAR_ELEVATION = 4.dp
private val BORDER_WIDTH = 1.dp
private val TITLE_GAP = 8.dp

@Preview
@Composable
private fun LiquidGlassBentoCardPreview() {
    PocketPilotTheme {
        Box(Modifier.padding(24.dp)) {
            LiquidGlassBentoCard(title = "Pinned") {
                Text("Quiet at work", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
