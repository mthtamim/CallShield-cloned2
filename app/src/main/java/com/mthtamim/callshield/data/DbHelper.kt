package com.mthtamim.callshield.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-only storage.
 *
 * Part 1 tables (schema v1):
 *  - list_entries: the user's own whitelist / blacklist (feature 4).
 *    type 0 = whitelist, 1 = blacklist. source: manual / log / contact.
 *    (Part 2 adds type 2 = emergency contacts, feature 16 — same table.)
 *  - prefix_rules: feature 19. action 0 = allow, 1 = block.
 *  - call_log: every screened call with its decision and reason (feature 29).
 *    Also the data source for repeat-caller detection (feature 9) and the
 *    repeated-attempt escalation count (feature 13).
 *
 * Part 2 tables (schema v2, added by migration, Part 1 tables untouched):
 *  - schedules: profile schedules (feature 15).
 *  - adv_rules: wildcard rules (20) and time-window rules (24).
 *    kind 0 = wildcard (pattern holds digits + "*"), kind 1 = time window
 *    (pattern holds a number key, or "g:" + a contact-group title).
 *  - hotlines: short-code / hotline allowlist (feature 22), seeded built-ins.
 *  - keywords: SMS keyword mute rules (feature 27).
 *  - sms_log: muted SMS notifications, the silent inbox (feature 28).
 *
 * v1.1 (schema v3, additive columns only, feature 48): list_entries,
 * prefix_rules and adv_rules each gain a `block_action` column —
 * 0 = REJECT (the default, i.e. the pre-v1.1 behaviour), 1 = SILENCE,
 * 2 = WARN_ONLY. No existing column or row is altered.
 */
class DbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createV1Tables(db)
        createV2Tables(db)
        addV3Columns(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Part 1 -> Part 2: additive only. No Part 1 table is altered or
        // dropped, so every Part 1 list, rule and log entry survives.
        if (oldVersion < 2) {
            createV2Tables(db)
        }
        // Part 2 -> v1.1: one ADD COLUMN per rule table; existing rows get
        // the default 0 = REJECT, which is exactly the old behaviour.
        if (oldVersion < 3) {
            addV3Columns(db)
        }
    }

    private fun addV3Columns(db: SQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE list_entries ADD COLUMN block_action INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE prefix_rules ADD COLUMN block_action INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE adv_rules ADD COLUMN block_action INTEGER NOT NULL DEFAULT 0"
        )
    }

    private fun createV1Tables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE list_entries (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "num_key TEXT NOT NULL, " +
                "raw_number TEXT NOT NULL, " +
                "label TEXT, " +
                "type INTEGER NOT NULL, " +
                "source TEXT, " +
                "created INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_list_key ON list_entries(num_key, type)")
        db.execSQL(
            "CREATE TABLE prefix_rules (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "prefix TEXT NOT NULL, " +
                "action INTEGER NOT NULL, " +
                "label TEXT, " +
                "created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE call_log (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "num_key TEXT, " +
                "raw_number TEXT, " +
                "display_name TEXT, " +
                "time INTEGER NOT NULL, " +
                "allowed INTEGER NOT NULL, " +
                "reason TEXT NOT NULL, " +
                "warn INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_log_key_time ON call_log(num_key, time)")
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS schedules (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "profile TEXT NOT NULL, " +
                "start_min INTEGER NOT NULL, " +
                "end_min INTEGER NOT NULL, " +
                "days INTEGER NOT NULL, " +
                "enabled INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS adv_rules (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "kind INTEGER NOT NULL, " +
                "pattern TEXT NOT NULL, " +
                "action INTEGER NOT NULL DEFAULT 0, " +
                "start_min INTEGER NOT NULL DEFAULT 0, " +
                "end_min INTEGER NOT NULL DEFAULT 0, " +
                "days INTEGER NOT NULL DEFAULT 0, " +
                "label TEXT, " +
                "created INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS hotlines (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "num_key TEXT NOT NULL, " +
                "raw_number TEXT NOT NULL, " +
                "label TEXT, " +
                "builtin INTEGER NOT NULL DEFAULT 0, " +
                "enabled INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS keywords (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "word TEXT NOT NULL, " +
                "created INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sms_log (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender TEXT, " +
                "snippet TEXT, " +
                "time INTEGER NOT NULL, " +
                "reason TEXT NOT NULL)"
        )
    }

    companion object {
        const val DB_NAME = "callshield.db"
        const val DB_VERSION = 3

        const val TYPE_WHITELIST = 0
        const val TYPE_BLACKLIST = 1
        const val TYPE_EMERGENCY = 2

        const val ACTION_ALLOW = 0
        const val ACTION_BLOCK = 1

        const val KIND_WILDCARD = 0
        const val KIND_TIME_WINDOW = 1

        // Feature 48 (v1.1): per-rule block action, stored in block_action.
        const val BLOCK_REJECT = 0
        const val BLOCK_SILENCE = 1
        const val BLOCK_WARN_ONLY = 2
    }
}

data class ListEntry(
    val id: Long,
    val numKey: String,
    val rawNumber: String,
    val label: String?,
    val type: Int,
    val source: String?,
    /** Feature 48 (v1.1): DbHelper.BLOCK_* — only used for blacklist rows. */
    val blockAction: Int = 0
)

data class PrefixRule(
    val id: Long,
    val prefix: String,
    val action: Int,
    val label: String?,
    /** Feature 48 (v1.1): DbHelper.BLOCK_* — only used when action is BLOCK. */
    val blockAction: Int = 0
)

data class LogEntry(
    val id: Long,
    val numKey: String?,
    val rawNumber: String?,
    val displayName: String?,
    val time: Long,
    val allowed: Boolean,
    val reason: String,
    val warn: Boolean
)

// ---- Part 2 row types ----

data class AdvRule(
    val id: Long,
    val kind: Int,
    val pattern: String,
    val action: Int,
    val startMin: Int,
    val endMin: Int,
    val daysMask: Int,
    val label: String?,
    /** Feature 48 (v1.1): DbHelper.BLOCK_* — only used for wildcard blocks. */
    val blockAction: Int = 0
)

data class HotlineEntry(
    val id: Long,
    val numKey: String,
    val rawNumber: String,
    val label: String?,
    val builtin: Boolean,
    val enabled: Boolean
)

data class KeywordEntry(
    val id: Long,
    val word: String
)

data class SmsLogEntry(
    val id: Long,
    val sender: String?,
    val snippet: String?,
    val time: Long,
    val reason: String
)

/** One row of the top-numbers statistics (features 31, 32, 35). */
data class TopNumber(
    val numKey: String,
    val rawNumber: String?,
    val count: Int,
    val lastTime: Long
)

/** One day of the trend view (feature 32). */
data class DayCount(
    val dayStart: Long,
    val blocked: Int,
    val allowed: Int
)

