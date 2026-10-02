package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.HomeVisibilityContract

/**
 * Android edge around [HomeVisibilityReportPolicy]: tracks whether the display is interactive and
 * whether the HUD shows the home, and reports both to the phone so it can stop feeding a dark or
 * covered home. HudController is the single owner of what is on screen and calls [onHudScreen].
 *
 * Inputs arrive on the main thread (screen broadcasts, HUD settle) or a link thread
 * ([onTransportUp], [onLinkDown]); the policy serializes them.
 */
internal object HomeVisibilityReporter {
    private val main = Handler(Looper.getMainLooper())
    private val policy = HomeVisibilityReportPolicy(
        scheduler = { delayMs, task ->
            main.postDelayed(task, delayMs)
            ({ main.removeCallbacks(task) })
        },
        send = { value ->
            GlassesHub.sendHomeVisibility(HomeVisibilityContract.toPayload(value))
        },
    )

    @Volatile private var screenOn = false
    @Volatile private var homeShown = false
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> setScreenOn(true)
                Intent.ACTION_SCREEN_OFF -> setScreenOn(false)
            }
        }
    }

    @Synchronized
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        }
        setScreenOn(appContext.getSystemService(PowerManager::class.java)?.isInteractive == true)
    }

    fun onHudScreen(homeVisibleOnHud: Boolean) {
        homeShown = homeVisibleOnHud
        policy.update(screenOn, homeShown)
    }

    fun onTransportUp() = policy.onTransportUp()

    fun onLinkDown() = policy.onLinkDown()

    private fun setScreenOn(on: Boolean) {
        screenOn = on
        policy.update(on, homeShown)
    }
}
