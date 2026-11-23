package cord.kasane.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cord.kasane.desktop.ui.ScaledDensity
import cord.kasane.desktop.ui.effectiveMultiplier
import cord.kasane.desktop.ui.stepScale
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaleTest {
    private val kscreen = "\u001B[0;32mOutput: \u001B[0m1 Virtual-0 8ca3db15\n\tenabled\n\tconnected\n\tGeometry: 0,0 1164x728\n\tScale: 1.65\n"

    @Test
    fun readsKdeOutputScaleAndXftDpi() {
        assertEquals(1.65, SystemScale.parseKScreen(kscreen))
        assertEquals(null, SystemScale.parseKScreen("Output: 1 DP-1\n\tdisabled\n\tScale: 2\n"))
        assertEquals(1.65625, SystemScale.parseXftDpi("Xft.antialias:\t1\nXft.dpi:\t159\n"))
        assertEquals(2.0, SystemScale.detect(mapOf("GDK_SCALE" to "2")) { null })
        assertEquals(1.0, SystemScale.detect(emptyMap()) { null })
        assertEquals(1.65, SystemScale.detect(emptyMap()) { if (it[0] == "kscreen-doctor") kscreen else null })
    }

    @Test
    fun scaleIsNeverAppliedTwice() {
        assertEquals(1.65f, effectiveMultiplier(1f, 1.65, 1f), 1e-6f)
        assertEquals(1f, effectiveMultiplier(2f, 1.65, 1f), 1e-6f)
        assertEquals(1.2f, effectiveMultiplier(2f, 1.65, 1.2f), 1e-6f)
    }

    @Test
    fun stepsStayInRange() {
        assertEquals(1.1f, stepScale(1f, +1))
        assertEquals(0.9f, stepScale(1f, -1))
        assertEquals(0.75f, stepScale(0.75f, -1))
        assertEquals(2.5f, stepScale(2.5f, +1))
    }

    /** What the user sees: layout boxes and text actually measure scale x dp in rendered pixels. */
    @Test
    fun renderedSizesFollowTheScale() {
        for ((detected, user) in listOf(1.0 to 1f, 1.65 to 1f, 1.65 to 1.2f)) {
            var box = 0; var textHeight = 0
            val scene = ImageComposeScene(800, 600, Density(1f)) {
                ScaledDensity(detected, user) {
                    Box(Modifier.size(100.dp).onGloballyPositioned { box = it.size.width })
                    Box { Text("Kasane", fontSize = 20.sp, modifier = Modifier.onGloballyPositioned { textHeight = it.size.height }) }
                }
            }
            scene.render(0); scene.render(16_000_000); scene.close()
            val factor = detected.toFloat() * user
            assertEquals((100 * factor).toInt(), box, "100 dp box at scale $factor")
            assert(textHeight >= (20 * factor).toInt()) { "20 sp text is $textHeight px tall at scale $factor" }
        }
    }
}
