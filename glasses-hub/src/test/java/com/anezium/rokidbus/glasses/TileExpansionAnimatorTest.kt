package com.anezium.rokidbus.glasses

import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.RokidHudTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TileExpansionAnimatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val from = Rect(10, 20, 50, 60)
    private val to = Rect(16, 12, 464, 340)

    private fun childInParent(): View {
        val parent = FrameLayout(context)
        val child = View(context)
        parent.addView(child, FrameLayout.LayoutParams(from.width(), from.height()).apply {
            leftMargin = from.left
            topMargin = from.top
        })
        return child
    }

    @Test
    fun `expanding lands exactly on the destination rect`() {
        val child = childInParent()
        val animator = TileExpansionAnimator(context)
        var settled = false

        animator.expand(child, from, to) { settled = true }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RokidHudTokens.DURATION_STRUCTURAL_MS + 50))

        val params = child.layoutParams as FrameLayout.LayoutParams
        assertEquals(to.left, params.leftMargin)
        assertEquals(to.top, params.topMargin)
        assertEquals(to.width(), params.width)
        assertEquals(to.height(), params.height)
        assertTrue(settled)
    }

    @Test
    fun `reduced motion skips the tween and lands directly on the end rect`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val child = childInParent()
        val animator = TileExpansionAnimator(context)
        var settled = false

        animator.expand(child, from, to) { settled = true }

        val params = child.layoutParams as FrameLayout.LayoutParams
        assertEquals(to.left, params.leftMargin)
        assertEquals(to.width(), params.width)
        assertTrue(settled)
    }

    @Test
    fun `disabling the animator falls back to an instant jump, same as reduced motion`() {
        val child = childInParent()
        val animator = TileExpansionAnimator(context).apply { enabled = false }
        var settled = false

        animator.expand(child, from, to) { settled = true }

        val params = child.layoutParams as FrameLayout.LayoutParams
        assertEquals(to.left, params.leftMargin)
        assertTrue(settled)
    }

    @Test
    fun `cancelling mid-tween leaves the view at the destination rect, not stuck mid-transition`() {
        val child = childInParent()
        val animator = TileExpansionAnimator(context)
        var settled = false

        animator.expand(child, from, to) { settled = true }
        // Cancelled before any idling — Robolectric's shadow ValueAnimator runs an animation to
        // completion as soon as the looper is idled at all, so catching it "mid-flight" means
        // cancelling before that first idle rather than after a partial one.
        animator.cancel()

        val params = child.layoutParams as FrameLayout.LayoutParams
        assertEquals(to.left, params.leftMargin)
        assertEquals(to.top, params.topMargin)
        assertEquals(to.width(), params.width)
        assertEquals(to.height(), params.height)
        // A cancelled tween must not fire the continuation of a sequence already superseded.
        assertTrue(!settled)
    }
}
