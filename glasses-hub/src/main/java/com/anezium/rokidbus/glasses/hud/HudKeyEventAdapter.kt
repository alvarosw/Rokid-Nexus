package com.anezium.rokidbus.glasses.hud

import android.view.KeyEvent

/** The thin Android edge of [HudInput]: `KeyEvent` and its `InputDevice` become a [RawKeyEvent]. */
object HudKeyEventAdapter {
    // InputDevice.SOURCE_* values, copied so classification stays a pure function.
    private const val SOURCE_KEYBOARD = 0x00000101
    private const val SOURCE_DPAD = 0x00000201
    private const val SOURCE_TOUCHPAD = 0x00100008
    private const val SOURCE_TOUCHSCREEN = 0x00001002

    fun from(event: KeyEvent): RawKeyEvent =
        RawKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            eventTime = event.eventTime,
            deviceClass = classify(event.device?.name, event.source),
        )

    /**
     * By name first (HARDWARE R1): a name containing `R08`, any case, is the ring and nothing else
     * is. The touchpad's own name is not documented (HARDWARE Q15 covers the ring only), so it is
     * recognised by name or source class; both generic classes take the same pipeline today.
     */
    fun classify(name: String?, sources: Int = 0): DeviceClass {
        val upper = name?.uppercase().orEmpty()
        return when {
            "R08" in upper -> DeviceClass.R08
            "TOUCH" in upper || sources.has(SOURCE_TOUCHPAD) || sources.has(SOURCE_TOUCHSCREEN) ->
                DeviceClass.TOUCHPAD
            sources.has(SOURCE_KEYBOARD) || sources.has(SOURCE_DPAD) -> DeviceClass.KEYBOARD_DPAD
            else -> DeviceClass.OTHER
        }
    }

    private fun Int.has(mask: Int) = (this and mask) == mask
}
