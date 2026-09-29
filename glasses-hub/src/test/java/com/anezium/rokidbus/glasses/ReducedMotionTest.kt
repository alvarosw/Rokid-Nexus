package com.anezium.rokidbus.glasses

import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReducedMotionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `default scale is not reduced motion`() {
        assertFalse(ReducedMotion.isEnabled(context))
    }

    @Test
    fun `scale of zero is reduced motion`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(ReducedMotion.isEnabled(context))
    }

    @Test
    fun `a nonzero scale is not reduced motion`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.5f)
        assertFalse(ReducedMotion.isEnabled(context))
    }

    @Test
    fun `duration scale is the setting, one when unset or off`() {
        org.junit.Assert.assertEquals(1f, ReducedMotion.durationScale(context), 0f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 5f)
        org.junit.Assert.assertEquals(5f, ReducedMotion.durationScale(context), 0f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        org.junit.Assert.assertEquals(1f, ReducedMotion.durationScale(context), 0f)
    }
}
