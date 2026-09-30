package com.anezium.rokidbus.glasses

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anezium.rokidbus.glasses.hud.assertSingleHue
import com.anezium.rokidbus.ink.InkEngine
import com.anezium.rokidbus.ink.InkSource
import com.anezium.rokidbus.shared.EditableSurfaceField
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real pixels of every non-Ink surface kind, and of an Ink card, on the glasses screen: 480x640 px
 * at 240 dpi. Like `HomeScreenshotTest` it writes the PNGs (the review artifact) and checks the
 * design system on them: exactly 480x640 and every lit pixel the one green hue, except the pixels
 * of a decoded picture, which keep their own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class SurfaceScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    init {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun surface(
        kind: String,
        title: String = "",
        subtitle: String = "",
        footer: String = "",
        rows: List<SurfaceRow> = emptyList(),
        timedLines: List<TimedLine> = emptyList(),
        anchor: SurfaceAnchor? = null,
        block: (NexusSurface) -> NexusSurface = { it },
    ) = block(
        NexusSurface(
            surfaceId = "shot:$kind",
            seq = 1,
            kind = kind,
            contentKey = "shot",
            title = title,
            subtitle = subtitle,
            footer = footer,
            rows = rows,
            timedLines = timedLines,
            anchor = anchor,
            handlesBack = false,
        ),
    )

    private fun host() = ActivityScenario.launch<HomeScreenshotHostActivity>(
        Intent(context, HomeScreenshotHostActivity::class.java),
    )

    private fun capture(name: String, exempt: ((View) -> Rect?)? = null, setup: (SurfaceHudView) -> Unit) {
        host().use { scenario ->
            lateinit var view: SurfaceHudView
            scenario.onActivity { activity ->
                view = SurfaceHudView(activity)
                activity.setContentView(view, FrameLayout.LayoutParams(480, 640))
                setup(view)
                stopMarquees(view)
                repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
            }
            val path = "build/outputs/roborazzi/surface-$name.png"
            // Not through Espresso's idle wait: a marquee title (a name too long for its slot) animates
            // for as long as it is shown, so the main looper is never idle.
            view.captureRoboImage(path)
            assertSingleHue(File(path), exempt?.invoke(view))
        }
    }

    /**
     * A marquee title (a name too long for its slot) animates for as long as it is shown, so the
     * looper never goes idle and the capture never returns. The frame is the marquee at rest.
     */
    private fun stopMarquees(root: View) {
        if (root is android.widget.TextView) root.isSelected = false
        (root as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).forEach { stopMarquees(g.getChildAt(it)) } }
    }

    /** Where the first view satisfying [isTarget] is, in [root]'s coordinates: a decoded picture keeps its pixels. */
    private fun boundsOf(root: View, isTarget: (View) -> Boolean): Rect? {
        fun find(v: View): View? = if (isTarget(v)) v else (v as? android.view.ViewGroup)?.let { g ->
            (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) }
        }
        val target = find(root) ?: return null
        val rect = Rect()
        target.getDrawingRect(rect)
        (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(target, rect)
        return rect
    }

    private fun show(name: String, surface: NexusSurface, exempt: ((View) -> Rect?)? = null) =
        capture(name, exempt) { it.render(surface) }

    // ---- card / text ------------------------------------------------------------------------

    @Test
    fun card_plain_short() = show(
        "01-card-plain-short",
        surface(
            NexusSurface.KIND_CARD, "Turn right", "in 150 m", "ETA 12:40 · 4.2 km",
            rows = listOf(SurfaceRow("Onto Avenida de la Constitucion"), SurfaceRow("Then continue 1.1 km")),
        ),
    )

    @Test
    fun card_plain_long() = show(
        "02-card-plain-long",
        surface(
            NexusSurface.KIND_CARD, "Assistant", "answer", "",
            rows = listOf(SurfaceRow(LONG_TEXT)),
        ),
    )

    @Test
    fun card_board() = show(
        "03-card-board",
        surface(
            NexusSurface.KIND_CARD, "Stop Plaza Mayor", "Departures", "3 lines",
            rows = listOf(
                SurfaceRow("Hospital", badge = "27", trail = listOf("2 min", "12 min", "22 min")),
                SurfaceRow("Central station", badge = "5", trail = listOf("7 min", "19 min")),
                SurfaceRow("Airport", badge = "C1", trail = listOf("14 min")),
            ),
        ),
    )

    // ---- list cards -------------------------------------------------------------------------

    private val departures = listOf(
        SurfaceRow("Line 27", badge = "2 min", sub = "Hospital", selected = true),
        SurfaceRow("Line 5", badge = "7 min", sub = "Central station"),
        SurfaceRow("Line C1", badge = "14 min", sub = "Airport", tone = SurfaceRow.TONE_DIM),
        SurfaceRow("Line 12", badge = "22 min", sub = "Delayed", tone = SurfaceRow.TONE_ALERT),
        SurfaceRow("Line 9", badge = "31 min", sub = "University"),
    )

    @Test
    fun list_badges() = show(
        "04-list-badges",
        surface(NexusSurface.KIND_CARD, "Stop Plaza Mayor", "Next departures", "3 lines", rows = departures),
    )

    @Test
    fun list_trail_and_tones() = show(
        "05-list-trail-tones",
        surface(
            NexusSurface.KIND_CARD, "Agents", "4 sessions", "",
            rows = listOf(
                SurfaceRow("Build the release", trail = listOf("running", "3m"), sub = "gradle assemble", selected = true),
                SurfaceRow("Review the diff", trail = listOf("waiting"), sub = "needs your answer", tone = SurfaceRow.TONE_ALERT),
                SurfaceRow("Write the changelog", trail = listOf("idle", "1h"), sub = "done", tone = SurfaceRow.TONE_DIM),
                SurfaceRow("Fix the lint", trail = listOf("idle", "2h"), sub = "queued"),
            ),
        ),
    )

    @Test
    fun list_scrolling() = show(
        "06-list-scrolling",
        surface(
            NexusSurface.KIND_CARD, "Conversations", "40 unread", "ring to scroll",
            rows = (0 until 24).map { i ->
                SurfaceRow(
                    "Conversation number $i",
                    badge = "${i + 1} min",
                    sub = "Last message from contact $i",
                    selected = i == 13,
                )
            },
        ),
    )

    @Test
    fun list_conversation_body_rows() = show(
        "07-list-body-rows",
        surface(
            NexusSurface.KIND_CARD, "Thread", "Ana", "",
            rows = listOf(
                SurfaceRow("Are we still meeting at the station at six?", badge = "Ana", tone = SurfaceRow.TONE_BODY),
                SurfaceRow("Yes, six works. Reference is on the way to you now.", badge = "You", tone = SurfaceRow.TONE_BODY, selected = true),
                SurfaceRow(LONG_TEXT, badge = "Ana", tone = SurfaceRow.TONE_BODY),
            ),
        ),
    )

    // ---- timed lines ------------------------------------------------------------------------

    private val lyricLines = listOf(
        TimedLine(0, "Take me down to the paradise city"),
        TimedLine(4_000, "Where the grass is green"),
        TimedLine(8_000, "And the girls are pretty"),
    )

    private fun playing(positionMs: Long, durationMs: Long = 180_000) =
        SurfaceAnchor(positionMs, true, 0, durationMs = durationMs)

    @Test
    fun lyrics_short() = show(
        "08-lyrics",
        surface(
            NexusSurface.KIND_TIMED_LINES, "Lyrics", "Demo Artist", "synced",
            timedLines = lyricLines, anchor = playing(4_500),
        ),
    )

    @Test
    fun lyrics_long_line() = show(
        "09-lyrics-long-line",
        surface(
            NexusSurface.KIND_TIMED_LINES, "Lyrics", "Demo Artist", "synced",
            timedLines = listOf(
                TimedLine(0, "Short line before"),
                TimedLine(4_000, LONG_TEXT),
                TimedLine(8_000, "And the girls are pretty, and the night is long, and nobody wants to go home just yet"),
            ),
            anchor = playing(4_500),
        ),
    )

    // ---- media ------------------------------------------------------------------------------

    private fun media(block: (NexusSurface) -> NexusSurface = { it }) = surface(
        NexusSurface.KIND_MEDIA, "Now Playing", "Phone", "",
        anchor = playing(61_000, 206_000),
    ) { block(it.copy(mediaTitle = "Paradise City", mediaArtist = "Demo Artist", mediaAlbum = "Demo Album")) }

    private fun monoArt(): MonoArtwork {
        val size = 64
        val bytes = ByteArray(size * size / 8)
        for (y in 0 until size) for (x in 0 until size) {
            val lit = (x / 8 + y / 8) % 2 == 0 || (x - 32) * (x - 32) + (y - 32) * (y - 32) < 200
            if (lit) bytes[(y * size + x) / 8] = (bytes[(y * size + x) / 8].toInt() or (1 shl (7 - (y * size + x) % 8))).toByte()
        }
        return MonoArtwork(size, size, bytes, "shot")
    }

    private fun picture(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            bitmap.setPixel(x, y, Color.rgb(x * 255 / width, y * 255 / height, 128))
        }
        return bitmap
    }

    private val pictureMetadata = SurfaceImageMetadata(1, "shot", "image/png", 200, 200, "0".repeat(64), "")

    @Test
    fun media_without_artwork() = show("10-media-no-artwork", media())

    @Test
    fun media_mono_artwork() = show("11-media-mono-artwork", media { it.copy(artwork = monoArt()) })

    @Test
    fun media_binary_artwork() = capture("12-media-binary-artwork", exempt = { boundsOf(it) { v -> v is android.widget.ImageView } }) {
        it.render(media { s -> s.copy(imageBitmap = picture(200, 200), mediaArtworkMetadata = pictureMetadata) })
    }

    @Test
    fun media_long_names() = show(
        "13-media-long-names",
        media {
            it.copy(
                mediaTitle = "The Extraordinarily Long Title of a Song That Never Seems to End",
                mediaArtist = "An Artist With A Very Long Name Feat. Another Artist And A Third One",
                mediaAlbum = "A Remastered Anniversary Deluxe Edition Of The Album",
                artwork = monoArt(),
            )
        },
    )

    // ---- reader -----------------------------------------------------------------------------

    private val conversation = listOf(
        ReaderSegment(ReaderSegmentKind.HEADER, "Ana · 2 min ago", emphasis = true),
        ReaderSegment(ReaderSegmentKind.PROSE, "Are we still meeting at the station at six? I can bring the tickets if you send me the booking reference."),
        ReaderSegment(ReaderSegmentKind.HEADER, "You · 1 min ago"),
        ReaderSegment(ReaderSegmentKind.PROSE, "Yes, six works. Reference is on the way."),
        ReaderSegment(ReaderSegmentKind.ASIDE, "delivered"),
        ReaderSegment(ReaderSegmentKind.HEADER, "Ana · now", emphasis = true),
        ReaderSegment(ReaderSegmentKind.PROSE, "Perfect. See you there, and do not forget the umbrella, the forecast says rain after seven."),
    )

    private fun reader(segments: List<ReaderSegment>, anchor: ReaderAnchor = ReaderAnchor.TOP) = surface(
        NexusSurface.KIND_READER, "Conversation", "3 messages", "scroll with the ring",
    ) { it.copy(readerSegments = segments, readerAnchor = anchor) }

    @Test
    fun reader_short() = show("14-reader-short", reader(conversation))

    private val longConversation = (0 until 12).flatMap { i ->
        listOf(
            ReaderSegment(ReaderSegmentKind.HEADER, "${if (i % 2 == 0) "Ana" else "You"} · $i min ago", emphasis = i % 2 == 0),
            ReaderSegment(ReaderSegmentKind.PROSE, LONG_TEXT),
        )
    }

    @Test
    fun reader_long_top() = show("15-reader-long-top", reader(longConversation))

    @Test
    fun reader_long_bottom() = show("16-reader-long-bottom-anchored", reader(longConversation, ReaderAnchor.BOTTOM))

    // ---- image, editable --------------------------------------------------------------------

    @Test
    fun image() = capture("17-image", exempt = { boundsOf(it) { v -> v is ImageHudView } }) {
        it.render(
            surface(NexusSurface.KIND_IMAGE, "Lens", "a gradient", "") { s ->
                s.copy(
                    imageMetadata = SurfaceImageMetadata(1, "shot", "image/png", 320, 200, "0".repeat(64), "a gradient"),
                    imageBitmap = picture(320, 200),
                )
            },
        )
    }

    @Test
    fun editable_card() = show(
        "18-editable-card",
        surface(NexusSurface.KIND_CARD, "Reply to Ana", "", "Enter sends, back cancels") {
            it.copy(editable = EditableSurfaceField(placeholder = "Type a reply", initialText = "See you at six"))
        },
    )

    @Test
    fun editable_card_empty() = show(
        "19-editable-card-empty",
        surface(NexusSurface.KIND_CARD, "Reply to Ana", "", "") {
            it.copy(editable = EditableSurfaceField(placeholder = "Type a reply"))
        },
    )

    // ---- Ink --------------------------------------------------------------------------------

    @Test
    fun ink_card() {
        val page = File("../plugins/assistant/src/main/assets/ink_templates/metrics.ink").readText()
        val cells = JSONArray().apply {
            listOf("Steps" to "8 412", "Heart" to "64 bpm", "Sleep" to "7 h 20", "Battery" to "83 %", "Water" to "1.4 l", "Focus" to "3 h")
                .forEach { (label, value) ->
                    put(JSONObject().put("label", label).put("value", value).put("detail", "today"))
                }
        }
        val compiled = InkEngine.compile(InkSource.Sfc(page), JSONObject().put("title", "Today").put("cells", cells))
        val document = requireNotNull(compiled.document) { "metrics must compile: ${compiled.problems}" }
        host().use { scenario ->
            scenario.onActivity { activity ->
                val card = InkHudView(activity)
                val frame = FrameLayout(activity)
                frame.addView(
                    card,
                    FrameLayout.LayoutParams(441, FrameLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 18; leftMargin = 20 },
                )
                activity.setContentView(frame, FrameLayout.LayoutParams(480, 640))
                card.show(InkNodeStore.from(document), debugActions = false)
                repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)) }
            }
            val path = "build/outputs/roborazzi/surface-20-ink-card.png"
            onView(isRoot()).captureRoboImage(path)
            assertSingleHue(File(path))
        }
    }

    private companion object {
        const val LONG_TEXT =
            "The quick brown fox jumps over the lazy dog while the rain keeps falling on the old station roof, " +
                "and somebody far down the platform keeps whistling the same three notes over and over until the " +
                "last train of the night finally rolls in from the north with its lights low and its doors open."
    }
}
