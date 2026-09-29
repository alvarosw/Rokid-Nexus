package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anezium.rokidbus.glasses.hud.HudController

class OpenLauncherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        log("Open launcher broadcast result: ${HudController.toggleLauncherFromBroadcast()}")
    }
}
