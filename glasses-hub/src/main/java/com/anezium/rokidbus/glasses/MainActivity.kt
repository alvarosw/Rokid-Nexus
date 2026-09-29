package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.glasses.hud.HudController
import com.anezium.rokidbus.glasses.hud.LauncherTrigger
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.SetupStage

class MainActivity : Activity() {
    private lateinit var onboardingView: View
    private lateinit var onboardingStepView: TextView
    private lateinit var onboardingTitleView: TextView
    private lateinit var onboardingBodyView: TextView
    private lateinit var onboardingDiagnosticView: TextView
    private lateinit var onboardingActionView: TextView
    private var onboardingState = SelfArmOnboardingState(
        stage = SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY,
        action = SelfArmOnboardingState.Action.OPEN_ACCESSIBILITY,
        detail = "",
    )
    private var insetUnsubscribe: (() -> Unit)? = null
    private var onboardingReceiverRegistered = false
    private var confirmationShownForSession = ""
    private val swipeDedupe = DpadPairDedupe()
    private val onboardingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SelfArmOnboardingStore.ACTION_CHANGED) renderScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveInstance = this
        window.statusBarColor = BusTheme.glassesBg
        window.navigationBarColor = BusTheme.glassesBg
        buildUi()
        requestBluetoothConnectIfNeeded()
        GlassesHub.start(applicationContext)
        insetUnsubscribe = HudTopInset.observe(this, ::applyHudTopInset)
        log("Launcher activity opened")
    }

    override fun onStart() {
        super.onStart()
        if (!onboardingReceiverRegistered) {
            val filter = IntentFilter(SelfArmOnboardingStore.ACTION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(onboardingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(onboardingReceiver, filter)
            }
            onboardingReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        resumedInstance = this
        SelfArmOnboardingStore.refreshNetworkPosture(applicationContext)
        RokidBusAccessibilityService.resumeSetupSessionIfNeeded(applicationContext)
        renderScreen()
        // The completed launcher view is not a setup screen; only the onboarding view counts as
        // the owner looking at setup, which resets the bridge-liveness attempt budget.
        if (onboardingState.stage != SelfArmOnboardingState.Stage.COMPLETE) {
            AccessibilityRearmWatcher.onSetupScreenOpened()
        }
    }

    override fun onPause() {
        if (resumedInstance === this) resumedInstance = null
        super.onPause()
    }

    override fun onStop() {
        if (onboardingReceiverRegistered) {
            onboardingReceiverRegistered = false
            unregisterReceiver(onboardingReceiver)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (resumedInstance === this) resumedInstance = null
        if (liveInstance === this) liveInstance = null
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (NoticeKeyDispatcher.handleKeyEvent(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return super.dispatchKeyEvent(event)
        }
        val direction = swipeDedupe.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)
        if (onboardingState.stage != SelfArmOnboardingState.Stage.COMPLETE) {
            if (direction != null) return true
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                -> true
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                -> {
                    performOnboardingAction()
                    true
                }
                else -> super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildUi() {
        onboardingStepView = text(11f, BusTheme.phosphor, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        onboardingTitleView = text(23f, BusTheme.text, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        onboardingBodyView = text(15f, BusTheme.muted).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.18f)
        }
        onboardingDiagnosticView = text(12f, BusTheme.dim).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12))
            visibility = View.GONE
        }
        onboardingActionView = text(17f, BusTheme.phosphor, bold = true).apply {
            minHeight = dp(58)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = outline(true)
        }
        onboardingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            visibility = View.GONE
            setBackgroundColor(BusTheme.glassesBg)
            setPadding(dp(24), dp(28), dp(24), dp(22))
            addView(onboardingStepView, matchWrap())
            addView(gap(22))
            addView(onboardingTitleView, matchWrap())
            addView(gap(24))
            addView(onboardingBodyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(onboardingDiagnosticView, matchWrap())
            addView(onboardingActionView, matchWrap())
            addView(gap(10))
            addView(
                text(11f, BusTheme.dim).apply {
                    // Swipe is filtered out during onboarding, so promising it here was a lie.
                    text = getString(R.string.onb_footer)
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                matchWrap(),
            )
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BusTheme.glassesBg)
            addView(
                onboardingView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        })
        renderScreen()
    }

    private fun applyHudTopInset(value: Int) {
        val inset = HudTopInset.sanitize(value)
        onboardingView.setPadding(dp(24), dp(28 + inset), dp(24), dp(22))
        onboardingView.requestLayout()
    }

    private fun renderScreen() {
        if (!::onboardingView.isInitialized) return
        val snapshot = SelfArmOnboardingStore.snapshot(applicationContext)
        onboardingState = SelfArmOnboardingStateMachine.evaluate(snapshot)
        val complete = onboardingState.stage == SelfArmOnboardingState.Stage.COMPLETE
        interactiveFlowActive = !complete
        if (complete) {
            // Land on a moment of confirmation rather than blinking straight to the launcher:
            // the wearer just did the one thing we asked of them and deserves to see it took.
            if (showSetupConfirmation()) return
            onboardingView.visibility = View.GONE
            openLauncherAndFinish()
            return
        }
        confirmationShownForSession = ""
        onboardingView.visibility = View.VISIBLE

        val diagnostic = onboardingState.diagnostic.takeIf {
            onboardingState.stage != SelfArmOnboardingState.Stage.RUNNING && it.isNotBlank()
        }
        onboardingDiagnosticView.text = diagnostic
            ?.let { getString(R.string.onb_support_code, it) }
            .orEmpty()
        onboardingDiagnosticView.visibility = if (diagnostic == null) View.GONE else View.VISIBLE

        when (onboardingState.stage) {
            SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY -> {
                onboardingStepView.setText(R.string.onb_accessibility_eyebrow)
                onboardingTitleView.setText(R.string.onb_accessibility_title)
                onboardingBodyView.setText(R.string.onb_accessibility_body)
                onboardingActionView.setText(R.string.onb_accessibility_action)
            }
            // Reaching this state means the automatic hand-off did not fire. It is a recovery
            // door, not the second half of a two-step wizard, so it no longer announces itself
            // as one.
            SelfArmOnboardingState.Stage.READY_FOR_WIRELESS,
            SelfArmOnboardingState.Stage.RUNNING,
            -> {
                onboardingStepView.setText(R.string.onb_running_eyebrow)
                onboardingTitleView.setText(R.string.onb_running_title)
                onboardingBodyView.text = getString(R.string.onb_running_body, phaseLabel(snapshot))
                onboardingActionView.text = if (
                    onboardingState.stage == SelfArmOnboardingState.Stage.READY_FOR_WIRELESS
                ) {
                    getString(R.string.onb_running_recovery_action)
                } else {
                    getString(R.string.onb_running_status)
                }
            }
            SelfArmOnboardingState.Stage.WAITING_FOR_WIFI -> {
                onboardingStepView.setText(R.string.onb_wifi_eyebrow)
                onboardingTitleView.setText(R.string.onb_wifi_title)
                onboardingBodyView.setText(R.string.onb_wifi_body)
                onboardingActionView.setText(R.string.onb_wifi_action)
            }
            SelfArmOnboardingState.Stage.MANUAL_REQUIRED -> {
                onboardingStepView.setText(R.string.onb_manual_eyebrow)
                onboardingTitleView.setText(R.string.onb_manual_title)
                onboardingBodyView.setText(R.string.onb_manual_body)
                onboardingActionView.setText(R.string.onb_manual_action)
            }
            SelfArmOnboardingState.Stage.FAILED -> {
                onboardingStepView.setText(R.string.onb_failed_eyebrow)
                onboardingTitleView.setText(R.string.onb_failed_title)
                onboardingBodyView.text = getString(
                    R.string.onb_failed_body,
                    reasonLabel(onboardingState.detail),
                )
                onboardingActionView.setText(R.string.onb_failed_action)
            }
            SelfArmOnboardingState.Stage.UNSUPPORTED -> {
                onboardingStepView.setText(R.string.onb_unsupported_eyebrow)
                onboardingTitleView.setText(R.string.onb_unsupported_title)
                onboardingBodyView.setText(R.string.onb_unsupported_body)
                onboardingActionView.setText(R.string.onb_unsupported_action)
            }
            SelfArmOnboardingState.Stage.COMPLETE -> Unit
        }
        // A state with nothing to tap is a status line, not a dimmed button: drop the outline so
        // the wearer never sits there pressing something that was never going to answer.
        val actionable = onboardingState.action != SelfArmOnboardingState.Action.NONE &&
            onboardingState.stage != SelfArmOnboardingState.Stage.RUNNING
        onboardingActionView.background = if (actionable) outline(true) else null
        onboardingActionView.setTextColor(if (actionable) BusTheme.phosphor else BusTheme.muted)
        onboardingActionView.alpha = if (actionable) 1f else 0.85f
    }

    /** Returns true while the confirmation panel owns the screen. */
    private fun showSetupConfirmation(): Boolean {
        val sessionId = SelfArmOnboardingStore.currentSessionId(applicationContext)
            .ifBlank { CONFIRMATION_SESSIONLESS }
        if (confirmationShownForSession == sessionId) return false
        if (onboardingView.visibility != View.VISIBLE) {
            // Already on the launcher (a resume, a reboot) — nothing was just completed on screen.
            confirmationShownForSession = sessionId
            return false
        }
        confirmationShownForSession = sessionId
        onboardingStepView.setText(R.string.onb_done_eyebrow)
        onboardingTitleView.setText(R.string.onb_done_title)
        onboardingBodyView.setText(R.string.onb_done_body)
        onboardingDiagnosticView.visibility = View.GONE
        onboardingActionView.text = ""
        onboardingActionView.background = null
        onboardingView.visibility = View.VISIBLE
        onboardingView.postDelayed({ renderScreen() }, SETUP_CONFIRMATION_MS)
        return true
    }

    private fun phaseLabel(snapshot: SelfArmOnboardingSnapshot): String = getString(
        when (snapshot.stage) {
            SetupStage.WAITING_FOR_ACCESSIBILITY -> R.string.onb_phase_waiting_for_accessibility
            SetupStage.ENABLING_WIFI -> R.string.onb_phase_enabling_wifi
            SetupStage.WAITING_FOR_WIFI -> R.string.onb_phase_waiting_for_wifi
            SetupStage.ENABLING_DEVELOPER_OPTIONS -> R.string.onb_phase_enabling_developer_options
            SetupStage.OPENING_WIRELESS_DEBUGGING -> R.string.onb_phase_opening_wireless_debugging
            SetupStage.READING_PAIRING_DIALOG -> R.string.onb_phase_reading_pairing_dialog
            SetupStage.PAIRING_LOCALLY -> R.string.onb_phase_pairing_locally
            SetupStage.PAIRING_VIA_PHONE -> R.string.onb_phase_pairing_via_phone
            SetupStage.ARMING -> R.string.onb_phase_arming
            SetupStage.COMPLETE -> R.string.onb_phase_complete
            SetupStage.MANUAL_REQUIRED -> R.string.onb_phase_manual_required
            SetupStage.FAILED -> R.string.onb_phase_failed
            else -> R.string.onb_phase_unknown
        },
    )

    private fun reasonLabel(failureState: String): String = getString(
        when {
            failureState == SelfArmOnboardingStore.LEASE_EXPIRED_FAILURE ->
                R.string.onb_reason_lease_expired
            failureState.contains("wifi") -> R.string.onb_reason_wifi_required
            failureState.contains("pairing_code_expired") -> R.string.onb_reason_pairing_expired
            failureState.contains("wireless_setup_timeout") -> R.string.onb_reason_wireless_timeout
            failureState.contains("wireless_debugging_manual_step") ->
                R.string.onb_reason_wireless_manual_step
            failureState.contains("developer_options_manual_step") ->
                R.string.onb_reason_developer_manual_step
            failureState.contains("accessibility_settings_unavailable") ->
                R.string.onb_reason_accessibility_unavailable
            failureState.contains("manual_pairing_timeout") ->
                R.string.onb_reason_phone_pairing_timeout
            failureState.contains("verification_failed") ->
                R.string.onb_reason_phone_verification_failed
            else -> R.string.onb_reason_generic
        },
    )

    /**
     * The app icon opens the same launcher as every other entry point, in the configured mode, and
     * gets out of the way: the launcher is the HUD's window, not this activity's.
     */
    private fun openLauncherAndFinish() {
        val opened = HudController.openLauncher(LauncherTrigger.APP_ICON)
        log("Launcher activity handed off to HUD opened=$opened")
        finish()
    }

    private fun performOnboardingAction() {
        when (onboardingState.action) {
            SelfArmOnboardingState.Action.OPEN_ACCESSIBILITY -> {
                val sessionId = SelfArmOnboardingStore.beginSession(applicationContext)
                SelfArmOnboardingStore.markAwaitingAccessibility(applicationContext)
                val landing = SelfArmAccessibilityHandoff.open(this)
                if (landing == SelfArmAccessibilityHandoff.Landing.UNAVAILABLE) {
                    SelfArmOnboardingStore.finish(
                        context = applicationContext,
                        sessionId = sessionId,
                        setupState = "accessibility_settings_unavailable",
                        success = false,
                    )
                }
            }
            SelfArmOnboardingState.Action.START_WIRELESS,
            SelfArmOnboardingState.Action.RETRY_WIRELESS,
            -> {
                val sessionId = SelfArmOnboardingStore.beginSession(applicationContext)
                if (!RokidBusAccessibilityService.requestWirelessBootstrap(applicationContext)) {
                    SelfArmOnboardingStore.reportProgress(
                        applicationContext,
                        sessionId,
                        "waiting_for_nexus_accessibility",
                    )
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .setPackage("com.android.settings"),
                        )
                    }
                }
            }
            SelfArmOnboardingState.Action.OPEN_WIFI_PANEL -> {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                            .setPackage("com.android.settings"),
                    )
                }
            }
            SelfArmOnboardingState.Action.OPEN_MANUAL_FALLBACK -> {
                SelfArmOnboardingStore.beginSession(applicationContext)
                RokidBusAccessibilityService.requestManualAction(
                    applicationContext,
                    SelfArmManualAction.OPEN_PAIRING_DIALOG,
                )
            }
            SelfArmOnboardingState.Action.NONE -> Unit
        }
        renderScreen()
    }

    private fun requestBluetoothConnectIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Photo sync reads /sdcard/DCIM/Camera; without this grant the sync engine reports
            // "storage permission needed" to the phone instead of silently syncing nothing.
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun gap(value: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(value))
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun outline(selected: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(if (selected) 2 else 1), if (selected) BusTheme.phosphor else BusTheme.hairline)
            cornerRadius = dp(4).toFloat()
        }

    private fun dp(value: Int): Int =
        BusTheme.dp(this, value)

    companion object {
        @Volatile private var resumedInstance: MainActivity? = null
        // Alive (created, not yet destroyed) whether resumed, paused, or stopped —
        // unlike resumedInstance, which clears the moment something else takes the
        // foreground. That gap is exactly the window a plugin surface opens and
        // closes in, and this app has no other idle screen for Android to fall back
        // on when that surface's own task disappears: it re-resumes whatever task
        // is next in line, which is this one if it is still sitting there paused.
        @Volatile private var liveInstance: MainActivity? = null
        @Volatile private var interactiveFlowActive = true

        internal fun isResumed(): Boolean = resumedInstance != null

        internal fun isInteractiveFlowActive(): Boolean =
            resumedInstance != null && interactiveFlowActive

        /**
         * Called right before a plugin surface takes the display. A paused
         * MainActivity sitting behind it is not worth keeping as a fallback
         * task — mid onboarding it is the setup screen itself, so this leaves
         * it alone there.
         */
        internal fun finishIfStale() {
            if (interactiveFlowActive) return
            liveInstance?.takeIf { it !== resumedInstance }?.finish()
        }

        /** Long enough to read four words, short enough that nobody waits on it. */
        const val SETUP_CONFIRMATION_MS = 1_600L
        const val CONFIRMATION_SESSIONLESS = "-"
    }
}
