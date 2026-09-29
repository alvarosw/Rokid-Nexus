package com.anezium.rokidbus.glasses

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The concrete, automatable enforcement of "every color in the new grid code path comes from the
 * design system, not from `BusTheme` or an invented value" (roadmap §0.5, acceptance criterion 8).
 * A static scan rather than a real lint pass, but it covers exactly what that criterion asks for:
 * no raw color literal and no `BusTheme` color reference anywhere in the new grid package.
 */
class GridColorLiteralLintTest {
    private val forbidden = listOf(
        Regex("""0x[0-9A-Fa-f]{6,8}"""),
        Regex("""Color\.rgb\("""),
        Regex("""Color\.argb\("""),
        Regex("""BusTheme\.(phosphor|dim|text|hairline|muted|bg|card|cardPressed|well|danger)\b"""),
    )

    // Every file that renders the home layer. RokidHudTokens/HudFrameLayout (bus-client) are the
    // token object itself and are exempt by design.
    private val homeFiles = listOf(
        "FallbackTileView.kt",
        "LiveTileView.kt",
        "hud/HomeComponents.kt",
        "hud/HomeLayer.kt",
        "hud/HomeModel.kt",
        "hud/HomeScreenView.kt",
        "hud/ListHome.kt",
        "hud/GridHome.kt",
        "hud/HudMorph.kt",
        "hud/HudMotionDriver.kt",
    )

    private val root = File("src/main/java/com/anezium/rokidbus/glasses")

    private fun text(name: String): String {
        val file = File(root, name)
        assertTrue("missing expected home file $name (looked in ${root.absolutePath})", file.isFile)
        return file.readText()
    }

    @Test
    fun `no raw color literal or BusTheme reference in the home layer`() {
        homeFiles.forEach { name ->
            val text = text(name)
            forbidden.forEach { pattern ->
                val match = pattern.find(text)
                assertTrue(
                    "forbidden color pattern ${pattern.pattern} found in $name: ${match?.value}",
                    match == null,
                )
            }
        }
    }

    @Test
    fun `no ScrollView and no density-scaled units in the home layer`() {
        // HARDWARE S5: layers dither grey grain, so scrolling is our own offset. Tokens are pixels.
        val forbiddenUnits = listOf(
            Regex("""\bScrollView\b"""),
            Regex("""RokidHudTokens\.dp\("""),
            Regex("""_TEXT_SIZE_SP\b"""),
            Regex("""COMPLEX_UNIT_(SP|DIP)"""),
            Regex("""displayMetrics\.(density|scaledDensity)"""),
        )
        homeFiles.forEach { name ->
            val text = text(name)
            forbiddenUnits.forEach { pattern ->
                val match = pattern.find(text.lineSequence().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n"))
                assertTrue("forbidden ${pattern.pattern} found in $name: ${match?.value}", match == null)
            }
        }
    }

    @Test
    fun `the renderings never rebuild the whole tree`() {
        listOf("hud/ListHome.kt", "hud/GridHome.kt").forEach { name ->
            assertTrue("removeAllViews in $name", !text(name).contains("removeAllViews"))
        }
    }
}
