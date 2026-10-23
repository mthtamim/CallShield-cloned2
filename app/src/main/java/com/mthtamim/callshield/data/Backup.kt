package com.mthtamim.callshield.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mthtamim.callshield.engine.Profile
import com.mthtamim.callshield.engine.RuleAction
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Feature 40: encrypted backup / restore of the user's own setup — lists,
 * prefix rules, wildcard and time-window rules, hotlines, SMS keywords,
 * schedules and the main settings.
 *
 * The file is AES-GCM encrypted with a key held in the Android Keystore
 * (alias "callshield_backup_key"). Stated plainly: that makes the backup
 * readable only while the same keystore key exists on this device. If the
 * app is uninstalled or the key is wiped, an old backup file can no longer
 * be decrypted — the encryption protects the file in transit and at rest,
 * it does not make it portable across a full device reset. The call log
 * itself is deliberately NOT part of the backup.
 *
 * Feature 39 note: everything else (the SQLite database, preferences)
 * lives in the app's private storage area, which Android isolates from
 * other apps. The database is not separately encrypted; the honest claim
 * is "private to this app on this device", plus this encrypted backup.
 */
object Backup {

    private const val KEY_ALIAS = "callshield_backup_key"
    private const val FORMAT_VERSION = 1

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** Builds the encrypted backup file content (a small JSON envelope). */
    fun exportEncrypted(store: Store): String {
        val plain = buildJson(store).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        val envelope = JSONObject()
        envelope.put("format", FORMAT_VERSION)
        envelope.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
        envelope.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        return envelope.toString(2)
    }

