package cord.kasane.music.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/** A subtle spring squeeze while the finger is down. Pure feedback: it never changes layout. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.97f): Modifier {
    val isPressed = source.collectIsPressedAsState().value
    val scale = animateFloatAsState(if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "pressScale").value
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Tap and long-press for any track surface, with haptics and TalkBack support.
 *
 * Long-press opens the menu, announced as "Open menu" so it is not an unlabeled gesture. Every menu entry is also
 * exposed as a TalkBack custom action (the actions menu), so people who cannot long-press or double-tap-and-hold
 * can reach every command without hunting for a button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.trackGestures(
    entries: List<MenuEntry>,
    onClickLabel: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return this
        .combinedClickable(
            interactionSource = source, indication = androidx.compose.material3.ripple(),
            onClickLabel = onClickLabel, onClick = onClick,
            onLongClickLabel = "Open menu",
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() })
        .semantics {
            customActions = entries.filter { it.enabled }.map { entry -> CustomAccessibilityAction(entry.label) { entry.action(); true } }
        }
}
