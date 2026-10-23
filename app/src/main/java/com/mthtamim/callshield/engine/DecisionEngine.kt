package com.mthtamim.callshield.engine

/** Which whitelist the master rule uses (features 1 and 2). */
enum class BlockMode { CONTACTS, FAVORITES }

/** Separate rule for hidden / private numbers (feature 3). */
enum class HiddenRule { BLOCK, ALLOW, WARN }

/**
 * Feature 48 (v1.1): how a block is carried out. REJECT is the original
 * behaviour (disallow + reject). SILENCE lets the call through to voicemail
 * without ringing (CallResponse.setSilenceCall). WARN_ONLY lets the call
 * ring and only posts a warning. Every profile and every block rule can
 * pick one; the default is REJECT everywhere, so nothing changes unless
 * the user opts in.
 */
enum class RuleAction { REJECT, SILENCE, WARN_ONLY }

/** Result of a per-number / per-group time-window check (feature 24). */
enum class WindowState { INSIDE, OUTSIDE }

/** Every outcome carries one of these so the log can explain itself (feature 29). */
enum class Reason {
    DISABLED,
    HIDDEN_BLOCKED,
    HIDDEN_ALLOWED,
    HIDDEN_WARN,
    BLACKLIST,
    WHITELIST,
    PREFIX_BLOCK,
    PREFIX_ALLOW,
    CONTACT,
    FAVORITE,
    EXPECTED_CALL,
    TEMP_ALLOW,
    CALLBACK,
    REPEAT_CALLER,
    ALLOW_NEXT,
    NOT_IN_CONTACTS,
    NOT_FAVORITE,
    // Part 2 additions
    EMERGENCY,
    HOTLINE,
    WILDCARD_BLOCK,
    WILDCARD_ALLOW,
    INTERNATIONAL_BLOCK,
    GROUP_ALLOW,
    TIME_WINDOW_ALLOW,
    TIME_WINDOW_BLOCK,
    PROFILE_OFF,
    // v1.1 additions
    SMS_CORRESPONDENT,
    WANGIRI
}

/** A matched prefix rule (feature 19, also used by feature 11). */
data class PrefixMatch(
    val prefix: String,
    val block: Boolean,
    /** Feature 48: how a block from this rule is carried out. */
    val action: RuleAction = RuleAction.REJECT
)

/** A matched wildcard rule (feature 20). */
data class WildcardMatch(
    val pattern: String,
    val block: Boolean,
    /** Feature 48: how a block from this rule is carried out. */
    val action: RuleAction = RuleAction.REJECT
)

/**
 * Everything the engine needs, gathered by the screening service from
 * Android APIs and local storage. The engine itself is pure: same facts and
 * settings in, same decision out, so it can be unit-tested without a phone.
 *
 * Part 2 facts are defaulted so Part 1 call sites keep compiling and keep
 * behaving exactly as before when the new features are unused.
 */
data class CallFacts(
    /** Null means a hidden / private number. */
    val number: String?,
    val inContacts: Boolean,
    val isFavorite: Boolean,
    val blacklisted: Boolean,
    val whitelisted: Boolean,
    /** Longest matching prefix rule, or null when no rule matches. */
    val prefixMatch: PrefixMatch?,
    val expectedMatch: Boolean,
    val tempAllowActive: Boolean,
    val allowNextArmed: Boolean,
    /** Blocked attempts from this number inside the repeat window, before this call. */
    val blockedAttemptsInRepeatWindow: Int,
    val callbackWithinWindow: Boolean,
    // ---- Part 2 ----
    /** User-marked emergency contact (feature 16). */
    val emergency: Boolean = false,
    /** Built-in / user hotline allowlist match (feature 22). */
    val hotline: Boolean = false,
    /** Most specific matching wildcard rule (feature 20). */
    val wildcardMatch: WildcardMatch? = null,
    /** Clearly non-local number (feature 21). */
    val isInternational: Boolean = false,
    /** Caller belongs to a contact group the user allows (feature 23). */
    val groupAllowed: Boolean = false,
    /** A time-window rule matches this caller (feature 24). */
    val windowState: WindowState? = null,
    /** Repeat-bypass passes already used by this number today (feature 36). */
    val repeatBypassUsedToday: Int = 0,
    // ---- v1.1 ----
    /** Feature 48: the block action chosen for this number's blacklist entry. */
    val blacklistAction: RuleAction = RuleAction.REJECT,
    /** Feature 50: the screen is on and the device unlocked right now. */
    val screenOnUnlocked: Boolean = false,
    /** Feature 52: the user has exchanged SMS with this number (toggle on). */
    val smsCorrespondent: Boolean = false,
    /** Feature 53: this number matches the one-ring (wangiri) pattern. */
    val wangiriSuspect: Boolean = false
)

