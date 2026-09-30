package com.anezium.rokidbus.glasses

import android.app.Activity
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * 01 §7.5 item 93 on the real view: the reader scrolls by its own offset (HARDWARE S5, no
 * `ScrollView`), by 45% of its height per page, and applies [resolveReaderScrollTarget] after a
 * re-render. The pure function is covered by [ReaderSurfaceModelsTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w480dp-h640dp-mdpi")
class ReaderSurfaceViewTest {
    private lateinit var activity: ActivityController<Activity>
    private lateinit var reader: ReaderSurfaceView

    @Before
    fun setUp() {
        // Reduced motion: every scroll lands on its target inside the call.
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        reader = ReaderSurfaceView(activity.get())
        activity.get().setContentView(reader)
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    private fun document(count: Int = 60) = List(count) {
        ReaderSegment(ReaderSegmentKind.PROSE, "Paragraph $it. " + "lorem ipsum dolor sit amet ".repeat(12))
    }

    private fun render(surfaceId: String, anchor: ReaderAnchor, segments: List<ReaderSegment> = document()) {
        reader.render(surfaceId, segments, anchor)
        settle()
    }

    /** The window lays the attached view out on idle; the test reads the size it was given. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun maximumScroll(): Int {
        val document = (reader.getChildAt(0) as ViewGroup)
        return (document.height - reader.height).coerceAtLeast(0)
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
    }

    @Test
    fun item93_the_reader_scrolls_by_its_own_offset_not_by_a_scroll_view() {
        render("s", ReaderAnchor.TOP)
        assertTrue("fixture must be taller than the viewport", maximumScroll() > 640)
        assertTrue(descendants(reader).none { it is ScrollView })
        assertEquals(0, reader.scrollY)
    }

    @Test
    fun item93_a_page_step_is_45_percent_of_the_viewport_and_clamps_at_both_ends() {
        render("s", ReaderAnchor.TOP)
        assertTrue("the view is laid out", reader.height > 0)
        val step = Math.round(reader.height * 0.45f)

        reader.smoothScrollByViewport(1)
        assertEquals(step, reader.scrollY)
        reader.smoothScrollByViewport(1)
        assertEquals(2 * step, reader.scrollY)
        reader.smoothScrollByViewport(-1)
        assertEquals(step, reader.scrollY)
        reader.smoothScrollByViewport(-1)
        reader.smoothScrollByViewport(-1)
        assertEquals("never above the top", 0, reader.scrollY)

        repeat(200) { reader.smoothScrollByViewport(1) }
        assertEquals("never past the end", maximumScroll(), reader.scrollY)
    }

    @Test
    fun item93_a_zero_direction_and_an_unlaid_out_view_do_not_scroll() {
        render("s", ReaderAnchor.TOP)
        reader.smoothScrollByViewport(0)
        assertEquals(0, reader.scrollY)

        val unlaidOut = ReaderSurfaceView(activity.get())
        unlaidOut.smoothScrollByViewport(1)
        assertEquals(0, unlaidOut.scrollY)
    }

    @Test
    fun item93_a_new_surface_starts_at_its_anchor() {
        render("a", ReaderAnchor.BOTTOM)
        assertEquals("BOTTOM pins the end", maximumScroll(), reader.scrollY)
        render("b", ReaderAnchor.TOP)
        assertEquals("TOP starts at the beginning", 0, reader.scrollY)
    }

    @Test
    fun item93_a_re_render_of_the_same_surface_keeps_position_or_bottom_pins_per_anchor() {
        render("s", ReaderAnchor.BOTTOM)
        // Near the bottom with a BOTTOM anchor: growth keeps the reader on the newest text.
        render("s", ReaderAnchor.BOTTOM, document(70))
        assertEquals(maximumScroll(), reader.scrollY)

        // Scrolled away from the bottom: the position is kept, not yanked to the end.
        reader.smoothScrollByViewport(-1)
        reader.smoothScrollByViewport(-1)
        val kept = reader.scrollY
        assertTrue(kept < maximumScroll() - 36)
        render("s", ReaderAnchor.BOTTOM, document(70))
        assertEquals(kept, reader.scrollY)

        // A TOP anchor never bottom-pins, even when the reader sits at the bottom.
        render("t", ReaderAnchor.TOP)
        repeat(200) { reader.smoothScrollByViewport(1) }
        assertEquals(maximumScroll(), reader.scrollY)
        val atBottom = reader.scrollY
        render("t", ReaderAnchor.TOP, document(70))
        assertEquals(atBottom, reader.scrollY)
        assertFalse(reader.scrollY == maximumScroll())
    }

    @Test
    fun item93_clear_returns_to_the_top_and_forgets_the_surface() {
        render("s", ReaderAnchor.BOTTOM)
        assertTrue(reader.scrollY > 0)
        reader.clear()
        assertEquals(0, reader.scrollY)
        // The same id after a clear is a new surface again, so BOTTOM pins the end.
        render("s", ReaderAnchor.BOTTOM)
        assertEquals(maximumScroll(), reader.scrollY)
    }
}