    /** Decrypts and restores a backup file. Returns false on any problem. */
    fun importEncrypted(store: Store, content: String): Boolean {
        return try {
            val envelope = JSONObject(content)
            val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(data), Charsets.UTF_8)
            restoreJson(store, JSONObject(plain))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildJson(store: Store): JSONObject {
        val root = JSONObject()
        root.put("app", "CallShield")
        root.put("version", FORMAT_VERSION)

        val lists = JSONArray()
        for (type in intArrayOf(DbHelper.TYPE_WHITELIST, DbHelper.TYPE_BLACKLIST, DbHelper.TYPE_EMERGENCY)) {
            for (e in store.listEntries(type)) {
                val o = JSONObject()
                o.put("raw", e.rawNumber)
                o.put("label", e.label ?: JSONObject.NULL)
                o.put("type", e.type)
                o.put("source", e.source ?: "backup")
                // v1.1 (feature 48): the per-entry block action.
                o.put("blockAction", e.blockAction)
                lists.put(o)
            }
        }
        root.put("lists", lists)

        val prefixes = JSONArray()
        for (r in store.prefixRules()) {
            val o = JSONObject()
            o.put("prefix", r.prefix)
            o.put("action", r.action)
            o.put("label", r.label ?: JSONObject.NULL)
            o.put("blockAction", r.blockAction)
            prefixes.put(o)
        }
        root.put("prefixes", prefixes)

        val wildcards = JSONArray()
        for (r in store.advRules(DbHelper.KIND_WILDCARD)) {
            val o = JSONObject()
            o.put("pattern", r.pattern)
            o.put("action", r.action)
            o.put("label", r.label ?: JSONObject.NULL)
            o.put("blockAction", r.blockAction)
            wildcards.put(o)
        }
        root.put("wildcards", wildcards)

        val windows = JSONArray()
        for (r in store.advRules(DbHelper.KIND_TIME_WINDOW)) {
            val o = JSONObject()
            o.put("pattern", r.pattern)
            o.put("start", r.startMin)
            o.put("end", r.endMin)
            o.put("days", r.daysMask)
            o.put("label", r.label ?: JSONObject.NULL)
            windows.put(o)
        }
        root.put("windows", windows)

        val hotlines = JSONArray()
        for (h in store.hotlines()) {
            val o = JSONObject()
            o.put("raw", h.rawNumber)
            o.put("label", h.label ?: JSONObject.NULL)
            o.put("enabled", h.enabled)
            hotlines.put(o)
        }
        root.put("hotlines", hotlines)

        val keywords = JSONArray()
        for (k in store.keywords()) {
            keywords.put(k.word)
        }
        root.put("keywords", keywords)

        val schedules = JSONArray()
        for (row in store.schedules()) {
            val o = JSONObject()
            o.put("profile", row.profile.name)
            o.put("start", row.startMin)
            o.put("end", row.endMin)
            o.put("days", row.daysMask)
            o.put("enabled", row.enabled)
            schedules.put(o)
        }
        root.put("schedules", schedules)

        val settings = JSONObject()
        settings.put("master", store.masterEnabled())
        settings.put("activeProfile", store.activeProfile().name)
        settings.put("intlBlock", store.intlBlock())
        settings.put("smsMute", store.smsMuteEnabled())
        settings.put("summary", store.summaryEnabled())
        settings.put("groups", JSONArray(store.allowedGroups().toList()))
        // v1.1 settings (features 48-58): headline toggles and values.
        settings.put("autoSmsOn", store.autoSmsOn())
        settings.put("autoSmsText", store.autoSmsText())
        settings.put("softScreenOn", store.softScreenOn())
        settings.put("smsKnownOn", store.smsKnownOn())
        settings.put("silentNotif", store.silentNotif())
        settings.put("logKeepDays", store.logKeepDays())
        settings.put("calFocusOn", store.calendarFocusOn())
        settings.put("calFocusId", store.calendarFocusId())
        settings.put("calFocusName", store.calendarFocusName())
        for (p in Profile.values()) {
            settings.put("profileAction_" + p.name, store.profileAction(p).name)
        }
        root.put("settings", settings)
        return root
    }

    private fun restoreJson(store: Store, root: JSONObject) {
        val lists = root.optJSONArray("lists")
        if (lists != null) {
            var i = 0
            while (i < lists.length()) {
                val o = lists.getJSONObject(i)
                val raw = o.getString("raw")
                val type = o.getInt("type")
                store.addToList(
                    raw,
                    if (o.isNull("label")) null else o.getString("label"),
                    type,
                    "backup"
                )
                // v1.1 (feature 48): restore the per-entry block action.
                if (type == DbHelper.TYPE_BLACKLIST && o.has("blockAction")) {
                    store.setBlacklistActionFor(
                        raw, ruleActionFromStored(o.getInt("blockAction"))
                    )
                }
                i++
            }
        }
        val prefixes = root.optJSONArray("prefixes")
        if (prefixes != null) {
            var i = 0
            while (i < prefixes.length()) {
                val o = prefixes.getJSONObject(i)
                store.addPrefixRule(
                    o.getString("prefix"), o.getInt("action"),
                    if (o.isNull("label")) null else o.getString("label")
                )
                if (o.has("blockAction")) {
                    store.setPrefixActionFor(
                        o.getString("prefix"),
                        ruleActionFromStored(o.getInt("blockAction"))
                    )
                }
                i++
            }
        }
        val wildcards = root.optJSONArray("wildcards")
        if (wildcards != null) {
            var i = 0
            while (i < wildcards.length()) {
                val o = wildcards.getJSONObject(i)
                store.addWildcardRule(
                    o.getString("pattern"), o.getInt("action"),
                    if (o.isNull("label")) null else o.getString("label")
                )
                if (o.has("blockAction")) {
                    store.setWildcardActionFor(
                        o.getString("pattern"),
                        ruleActionFromStored(o.getInt("blockAction"))
                    )
                }
                i++
            }
        }
        val windows = root.optJSONArray("windows")
        if (windows != null) {
            var i = 0
            while (i < windows.length()) {
                val o = windows.getJSONObject(i)
                store.addWindowRule(
                    o.getString("pattern"), o.getInt("start"), o.getInt("end"),
                    o.getInt("days"),
                    if (o.isNull("label")) null else o.getString("label")
                )
                i++
            }
        }
        val hotlineRaws = HashSet<String>()
        val hotlines = root.optJSONArray("hotlines")
        if (hotlines != null) {
            var i = 0
            while (i < hotlines.length()) {
                val o = hotlines.getJSONObject(i)
                hotlineRaws.add(o.getString("raw"))
                i++
            }
        }
        // Hotlines restore as user entries only for numbers not already
        // present, so built-in rows are never duplicated.
        if (hotlines != null) {
            val existing = HashSet<String>()
            for (h in store.hotlines()) existing.add(h.rawNumber)
            var i = 0
            while (i < hotlines.length()) {
                val o = hotlines.getJSONObject(i)
                val raw = o.getString("raw")
                if (!existing.contains(raw)) {
                    store.addHotline(raw, if (o.isNull("label")) null else o.getString("label"))
                }
                i++
            }
        }
        val keywords = root.optJSONArray("keywords")
        if (keywords != null) {
            val existingWords = HashSet<String>()
            for (k in store.keywords()) existingWords.add(k.word.lowercase())
            var i = 0
            while (i < keywords.length()) {
                val w = keywords.getString(i)
                if (!existingWords.contains(w.lowercase())) store.addKeyword(w)
                i++
            }
        }
        val schedules = root.optJSONArray("schedules")
        if (schedules != null) {
            var i = 0
            while (i < schedules.length()) {
                val o = schedules.getJSONObject(i)
                store.addSchedule(
                    Profile.parse(o.getString("profile")),
                    o.getInt("start"), o.getInt("end"), o.getInt("days")
                )
                i++
            }
        }
        val settings = root.optJSONObject("settings")
        if (settings != null) {
            store.setMasterEnabled(settings.optBoolean("master", store.masterEnabled()))
            store.setIntlBlock(settings.optBoolean("intlBlock", store.intlBlock()))
            store.setSmsMuteEnabled(settings.optBoolean("smsMute", store.smsMuteEnabled()))
            store.setSummaryEnabled(settings.optBoolean("summary", store.summaryEnabled()))
            val profileName = settings.optString("activeProfile", "")
            if (profileName.isNotEmpty()) {
                store.setActiveProfile(
                    Profile.parse(profileName)
                )
            }
            val groups = settings.optJSONArray("groups")
            if (groups != null) {
                val set = HashSet<String>()
                var i = 0
                while (i < groups.length()) {
                    set.add(groups.getString(i))
                    i++
                }
                store.setAllowedGroups(set)
            }
            // v1.1 settings: every key is optional, so Part 2 backups
            // restore exactly as before.
            if (settings.has("autoSmsOn")) {
                store.setAutoSmsOn(settings.getBoolean("autoSmsOn"))
            }
            if (settings.has("autoSmsText")) {
                store.setAutoSmsText(settings.getString("autoSmsText"))
            }
            if (settings.has("softScreenOn")) {
                store.setSoftScreenOn(settings.getBoolean("softScreenOn"))
            }
            if (settings.has("smsKnownOn")) {
                store.setSmsKnownOn(settings.getBoolean("smsKnownOn"))
            }
            if (settings.has("silentNotif")) {
                store.setSilentNotif(settings.getBoolean("silentNotif"))
            }
            if (settings.has("logKeepDays")) {
                store.setLogKeepDays(settings.getInt("logKeepDays"))
            }
            if (settings.has("calFocusId")) {
                store.setCalendarFocus(
                    settings.getLong("calFocusId"),
                    settings.optString("calFocusName", "")
                )
            }
            if (settings.has("calFocusOn")) {
                store.setCalendarFocusOn(settings.getBoolean("calFocusOn"))
            }
            for (p in Profile.values()) {
                val key = "profileAction_" + p.name
                if (settings.has(key)) {
                    val actionName = settings.getString(key)
                    val action = RuleAction.values()
                        .firstOrNull { it.name == actionName }
                    if (action != null) store.setProfileAction(p, action)
                }
            }
        }
    }
}
