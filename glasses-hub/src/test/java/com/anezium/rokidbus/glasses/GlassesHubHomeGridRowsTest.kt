package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The glasses tell the phone how many home grid rows fit, and say so again when the HUD inset moves it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class GlassesHubHomeGridRowsTest {
    private val context = RuntimeEnvironment.getApplication()
    private var stop: (() -> Unit)? = null

    private fun rowsFor(insetDp: Int) = HudGridMetrics.visibleRows(HudTopInset.toPx(context, insetDp))

    private fun announcedRows(): List<Int> =
        GlassesHubTestSupport.sentOn(BusPaths.HUB_CAPABILITIES)
            .map { GlassesHubCapabilitiesContract.parse(it.payload).homeGridVisibleRows }

    @Before
    fun setUp() {
        GlassesHubTestSupport.install(context)
        HudTopInset.set(context, manualDp = 0, auto = false)
        stop = GlassesHub.observeHomeGridRows(context)
    }

    @After
    fun tearDown() {
        stop?.invoke()
        GlassesHub.onCxrState(false)
        HudTopInset.set(context, manualDp = 0, auto = false)
        GlassesHubTestSupport.uninstall()
    }

    @Test
    fun the_capabilities_announcement_carries_the_row_count_for_the_current_inset() {
        GlassesHub.onCxrState(true)
        assertEquals(listOf(rowsFor(0)), announcedRows())
        assertEquals(5, rowsFor(0))
    }

    @Test
    fun an_inset_change_that_changes_the_row_count_is_announced_once() {
        GlassesHub.onCxrState(true)
        GlassesHubTestSupport.sent.clear()

        HudTopInset.set(context, manualDp = 80, auto = false)
        assertNotEquals(rowsFor(0), rowsFor(80))
        assertEquals(listOf(rowsFor(80)), announcedRows())

        // Same row count at a different inset: nothing new to tell the phone.
        GlassesHubTestSupport.sent.clear()
        HudTopInset.set(context, manualDp = 79, auto = false)
        assertEquals(rowsFor(80), rowsFor(79))
        assertEquals(emptyList<Int>(), announcedRows())
    }

    @Test
    fun nothing_is_announced_while_the_link_is_down() {
        GlassesHub.onCxrState(true)
        GlassesHub.onCxrState(false)
        GlassesHubTestSupport.sent.clear()

        HudTopInset.set(context, manualDp = 80, auto = false)
        assertEquals(emptyList<Int>(), announcedRows())

        // The next link-up announces the value the inset has by then.
        GlassesHub.onCxrState(true)
        assertEquals(listOf(rowsFor(80)), announcedRows())
    }
}
