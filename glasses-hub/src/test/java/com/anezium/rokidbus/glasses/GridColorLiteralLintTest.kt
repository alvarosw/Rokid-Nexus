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

    // Plugin-surface rendering (U7a): every color is a RokidHudTokens intensity.
    private val surfaceFiles = listOf(
        "SurfaceHudView.kt",
        "SurfaceStyle.kt",
        "MediaHudView.kt",
        "MediaProgressView.kt",
        "ImageHudView.kt",
        "ReaderSurfaceView.kt",
        "SurfaceActivity.kt",
        "InkHudView.kt",
        "InkCardPresentation.kt",
    )

    // The Ink card keeps its dp geometry (the rpx layout depends on it), so only its colors are
    // policed; the other surface files are pixels and tokens through and through.
    private val inkFiles = setOf("InkHudView.kt", "InkCardPresentation.kt")

    @Test
    fun `no raw color literal or BusTheme color in the surface views`() {
        val glassesBg = Regex("""BusTheme\.glassesBg\b""")
        surfaceFiles.forEach { name ->
            val text = text(name)
            (forbidden + glassesBg).forEach { pattern ->
                val match = pattern.find(text)
                assertTrue(
                    "forbidden color pattern ${pattern.pattern} found in $name: ${match?.value}",
                    match == null,
                )
            }
        }
    }

    @Test
    fun `the surface views use no ScrollView and no density-scaled units`() {
        val forbiddenUnits = listOf(
            Regex("""\bScrollView\b"""),
            Regex("""COMPLEX_UNIT_(SP|DIP)"""),
            Regex("""displayMetrics\.(density|scaledDensity)"""),
            Regex("""BusTheme\.dp\("""),
        )
        surfaceFiles.filterNot { it in inkFiles }.forEach { name ->
            val code = text(name).lineSequence()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            forbiddenUnits.forEach { pattern ->
                val match = pattern.find(code)
                assertTrue("forbidden ${pattern.pattern} found in $name: ${match?.value}", match == null)
            }
        }
    }

    // Every main source of the glasses hub (U7b). Files that legitimately hold something the
    // patterns would flag are listed with the reason; everything else is UI and must use tokens.
    private val exempt = mapOf(
        "CameraOverlayView.kt" to "the camera HUD keeps its own multi-hue status colors; the camera pipeline is out of scope",
        "InkStyleMapping.kt" to "InkTierReference: the historical anchor colors that literal Ink colors are matched against",
    )

    private val retired = Regex("""\b(BusTheme|NexusUi)\b""")
    private val namedColors = Regex(
        """Color\.(rgb|argb|parseColor|WHITE|RED|GREEN|BLUE|YELLOW|CYAN|MAGENTA|GRAY|GREY|DKGRAY|LTGRAY)\b""",
    )
    private val hexStrings = Regex("""#[0-9A-Fa-f]{6,8}\b""")

    // A color is a 6-digit hex or an 8-digit one converted with `.toInt()`; other 8-digit constants
    // (protocol versions, input source flags) are not colors.
    private val hexColors = listOf(Regex("""0x[0-9A-Fa-f]{6}\b"""), Regex("""0x[0-9A-Fa-f]{8}\.toInt\(\)"""))

    private fun allMainSources(): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no color literal and no retired theme in any glasses hub main source`() {
        val sources = allMainSources()
        assertTrue("found only ${sources.size} sources under ${root.absolutePath}", sources.size > 100)
        sources.filterNot { it.name in exempt }.forEach { file ->
            val relative = file.relativeTo(root).path
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                // The retired themes are named nowhere, comments included.
                assertTrue("$relative:${index + 1} names ${retired.find(line)?.value}", retired.find(line) == null)
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return@forEachIndexed
                (hexColors + forbidden.drop(1).take(2) + namedColors + hexStrings).forEach { pattern ->
                    val match = pattern.find(line)
                    assertTrue("$relative:${index + 1} has a color literal ${match?.value}", match == null)
                }
            }
        }
    }

    @Test
    fun `every exemption still exists and still needs it`() {
        exempt.forEach { (name, reason) ->
            val file = allMainSources().firstOrNull { it.name == name }
            assertTrue("$name is exempt ($reason) but no longer exists; drop the exemption", file != null)
            val needed = file!!.readText().let { text ->
                (hexColors + namedColors + hexStrings).any { it.containsMatchIn(text) }
            }
            assertTrue("$name is exempt ($reason) but holds no color literal; drop the exemption", needed)
        }
    }

    // The ambient layers (U7b) are pixels and tokens like the home layer: no density-scaled units
    // except where a hardware constraint in docs/HARDWARE.md needs the ROM's own row size.
    private val ambientFiles = listOf(
        "AmbientStyle.kt",
        "NoticeOverlayRenderer.kt",
        "NoticeComposeMirror.kt",
        "PinOverlayRenderer.kt",
        "ActivityOverlayRenderer.kt",
        "ActivityExtrasViews.kt",
        "HudIsland.kt",
        "HudActionRowView.kt",
        "RemotePointerOverlayRenderer.kt",
    )

    @Test
    fun `the ambient layers use pixels and tokens, not density-scaled units or the old motion`() {
        val forbiddenUnits = listOf(
            Regex("""COMPLEX_UNIT_(SP|DIP)"""),
            Regex("""displayMetrics\.(density|scaledDensity|widthPixels|heightPixels)"""),
            Regex("""\bdp\("""),
            Regex("""HudMotionValue"""),
            // The activity flare keeps `STANDARD_MS + HOLD_MS` as the dwell of its spring island: not a tween.
            Regex("""HudMotion\.(MICRO_MS|EXIT_MS|enter|exit)\b"""),
        )
        ambientFiles.forEach { name ->
            val code = text(name).lineSequence()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            forbiddenUnits.forEach { pattern ->
                val match = pattern.find(code)
                assertTrue("forbidden ${pattern.pattern} found in $name: ${match?.value}", match == null)
            }
        }
    }
}
