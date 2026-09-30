package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.hud.HudEffect.*
import com.anezium.rokidbus.glasses.hud.HudScreen.App
import com.anezium.rokidbus.glasses.hud.HudScreen.External
import com.anezium.rokidbus.glasses.hud.HudScreen.Hidden
import com.anezium.rokidbus.glasses.hud.HudScreen.Home
import com.anezium.rokidbus.glasses.hud.HudScreen.Opening

/**
 * The single owner of what the glasses show and who receives each key. Pure: no Android types, no
 * threads, no clock of its own. Time enters as `now` and leaves as [ScheduleDeadline]; the runner
 * answers with [HudEvent.DeadlineElapsed]. There are no "animation pending" states: every event is
 * handled against the current screen, whatever motion the views are still playing.
 */
class HudStateMachine(private val config: HudConfig = HudConfig()) {

    fun reduce(state: HudState, event: HudEvent, now: Long): Transition {
        val run = Run(state, now)
        run.handle(event)
        return run.finish(state, event)
    }

    private inner class Run(var s: HudState, val now: Long) {
        val fx = ArrayList<HudEffect>()

        fun handle(event: HudEvent) {
            when (event) {
                HudEvent.ServiceConnected -> onConnected()
                HudEvent.ServiceDestroyed -> onDestroyed()
                is HudEvent.LauncherEntriesChanged -> onEntries(event.entries, event.appearance)
                HudEvent.HostAttachFailed -> onHostAttachFailed()
                is HudEvent.ModeChanged -> s = s.copy(configuredMode = event.mode)
                is HudEvent.NoticeOwnsRingChanged -> s = s.copy(noticeOwnsRing = event.owns)
                else -> if (s.serviceConnected) online(event) else offline(event)
            }
        }

        // The service is down: no window exists, so nothing is drawn, but the surface the
        // controller keeps active must still be known for the reconnect (T30).
        fun offline(event: HudEvent) {
            when (event) {
                is HudEvent.SurfaceShown ->
                    s = s.copy(suspended = surfaceScreen(event.info(), event.displayPath, Origin.HIDDEN))
                is HudEvent.SurfaceHidden ->
                    if (surfaceInfo(s.suspended)?.surfaceId == event.surfaceId) s = s.copy(suspended = null)
                else -> Unit
            }
        }

        fun online(event: HudEvent) {
            when (event) {
                is HudEvent.Intent -> onIntent(event.intent)
                is HudEvent.SurfaceShown -> onSurfaceShown(event)
                is HudEvent.SurfaceInfoChanged -> onSurfaceInfoChanged(event)
                is HudEvent.SurfaceHidden -> onSurfaceHidden(event.surfaceId)
                is HudEvent.ExternalStarted -> onExternalStarted(event.kind)
                is HudEvent.ExternalEnded -> onExternalEnded(event.kind)
                is HudEvent.OpenFailed -> {
                    val sc = s.screen
                    if (sc is Opening && sc.openToken == event.token) restoreHome(sc, event.reason)
                }
                is HudEvent.DeadlineElapsed -> onDeadline(event.token)
                else -> Unit
            }
        }

        fun finish(before: HudState, event: HudEvent): Transition {
            val expectedToken = tokenOf(s.screen)
            val fired = event is HudEvent.DeadlineElapsed && event.token == before.deadlineToken
            if (before.deadlineToken != null && expectedToken == null && !fired) fx += CancelDeadline
            s = s.copy(deadlineToken = expectedToken)

            val hostBefore = hostAttached(before.screen)
            val hostAfter = hostAttached(s.screen)
            if (!hostBefore && hostAfter) fx.add(0, AttachHost)
            if (hostBefore && !hostAfter) fx += DetachHost

            val focus = s.ringFocus()
            if (focus != s.ringFocusPublished) {
                fx += PublishRingFocus(focus)
                s = s.copy(ringFocusPublished = focus)
            }
            return Transition(s, fx)
        }

        // ---- service lifecycle -------------------------------------------------------------

        fun onConnected() {
            // The runner drops focus broadcasts sent before it connected (F-25): republish.
            s = s.copy(serviceConnected = true, ringFocusPublished = false)
            val back = s.suspended
            if (back != null) {
                s = s.copy(suspended = null)
                val info = surfaceInfo(back)
                if (back is App && info != null) {
                    s = s.copy(screen = back)
                    fx += ShowApp(info.surfaceId)
                } else if (back is External) {
                    s = s.copy(screen = back)
                }
            }
        }

        fun onDestroyed() {
            // All windows are gone; the surface stays active in the controller (item 15).
            val keep = when (val sc = s.screen) {
                is App -> sc.copy(backToken = null)
                is External -> if (sc.kind == ExternalKind.ACTIVITY_SURFACE) sc.copy(backToken = null) else null
                is Home -> beneathSurface(sc.beneath)
                is Opening -> beneathSurface(sc.home.beneath)
                Hidden -> null
            }
            s = s.copy(screen = Hidden, serviceConnected = false, suspended = keep)
        }

        /**
         * A launcher with no window would own every key invisibly. Step back to what it was opened
         * over, or to Hidden, which releases the ring focus and lets keys reach the system again.
         */
        fun onHostAttachFailed() {
            val sc = s.screen
            val home = when (sc) { is Home -> sc; is Opening -> sc.home; else -> return }
            s = s.copy(screen = home.beneath?.takeIf { it is External } ?: Hidden)
        }

        fun beneathSurface(b: HudScreen?): HudScreen? = when {
            b is App -> b.copy(backToken = null)
            b is External && b.kind == ExternalKind.ACTIVITY_SURFACE -> b.copy(backToken = null)
            else -> null
        }

        // ---- entries and selection ---------------------------------------------------------

        fun onEntries(raw: List<String>, appearance: Map<String, String>) {
            val list = raw.distinct()
            val sc = s.screen
            val home = when (sc) { is Home -> sc; is Opening -> sc.home; else -> null }
            if (list == s.entries) {
                if (appearance != s.entryAppearance) {
                    s = s.copy(entryAppearance = appearance)
                    if (home != null) fx += RefreshHomeEntries(list, home.selectedId)
                }
                return
            }
            val old = s.entries
            val base = home?.selectedId ?: s.lastSelectedId
            val sel = if (home != null || base != null) resolveSelection(old, list, base) else null
            s = s.copy(entries = list, entryAppearance = appearance, lastSelectedId = sel)
            when (sc) {
                is Home -> s = s.copy(screen = sc.copy(selectedId = sel))
                is Opening -> s = s.copy(screen = sc.copy(home = sc.home.copy(selectedId = sel)))
                else -> Unit
            }
            if (home != null) fx += RefreshHomeEntries(list, sel)
        }

        fun move(home: Home, step: Int) {
            val n = s.entries.size
            if (n == 0) return
            val idx = s.entries.indexOf(home.selectedId).let { if (it < 0) 0 else it }
            val next = s.entries[((idx + step) % n + n) % n]
            s = s.copy(screen = home.copy(selectedId = next), lastSelectedId = next)
            if (next != home.selectedId) fx += SetHomeSelection(next)
        }

        fun validSelection(): String? =
            s.lastSelectedId?.takeIf { it in s.entries } ?: s.entries.firstOrNull()

        // ---- intents -----------------------------------------------------------------------

        fun onIntent(intent: HudIntent) {
            if (intent is HudIntent.Raw && intent.key.keyCode == HudKeys.PROG_BLUE) {
                fx += PassToSystem
                return
            }
            when (val sc = s.screen) {
                Hidden -> onHiddenIntent(intent)
                is Home -> onHomeIntent(sc, intent)
                is Opening -> onOpeningIntent(sc, intent)
                is App -> onSurfaceIntent(sc, intent)
                is External ->
                    if (sc.kind == ExternalKind.ACTIVITY_SURFACE) onSurfaceIntent(sc, intent)
                    else onForeignIntent(sc, intent)
            }
        }

        fun onHiddenIntent(intent: HudIntent) {
            when (intent) {
                is HudIntent.OpenLauncher -> openHome(null)
                HudIntent.Dismiss -> {
                    val last = s.lastDismissAt
                    if (config.unclaimedBackGuardMs > 0 && last != null &&
                        now - last < config.unclaimedBackGuardMs
                    ) fx += SwallowBack else fx += PassToSystem
                }
                else -> fx += PassToSystem
            }
        }

        fun openHome(beneath: HudScreen?) {
            val sel = validSelection()
            val mode = s.configuredMode
            s = s.copy(
                screen = Home(mode, sel, beneath),
                lastSelectedId = sel ?: s.lastSelectedId,
            )
            fx += ShowHome(mode, sel, s.entries)
        }

        fun onHomeIntent(sc: Home, intent: HudIntent) {
            when (intent) {
                HudIntent.Next -> move(sc, +1)
                HudIntent.Prev -> move(sc, -1)
                HudIntent.Select -> select(sc)
                HudIntent.Dismiss -> {
                    s = s.copy(lastDismissAt = now)
                    leaveHome(sc.beneath)
                }
                is HudIntent.OpenLauncher ->
                    if (intent.trigger == LauncherTrigger.BROADCAST_TOGGLE) leaveHome(sc.beneath)
                // Everything else is consumed: the launcher owns input exclusively (item 28, 119).
                is HudIntent.Raw -> Unit
            }
        }

        fun select(sc: Home) {
            val id = sc.selectedId ?: return
            val token = newToken()
            val opening = Opening(id, token, now + config.openTimeoutMs, sc)
            // Opening the plugin again makes its next show that open's answer, not a late one.
            s = s.copy(
                screen = opening,
                lastSelectedId = id,
                cancelledOpen = s.cancelledOpen?.takeUnless { it.pluginId == id },
            )
            fx += ShowOpening(id)
            fx += if (id == CAMERA_ENTRY_ID) StartCamera(token) else SendLauncherOpen(id, token)
            fx += ScheduleDeadline(token, opening.deadline)
        }

        fun leaveHome(beneath: HudScreen?) {
            when (beneath) {
                null -> s = s.copy(screen = Hidden)
                is App -> {
                    s = s.copy(screen = beneath)
                    fx += ShowApp(beneath.surfaceId)
                }
                else -> s = s.copy(screen = beneath)
            }
        }

        fun onOpeningIntent(sc: Opening, intent: HudIntent) {
            when {
                intent == HudIntent.Dismiss -> {
                    // Cancel the pending open and stay on the launcher the wearer is looking at, so the
                    // next BACK is still ours (B1). The plugin's late answer is closed unseen until the
                    // open's own deadline (F-3, F-9).
                    s = s.copy(
                        screen = sc.home,
                        lastDismissAt = now,
                        cancelledOpen = CancelledOpen(sc.pluginId, sc.deadline),
                    )
                    fx += ShowHome(sc.home.mode, sc.home.selectedId, s.entries)
                }
                intent is HudIntent.OpenLauncher && intent.trigger == LauncherTrigger.BROADCAST_TOGGLE ->
                    leaveHome(sc.home.beneath)
                // A second Select must not send a second open; the rest is consumed.
                else -> Unit
            }
        }

        fun onSurfaceIntent(sc: HudScreen, intent: HudIntent) {
            val info = surfaceInfo(sc) ?: return
            when (intent) {
                HudIntent.Next, HudIntent.Prev, HudIntent.Select, is HudIntent.Raw ->
                    fx += ForwardToApp(info.surfaceId, intent)
                HudIntent.Dismiss -> {
                    s = s.copy(lastDismissAt = now)
                    if (info.handlesBack) {
                        fx += ForwardToApp(info.surfaceId, intent)
                        if (backTokenOf(sc) == null) {
                            val token = newToken()
                            s = s.copy(screen = withBackToken(sc, token))
                            fx += ScheduleDeadline(token, now + config.backFailsafeMs)
                        }
                    } else {
                        fx += CloseApp(info.surfaceId, CloseReason.WEARER_DISMISSED)
                        leaveTo(originOf(sc))
                    }
                }
                is HudIntent.OpenLauncher ->
                    if (!(intent.trigger == LauncherTrigger.TRIPLE_TAP && info.editable)) {
                        openHome(withBackToken(sc, null))
                    }
            }
        }

        fun onForeignIntent(sc: External, intent: HudIntent) {
            if (intent is HudIntent.OpenLauncher) openHome(sc) else fx += PassToExternal(sc.kind)
        }

        // ---- surfaces ----------------------------------------------------------------------

        fun HudEvent.SurfaceShown.info() = SurfaceInfo(surfaceId, ownerPluginId, handlesBack, editable)

        fun onSurfaceShown(e: HudEvent.SurfaceShown) {
            val info = e.info()
            if (answersCancelledOpen(info)) {
                fx += CloseApp(info.surfaceId, CloseReason.OPEN_CANCELLED)
                return
            }
            when (val sc = s.screen) {
                Hidden -> showSurface(info, e.displayPath, Origin.HIDDEN)
                is Home -> {
                    val beneath = sc.beneath
                    if (beneath != null && surfaceInfo(beneath)?.surfaceId == info.surfaceId) {
                        val moved = surfaceScreen(info, e.displayPath, originOf(beneath), null)
                        s = s.copy(screen = sc.copy(beneath = moved))
                    } else {
                        // Unsolicited: the launcher steps aside and claims no return (F-3).
                        showSurface(info, e.displayPath, inheritOrigin(beneath, info))
                    }
                }
                is Opening ->
                    if (matchesOpen(sc.pluginId, info)) showSurface(info, e.displayPath, Origin.HOME)
                    else showSurface(info, e.displayPath, inheritOrigin(sc.home.beneath, info))
                is App -> replaceOrUpdate(sc, info, e.displayPath)
                is External ->
                    if (sc.kind == ExternalKind.ACTIVITY_SURFACE) replaceOrUpdate(sc, info, e.displayPath)
                    else showSurface(info, e.displayPath, Origin.HIDDEN)
            }
        }

        /**
         * A show from the plugin of an open the wearer cancelled, inside that open's deadline, and not
         * an update of a surface already on screen. Past the deadline the record is dropped and the
         * show is unsolicited like any other.
         */
        fun answersCancelledOpen(info: SurfaceInfo): Boolean {
            val cancelled = s.cancelledOpen ?: return false
            if (now >= cancelled.until) {
                s = s.copy(cancelledOpen = null)
                return false
            }
            if (!matchesOpen(cancelled.pluginId, info)) return false
            val visible = when (val sc = s.screen) {
                is Home -> sc.beneath
                is Opening -> sc.home.beneath
                else -> sc
            }
            return surfaceInfo(visible)?.surfaceId != info.surfaceId
        }

        fun replaceOrUpdate(sc: HudScreen, info: SurfaceInfo, path: DisplayPath) {
            val cur = surfaceInfo(sc)!!
            if (cur.surfaceId != info.surfaceId) {
                showSurface(info, path, inheritOrigin(sc, info))
                return
            }
            // Any show of the surface is the plugin's answer to a forwarded BACK (it navigated inside
            // itself), so the failsafe that would close it is disarmed.
            val next = surfaceScreen(info, path, originOf(sc), null)
            val kindChanged = (sc is App) != (next is App)
            s = s.copy(screen = next)
            if (kindChanged) fx += showEffect(next)
        }

        fun showSurface(info: SurfaceInfo, path: DisplayPath, origin: Origin) {
            val next = surfaceScreen(info, path, origin, null)
            s = s.copy(screen = next)
            fx += showEffect(next)
        }

        fun showEffect(sc: HudScreen): HudEffect = when (sc) {
            is App -> ShowApp(sc.surfaceId)
            else -> ShowActivitySurface(surfaceInfo(sc)!!.surfaceId)
        }

        /** A replacement keeps the return only when the same plugin replaces its own surface. */
        fun inheritOrigin(prev: HudScreen?, info: SurfaceInfo): Origin {
            val prevInfo = surfaceInfo(prev) ?: return Origin.HIDDEN
            val same = info.ownerPluginId != null && info.ownerPluginId == prevInfo.ownerPluginId
            return if (same) originOf(prev!!) else Origin.HIDDEN
        }

        fun matchesOpen(pluginId: String, info: SurfaceInfo): Boolean =
            info.ownerPluginId == pluginId ||
                (info.ownerPluginId == null &&
                    (info.surfaceId == pluginId || info.surfaceId.startsWith("$pluginId:")))

        fun onSurfaceInfoChanged(e: HudEvent.SurfaceInfoChanged) {
            fun update(sc: HudScreen): HudScreen {
                val cur = surfaceInfo(sc)
                if (cur == null || cur.surfaceId != e.surfaceId) return sc
                val info = cur.copy(handlesBack = e.handlesBack, editable = e.editable)
                // An update is the plugin's answer to a forwarded BACK: disarm the failsafe.
                return when (sc) {
                    is App -> sc.copy(surface = info, backToken = null)
                    is External -> sc.copy(surface = info, backToken = null)
                    else -> sc
                }
            }
            s = s.copy(
                screen = when (val sc = s.screen) {
                    is Home -> sc.copy(beneath = sc.beneath?.let(::update))
                    is Opening -> sc.copy(home = sc.home.copy(beneath = sc.home.beneath?.let(::update)))
                    else -> update(sc)
                },
            )
        }

        fun onSurfaceHidden(id: String) {
            fun gone(b: HudScreen?): Boolean = surfaceInfo(b)?.surfaceId == id
            when (val sc = s.screen) {
                is App -> if (sc.surfaceId == id) leaveTo(sc.origin)
                is External ->
                    if (sc.kind == ExternalKind.ACTIVITY_SURFACE && sc.surface?.surfaceId == id) leaveTo(sc.origin)
                is Home -> if (gone(sc.beneath)) s = s.copy(screen = sc.copy(beneath = null))
                is Opening ->
                    if (gone(sc.home.beneath)) {
                        s = s.copy(screen = sc.copy(home = sc.home.copy(beneath = null)))
                    }
                Hidden -> Unit
            }
        }

        fun leaveTo(origin: Origin) {
            when (origin) {
                Origin.HIDDEN -> s = s.copy(screen = Hidden)
                Origin.HOME -> {
                    val sel = validSelection()
                    val mode = s.configuredMode
                    s = s.copy(screen = Home(mode, sel), lastSelectedId = sel ?: s.lastSelectedId)
                    fx += ShowHome(mode, sel, s.entries)
                }
            }
        }

        // ---- external content --------------------------------------------------------------

        fun onExternalStarted(kind: ExternalKind) {
            if (kind == ExternalKind.ACTIVITY_SURFACE) return
            when (val sc = s.screen) {
                is External -> if (sc.kind == kind) return
                is Opening ->
                    if (kind == ExternalKind.CAMERA && sc.pluginId == CAMERA_ENTRY_ID) {
                        // The launcher may have been opened over a surface; the camera takes the display.
                        closeVisibleSurfaces(sc)
                        s = s.copy(screen = External(kind, Origin.HOME))
                        return
                    }
                else -> Unit
            }
            // A trusted launch of foreign content steps Nexus aside (item 88); the wearer comes back
            // to a hidden state, not to a stale launcher or surface.
            closeVisibleSurfaces(s.screen)
            s = s.copy(screen = External(kind, Origin.HIDDEN))
        }

        fun closeVisibleSurfaces(sc: HudScreen) {
            val target = when (sc) {
                is Home -> sc.beneath
                is Opening -> sc.home.beneath
                else -> sc
            }
            val info = surfaceInfo(target) ?: return
            fx += CloseApp(info.surfaceId, CloseReason.SUPERSEDED)
        }

        fun onExternalEnded(kind: ExternalKind) {
            when (val sc = s.screen) {
                is External -> if (sc.kind == kind) leaveTo(sc.origin)
                is Home -> if ((sc.beneath as? External)?.kind == kind) s = s.copy(screen = sc.copy(beneath = null))
                is Opening ->
                    if ((sc.home.beneath as? External)?.kind == kind) {
                        s = s.copy(screen = sc.copy(home = sc.home.copy(beneath = null)))
                    }
                else -> Unit
            }
        }

        // ---- deadlines ---------------------------------------------------------------------

        fun onDeadline(token: Long) {
            when (val sc = s.screen) {
                is Opening -> if (sc.openToken == token) restoreHome(sc, OpenFailure.TIMEOUT)
                is App, is External -> {
                    val info = surfaceInfo(sc)
                    if (info != null && backTokenOf(sc) == token) {
                        fx += CloseApp(info.surfaceId, CloseReason.BACK_FAILSAFE)
                        leaveTo(originOf(sc))
                    }
                }
                else -> Unit
            }
        }

        fun restoreHome(sc: Opening, reason: OpenFailure) {
            s = s.copy(screen = sc.home)
            fx += ShowHome(sc.home.mode, sc.home.selectedId, s.entries)
            fx += ShowStatus(HudStatus.OpenFailed(sc.pluginId, reason))
        }

        fun newToken(): Long {
            val t = s.nextToken
            s = s.copy(nextToken = t + 1)
            return t
        }
    }

