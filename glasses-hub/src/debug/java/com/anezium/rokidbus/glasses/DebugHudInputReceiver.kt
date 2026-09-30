package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.anezium.rokidbus.glasses.hud.DeviceClass
import com.anezium.rokidbus.glasses.hud.HudInputSeam
import com.anezium.rokidbus.glasses.hud.RawKeyEvent

/**
 * DUMP-protected, debug-build-only raw key injection into the live `HudInput`, tagged with a device
 * class so the ring (R08) pipeline runs in emulation, where `adb input keyevent` never reaches the
 * accessibility key filter and no device can be named R08. Has no effect until the service wires
 * [HudInputSeam.sink]. See tools/emulator/ring.sh (`INPUT_MODE=hud`) and docs/EMULATION.md.
 *
 * Extras: `key` (int keycode, required), `device` (`R08` default | `TOUCHPAD` | `KEYBOARD_DPAD` |
 * `OTHER`), `action` (`press` default = DOWN then UP | `down` | `up`), `repeat` (repeatCount of a
 * `down`). Event times are stamped with the uptime clock at delivery.
 */
class DebugHudInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        if (!intent.hasExtra(EXTRA_KEY)) {
            log("HUD_INPUT rejected: missing key")
            return
        }
        val key = intent.getIntExtra(EXTRA_KEY, -1)
        val device = when (val name = intent.getStringExtra(EXTRA_DEVICE) ?: "R08") {
            "R08" -> DeviceClass.R08
            "TOUCHPAD" -> DeviceClass.TOUCHPAD
            "KEYBOARD_DPAD" -> DeviceClass.KEYBOARD_DPAD
            "OTHER" -> DeviceClass.OTHER
            else -> {
                log("HUD_INPUT rejected device='$name'")
                return
            }
        }
        val repeat = intent.getIntExtra(EXTRA_REPEAT, 0).coerceAtLeast(0)
        val actions = when (val action = intent.getStringExtra(EXTRA_ACTION) ?: "press") {
            "press" -> listOf(RawKeyEvent.ACTION_DOWN, RawKeyEvent.ACTION_UP)
            "down" -> listOf(RawKeyEvent.ACTION_DOWN)
            "up" -> listOf(RawKeyEvent.ACTION_UP)
            else -> {
                log("HUD_INPUT rejected action='$action'")
                return
            }
        }
        val sink = HudInputSeam.sink
        if (sink == null) {
            log("HUD_INPUT dropped key=$key device=$device: no HudInput is wired")
            return
        }
        // The notice matches an UP to its DOWN by (device, keycode, downTime), so a pair shares one downTime.
        val downTime = if (actions.first() == RawKeyEvent.ACTION_DOWN) {
            SystemClock.uptimeMillis().also { lastDownTimes[key to device] = it }
        } else {
            lastDownTimes[key to device] ?: SystemClock.uptimeMillis()
        }
        for (action in actions) {
            sink(
                RawKeyEvent(
                    keyCode = key,
                    action = action,
                    repeatCount = if (action == RawKeyEvent.ACTION_DOWN) repeat else 0,
                    eventTime = SystemClock.uptimeMillis(),
                    deviceClass = device,
                    downTime = downTime,
                    deviceId = INJECTED_DEVICE_ID,
                ),
            )
        }
        log("HUD_INPUT injected key=$key device=$device actions=$actions repeat=$repeat")
    }

    private companion object {
        /** `KeyEvent.VIRTUAL_KEYBOARD`: the id the framework gives events with no physical device. */
        const val INJECTED_DEVICE_ID = -1
        val lastDownTimes = HashMap<Pair<Int, DeviceClass>, Long>()
        const val ACTION = "com.anezium.rokidbus.glasses.DEBUG_HUD_INPUT"
        const val EXTRA_KEY = "key"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_ACTION = "action"
        const val EXTRA_REPEAT = "repeat"
    }
}
