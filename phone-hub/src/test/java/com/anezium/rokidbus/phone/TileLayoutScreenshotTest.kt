package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.anezium.rokidbus.client.ui.GlyphDrawable
import android.view.View
import org.robolectric.RobolectricTestRunner
import com.anezium.rokidbus.shared.tile.GridRect
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class ScreenshotTileLayoutActivity : TileLayoutSettingsActivity() {
    override fun launchableEntries(): List<PluginCatalogEntry> =
        PLUGINS.map { (id, name, icon) ->
            PluginCatalogEntry(
                catalogKey = "test:$id",
                id = id,
                displayName = name,
                state = PluginCatalogState.BUILT_IN,
                launchable = true,
                iconKey = icon,
            )
        }

    override fun cameraTileName(): String = "Lens"

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
    }

    override fun glyphFor(tileId: String): Drawable = GlyphDrawable(GLYPHS[tileId] ?: GLYPHS.getValue("camera"))

    companion object {
        // The reference design's icon paths; the bundled vectors are not in this test's resources.
        private val GLYPHS = mapOf(
            "media" to "M2 12h3l2-7 3 14 3-11 2 7 3-4h4",
            "transit" to "M3 4h18v12H3z M3 10h18 M6 16v3 M18 16v3",
            "feeds" to "M22 2L11 13 M22 2l-7 20-4-9-9-4 20-7z",
            "relay" to "M6 10a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6 M10 20a2 2 0 0 0 4 0",
            "assistant" to "M12 2a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v4 M9 22h6",
            "tasker" to "M13 2 4 14h6l-1 8 9-12h-6l1-8z",
            "camera" to "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        )
        val PLUGINS = listOf(
            Triple("media", "Media Deck", "music"),
            Triple("transit", "Transit", "bus"),
            Triple("feeds", "Feeds", "send"),
            Triple("relay", "Relay", "bell"),
            Triple("assistant", "Assistant", "mic"),
            Triple("tasker", "Tasker", "bolt"),
        )
    }
}