    private companion object {
        fun surfaceScreen(info: SurfaceInfo, path: DisplayPath, origin: Origin, backToken: Long? = null): HudScreen =
            if (path == DisplayPath.OVERLAY) App(info, origin, backToken)
            else External(ExternalKind.ACTIVITY_SURFACE, origin, info, backToken)

        fun surfaceInfo(sc: HudScreen?): SurfaceInfo? = when (sc) {
            is App -> sc.surface
            is External -> sc.surface
            else -> null
        }

        fun originOf(sc: HudScreen): Origin = when (sc) {
            is App -> sc.origin
            is External -> sc.origin
            else -> Origin.HIDDEN
        }

        fun backTokenOf(sc: HudScreen): Long? = when (sc) {
            is App -> sc.backToken
            is External -> sc.backToken
            else -> null
        }

        fun withBackToken(sc: HudScreen, token: Long?): HudScreen = when (sc) {
            is App -> sc.copy(backToken = token)
            is External -> sc.copy(backToken = token)
            else -> sc
        }

        fun tokenOf(sc: HudScreen): Long? = when (sc) {
            is Opening -> sc.openToken
            is App -> sc.backToken
            is External -> sc.backToken
            else -> null
        }

        fun hostAttached(sc: HudScreen): Boolean = sc is Home || sc is Opening || sc is App

        /**
         * Keeps the selected id if it survived. Otherwise the entry that slid into its slot: the
         * nearest survivor after it in the old order, else the nearest before it, else the first.
         */
        fun resolveSelection(old: List<String>, new: List<String>, sel: String?): String? {
            if (new.isEmpty()) return null
            if (sel == null || sel in new) return sel ?: new.first()
            val idx = old.indexOf(sel)
            if (idx < 0) return new.first()
            for (i in idx + 1 until old.size) if (old[i] in new) return old[i]
            for (i in idx - 1 downTo 0) if (old[i] in new) return old[i]
            return new.first()
        }
    }
}
