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

    // The new grid package's own files. RokidHudTokens/HudFrameLayout (bus-client) are the token
    // object itself and are exempt by design; everything that renders a grid tile lives here.
    private val gridFiles = listOf(
        "FallbackTileView.kt",
        "GridLauncherView.kt",
    )

    @Test
    fun `no raw color literal or BusTheme reference in the new grid package`() {
        val root = File("src/main/java/com/anezium/rokidbus/glasses")
        assertTrue("expected grid package at ${root.absolutePath}", root.isDirectory)

        gridFiles.forEach { name ->
            val file = File(root, name)
            assertTrue("missing expected grid file $name", file.isFile)
            val text = file.readText()
            forbidden.forEach { pattern ->
                val match = pattern.find(text)
                assertTrue(
                    "forbidden color pattern ${pattern.pattern} found in $name: ${match?.value}",
                    match == null,
                )
            }
        }
    }
}
