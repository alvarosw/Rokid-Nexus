package com.anezium.rokidbus.glasses

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class CriticalBlinkTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun mainLooper(): ShadowLooper = shadowOf(android.os.Looper.getMainLooper())

    @Test
    fun `blinks exactly 3 times then settles once and never loops`() {
        val view = View(context)
        var settledCount = 0
        CriticalBlink.animate(view) { settledCount++ }

        val observedAlphas = mutableListOf<Float>()
        repeat(6) {
            observedAlphas += view.alpha
            mainLooper().idleFor(java.time.Duration.ofMillis(200))
        }
        // Idle well past the point the sequence should have settled, to confirm it never resumes.
        mainLooper().idleFor(java.time.Duration.ofSeconds(5))

        assertEquals(listOf(0.2f, 1f, 0.2f, 1f, 0.2f, 1f), observedAlphas)
        assertEquals(1f, view.alpha)
        assertEquals(1, settledCount)
    }

    @Test
    fun `cancelling mid-blink stops further steps and never settles`() {
        val view = View(context)
        var settledCount = 0
        val handle = CriticalBlink.animate(view) { settledCount++ }

        mainLooper().idleFor(java.time.Duration.ofMillis(200))
        handle.cancel()
        val alphaAtCancel = view.alpha
        mainLooper().idleFor(java.time.Duration.ofSeconds(5))

        assertEquals(alphaAtCancel, view.alpha)
        assertTrue(settledCount == 0)
    }
}
