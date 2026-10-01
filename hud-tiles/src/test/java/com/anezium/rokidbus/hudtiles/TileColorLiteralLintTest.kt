package com.anezium.rokidbus.hudtiles

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer draws inside the glasses' home layer, so it follows the same rule the home files do
 * (glasses-hub `GridColorLiteralLintTest`): every color comes from `RokidHudTokens`.
 */
class TileColorLiteralLintTest {
    private val forbidden = listOf(
        Regex("""0x[0-9A-Fa-f]{6,8}"""),
        Regex("""Color\.rgb\("""),
        Regex("""Color\.argb\("""),
        Regex("""BusTheme\."""),
    )

    @Test
    fun `no raw color literal in the renderer`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("no renderer sources found", sources.isNotEmpty())
        sources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { pattern ->
                assertTrue("${pattern.pattern} in ${file.name}: ${pattern.find(text)?.value}", pattern.find(text) == null)
            }
        }
    }
}