data class EngineSettings(
    val masterEnabled: Boolean,
    val mode: BlockMode,
    val hiddenRule: HiddenRule,
    val repeatEnabled: Boolean,
    /** The attempt number (counting this call) that is let through by the repeat bypass. */
    val repeatCount: Int,
    // ---- Part 2 ----
    /** The active profile resolved everything into this flag already; OFF means allow all. */
    val profileOff: Boolean = false,
    /** Block clearly international numbers outright (feature 21). */
    val intlBlock: Boolean = false,
    /** Daily cap on repeat-bypass passes per number (feature 36). */
    val repeatDailyCap: Int = 3,
    // ---- v1.1 ----
    /** Feature 48: the active profile's default action for blocked calls. */
    val blockAction: RuleAction = RuleAction.REJECT,
    /** Feature 50: soften blocks to warnings while the screen is on. */
    val softScreenOn: Boolean = false
)

data class Decision(
    val allowed: Boolean,
    val reason: Reason,
    /** Let the call through but raise a warning notification (hidden-number WARN rule). */
    val warn: Boolean = false,
    /** The one-shot "allow next unknown call" flag was spent on this call. */
    val consumeAllowNext: Boolean = false,
    /**
     * Feature 48: how this decision is carried out. For blocks it is the
     * resolved REJECT / SILENCE / WARN_ONLY; a WARN_ONLY block has already
     * been converted to allowed=true by the engine (the call rings, the
     * service posts the warning). SILENCE keeps allowed=false and tells
     * the service to answer with setSilenceCall instead of a rejection.
     */
    val action: RuleAction = RuleAction.REJECT
)

object DecisionEngine {

    /**
     * The public entry point: the base rule chain, then the v1.1
     * post-processing (per-rule action conversion, screen-on softening,
     * wangiri relabelling). Pure, like the chain itself.
     */
    fun decide(f: CallFacts, s: EngineSettings): Decision {
        var d = decideBase(f, s)
        if (!d.allowed) {
            // Feature 53: a one-ring suspect that is blocked anyway gets
            // the distinct reason so the log and notifications can say why
            // this number deserves extra care.
            if (f.wangiriSuspect) {
                d = d.copy(reason = Reason.WANGIRI)
            }
            when {
                // Feature 48: WARN_ONLY blocks ring with a warning.
                d.action == RuleAction.WARN_ONLY ->
                    d = d.copy(allowed = true, warn = true)
                // Feature 50: while the user is actively on the phone, a
                // plain rejection softens into a warning (explicit SILENCE
                // choices are already soft and stay untouched).
                d.action == RuleAction.REJECT && s.softScreenOn && f.screenOnUnlocked ->
                    d = d.copy(allowed = true, warn = true, action = RuleAction.WARN_ONLY)
            }
        } else if (f.wangiriSuspect) {
            // Allowed, but flagged: the service posts the wangiri warning.
            d = d.copy(warn = true)
        }
        return d
    }

