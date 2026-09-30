package com.anezium.rokidbus.glasses

import android.app.Activity

/**
 * Debug-only host for [com.anezium.rokidbus.glasses.hud.HomeScreenshotTest]: an `Activity` with a
 * real window for `ActivityScenario` to attach a home rendering to, so Roborazzi's capture goes
 * through the genuine window-attached render path rather than a hand-rolled, unreliable
 * `view.draw(canvas)`. The test sets its own content view; this class exists only to be a
 * launchable component.
 */
class HomeScreenshotHostActivity : Activity()
