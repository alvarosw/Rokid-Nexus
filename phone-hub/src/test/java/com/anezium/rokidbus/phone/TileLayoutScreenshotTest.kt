package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.anezium.rokidbus.client.ui.GlyphDrawable
import android.view.View
import org.robolectric.RobolectricTestRunner
import com.anezium.rokidbus.hudtiles.SystemWidgetContent
import com.anezium.rokidbus.shared.tile.GridRect
import com.anezium.rokidbus.shared.tile.SystemWidget
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import android.widget.Button
import android.widget.TextView

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

    override fun tilePreviewSample(tileId: String): TileSnapshot? = SAMPLES[tileId]

    /** A fixed 14:32 so the captures do not change with the clock. */
    override fun widgetSample(widget: SystemWidget): SystemWidgetContent? =
        super.widgetSample(widget).let { sample ->
            if (sample is SystemWidgetContent.Clock) {
                sample.copy(epochMs = FIXED_TIME_MS, timeZone = TimeZone.getTimeZone("UTC"), locale = Locale.US, use24Hour = true)
            } else {
                sample
            }
        }

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
            "sys:clock" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M12 7v5l4 2",
            "sys:status" to "M4 7h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z M22 11v2 M6 11v2 M10 11v2",
        )
        /** Thursday 1 October 2026, 14:32 UTC. */
        const val FIXED_TIME_MS = 1_790_865_120_000L
        /** What a plugin's `TILE_PREVIEW` raw resource would decode to. */
        val SAMPLES = mapOf(
            "assistant" to TileSnapshot(
                pluginId = "assistant",
                contentKey = "sample",
                content = TileContent.ListContent(
                    sections = listOf(
                        TileContent.ListContent.Section(
                            items = listOf(
                                TileContent.ListContent.Item("Ana Ribeiro", "WhatsApp", "Leaving now, ten minutes away."),
                                TileContent.ListContent.Item("Family", "WhatsApp", "Dad: dinner moved to 8"),
                            ),
                        ),
                    ),
                    summary = "3 new",
                    summaryShort = "3",
                ),
            ),
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

    private fun capture(
        name: String,
        stored: List<TileLayoutEntry>,
        height: Int = 1688,
        prepare: (Activity) -> Unit = {},
    ) {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        TileLayoutSettingsStore(app).setEntries(stored)
        snapshot("media", "Midnight Transit", "Analog Youth", listOf("1:24 / 3:40", "Up next  Night Drive", "Queue  12"), 0.38f)
        snapshot("transit", "Av. Central & 5th", "Next in 3 min", listOf("Line 4 to Riverside  3 min", "Line 9 to Old Town  11 min", "Line 2 to Central  18 min"))
        snapshot("feeds", "@kaelan.bsky", "rewired the desk lamp", listOf("@noa.codes  22m", "@ferra.dev  1h", "@lin.ink  2h", "@oskar.bsky  3h"))
        snapshot("relay", "3", "Maya Liu", emptyList())
        val activity = Robolectric.buildActivity(ScreenshotTileLayoutActivity::class.java).setup().get()
        prepare(activity)
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

    private fun <T : View> find(root: View, type: Class<T>, match: (T) -> Boolean = { true }): T? {
        if (type.isInstance(root) && match(type.cast(root)!!)) return type.cast(root)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), type, match)?.let { return it }
        }
        return null
    }

    @Test
    fun `the selected card previews the last tapped size, with the plugin's sample before any publish`() {
        val stored = listOf(TileLayoutEntry("assistant", TileSize.SMALL, 0, 0), TileLayoutEntry("camera", TileSize.SMALL, 1, 0))
        var preview: TileSizePreviewView? = null
        capture("tile-layout-preview", stored, height = 2600) { activity ->
            val root = activity.window.decorView
            assertEquals(TileSize.SMALL, find(root, TileSizePreviewView::class.java)!!.size)
            find(root, View::class.java) { it.contentDescription == "3×3" && it.isClickable }!!.performClick()
            preview = find(activity.window.decorView, TileSizePreviewView::class.java)
        }
        assertEquals(TileSize.JUMBO, preview!!.size)
        assertEquals(ScreenshotTileLayoutActivity.SAMPLES["assistant"], preview!!.snapshotForTest)
    }

    @Test
    fun `the preview prefers the live tile over the sample and shows the header alone without either`() {
        val stored = listOf(TileLayoutEntry("assistant", TileSize.WIDE, 0, 0))
        TileSnapshotCache.record("assistant", WidgetTileContract.toPayload(TileSnapshot("assistant", "k", "Live")))
        val activity = Robolectric.buildActivity(ScreenshotTileLayoutActivity::class.java)
            .also { TileLayoutSettingsStore(org.robolectric.RuntimeEnvironment.getApplication()).setEntries(stored) }
            .setup().get()
        val live = find(activity.window.decorView, TileSizePreviewView::class.java)!!
        assertEquals(TileSize.WIDE, live.size)
        assertEquals("Live", (live.snapshotForTest?.content as TileContent.Generic).title)

        TileSnapshotCache.clear()
        TileLayoutSettingsStore(org.robolectric.RuntimeEnvironment.getApplication())
            .setEntries(listOf(TileLayoutEntry("tasker", TileSize.SMALL, 0, 0)))
        val bare = Robolectric.buildActivity(ScreenshotTileLayoutActivity::class.java).setup().get()
        assertEquals(null, find(bare.window.decorView, TileSizePreviewView::class.java)!!.snapshotForTest)
    }

    private val withWidgets = listOf(
        TileLayoutEntry("sys:clock", TileSize.WIDE, 0, 0),
        TileLayoutEntry("media", TileSize.WIDE, 2, 0),
        TileLayoutEntry("camera", TileSize.SMALL, 0, 1),
        TileLayoutEntry("relay", TileSize.SMALL, 1, 1),
        TileLayoutEntry("sys:status", TileSize.WIDE, 2, 1),
        TileLayoutEntry("transit", TileSize.PANEL, 0, 2),
        TileLayoutEntry("feeds", TileSize.TALL, 3, 2),
        TileLayoutEntry("assistant", TileSize.BANNER, 0, 5),
        TileLayoutEntry("tasker", TileSize.SMALL, 3, 5),
    )

    private fun clickable(root: View, description: String): View =
        find(root, View::class.java) { it.contentDescription == description && it.isClickable }
            ?: error("no clickable \"$description\"")

    @Test
    fun `a selected widget shows the SYSTEM chip, its supported sizes, its preview and REMOVE`() {
        var preview: TileSizePreviewView? = null
        var canvas: TileLayoutCanvasView? = null
        capture("tile-layout-widget-selected", withWidgets, height = 2900) { activity ->
            val root = activity.window.decorView
            canvas = find(root, TileLayoutCanvasView::class.java)
            // Select the status widget as a TalkBack click would.
            val state = ReflectionHelpers.getField<TileLayoutEditorState>(activity, "state")
            state.select("sys:status")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onEditorChanged")
            val after = activity.window.decorView
            assertEquals("SYSTEM", find(after, TextView::class.java) { it.text == "SYSTEM" }?.text)
            assertEquals(true, clickable(after, "2×1").isEnabled)
            assertEquals(false, find(after, View::class.java) { it.contentDescription == "2×2, not supported by Status" }!!.isEnabled)
            assertEquals(true, clickable(after, "Remove Status widget") is Button)
            preview = find(after, TileSizePreviewView::class.java)
        }
        assertEquals(TileSize.WIDE, preview!!.size)
        assertEquals(true, preview!!.widgetForTest is SystemWidgetContent.Status)
        assertEquals(
            "Status, system widget, 2 by 1, column 3, row 2. Drag or use actions to move.",
            canvas!!.descriptionForTest("sys:status"),
        )
    }

    @Test
    fun `remove takes the widget off and the add list offers it again`() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        capture("tile-layout-add-widget", withWidgets.filterNot { it.pluginId == "sys:clock" }, height = 2600) { activity ->
            val root = activity.window.decorView
            clickable(root, "Add a system widget").performClick()
            val list = activity.window.decorView
            assertEquals(true, clickable(list, "Add Clock widget") is Button)
            assertEquals(null, find(list, View::class.java) { it.contentDescription == "Add Status widget" })
            assertEquals("Local time and date", find(list, TextView::class.java) { it.text == "Local time and date" }?.text)
        }
        // Adding selects the widget, closes the list and saves with it.
        val activity = Robolectric.buildActivity(ScreenshotTileLayoutActivity::class.java).setup().get()
        clickable(activity.window.decorView, "Add a system widget").performClick()
        clickable(activity.window.decorView, "Add Clock widget").performClick()
        val state = ReflectionHelpers.getField<TileLayoutEditorState>(activity, "state")
        assertEquals("sys:clock", state.selectedId)
        assertEquals(GridRect(0, 0, 1, 1), state.layout["sys:clock"])
        clickable(activity.window.decorView, "Remove Clock widget").performClick()
        assertEquals(null, state.layout["sys:clock"])
        find(activity.window.decorView, Button::class.java) { it.text == "SAVE LAYOUT" }!!.performClick()
        assertEquals(
            listOf("sys:status"),
            TileLayoutSettingsStore(app).getEntries().map { it.pluginId }.filter { it.startsWith("sys:") },
        )
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