    private fun decideBase(f: CallFacts, s: EngineSettings): Decision {
        if (!s.masterEnabled) {
            return Decision(true, Reason.DISABLED)
        }
        // Feature 14: the OFF profile lets everything ring, but keeps its own
        // reason so the log can tell it apart from the master switch (6).
        if (s.profileOff) {
            return Decision(true, Reason.PROFILE_OFF)
        }

        // Hidden / private numbers follow their own rule (feature 3).
        if (f.number == null) {
            return when (s.hiddenRule) {
                HiddenRule.BLOCK ->
                    Decision(false, Reason.HIDDEN_BLOCKED, action = s.blockAction)
                HiddenRule.ALLOW -> Decision(true, Reason.HIDDEN_ALLOWED)
                HiddenRule.WARN -> Decision(true, Reason.HIDDEN_WARN, warn = true)
            }
        }

        // Blacklist beats everything, including Contacts and Favorites (feature 5).
        if (f.blacklisted) {
            return Decision(false, Reason.BLACKLIST, action = f.blacklistAction)
        }

        // Emergency contacts bypass every profile and mode (feature 16).
        // They sit below the blacklist on purpose: an explicit user block
        // still wins over the emergency mark.
        if (f.emergency) {
            return Decision(true, Reason.EMERGENCY)
        }

        // The user's own whitelist beats the master rule.
        if (f.whitelisted) {
            return Decision(true, Reason.WHITELIST)
        }

        // Built-in service hotlines (feature 22): real bank / operator /
        // emergency lines must not die on the unknown-number rule.
        if (f.hotline) {
            return Decision(true, Reason.HOTLINE)
        }

        // Prefix rules (feature 19) and wildcard rules (feature 20): the
        // screening service has already kept only the more specific of the
        // two matches, nulling the weaker one.
        val pm = f.prefixMatch
        if (pm != null) {
            return if (pm.block) {
                Decision(false, Reason.PREFIX_BLOCK, action = pm.action)
            } else {
                Decision(true, Reason.PREFIX_ALLOW)
            }
        }
        val wm = f.wildcardMatch
        if (wm != null) {
            return if (wm.block) {
                Decision(false, Reason.WILDCARD_BLOCK, action = wm.action)
            } else {
                Decision(true, Reason.WILDCARD_ALLOW)
            }
        }

        // International rule (feature 21).
        if (s.intlBlock && f.isInternational) {
            return Decision(false, Reason.INTERNATIONAL_BLOCK, action = s.blockAction)
        }

        // Time windows (feature 24): a caller with a window rule may ring
        // only inside the window. This is an explicit per-number rule, so it
        // wins over the master rule and over Smart Allow.
        val ws = f.windowState
        if (ws != null) {
            return if (ws == WindowState.INSIDE) {
                Decision(true, Reason.TIME_WINDOW_ALLOW)
            } else {
                Decision(false, Reason.TIME_WINDOW_BLOCK, action = s.blockAction)
            }
        }

        // Contact-group allow (feature 23): group members ring even when the
        // master mode would not pass them (for example Favorites-only mode).
        if (f.groupAllowed) {
            return Decision(true, Reason.GROUP_ALLOW)
        }

        // The master whitelist rule (features 1 and 2).
        val contactPass = when (s.mode) {
            BlockMode.CONTACTS -> f.inContacts
            BlockMode.FAVORITES -> f.isFavorite
        }
        if (contactPass) {
            val reason = if (s.mode == BlockMode.FAVORITES) Reason.FAVORITE else Reason.CONTACT
            return Decision(true, reason)
        }

        // Smart Allow (features 7-11): an unknown caller can still get through
        // when the user has explicitly prepared for it.
        if (f.expectedMatch) {
            return Decision(true, Reason.EXPECTED_CALL)
        }
        if (f.tempAllowActive) {
            return Decision(true, Reason.TEMP_ALLOW)
        }
        if (f.callbackWithinWindow) {
            return Decision(true, Reason.CALLBACK)
        }
        // Feature 52 (v1.1): a number the user already exchanges SMS with
        // counts as known, when the user turned that on.
        if (f.smsCorrespondent) {
            return Decision(true, Reason.SMS_CORRESPONDENT)
        }
        // Feature 36: the repeat bypass has a daily cap per number, so a
        // spammer redialling all day cannot turn it into an open door.
        if (s.repeatEnabled && s.repeatCount >= 2 &&
            f.blockedAttemptsInRepeatWindow >= s.repeatCount - 1 &&
            f.repeatBypassUsedToday < s.repeatDailyCap
        ) {
            return Decision(true, Reason.REPEAT_CALLER)
        }
        if (f.allowNextArmed) {
            return Decision(true, Reason.ALLOW_NEXT, consumeAllowNext = true)
        }

        val blockReason = if (s.mode == BlockMode.FAVORITES) {
            Reason.NOT_FAVORITE
        } else {
            Reason.NOT_IN_CONTACTS
        }
        return Decision(false, blockReason, action = s.blockAction)
    }
}
