package com.mthtamim.callshield.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import com.mthtamim.callshield.calendar.CalendarHelper
import com.mthtamim.callshield.engine.BlockMode
import com.mthtamim.callshield.engine.EngineSettings
import com.mthtamim.callshield.engine.HiddenRule
import com.mthtamim.callshield.engine.PrefixMatch
import com.mthtamim.callshield.engine.Profile
import com.mthtamim.callshield.engine.Reason
import com.mthtamim.callshield.engine.RuleAction
import com.mthtamim.callshield.engine.ScheduleEntry
import com.mthtamim.callshield.engine.WildcardMatch
import com.mthtamim.callshield.engine.WindowMath
import com.mthtamim.callshield.engine.WindowState
import java.util.Calendar
import com.mthtamim.callshield.util.Numbers

/**
 * Single access point for settings (SharedPreferences) and the SQLite store.
 * Used by the screening service (per incoming call) and by every UI screen.
 */
class Store(context: Context) {

    private val appCtx: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val db = DbHelper(appCtx).writableDatabase

    // ------------------------------------------------------------- settings

    fun masterEnabled(): Boolean = prefs.getBoolean(KEY_MASTER, true)

    fun setMasterEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER, on).apply()
    }

    fun blockMode(): BlockMode =
        if (prefs.getString(KEY_MODE, "CONTACTS") == "FAVORITES") {
            BlockMode.FAVORITES
        } else {
            BlockMode.CONTACTS
        }

    fun setBlockMode(mode: BlockMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun hiddenRule(): HiddenRule = when (prefs.getString(KEY_HIDDEN, "BLOCK")) {
        "ALLOW" -> HiddenRule.ALLOW
        "WARN" -> HiddenRule.WARN
        else -> HiddenRule.BLOCK
    }

    fun setHiddenRule(rule: HiddenRule) {
        prefs.edit().putString(KEY_HIDDEN, rule.name).apply()
    }

    fun engineSettings(): EngineSettings {
        val now = System.currentTimeMillis()
        val profile = effectiveProfile(now)
        return EngineSettings(
            masterEnabled = masterEnabled(),
            mode = profileMode(profile),
            hiddenRule = profileHidden(profile),
            repeatEnabled = repeatEnabled(),
            repeatCount = repeatCount(),
            profileOff = profile == Profile.OFF,
            intlBlock = intlBlock(),
            repeatDailyCap = repeatDailyCap(),
            blockAction = profileAction(profile),
            softScreenOn = softScreenOn()
        )
    }

    // ------------------------------------------------- temporary allow (7)

    fun tempAllowUntil(): Long = prefs.getLong(KEY_TEMP_UNTIL, 0L)

    fun tempAllowActive(now: Long): Boolean = tempAllowUntil() > now

    fun startTempAllow(minutes: Int, now: Long) {
        prefs.edit().putLong(KEY_TEMP_UNTIL, now + minutes * 60_000L).apply()
    }

    fun cancelTempAllow() {
        prefs.edit().putLong(KEY_TEMP_UNTIL, 0L).apply()
    }

    // ---------------------------------------------- allow next unknown (10)

    fun allowNextArmed(): Boolean = prefs.getBoolean(KEY_ALLOW_NEXT, false)

    fun setAllowNext(armed: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_NEXT, armed).apply()
    }

    // --------------------------------------------------- expected call (11)

    fun expectedValue(): String = prefs.getString(KEY_EXPECTED, "") ?: ""

    fun setExpected(value: String) {
        prefs.edit().putString(KEY_EXPECTED, value).apply()
    }

    fun clearExpected() {
        prefs.edit().putString(KEY_EXPECTED, "").apply()
    }

    /** Called after a full-number expected value matched once: it is spent. */
    fun consumeExpectedIfFullNumber() {
        val v = expectedValue()
        if (v.isNotEmpty() && Numbers.digits(v).length >= 10) {
            clearExpected()
        }
    }

    fun expectedMatch(number: String): Boolean {
        val v = expectedValue()
        if (v.isEmpty()) return false
        return Numbers.startsWithPrefix(number, v)
    }

    // ------------------------------------------------------ callback (8)

    fun callbackHours(): Int = prefs.getInt(KEY_CALLBACK_HOURS, 24)

    fun setCallbackHours(hours: Int) {
        prefs.edit().putInt(KEY_CALLBACK_HOURS, hours).apply()
    }

    // ------------------------------------------------ repeat caller (9)

    fun repeatEnabled(): Boolean = prefs.getBoolean(KEY_REPEAT_ON, true)

    fun setRepeatEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_REPEAT_ON, on).apply()
    }

    fun repeatCount(): Int = prefs.getInt(KEY_REPEAT_COUNT, 2)

    fun setRepeatCount(count: Int) {
        prefs.edit().putInt(KEY_REPEAT_COUNT, count).apply()
    }

    fun repeatWindowMinutes(): Int = prefs.getInt(KEY_REPEAT_WINDOW, 3)

    fun setRepeatWindowMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_REPEAT_WINDOW, minutes).apply()
    }

    // ------------------------------------------- escalation summary (13)

    fun escalationEnabled(): Boolean = prefs.getBoolean(KEY_ESC_ON, true)

    fun setEscalationEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ESC_ON, on).apply()
    }

    fun escalationThreshold(): Int = prefs.getInt(KEY_ESC_THRESHOLD, 3)

    /** True (once) when this number last triggered a summary over an hour ago. */
    fun claimEscalation(numKey: String, now: Long): Boolean {
        val key = KEY_ESC_LAST_PREFIX + numKey
        val last = prefs.getLong(key, 0L)
        if (now - last < 3_600_000L) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }

    // ------------------------------------------------------------ UI prefs

    fun language(): String = prefs.getString(KEY_LANG, "bn") ?: "bn"

    fun setLanguage(lang: String) {
        prefs.edit().putString(KEY_LANG, lang).apply()
    }

    /** system / dark / light */
    fun themeMode(): String = prefs.getString(KEY_THEME, "system") ?: "system"

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME, mode).apply()
    }

    fun wizardDone(): Boolean = prefs.getBoolean(KEY_WIZARD_DONE, false)

    fun setWizardDone() {
        prefs.edit().putBoolean(KEY_WIZARD_DONE, true).apply()
    }

    // ------------------------------------------------------------ lists (4)

    fun isListed(numKey: String, type: Int): Boolean {
        db.query(
            "list_entries", arrayOf("_id"), "num_key=? AND type=?",
            arrayOf(numKey, type.toString()), null, null, null
        ).use { c -> return c.moveToFirst() }
    }

    fun addToList(rawNumber: String, label: String?, type: Int, source: String) {
        val key = Numbers.key(rawNumber)
        // One entry per number per list: re-adding updates it.
        db.delete("list_entries", "num_key=? AND type=?", arrayOf(key, type.toString()))
        val cv = ContentValues()
        cv.put("num_key", key)
        cv.put("raw_number", rawNumber)
        cv.put("label", label)
        cv.put("type", type)
        cv.put("source", source)
        cv.put("created", System.currentTimeMillis())
        db.insert("list_entries", null, cv)
    }

    fun removeListEntry(id: Long) {
        db.delete("list_entries", "_id=?", arrayOf(id.toString()))
    }

    fun listEntries(type: Int): List<ListEntry> {
        val out = ArrayList<ListEntry>()
        db.query(
            "list_entries",
            arrayOf("_id", "num_key", "raw_number", "label", "type", "source", "block_action"),
            "type=?", arrayOf(type.toString()), null, null, "created DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ListEntry(
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getInt(4), c.getString(5), c.getInt(6)
                    )
                )
            }
        }
        return out
    }

    /** Feature 48 (v1.1): set how one blacklist entry blocks. */
    fun setListEntryAction(id: Long, action: RuleAction) {
        val cv = ContentValues()
        cv.put("block_action", ruleActionToStored(action))
        db.update("list_entries", cv, "_id=?", arrayOf(id.toString()))
    }

    /** Feature 48 (v1.1): set a blacklist entry's action by number (backup restore). */
    fun setBlacklistActionFor(rawNumber: String, action: RuleAction) {
        val cv = ContentValues()
        cv.put("block_action", ruleActionToStored(action))
        db.update(
            "list_entries", cv, "num_key=? AND type=?",
            arrayOf(Numbers.key(rawNumber), DbHelper.TYPE_BLACKLIST.toString())
        )
    }

    /** Feature 48 (v1.1): the action stored on this number's blacklist entry. */
    fun blacklistActionFor(numKey: String): RuleAction {
        db.query(
            "list_entries", arrayOf("block_action"), "num_key=? AND type=?",
            arrayOf(numKey, DbHelper.TYPE_BLACKLIST.toString()), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) ruleActionFromStored(c.getInt(0)) else RuleAction.REJECT
        }
    }

    // ----------------------------------------------------- prefix rules (19)

    fun addPrefixRule(prefix: String, action: Int, label: String?) {
        val cv = ContentValues()
        cv.put("prefix", prefix)
        cv.put("action", action)
        cv.put("label", label)
        cv.put("created", System.currentTimeMillis())
        db.insert("prefix_rules", null, cv)
    }

    fun removePrefixRule(id: Long) {
        db.delete("prefix_rules", "_id=?", arrayOf(id.toString()))
    }

    fun prefixRules(): List<PrefixRule> {
        val out = ArrayList<PrefixRule>()
        db.query(
            "prefix_rules", arrayOf("_id", "prefix", "action", "label", "block_action"),
            null, null, null, null, "created DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PrefixRule(
                        c.getLong(0), c.getString(1), c.getInt(2),
                        c.getString(3), c.getInt(4)
                    )
                )
            }
        }
        return out
    }

    /** Feature 48 (v1.1): set a prefix rule's action by prefix (backup restore). */
    fun setPrefixActionFor(prefix: String, action: RuleAction) {
        val cv = ContentValues()
        cv.put("block_action", ruleActionToStored(action))
        db.update("prefix_rules", cv, "prefix=?", arrayOf(prefix))
    }

    /** Feature 48 (v1.1): set how one prefix rule blocks. */
    fun setPrefixRuleAction(id: Long, action: RuleAction) {
        val cv = ContentValues()
        cv.put("block_action", ruleActionToStored(action))
        db.update("prefix_rules", cv, "_id=?", arrayOf(id.toString()))
    }

    /** The longest matching prefix rule wins, so specific rules beat broad ones. */
    fun longestPrefixMatch(number: String): PrefixMatch? {
        var best: PrefixRule? = null
        for (rule in prefixRules()) {
            if (Numbers.startsWithPrefix(number, rule.prefix)) {
                val current = best
                if (current == null ||
                    Numbers.localForm(rule.prefix).length > Numbers.localForm(current.prefix).length
                ) {
                    best = rule
                }
            }
        }
        val b = best ?: return null
        return PrefixMatch(
            b.prefix, b.action == DbHelper.ACTION_BLOCK, ruleActionFromStored(b.blockAction)
        )
    }

    // ------------------------------------------------------------- log (29)

    fun logCall(
        numKey: String?,
        rawNumber: String?,
        displayName: String?,
        allowed: Boolean,
        reason: Reason,
        warn: Boolean,
        time: Long
    ) {
        val cv = ContentValues()
        cv.put("num_key", numKey)
        cv.put("raw_number", rawNumber)
        cv.put("display_name", displayName)
        cv.put("time", time)
        cv.put("allowed", if (allowed) 1 else 0)
        cv.put("reason", reason.name)
        cv.put("warn", if (warn) 1 else 0)
        db.insert("call_log", null, cv)
    }

    fun logEntries(limit: Int): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        db.query(
            "call_log",
            arrayOf("_id", "num_key", "raw_number", "display_name", "time", "allowed", "reason", "warn"),
            null, null, null, null, "time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LogEntry(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getInt(5) == 1, c.getString(6), c.getInt(7) == 1
                    )
                )
            }
        }
        return out
    }

    fun removeLogEntry(id: Long) {
        db.delete("call_log", "_id=?", arrayOf(id.toString()))
    }

    fun clearLog() {
        db.delete("call_log", null, null)
    }

    fun blockedCountSince(numKey: String?, since: Long): Int {
        if (numKey == null) return 0
        db.query(
            "call_log", arrayOf("COUNT(*)"), "num_key=? AND allowed=0 AND time>=?",
            arrayOf(numKey, since.toString()), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun blockedTodayCount(dayStart: Long): Int = blockedCountSince(dayStart)

    /** Blocked calls at or after [since] (the dashboard week tile, v1.1). */
    fun blockedCountSince(since: Long): Int {
        db.query(
            "call_log", arrayOf("COUNT(*)"), "allowed=0 AND time>=?",
            arrayOf(since.toString()), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }


    // ============================================================ Part 2

    // ------------------------------------------------------ profiles (14)

    fun activeProfile(): Profile =
        Profile.parse(prefs.getString(KEY_ACTIVE_PROFILE, Profile.NORMAL.name))

    fun setActiveProfile(profile: Profile) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profile.name).apply()
    }

    /**
     * The mode of a profile. NORMAL reads and writes the original Part 1
     * keys, so the Part 1 Settings screen and the Profiles screen always
     * agree about the Normal profile.
     */
    fun profileMode(profile: Profile): BlockMode {
        if (profile == Profile.NORMAL) return blockMode()
        val v = prefs.getString(KEY_PROF_PREFIX + profile.name + "_mode", null)
        return if (v == null) profile.defaultMode else BlockMode.valueOf(v)
    }

    fun setProfileMode(profile: Profile, mode: BlockMode) {
        if (profile == Profile.NORMAL) {
            setBlockMode(mode)
        } else {
            prefs.edit().putString(KEY_PROF_PREFIX + profile.name + "_mode", mode.name).apply()
        }
    }

    fun profileHidden(profile: Profile): HiddenRule {
        if (profile == Profile.NORMAL) return hiddenRule()
        val v = prefs.getString(KEY_PROF_PREFIX + profile.name + "_hidden", null)
        return if (v == null) profile.defaultHidden else HiddenRule.valueOf(v)
    }

    fun setProfileHidden(profile: Profile, rule: HiddenRule) {
        if (profile == Profile.NORMAL) {
            setHiddenRule(rule)
        } else {
            prefs.edit().putString(KEY_PROF_PREFIX + profile.name + "_hidden", rule.name).apply()
        }
    }

    // ----------------------------------------------------- schedules (15)

    fun schedules(): List<ScheduleEntry> {
        val out = ArrayList<ScheduleEntry>()
        db.query(
            "schedules", arrayOf("_id", "profile", "start_min", "end_min", "days", "enabled"),
            null, null, null, null, "_id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ScheduleEntry(
                        c.getLong(0), Profile.parse(c.getString(1)),
                        c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5) == 1
                    )
                )
            }
        }
        return out
    }

    fun addSchedule(profile: Profile, startMin: Int, endMin: Int, daysMask: Int) {
        val cv = ContentValues()
        cv.put("profile", profile.name)
        cv.put("start_min", startMin)
        cv.put("end_min", endMin)
        cv.put("days", daysMask)
        cv.put("enabled", 1)
        db.insert("schedules", null, cv)
    }

    fun removeSchedule(id: Long) {
        db.delete("schedules", "_id=?", arrayOf(id.toString()))
    }

    fun setScheduleEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues()
        cv.put("enabled", if (enabled) 1 else 0)
        db.update("schedules", cv, "_id=?", arrayOf(id.toString()))
    }

    /** The profile a schedule dictates right now, or null when none covers now. */
    fun scheduledProfile(now: Long): Profile? {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayIdx = WindowMath.dayIndex(cal.get(Calendar.DAY_OF_WEEK))
        var result: Profile? = null
        for (row in schedules()) {
            if (row.enabled &&
                WindowMath.inWindow(minuteOfDay, todayIdx, row.startMin, row.endMin, row.daysMask)
            ) {
                result = row.profile
            }
        }
        return result
    }

    /**
     * Schedule wins over the manually chosen profile; a calendar Focus
     * event (feature 54, v1.1) sits between the two.
     */
    fun effectiveProfile(now: Long): Profile {
        scheduledProfile(now)?.let { return it }
        if (calendarFocusOn() &&
            CalendarHelper.hasOngoingEvent(appCtx, calendarFocusId(), now)
        ) {
            return Profile.FOCUS
        }
        return activeProfile()
    }

    // ------------------------------------ per-profile block action (48)

    /**
     * Feature 48 (v1.1): the action this profile applies to blocked calls.
     * Follows the same storage pattern as profileMode/profileHidden —
     * NORMAL uses its own plain key, the others a per-profile key — and
     * defaults to REJECT, the pre-v1.1 behaviour.
     */
    fun profileAction(profile: Profile): RuleAction {
        val raw = if (profile == Profile.NORMAL) {
            prefs.getInt(KEY_BLOCK_ACTION, DbHelper.BLOCK_REJECT)
        } else {
            prefs.getInt(KEY_PROF_PREFIX + profile.name + "_action", DbHelper.BLOCK_REJECT)
        }
        return ruleActionFromStored(raw)
    }

    fun setProfileAction(profile: Profile, action: RuleAction) {
        val stored = ruleActionToStored(action)
        if (profile == Profile.NORMAL) {
            prefs.edit().putInt(KEY_BLOCK_ACTION, stored).apply()
        } else {
            prefs.edit().putInt(KEY_PROF_PREFIX + profile.name + "_action", stored).apply()
        }
    }

    // ------------------------------------------- international rule (21)

    fun intlBlock(): Boolean = prefs.getBoolean(KEY_INTL_BLOCK, false)

    fun setIntlBlock(on: Boolean) {
        prefs.edit().putBoolean(KEY_INTL_BLOCK, on).apply()
    }

    // ------------------------------------------- repeat daily cap (36)

    fun repeatDailyCap(): Int = prefs.getInt(KEY_REPEAT_CAP, 3)

    fun setRepeatDailyCap(cap: Int) {
        prefs.edit().putInt(KEY_REPEAT_CAP, cap).apply()
    }

    // --------------------------------- wildcard (20) and time windows (24)

    fun advRules(kind: Int): List<AdvRule> {
        val out = ArrayList<AdvRule>()
        db.query(
            "adv_rules",
            arrayOf(
                "_id", "kind", "pattern", "action", "start_min", "end_min",
                "days", "label", "block_action"
            ),
            "kind=?", arrayOf(kind.toString()), null, null, "created DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    AdvRule(
                        c.getLong(0), c.getInt(1), c.getString(2), c.getInt(3),
                        c.getInt(4), c.getInt(5), c.getInt(6), c.getString(7),
                        c.getInt(8)
                    )
                )
            }
        }
        return out
    }

    /** Feature 48 (v1.1): set a wildcard rule's action by pattern (backup restore). */
    fun setWildcardActionFor(pattern: String, action: RuleAction) {
        val cv = ContentValues()
        cv.put("block_action", ruleActionToStored(action))
        db.update(
            "adv_rules", cv, "pattern=? AND kind=?",
            arrayOf(pattern, DbHelper.KIND_WILDCARD.toString())
        )
    }

    /** Feature 48 (v1.1): set how one wildcard rule blocks. */
    fun setWildcardRuleAction(id: Long, action: RuleAction) {
        val cv = ContentValues()
        cv.put("block_action", ruleActionToStored(action))
        db.update("adv_rules", cv, "_id=?", arrayOf(id.toString()))
    }

    fun addWildcardRule(pattern: String, action: Int, label: String?) {
        val cv = ContentValues()
        cv.put("kind", DbHelper.KIND_WILDCARD)
        cv.put("pattern", pattern)
        cv.put("action", action)
        cv.put("label", label)
        cv.put("created", System.currentTimeMillis())
        db.insert("adv_rules", null, cv)
    }

    fun addWindowRule(
        pattern: String,
        startMin: Int,
        endMin: Int,
        daysMask: Int,
        label: String?
    ) {
        val cv = ContentValues()
        cv.put("kind", DbHelper.KIND_TIME_WINDOW)
        cv.put("pattern", pattern)
        cv.put("start_min", startMin)
        cv.put("end_min", endMin)
        cv.put("days", daysMask)
        cv.put("label", label)
        cv.put("created", System.currentTimeMillis())
        db.insert("adv_rules", null, cv)
    }

    fun removeAdvRule(id: Long) {
        db.delete("adv_rules", "_id=?", arrayOf(id.toString()))
    }

    /** The most specific matching wildcard rule, or null. */
    fun bestWildcardMatch(number: String): WildcardMatch? {
        var bestPattern: String? = null
        var bestBlock = false
        var bestAction = RuleAction.REJECT
        var bestSpec = -1
        for (rule in advRules(DbHelper.KIND_WILDCARD)) {
            val spec = Numbers.wildcardSpecificity(number, rule.pattern)
            if (spec > bestSpec) {
                bestSpec = spec
                bestPattern = rule.pattern
                bestBlock = rule.action == DbHelper.ACTION_BLOCK
                bestAction = ruleActionFromStored(rule.blockAction)
            }
        }
        val pat = bestPattern ?: return null
        return WildcardMatch(pat, bestBlock, bestAction)
    }

    /**
     * Feature 24: when a window rule matches this caller (by number key, or
     * by an allowed contact-group title stored as "g:<title>"), returns
     * whether now is inside that window.
     */
    fun windowStateFor(numKey: String?, groupTitles: Set<String>, now: Long): WindowState? {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayIdx = WindowMath.dayIndex(cal.get(Calendar.DAY_OF_WEEK))
        for (rule in advRules(DbHelper.KIND_TIME_WINDOW)) {
            val matches = if (rule.pattern.startsWith("g:")) {
                groupTitles.contains(rule.pattern.substring(2))
            } else {
                numKey != null && rule.pattern == numKey
            }
            if (matches) {
                return if (WindowMath.inWindow(
                        minuteOfDay, todayIdx, rule.startMin, rule.endMin, rule.daysMask
                    )
                ) {
                    WindowState.INSIDE
                } else {
                    WindowState.OUTSIDE
                }
            }
        }
        return null
    }

    // ------------------------------------------------------ hotlines (22)

    /** Seeds the built-in hotline list once. Users can edit or delete any row. */
    fun ensureHotlinesSeeded() {
        db.query("hotlines", arrayOf("COUNT(*)"), null, null, null, null, null).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) return
        }
        val seed = listOf(
            Triple("999", "999", "National emergency (police, fire, ambulance)"),
            Triple("121", "121", "Mobile operator customer care"),
            Triple("16247", "16247", "bKash customer care"),
            Triple("16167", "16167", "Nagad customer care"),
            Triple("16216", "16216", "Dutch-Bangla Bank / Rocket")
        )
        for ((raw, _, label) in seed) {
            val cv = ContentValues()
            cv.put("num_key", Numbers.key(raw))
            cv.put("raw_number", raw)
            cv.put("label", label)
            cv.put("builtin", 1)
            cv.put("enabled", 1)
            db.insert("hotlines", null, cv)
        }
    }

    fun hotlines(): List<HotlineEntry> {
        ensureHotlinesSeeded()
        val out = ArrayList<HotlineEntry>()
        db.query(
            "hotlines", arrayOf("_id", "num_key", "raw_number", "label", "builtin", "enabled"),
            null, null, null, null, "builtin DESC, raw_number ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HotlineEntry(
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getInt(4) == 1, c.getInt(5) == 1
                    )
                )
            }
        }
        return out
    }

    fun addHotline(rawNumber: String, label: String?) {
        val cv = ContentValues()
        cv.put("num_key", Numbers.key(rawNumber))
        cv.put("raw_number", rawNumber)
        cv.put("label", label)
        cv.put("builtin", 0)
        cv.put("enabled", 1)
        db.insert("hotlines", null, cv)
    }

    fun removeHotline(id: Long) {
        db.delete("hotlines", "_id=?", arrayOf(id.toString()))
    }

    fun setHotlineEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues()
        cv.put("enabled", if (enabled) 1 else 0)
        db.update("hotlines", cv, "_id=?", arrayOf(id.toString()))
    }

    fun isHotline(number: String): Boolean {
        val key = Numbers.key(number)
        if (key.isEmpty()) return false
        db.query(
            "hotlines", arrayOf("_id"), "num_key=? AND enabled=1",
            arrayOf(key), null, null, null
        ).use { c -> return c.moveToFirst() }
    }

    // ------------------------------------------------- contact groups (23)

    fun allowedGroups(): Set<String> =
        prefs.getStringSet(KEY_GROUPS, emptySet()) ?: emptySet()

    fun setAllowedGroups(groups: Set<String>) {
        prefs.edit().putStringSet(KEY_GROUPS, groups).apply()
    }

    // ------------------------------------------------------------ SMS (25-28)

    fun smsMuteEnabled(): Boolean = prefs.getBoolean(KEY_SMS_MUTE, false)

    fun setSmsMuteEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_SMS_MUTE, on).apply()
    }

    fun keywords(): List<KeywordEntry> {
        val out = ArrayList<KeywordEntry>()
        db.query(
            "keywords", arrayOf("_id", "word"), null, null, null, null, "word ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(KeywordEntry(c.getLong(0), c.getString(1)))
            }
        }
        return out
    }

    fun addKeyword(word: String) {
        val cv = ContentValues()
        cv.put("word", word)
        cv.put("created", System.currentTimeMillis())
        db.insert("keywords", null, cv)
    }

    fun removeKeyword(id: Long) {
        db.delete("keywords", "_id=?", arrayOf(id.toString()))
    }

    fun logSms(sender: String?, snippet: String?, reason: String, time: Long) {
        val cv = ContentValues()
        cv.put("sender", sender)
        cv.put("snippet", snippet)
        cv.put("time", time)
        cv.put("reason", reason)
        db.insert("sms_log", null, cv)
    }

    fun smsLog(limit: Int): List<SmsLogEntry> {
        val out = ArrayList<SmsLogEntry>()
        db.query(
            "sms_log", arrayOf("_id", "sender", "snippet", "time", "reason"),
            null, null, null, null, "time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SmsLogEntry(
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getLong(3), c.getString(4)
                    )
                )
            }
        }
        return out
    }

    fun clearSmsLog() {
        db.delete("sms_log", null, null)
    }

    // ------------------------------------------------------- statistics (31, 32, 35)

    fun attemptsCountSince(numKey: String?, since: Long): Int {
        if (numKey == null) return 0
        db.query(
            "call_log", arrayOf("COUNT(*)"), "num_key=? AND time>=?",
            arrayOf(numKey, since.toString()), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun countByReasonSince(reason: String, numKey: String?, since: Long): Int {
        val selection = if (numKey == null) {
            "reason=? AND time>=?"
        } else {
            "reason=? AND num_key=? AND time>=?"
        }
        val args = if (numKey == null) {
            arrayOf(reason, since.toString())
        } else {
            arrayOf(reason, numKey, since.toString())
        }
        db.query("call_log", arrayOf("COUNT(*)"), selection, args, null, null, null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun topBlocked(limit: Int, since: Long): List<TopNumber> {
        val out = ArrayList<TopNumber>()
        db.query(
            "call_log",
            arrayOf("num_key", "raw_number", "COUNT(*) AS cnt", "MAX(time) AS last_time"),
            "allowed=0 AND num_key IS NOT NULL AND time>=?",
            arrayOf(since.toString()), "num_key", null, "cnt DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(TopNumber(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)))
            }
        }
        return out
    }

    fun topByReason(reason: String, limit: Int, since: Long): List<TopNumber> {
        val out = ArrayList<TopNumber>()
        db.query(
            "call_log",
            arrayOf("num_key", "raw_number", "COUNT(*) AS cnt", "MAX(time) AS last_time"),
            "reason=? AND num_key IS NOT NULL AND time>=?",
            arrayOf(reason, since.toString()), "num_key", null, "cnt DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(TopNumber(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)))
            }
        }
        return out
    }

    fun dailyCounts(days: Int, now: Long): List<DayCount> {
        val out = ArrayList<DayCount>()
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        var i = 0
        while (i < days) {
            val start = cal.timeInMillis
            val end = start + 86_400_000L
            var blocked = 0
            var allowed = 0
            db.query(
                "call_log", arrayOf("allowed", "COUNT(*)"), "time>=? AND time<?",
                arrayOf(start.toString(), end.toString()), "allowed", null, null
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(0) == 0) blocked = c.getInt(1) else allowed = c.getInt(1)
                }
            }
            out.add(DayCount(start, blocked, allowed))
            cal.add(Calendar.DAY_OF_YEAR, 1)
            i++
        }
        return out
    }

    /** Blocked numbers that have gone quiet: no attempt at all since [quietSince]. */
    fun quietNumbers(limit: Int, quietBefore: Long, since: Long): List<TopNumber> {
        val out = ArrayList<TopNumber>()
        db.query(
            "call_log",
            arrayOf("num_key", "raw_number", "COUNT(*) AS cnt", "MAX(time) AS last_time"),
            "allowed=0 AND num_key IS NOT NULL AND time>=?",
            arrayOf(since.toString()), "num_key", "MAX(time) < " + quietBefore,
            "cnt DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(TopNumber(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)))
            }
        }
        return out
    }

    // --------------------------------------------------- log search (33)

    fun logSearch(query: String, filter: Int, limit: Int): List<LogEntry> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (query.isNotEmpty()) {
            where.append(" AND (raw_number LIKE ? OR display_name LIKE ?)")
            args.add("%" + query + "%")
            args.add("%" + query + "%")
        }
        when (filter) {
            1 -> where.append(" AND allowed=0")
            2 -> where.append(" AND allowed=1")
            3 -> where.append(" AND warn=1")
        }
        val out = ArrayList<LogEntry>()
        db.query(
            "call_log",
            arrayOf("_id", "num_key", "raw_number", "display_name", "time", "allowed", "reason", "warn"),
            where.toString(), args.toTypedArray(), null, null, "time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LogEntry(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getInt(5) == 1, c.getString(6), c.getInt(7) == 1
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------- summary (31)

    fun summaryEnabled(): Boolean = prefs.getBoolean(KEY_SUMMARY_ON, true)

    fun setSummaryEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_SUMMARY_ON, on).apply()
    }

    fun summaryHour(): Int = prefs.getInt(KEY_SUMMARY_HOUR, 21)

    fun setSummaryHour(hour: Int) {
        prefs.edit().putInt(KEY_SUMMARY_HOUR, hour).apply()
    }

    // --------------------------------------------------------- app lock (38)

    fun lockEnabled(): Boolean = prefs.getBoolean(KEY_LOCK_ON, false)

    fun setLockEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_ON, on).apply()
    }

    fun pinSalt(): String = prefs.getString(KEY_PIN_SALT, "") ?: ""

    fun pinHash(): String = prefs.getString(KEY_PIN_HASH, "") ?: ""

    fun setPin(salt: String, hash: String) {
        prefs.edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash).apply()
    }

    fun biometricEnabled(): Boolean = prefs.getBoolean(KEY_BIO_ON, true)

    fun setBiometricEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_BIO_ON, on).apply()
    }

    // ------------------------------------------------- per-SIM rules (17)

    /**
     * Per-SIM strictness preference, keyed by a hash of the phone-account id.
     * Stored and shown in the UI; see the honest note in ProfilesActivity —
     * the screening API does not tell the app which SIM a call arrived on.
     */
    fun simMode(accountKey: String): String =
        prefs.getString(KEY_SIM_PREFIX + accountKey, "GLOBAL") ?: "GLOBAL"

    fun setSimMode(accountKey: String, modeName: String) {
        prefs.edit().putString(KEY_SIM_PREFIX + accountKey, modeName).apply()
    }

    // ------------------------------------------------- online layer (46, 47)

    fun onlineEnabled(): Boolean = prefs.getBoolean(KEY_ONLINE_ON, false)

    fun setOnlineEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_ON, on).apply()
    }

    fun onlineUrl(): String = prefs.getString(KEY_ONLINE_URL, "") ?: ""

    fun setOnlineUrl(url: String) {
        prefs.edit().putString(KEY_ONLINE_URL, url).apply()
    }

    fun onlineKey(): String = prefs.getString(KEY_ONLINE_KEY, "") ?: ""

    fun setOnlineKey(key: String) {
        prefs.edit().putString(KEY_ONLINE_KEY, key).apply()
    }

    // ============================================================ v1.1

    // ------------------------------------------------- auto-SMS reply (49)

    fun autoSmsOn(): Boolean = prefs.getBoolean(KEY_AUTO_SMS_ON, false)

    fun setAutoSmsOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SMS_ON, on).apply()
    }

    fun autoSmsText(): String = prefs.getString(KEY_AUTO_SMS_TEXT, "") ?: ""

    fun setAutoSmsText(text: String) {
        prefs.edit().putString(KEY_AUTO_SMS_TEXT, text).apply()
    }

    /** True (once) when this number has not had an auto-reply in 24 hours. */
    fun claimAutoSms(numKey: String, now: Long): Boolean {
        val key = KEY_AUTO_SMS_LAST_PREFIX + numKey
        val last = prefs.getLong(key, 0L)
        if (now - last < 24 * 3_600_000L) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }

    // ------------------------------------------- softer on screen-on (50)

    fun softScreenOn(): Boolean = prefs.getBoolean(KEY_SOFT_SCREEN_ON, false)

    fun setSoftScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_SOFT_SCREEN_ON, on).apply()
    }

    // -------------------------------------- post-call quick decision (51)

    /**
     * The screening service leaves this marker when it allows an unknown
     * number; the post-call detector consumes it once the call has ended.
     * [repeatBypass] remembers that the allow came from the repeat-caller
     * bypass, which feature 55 needs.
     */
    fun setPendingPostCall(numKey: String, rawNumber: String, now: Long, repeatBypass: Boolean) {
        prefs.edit()
            .putString(KEY_POSTCALL_KEY, numKey)
            .putString(KEY_POSTCALL_RAW, rawNumber)
            .putLong(KEY_POSTCALL_AT, now)
            .putBoolean(KEY_POSTCALL_REPEAT, repeatBypass)
            .apply()
    }

    fun pendingPostCall(): Triple<String, String, Boolean>? {
        val key = prefs.getString(KEY_POSTCALL_KEY, "") ?: ""
        if (key.isEmpty()) return null
        val raw = prefs.getString(KEY_POSTCALL_RAW, key) ?: key
        return Triple(key, raw, prefs.getBoolean(KEY_POSTCALL_REPEAT, false))
    }

    fun pendingPostCallAt(): Long = prefs.getLong(KEY_POSTCALL_AT, 0L)

    fun clearPendingPostCall() {
        prefs.edit()
            .remove(KEY_POSTCALL_KEY)
            .remove(KEY_POSTCALL_RAW)
            .remove(KEY_POSTCALL_AT)
            .remove(KEY_POSTCALL_REPEAT)
            .apply()
    }

    /** True (once) when the quick-decision prompt has not shown today for this number. */
    fun claimPostCallShown(numKey: String, now: Long): Boolean {
        val key = KEY_POSTCALL_SHOWN_PREFIX + numKey
        val last = prefs.getLong(key, 0L)
        if (now - last < 24 * 3_600_000L) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }

    // ------------------------------------------- SMS correspondents (52)

    fun smsKnownOn(): Boolean = prefs.getBoolean(KEY_SMS_KNOWN_ON, false)

    fun setSmsKnownOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_SMS_KNOWN_ON, on).apply()
    }

    // ---------------------------------------------- calendar Focus (54)

    fun calendarFocusOn(): Boolean = prefs.getBoolean(KEY_CAL_FOCUS_ON, false)

    fun setCalendarFocusOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_CAL_FOCUS_ON, on).apply()
    }

    fun calendarFocusId(): Long = prefs.getLong(KEY_CAL_FOCUS_ID, -1L)

    fun calendarFocusName(): String = prefs.getString(KEY_CAL_FOCUS_NAME, "") ?: ""

    fun setCalendarFocus(id: Long, name: String) {
        prefs.edit().putLong(KEY_CAL_FOCUS_ID, id).putString(KEY_CAL_FOCUS_NAME, name).apply()
    }

    // ----------------------------------- urgent missed-call reminder (55)

    /** True (once) when no urgent reminder went out for this number today. */
    fun claimUrgentReminder(numKey: String, now: Long): Boolean {
        val key = KEY_URGENT_LAST_PREFIX + numKey
        val last = prefs.getLong(key, 0L)
        if (now - last < 24 * 3_600_000L) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }

    // ------------------------------------------------ log auto-clean (57)

    /** Days of internal log to keep; 0 = keep everything (the default). */
    fun logKeepDays(): Int = prefs.getInt(KEY_LOG_KEEP_DAYS, 0)

    fun setLogKeepDays(days: Int) {
        prefs.edit().putInt(KEY_LOG_KEEP_DAYS, days).apply()
    }

    /** Deletes internal-log rows older than the configured window. Cheap. */
    fun purgeOldLog(now: Long) {
        val days = logKeepDays()
        if (days <= 0) return
        val cutoff = now - days.toLong() * 86_400_000L
        db.delete("call_log", "time<?", arrayOf(cutoff.toString()))
        db.delete("sms_log", "time<?", arrayOf(cutoff.toString()))
    }

    // ------------------------------------------- silent notifications (58)

    fun silentNotif(): Boolean = prefs.getBoolean(KEY_SILENT_NOTIF, false)

    fun setSilentNotif(on: Boolean) {
        prefs.edit().putBoolean(KEY_SILENT_NOTIF, on).apply()
    }

    /** Counts a block for the daily summary while silent mode is on. */
    fun noteSilentBlock(rawNumber: String?) {
        val count = prefs.getInt(KEY_SUMMARY_BLOCKS, 0) + 1
        val editor = prefs.edit().putInt(KEY_SUMMARY_BLOCKS, count)
        if (!rawNumber.isNullOrEmpty()) {
            editor.putString(KEY_SUMMARY_LAST, rawNumber)
        }
        editor.apply()
    }

    /** Reads the silent-mode counters and resets them (called by the summary). */
    fun takeSilentSummary(): Pair<Int, String?> {
        val count = prefs.getInt(KEY_SUMMARY_BLOCKS, 0)
        val last = prefs.getString(KEY_SUMMARY_LAST, null)
        if (count > 0) {
            prefs.edit().putInt(KEY_SUMMARY_BLOCKS, 0).remove(KEY_SUMMARY_LAST).apply()
        }
        return Pair(count, last)
    }

    // ------------------------------------------------------- wangiri (53)

    /** This number's attempt timestamps inside [since, now], from the internal log. */
    fun attemptTimesSince(numKey: String, since: Long): List<Long> {
        val out = ArrayList<Long>()
        db.query(
            "call_log", arrayOf("time"), "num_key=? AND time>=?",
            arrayOf(numKey, since.toString()), null, null, "time ASC"
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    companion object {
        const val PREFS = "callshield_prefs"
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_MODE = "block_mode"
        private const val KEY_HIDDEN = "hidden_rule"
        private const val KEY_TEMP_UNTIL = "temp_allow_until"
        private const val KEY_ALLOW_NEXT = "allow_next"
        private const val KEY_EXPECTED = "expected_value"
        private const val KEY_CALLBACK_HOURS = "callback_hours"
        private const val KEY_REPEAT_ON = "repeat_on"
        private const val KEY_REPEAT_COUNT = "repeat_count"
        private const val KEY_REPEAT_WINDOW = "repeat_window_min"
        private const val KEY_ESC_ON = "escalation_on"
        private const val KEY_ESC_THRESHOLD = "escalation_threshold"
        private const val KEY_ESC_LAST_PREFIX = "esc_last_"
        const val KEY_LANG = "language"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_PROF_PREFIX = "prof_"
        private const val KEY_INTL_BLOCK = "intl_block"
        private const val KEY_REPEAT_CAP = "repeat_daily_cap"
        private const val KEY_GROUPS = "allowed_groups"
        private const val KEY_SMS_MUTE = "sms_mute_on"
        private const val KEY_SUMMARY_ON = "summary_on"
        private const val KEY_SUMMARY_HOUR = "summary_hour"
        private const val KEY_LOCK_ON = "lock_on"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_BIO_ON = "biometric_on"
        private const val KEY_SIM_PREFIX = "sim_"
        private const val KEY_ONLINE_ON = "online_on"
        private const val KEY_ONLINE_URL = "online_url"
        private const val KEY_ONLINE_KEY = "online_key"
        const val KEY_THEME = "theme_mode"
        private const val KEY_WIZARD_DONE = "wizard_done"

        // ---- v1.1 ----
        private const val KEY_BLOCK_ACTION = "block_action"
        private const val KEY_AUTO_SMS_ON = "auto_sms_on"
        private const val KEY_AUTO_SMS_TEXT = "auto_sms_text"
        private const val KEY_AUTO_SMS_LAST_PREFIX = "auto_sms_last_"
        private const val KEY_SOFT_SCREEN_ON = "soft_screen_on"
        private const val KEY_POSTCALL_KEY = "postcall_key"
        private const val KEY_POSTCALL_RAW = "postcall_raw"
        private const val KEY_POSTCALL_AT = "postcall_at"
        private const val KEY_POSTCALL_REPEAT = "postcall_repeat"
        private const val KEY_POSTCALL_SHOWN_PREFIX = "postcall_shown_"
        private const val KEY_SMS_KNOWN_ON = "sms_known_on"
        private const val KEY_CAL_FOCUS_ON = "cal_focus_on"
        private const val KEY_CAL_FOCUS_ID = "cal_focus_id"
        private const val KEY_CAL_FOCUS_NAME = "cal_focus_name"
        private const val KEY_URGENT_LAST_PREFIX = "urgent_last_"
        private const val KEY_LOG_KEEP_DAYS = "log_keep_days"
        private const val KEY_SILENT_NOTIF = "silent_notif"
        private const val KEY_SUMMARY_BLOCKS = "summary_blocks"
        private const val KEY_SUMMARY_LAST = "summary_last_num"
    }
}

/** Feature 48: DbHelper.BLOCK_* storage value to engine action. */
fun ruleActionFromStored(stored: Int): RuleAction = when (stored) {
    DbHelper.BLOCK_SILENCE -> RuleAction.SILENCE
    DbHelper.BLOCK_WARN_ONLY -> RuleAction.WARN_ONLY
    else -> RuleAction.REJECT
}

/** Feature 48: engine action to DbHelper.BLOCK_* storage value. */
fun ruleActionToStored(action: RuleAction): Int = when (action) {
    RuleAction.SILENCE -> DbHelper.BLOCK_SILENCE
    RuleAction.WARN_ONLY -> DbHelper.BLOCK_WARN_ONLY
    RuleAction.REJECT -> DbHelper.BLOCK_REJECT
}