/**
 * Real pixels of the editor at 390x844 dp (xhdpi, 780x1688 px) for visual review against
 * `docs/tile-layout/reference-Main.dc.html`. Written to `build/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [28], qualifiers = "w390dp-h844dp-xhdpi")
class TileLayoutScreenshotTest {
    @After
    fun clear() = TileSnapshotCache.clear()

    private fun snapshot(id: String, title: String, sub: String, rows: List<String>, progress: Float? = null) {
        TileSnapshotCache.record(
            id,
            WidgetTileContract.toPayload(TileSnapshot(id, "k", title, sub, rows = rows, progress = progress)),
        )
    }

    private fun capture(name: String, stored: List<TileLayoutEntry>, height: Int = 1688) {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        TileLayoutSettingsStore(app).setEntries(stored)
        snapshot("media", "Midnight Transit", "Analog Youth", listOf("1:24 / 3:40", "Up next  Night Drive", "Queue  12"), 0.38f)
        snapshot("transit", "Av. Central & 5th", "Next in 3 min", listOf("Line 4 to Riverside  3 min", "Line 9 to Old Town  11 min", "Line 2 to Central  18 min"))
        snapshot("feeds", "@kaelan.bsky", "rewired the desk lamp", listOf("@noa.codes  22m", "@ferra.dev  1h", "@lin.ink  2h", "@oskar.bsky  3h"))
        snapshot("relay", "3", "Maya Liu", emptyList())
        val activity = Robolectric.buildActivity(ScreenshotTileLayoutActivity::class.java).setup().get()
        val root = activity.window.decorView
        val width = 780
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(width, bitmap.width)
    }

    private val representative = listOf(
                TileLayoutEntry("camera", TileSize.SMALL, 0, 0),
                TileLayoutEntry("media", TileSize.WIDE, 1, 0),
                TileLayoutEntry("relay", TileSize.SMALL, 3, 0),
                TileLayoutEntry("transit", TileSize.PANEL, 0, 1),
                TileLayoutEntry("feeds", TileSize.TALL, 3, 1),
                TileLayoutEntry("assistant", TileSize.BANNER, 0, 4),
                TileLayoutEntry("tasker", TileSize.SMALL, 3, 4),
            )

    @Test
    fun `representative layout with a tile below the fold`() {
        capture("tile-layout-editor", representative)
        // The same screen tall enough to show the selected-tile card below the preview.
        capture("tile-layout-editor-full", representative, height = 2500)
    }

    private fun render(view: TileLayoutCanvasView, name: String, width: Int = 780): Bitmap {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, width, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots").apply { mkdirs() }
        File("build/screenshots/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test
    fun `a tile lifted mid-drag shows the ghost at its snap target`() {
        val tiles = listOf("a", "b", "c").map { EditorTile(it, "Tile $it", TileSize.entries.toSet(), live = false) }
        val layout = mapOf("a" to GridRect(0, 0, 2, 1), "b" to GridRect(2, 0, 1, 1), "c" to GridRect(0, 1, 2, 2))
        val state = TileLayoutEditorState(tiles, layout, layout)
        val view = TileLayoutCanvasView(org.robolectric.RuntimeEnvironment.getApplication())
        view.bind(state, emptyMap(), visibleRows = 3)
        state.dragStart("c", 100f, 140f)
        state.dragMove(330f, 40f, travelDp = 80f)
        view.stateChanged()
        render(view, "tile-layout-drag")
        assertEquals(GridRect(2, 0, 2, 2), state.layout["c"])
        // The displaced tile is drawn at its new cell, not where it started.
        val displaced = state.layout.getValue("b")
        assert(displaced != GridRect(2, 0, 1, 1))
        val drawn = view.drawnRect("b")!!
        assertEquals(displaced.col * 114f, drawn.left, 0.5f)
        assertEquals(displaced.row * 114f, drawn.top, 0.5f)
    }

    /** The fixture of the glasses capture `grid-17-wide-live-progress`, for a side-by-side check. */
    @Test
    fun `preview of the glasses grid-17 fixture`() {
        val names = listOf("Lyrics", "Now Playing", "Navigation", "Transit", "Relay")
        val glyphs = listOf(
            "M9 18V5l11-2v13 M3 18a3 3 0 1 0 6 0a3 3 0 1 0-6 0 M14 16a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M12 7v5l3.5 2",
            "M22 2L11 13 M22 2l-7 20-4-9-9-4 20-7z",
            "M3 4h18v12H3z M3 10h18 M6 16v3 M18 16v3",
            "M22 2L11 13 M22 2l-7 20-4-9-9-4 20-7z",
        )
        val tiles = names.mapIndexed { i, n -> EditorTile("plugin$i", n, TileSize.entries.toSet(), live = true) }
        val layout = TileLayoutEditorState.layoutOf(
            com.anezium.rokidbus.shared.tile.TileGridLayout.resolve(
                tiles.map { it.id to null },
                listOf(
                    TileLayoutEntry("plugin0", TileSize.BANNER, 0, 0),
                    TileLayoutEntry("plugin1", TileSize.SMALL, 3, 0),
                    TileLayoutEntry("plugin2", TileSize.PANEL, 1, 2),
                    TileLayoutEntry("plugin3", TileSize.SMALL, 0, 3),
                    TileLayoutEntry("plugin4", TileSize.JUMBO, 0, 5),
                ),
            ),
        )
        fun snap(
            id: String,
            title: String,
            unit: String,
            tone: TileTone,
            subtitle: String = "",
            rows: List<String> = emptyList(),
            progress: Float? = null,
            badge: String = "",
        ) = TileSnapshot(id, "k", title, subtitle, badge, progress, unit, tone, rows)
        val snapshots = mapOf(
            "plugin0" to snap("plugin0", "Next bus in 12 min", "", TileTone.OK, "Line 4 to Central"),
            "plugin1" to snap("plugin1", "7", "new", TileTone.INFO),
            "plugin2" to snap(
                "plugin2", "3", "tasks", TileTone.OK, "Today",
                rows = listOf("Call Ana", "Buy milk", "Send report"), progress = 0.66f,
            ),
            "plugin4" to snap(
                "plugin4", "Sync", "", TileTone.OFF, "Photos",
                rows = listOf("IMG_0412", "IMG_0413", "IMG_0414", "IMG_0415"), progress = 0.3f, badge = "42%",
            ),
        )
        val state = TileLayoutEditorState(tiles, layout, layout)
        state.select("plugin2")
        val view = TileLayoutCanvasView(org.robolectric.RuntimeEnvironment.getApplication())
        view.bind(
            state,
            tiles.mapIndexed { i, t -> t.id to TileVisual(GlyphDrawable(glyphs[i]), snapshots[t.id]) }.toMap(),
            visibleRows = 5,
        )
        render(view, "tile-layout-grid-17")
    }
}
