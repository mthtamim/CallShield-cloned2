package com.mthtamim.callshield.screening

import android.app.KeyguardManager
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import com.mthtamim.callshield.R
import com.mthtamim.callshield.contacts.ContactsHelper
import com.mthtamim.callshield.data.DbHelper
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.engine.CallFacts
import com.mthtamim.callshield.engine.DecisionEngine
import com.mthtamim.callshield.engine.Heuristics
import com.mthtamim.callshield.engine.Reason
import com.mthtamim.callshield.engine.RuleAction
import com.mthtamim.callshield.engine.Wangiri
import com.mthtamim.callshield.notify.AutoSms
import com.mthtamim.callshield.notify.BlockActions
import com.mthtamim.callshield.notify.PostCallScheduler
import com.mthtamim.callshield.ui.Perms
import com.mthtamim.callshield.util.Numbers
import com.mthtamim.callshield.widget.WidgetUpdater
import java.util.Calendar

/**
 * The blocking entry point. Android calls [onScreenCall] for every incoming
 * call before it rings; the pure [DecisionEngine] decides, this class only
 * gathers facts and carries the decision out.
 *
 * Part 2: facts now also cover profiles (folded into the engine settings by
 * the store), emergency contacts, hotlines, wildcard rules, the
 * international rule, contact groups, time windows, the repeat-bypass daily
 * cap, and a behaviour heuristic that warns even when a call is let through.
 *
 * v1.1: facts also cover the per-rule block action (48), the screen state
 * (50), SMS correspondents (52) and the one-ring wangiri pattern (53);
 * applying a decision now also covers silenced blocks (48), the auto-SMS
 * reply (49), the post-call marker (51, 55), silent notification mode
 * (58), widget refresh on block (56) and the log auto-clean sweep (57).
 *
 * Note on feature 17 (per-SIM rules): Call.Details (the onScreenCall
 * parameter type) does
 * not expose which phone account / SIM an incoming call arrived on, so no
 * per-SIM fact can be gathered here. The stored per-SIM preferences stay a
 * UI-level record and screening falls back to the global rule — see the
 * honest note in the manual.
 */
class ShieldCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Only incoming calls are screened; outgoing calls pass untouched.
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val store = Store(this)
        val now = System.currentTimeMillis()

        val raw = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val numKey = raw?.let { Numbers.key(it) }

        val hasContacts = Perms.hasContacts(this)
        val contactInfo = if (raw != null && hasContacts) {
            ContactsHelper.lookup(this, raw)
        } else {
            null
        }
        val groupTitles = if (raw != null && hasContacts && contactInfo?.inContacts == true) {
            ContactsHelper.groupsOf(this, raw)
        } else {
            emptySet()
        }

        // Prefix (19) vs wildcard (20): keep only the more specific match.
        var prefixMatch = raw?.let { store.longestPrefixMatch(it) }
        var wildcardMatch = raw?.let { store.bestWildcardMatch(it) }
        if (prefixMatch != null && wildcardMatch != null) {
            val prefixSpec = Numbers.localForm(prefixMatch.prefix).length
            val wildSpec = wildcardMatch.pattern.count { it.isDigit() }
            if (wildSpec > prefixSpec) {
                prefixMatch = null
            } else {
                wildcardMatch = null
            }
        }

        val isBlacklisted = numKey != null &&
            store.isListed(numKey, DbHelper.TYPE_BLACKLIST)

        // Feature 53: probe the one-ring pattern from the internal log
        // (attempts already recorded; this call is not logged yet).
        val wangiriWindowStart = now - Wangiri.WINDOW_MS
        val wangiriSuspect = numKey != null && raw != null && Wangiri.isSuspect(
            store.attemptTimesSince(numKey, wangiriWindowStart),
            ContactsHelper.hasRealConversation(this, raw, wangiriWindowStart)
        )

        val dayStart = startOfToday(now)
        val repeatWindowMs = store.repeatWindowMinutes() * 60_000L
        val facts = CallFacts(
            number = raw,
            inContacts = contactInfo?.inContacts == true,
            isFavorite = contactInfo?.isFavorite == true,
            blacklisted = isBlacklisted,
            whitelisted = numKey != null &&
                store.isListed(numKey, DbHelper.TYPE_WHITELIST),
            prefixMatch = prefixMatch,
            expectedMatch = raw != null && store.expectedMatch(raw),
            tempAllowActive = store.tempAllowActive(now),
            allowNextArmed = store.allowNextArmed(),
            blockedAttemptsInRepeatWindow =
                store.blockedCountSince(numKey, now - repeatWindowMs),
            callbackWithinWindow = raw != null && Perms.hasCallLog(this) &&
                ContactsHelper.calledWithin(this, raw, store.callbackHours(), now),
            // ---- Part 2 facts ----
            emergency = numKey != null &&
                store.isListed(numKey, DbHelper.TYPE_EMERGENCY),
            hotline = raw != null && store.isHotline(raw),
            wildcardMatch = wildcardMatch,
            isInternational = raw != null && Numbers.isInternational(raw),
            groupAllowed = groupTitles.any { store.allowedGroups().contains(it) },
            windowState = store.windowStateFor(numKey, groupTitles, now),
            repeatBypassUsedToday =
                store.countByReasonSince(Reason.REPEAT_CALLER.name, numKey, dayStart),
            // ---- v1.1 facts ----
            blacklistAction = if (isBlacklisted && numKey != null) {
                store.blacklistActionFor(numKey)
            } else {
                RuleAction.REJECT
            },
            screenOnUnlocked = isScreenOnUnlocked(),
            smsCorrespondent = raw != null && store.smsKnownOn() &&
                Perms.hasSms(this) && ContactsHelper.hasSmsHistory(this, raw),
            wangiriSuspect = wangiriSuspect
        )

        val decision = DecisionEngine.decide(facts, store.engineSettings())

        if (decision.consumeAllowNext) {
            store.setAllowNext(false)
        }
        if (raw != null && decision.reason == Reason.EXPECTED_CALL) {
            store.consumeExpectedIfFullNumber()
        }

        // Feature 29: every screened call is written to the internal log with
        // its reason, whether it was let through or blocked.
        store.logCall(
            numKey, raw, contactInfo?.displayName,
            decision.allowed, decision.reason, decision.warn, now
        )

        if (decision.allowed) {
            if (decision.warn) {
                when {
                    // Feature 53: a one-ring suspect gets its own warning.
                    decision.reason == Reason.WANGIRI ->
                        BlockActions.notifyWangiri(this, raw)
                    // Feature 48: a WARN_ONLY block rings, with the reason.
                    decision.action == RuleAction.WARN_ONLY ->
                        BlockActions.notifyWarnOnly(this, raw, decision.reason)
                    else -> BlockActions.notifyWarning(this)
                }
            }
            // Feature 34: even an allowed call earns a warning when the
            // behaviour is hammering (rapid retries / all-day persistence).
            if (numKey != null) {
                val rapid = store.attemptsCountSince(numKey, now - Heuristics.RAPID_WINDOW_MS)
                val today = store.attemptsCountSince(numKey, dayStart)
                if (Heuristics.isSuspicious(rapid, today)) {
                    BlockActions.notifyHeuristic(this, raw, today)
                }
            }
            // Features 51 and 55: an allowed call from a number the user
            // does not know leaves a marker; the post-call detector checks
            // how the call ended and offers a one-tap decision (and, for a
            // missed repeat-bypass call, an urgent reminder).
            val knownCaller = contactInfo?.inContacts == true ||
                decision.reason == Reason.WHITELIST ||
                decision.reason == Reason.EMERGENCY ||
                decision.reason == Reason.HOTLINE ||
                decision.reason == Reason.GROUP_ALLOW
            if (numKey != null && raw != null && !knownCaller &&
                decision.reason != Reason.DISABLED &&
                decision.reason != Reason.PROFILE_OFF
            ) {
                store.setPendingPostCall(
                    numKey, raw, now,
                    repeatBypass = decision.reason == Reason.REPEAT_CALLER
                )
                PostCallScheduler.scheduleCheck(
                    this, now + PostCallScheduler.CHECK_DELAY_MS
                )
            }
            respondToCall(callDetails, CallResponse.Builder().build())
        } else if (decision.action == RuleAction.SILENCE) {
            // Feature 48: the call is not rejected — it is silenced, so it
            // lands in voicemail without ever ringing.
            onBlocked(
                store, raw, contactInfo?.displayName, decision.reason, numKey, now,
                silenced = true
            )
            val response = CallResponse.Builder()
                .setSilenceCall(true)
                // Our own notification (with actions) replaces the system one.
                .setSkipNotification(true)
                .build()
            respondToCall(callDetails, response)
        } else {
            onBlocked(store, raw, contactInfo?.displayName, decision.reason, numKey, now)

            // Feature 49: the auto-SMS reply goes out only for a true
            // rejection (AutoSms applies its own toggle/permission/24 h
            // cooldown guards; hidden numbers are excluded by numKey null).
            AutoSms.maybeReply(this, raw, numKey, store)

            val response = CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                // Our own notification (with actions) replaces the system one.
                .setSkipNotification(true)
                .build()
            respondToCall(callDetails, response)
        }

        // Feature 57: the auto-clean sweep rides along with screening —
        // one indexed delete, only when the user configured a window.
        store.purgeOldLog(now)
    }

    /**
     * Shared blocked-call follow-up for REJECT and SILENCE (features 12,
     * 13, 56, 58): the per-block notification (or the silent-mode counter),
     * the retry-escalation summary, and the widget refresh.
     */
    private fun onBlocked(
        store: Store,
        raw: String?,
        displayName: String?,
        reason: Reason,
        numKey: String?,
        now: Long,
        silenced: Boolean = false
    ) {
        if (store.silentNotif()) {
            // Feature 58: no individual notification; the daily summary
            // reports the count (ScheduleReceiver folds it in).
            store.noteSilentBlock(raw)
        } else {
            // Feature 12: tell the user, with one-tap whitelist / keep actions.
            BlockActions.notifyBlocked(
                this, raw, displayName, reason,
                titleRes = if (silenced) {
                    R.string.notif_silenced_title
                } else {
                    R.string.notif_blocked_title
                }
            )
        }

        // Feature 13: the same number retrying again and again inside an
        // hour earns one summary notification, not one per attempt.
        if (store.escalationEnabled() && numKey != null) {
            val attempts = store.blockedCountSince(numKey, now - 3_600_000L)
            if (attempts >= store.escalationThreshold() &&
                store.claimEscalation(numKey, now)
            ) {
                BlockActions.notifyEscalation(this, raw, attempts)
            }
        }

        // Feature 56: the widget shows the new blocked-today count at once.
        WidgetUpdater.updateAll(this)
    }

    /** Feature 50's fact: screen on and no lock screen in the way. */
    private fun isScreenOnUnlocked(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        if (!pm.isInteractive) return false
        val km = getSystemService(KeyguardManager::class.java) ?: return true
        return !km.isKeyguardLocked
    }

    private fun startOfToday(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
