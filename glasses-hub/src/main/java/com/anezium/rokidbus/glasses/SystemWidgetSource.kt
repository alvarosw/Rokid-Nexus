package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import com.anezium.rokidbus.hudtiles.SystemWidgetContent
import com.anezium.rokidbus.shared.tile.SystemWidget
import com.anezium.rokidbus.shared.tile.SystemWidgets
import java.util.Locale
import java.util.TimeZone

/** Where a system widget's data comes from. A widget asks for it only while it is on screen. */
internal interface SystemWidgetSource {
    /** What [widget] shows now; null for a widget this source has no data for. */
    fun content(widget: SystemWidget): SystemWidgetContent?

    /** Calls [onChange] on the main thread whenever [widget]'s data may have changed, until the returned stop runs. */
    fun observe(widget: SystemWidget, onChange: () -> Unit): () -> Unit
}

/**
 * The glasses' own clock, battery and link, the phone's charge as the phone last reported it on
 * `/phone/battery` ([PhoneBatteryController]), and its weather from `/phone/weather`
 * ([PhoneWeatherController]). Nothing is polled: the clock's minute and the weather's age are the
 * renderer's to schedule, everything else is a broadcast or a hub callback.
 */
internal class DeviceWidgetSource(context: Context) : SystemWidgetSource {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun content(widget: SystemWidget): SystemWidgetContent? = when (widget.id) {
        SystemWidgets.CLOCK.id -> SystemWidgetContent.Clock(
            epochMs = System.currentTimeMillis(),
            timeZone = TimeZone.getDefault(),
            locale = Locale.getDefault(),
            use24Hour = DateFormat.is24HourFormat(context),
        )
        SystemWidgets.STATUS.id -> SystemWidgetContent.Status(
            glasses = glassesBattery(),
            phone = PhoneBatteryController.reading()?.let { SystemWidgetContent.Battery(it.level, it.charging) },
            phoneLinked = GlassesHub.isPhoneLinked(),
        )
        SystemWidgets.WEATHER.id -> {
            val cached = PhoneWeatherController.cached(context)
            SystemWidgetContent.Weather(cached?.reading, cached?.let { PhoneWeatherController.ageMs(context, it) })
        }
        else -> null
    }

    override fun observe(widget: SystemWidget, onChange: () -> Unit): () -> Unit {
        val stops = ArrayList<() -> Unit>()
        val filter = IntentFilter()
        when (widget.id) {
            SystemWidgets.CLOCK.id -> {
                filter.addAction(Intent.ACTION_TIME_CHANGED)
                filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
                filter.addAction(Intent.ACTION_LOCALE_CHANGED)
            }
            SystemWidgets.STATUS.id -> {
                filter.addAction(Intent.ACTION_BATTERY_CHANGED)
                stops += PhoneBatteryController.observe(onChange)
                stops += GlassesHub.observePhoneLink { main.post(onChange) }
            }
            SystemWidgets.WEATHER.id -> stops += PhoneWeatherController.observe(onChange)
        }
        if (filter.countActions() > 0) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onChange()
            }
            context.registerReceiver(receiver, filter)
            stops += { runCatching { context.unregisterReceiver(receiver) } }
        }
        return { stops.forEach { it() } }
    }

    /** The sticky battery broadcast read once, as the surface status row reads it. */
    private fun glassesBattery(): SystemWidgetContent.Battery? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return SystemWidgetContent.Battery(level * 100 / scale, charging)
    }
}
