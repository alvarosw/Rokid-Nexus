package com.anezium.rokidbus.glasses.hud

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.HomeScreenshotHostActivity
import com.anezium.rokidbus.glasses.LiveTileView
import com.anezium.rokidbus.hudtiles.TileRenderer
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileContent.ListContent
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three templates at all seven sizes, one sheet per artboard of `docs/live-tiles/design/`
 * with the artboard's own sample data and placement, for review against it. Glasses pixels: the
 * sheet is laid out at mdpi so one px is one px.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1210dp-h460dp-mdpi")
class TileTemplateScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    init {
        System.setProperty("roborazzi.test.record", "true")
    }

    private val now = 1_000_000L

    /** Where the artboards put each size: two rows on the left, the 3x3 on the right. */
    private val placement = listOf(
        TileSize.SMALL to (0 to 0), TileSize.WIDE to (146 to 0), TileSize.BANNER to (406 to 0),
        TileSize.TALL to (0 to 146), TileSize.LARGE to (146 to 146), TileSize.PANEL to (406 to 146),
        TileSize.JUMBO to (780 to 0),
    )

    private fun sheet(name: String, entry: GlassesHub.LauncherEntry, content: TileContent, sinceReceiptMs: Long = 0, artwork: Bitmap? = null) {
        ActivityScenario.launch<HomeScreenshotHostActivity>(Intent(context, HomeScreenshotHostActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val root = FrameLayout(activity).apply { setBackgroundColor(RokidHudTokens.GROUND) }
                placement.forEach { (size, at) ->
                    val tile = LiveTileView(activity, size, motion = HudMotionDriver.instant(), clock = { now })
                    tile.bindEntry(entry)
                    tile.bind(TileSnapshot(pluginId = entry.id, contentKey = "k", content = content), stale = false, receivedAtElapsed = now - sinceReceiptMs, artwork = artwork)
                    root.addView(
                        tile,
                        FrameLayout.LayoutParams(TileRenderer.widthOf(size), TileRenderer.heightOf(size), Gravity.TOP or Gravity.START)
                            .apply { leftMargin = MARGIN + at.first; topMargin = MARGIN + at.second },
                    )
                }
                activity.setContentView(root, FrameLayout.LayoutParams(1162, 414))
            }
            onView(isRoot()).captureRoboImage("build/outputs/roborazzi/tile-template-$name.png")
        }
    }

    @Test
    fun media_deck() = sheet("music-media-deck", GlassesHub.LauncherEntry("media", "Media Deck", "music"), music, artwork = art())

    @Test
    fun media_deck_without_artwork() = sheet("music-text-only", GlassesHub.LauncherEntry("media", "Media Deck", "music"), music)

    @Test
    fun lyrics() = sheet("lines-lyrics", GlassesHub.LauncherEntry("lyrics", "Lyrics", "disc"), lyrics)

    @Test
    fun relay() = sheet("list-relay", GlassesHub.LauncherEntry("relay", "Relay", "send"), relay)

    @Test
    fun news() = sheet("list-news", GlassesHub.LauncherEntry("news", "News", "lens"), news)

    @Test
    fun feeds() = sheet("list-feeds", GlassesHub.LauncherEntry("feeds", "Feeds", "bolt"), feeds, sinceReceiptMs = 12_000)

    /** A stand-in album cover: a disc on a dark ground, in colors, as decoded artwork is drawn. */
    private fun art(): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(48f, 54f, 90f, RokidHudTokens.GREEN_100, RokidHudTokens.GROUND, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 128f, 128f, paint)
        paint.shader = null
        paint.color = RokidHudTokens.GROUND
        canvas.drawCircle(48f, 54f, 10f, paint)
        return bitmap
    }

    private companion object {
        const val MARGIN = 24

        val music = TileContent.Music(
            title = "Low Tide Static", artist = "Marta Velez", album = "Salt Rooms", source = "Spotify",
            playing = true, positionMs = 102_000, durationMs = 236_000, artworkKey = "low-tide",
        )

        val lyrics = TileContent.Lines(
            lines = listOf(
                "the kettle sang the only song we knew",
                "we left the porch light on for no one",
                "salt on the window, salt on the sleeve",
                "and the radio kept the weather to itself",
                "I counted cars until the counting stopped",
                "you said the river doesn’t owe us anything",
                "so we waited, and the waiting was enough",
            ).mapIndexed { i, text -> TileContent.Lines.Line(text, startMs = 80_000L + i * 6_000L) },
            current = 0,
            title = "Low Tide Static",
            subtitle = "Marta Velez",
            source = "LrcLib",
            positionMs = 102_000,
            durationMs = 236_000,
            playing = true,
        )

        private const val MIN = 60_000L

        val relay = ListContent(
            sections = listOf(
                ListContent.Section(
                    items = listOf(
                        ListContent.Item("Ana Ribeiro", "WhatsApp", "Leaving now, ten minutes away. Want me to grab bread on the way?", 1 * MIN, ListContent.Leading.Initials("AR")),
                        ListContent.Item("Family", "WhatsApp", "Dad: dinner moved to 8, bring the speaker", 6 * MIN, ListContent.Leading.Initials("FA")),
                        ListContent.Item("Tomás Prado", "Telegram", "Did the files come through?", 18 * MIN, ListContent.Leading.Initials("TP")),
                        ListContent.Item("Júlia Mendes", "Signal", "ok, see you there", 42 * MIN, ListContent.Leading.Initials("JM")),
                    ),
                ),
            ),
            summary = "3 new",
            summaryShort = "3",
        )

        val news = ListContent(
            sections = listOf(
                ListContent.Section("Civic Wire", "5", listOf(ListContent.Item("Regional rail strike called off after late-night deal", ageMs = 4 * MIN))),
                ListContent.Section("Harbor Ledger", "6", listOf(ListContent.Item("Night bus routes extended through the winter", ageMs = 12 * MIN))),
                ListContent.Section("Field Notes", "3", listOf(ListContent.Item("Library adds a Saturday repair café", ageMs = 31 * MIN))),
            ),
            summary = "14 unread",
            summaryShort = "14",
            overflow = 11,
        )

        val feeds = ListContent(
            sections = listOf(
                ListContent.Section(
                    items = listOf(
                        ListContent.Item("Inês Castro", "@inescastro", "Fixed the fog light on the old Vespa. Coast ride this weekend if the rain holds. [photo]", 2 * MIN),
                        ListContent.Item("Port Weather", "@portweather", "Gusts up to 45 km/h after 18:00. Ferries on a reduced schedule.", 6 * MIN),
                        ListContent.Item("Davi Lopes", "@davilopes", "Sketchbook pages from the market this morning. [3 photos]", 11 * MIN),
                    ),
                ),
            ),
            summary = "Bluesky · 8 new",
            summaryShort = "+8",
            paragraphLines = 6,
            overflow = 5,
        )
    }
}
