package app.pocketpilot.designsystem.agent

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.designsystem.R
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.designsystem.PocketPilotTheme

/**
 * Home's quick prompt: one line to the agent. The bar lifts a little when it takes focus and the send
 * button presses in and springs back, each with a short haptic tick; sending gives a firmer one and clears
 * the field. [onSubmit] gets the trimmed text.
 */
@Composable
fun AgentCommandBar(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val canSend = enabled && text.isNotBlank()
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "send-press",
    )
    val barScale by animateFloatAsState(
        targetValue = if (focused) FOCUSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "bar-focus",
    )
    LaunchedEffect(pressed) { if (pressed && canSend) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    fun submit() {
        val prompt = text.trim()
        if (!enabled || prompt.isEmpty()) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onSubmit(prompt)
        text = ""
    }

    LiquidGlassBentoCard(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = barScale
                    scaleY = barScale
                },
        contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = MAX_LINES,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .onFocusChanged { focused = it.isFocused },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                stringResource(R.string.core_designsystem_command_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                },
            )
            val sendLabel = stringResource(R.string.core_designsystem_command_send)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(SEND_SIZE)
                        .graphicsLayer {
                            scaleX = buttonScale
                            scaleY = buttonScale
                        }.background(
                            if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            CircleShape,
                        ).clickable(
                            interactionSource = pressSource,
                            indication = null,
                            enabled = canSend,
                            onClickLabel = sendLabel,
                            role = Role.Button,
                            onClick = ::submit,
                        ),
            ) {
                Icon(
                    painterResource(R.drawable.core_designsystem_ic_send),
                    contentDescription = sendLabel,
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ICON_SIZE),
                )
            }
        }
    }
}

private const val PRESSED_SCALE = 0.84f
private const val FOCUSED_SCALE = 1.015f
private const val MAX_LINES = 4
private val SEND_SIZE = 44.dp
private val ICON_SIZE = 20.dp

@Preview
@Composable
private fun AgentCommandBarPreview() {
    PocketPilotTheme {
        Box(Modifier.padding(16.dp)) { AgentCommandBar(onSubmit = {}) }
    }
}
