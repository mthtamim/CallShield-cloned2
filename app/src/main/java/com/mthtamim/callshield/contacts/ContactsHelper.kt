package com.mthtamim.callshield.contacts

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.mthtamim.callshield.util.Numbers

data class ContactInfo(
    val inContacts: Boolean,
    val isFavorite: Boolean,
    val displayName: String?
)

/** How the most recent call with a number ended (features 51 and 55). */
enum class CallOutcome { ANSWERED, MISSED, REJECTED }

/**
 * Reads the phone's own Contacts — the only data source for the core rule.
 * Everything happens on-device; nothing is uploaded anywhere (feature 37).
 */
object ContactsHelper {

    fun lookup(context: Context, number: String): ContactInfo {
        val resolver = context.contentResolver
        val filterUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        try {
            // Column names are the provider contract ("contact_id",
            // "display_name"); using the literals avoids depending on which
            // nested interface happens to re-export them as constants.
            resolver.query(
                filterUri,
                arrayOf("contact_id", "display_name"),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val contactId = c.getLong(c.getColumnIndex("contact_id"))
                    val name = c.getString(c.getColumnIndex("display_name"))
                    var starred = 0
                    resolver.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        arrayOf(ContactsContract.Contacts.STARRED),
                        ContactsContract.Contacts._ID + "=?",
                        arrayOf(contactId.toString()),
                        null
                    )?.use { c2 ->
                        if (c2.moveToFirst()) starred = c2.getInt(0)
                    }
                    return ContactInfo(true, starred == 1, name)
                }
            }
        } catch (e: SecurityException) {
            // Contacts permission was revoked after the check: treat as unknown.
            return ContactInfo(false, false, null)
        }
        return ContactInfo(false, false, null)
    }

    /**
     * Feature 8: true when the user has called this number themselves within
     * [windowHours]. Needs READ_CALL_LOG; returns false when the permission
     * is missing, so the app degrades gracefully without it.
     */
    fun calledWithin(context: Context, number: String, windowHours: Int, now: Long): Boolean {
        val targetKey = Numbers.key(number)
        if (targetKey.isEmpty()) return false
        val since = now - windowHours * 3_600_000L
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                CallLog.Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE +
                    " AND " + CallLog.Calls.DATE + ">=?",
                arrayOf(since.toString()),
                CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0)
                    if (num != null && Numbers.key(num) == targetKey) {
                        return true
                    }
                }
            }
        } catch (e: SecurityException) {
            return false
        }
        return false
    }

    /**
     * Feature 23: the titles of the system contact groups this number
     * belongs to. Empty set when the number is not in Contacts, or when the
     * Contacts permission is missing.
     */
    fun groupsOf(context: Context, number: String): Set<String> {
        val resolver = context.contentResolver
        var contactId = -1L
        val filterUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        try {
            resolver.query(filterUri, arrayOf("contact_id"), null, null, null)?.use { c ->
                if (c.moveToFirst()) contactId = c.getLong(0)
            }
            if (contactId < 0) return emptySet()
            val groupIds = HashSet<Long>()
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
                ContactsContract.Data.CONTACT_ID + "=? AND " +
                    ContactsContract.Data.MIMETYPE + "=?",
                arrayOf(
                    contactId.toString(),
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
                ),
                null
            )?.use { c ->
                while (c.moveToNext()) groupIds.add(c.getLong(0))
            }
            if (groupIds.isEmpty()) return emptySet()
            val titles = HashSet<String>()
            resolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1)
                    if (groupIds.contains(id) && title != null) titles.add(title)
                }
            }
            return titles
        } catch (e: SecurityException) {
            return emptySet()
        }
    }

    /** All system contact-group titles, for the group-allow picker (feature 23). */
    fun allGroupTitles(context: Context): List<String> {
        val out = ArrayList<String>()
        try {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups.TITLE),
                ContactsContract.Groups.DELETED + "=0",
                null,
                ContactsContract.Groups.TITLE + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)
                    if (title != null && !out.contains(title)) out.add(title)
                }
            }
        } catch (e: SecurityException) {
            // No Contacts permission: the picker simply shows nothing.
        }
        return out
    }

    /**
     * Feature 52 (v1.1): true when the user has exchanged SMS with this
     * number — at least one message in either direction in the SMS
     * provider. Needs READ_SMS; false without it. Results are cached per
     * number for the process lifetime: screening stays fast, and a number
     * the user texts mid-session is picked up on the next app start.
     */
    private val smsHistoryCache = HashMap<String, Boolean>()

    fun hasSmsHistory(context: Context, number: String): Boolean {
        val targetKey = Numbers.key(number)
        if (targetKey.isEmpty()) return false
        synchronized(smsHistoryCache) {
            smsHistoryCache[targetKey]?.let { return it }
        }
        var found = false
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                null, null,
                Telephony.Sms.DATE + " DESC"
            )?.use { c ->
                var read = 0
                while (c.moveToNext() && read < 400) {
                    read++
                    val addr = c.getString(0) ?: continue
                    if (Numbers.key(addr) == targetKey) {
                        found = true
                        break
                    }
                }
            }
        } catch (e: SecurityException) {
            return false
        }
        synchronized(smsHistoryCache) {
            smsHistoryCache[targetKey] = found
        }
        return found
    }

    /**
     * Features 51 and 55 (v1.1): how the latest call with this number at or
     * after [sinceMs] ended, from the call log — ANSWERED (incoming with
     * duration), MISSED (incoming, never answered) or REJECTED (declined).
     * Null when there is no such row or READ_CALL_LOG is missing; callers
     * treat null as "not known yet", never as "missed".
     *
     * Also the feature 53 guard: an answered incoming call of 5+ seconds
     * or any outgoing call means a real relationship — see [hasRealConversation].
     */
    fun lastCallOutcome(context: Context, number: String, sinceMs: Long): CallOutcome? {
        val targetKey = Numbers.key(number)
        if (targetKey.isEmpty()) return null
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION
                ),
                CallLog.Calls.DATE + ">=?",
                arrayOf(sinceMs.toString()),
                CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                var read = 0
                while (c.moveToNext() && read < 40) {
                    read++
                    val num = c.getString(0) ?: continue
                    if (Numbers.key(num) != targetKey) continue
                    val type = c.getInt(1)
                    val duration = c.getLong(2)
                    return when {
                        type == CallLog.Calls.INCOMING_TYPE && duration > 0 ->
                            CallOutcome.ANSWERED
                        type == CallLog.Calls.REJECTED_TYPE ->
                            CallOutcome.REJECTED
                        type == CallLog.Calls.INCOMING_TYPE ||
                            type == CallLog.Calls.MISSED_TYPE ||
                            type == CallLog.Calls.BLOCKED_TYPE ->
                            CallOutcome.MISSED
                        else -> null
                    }
                }
            }
        } catch (e: SecurityException) {
            return null
        }
        return null
    }

    /**
     * Feature 53 (v1.1) guard: true when the call log shows a real
     * conversation with this number — an answered incoming call of 5+
     * seconds, or any outgoing call to it. Conservative on purpose: any
     * hit clears the wangiri suspicion. Needs READ_CALL_LOG; returns false
     * without it (the suspicion then rests on the internal log alone,
     * which the manual states plainly).
     */
    fun hasRealConversation(context: Context, number: String, sinceMs: Long): Boolean {
        val targetKey = Numbers.key(number)
        if (targetKey.isEmpty()) return false
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION
                ),
                CallLog.Calls.DATE + ">=?",
                arrayOf(sinceMs.toString()),
                CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                var read = 0
                while (c.moveToNext() && read < 300) {
                    read++
                    val num = c.getString(0) ?: continue
                    if (Numbers.key(num) != targetKey) continue
                    val type = c.getInt(1)
                    val duration = c.getLong(2)
                    if (type == CallLog.Calls.OUTGOING_TYPE) return true
                    if (type == CallLog.Calls.INCOMING_TYPE && duration >= 5) return true
                }
            }
        } catch (e: SecurityException) {
            return false
        }
        return false
    }

    /**
     * Feature 45: how often the user has called each number themselves
     * (outgoing calls), as numKey -> (raw number, count). Used only to
     * *suggest* whitelist additions; nothing is changed automatically.
     * Needs READ_CALL_LOG; empty map without it.
     */
    fun outgoingCounts(context: Context, maxRows: Int = 600): Map<String, Pair<String, Int>> {
        val counts = HashMap<String, Pair<String, Int>>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                CallLog.Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE,
                null,
                CallLog.Calls.DATE + " DESC",
                null
            )?.use { c ->
                var read = 0
                while (c.moveToNext() && read < maxRows) {
                    read++
                    val num = c.getString(0) ?: continue
                    val key = Numbers.key(num)
                    if (key.isEmpty()) continue
                    val cur = counts[key]
                    counts[key] = Pair(num, (cur?.second ?: 0) + 1)
                }
            }
        } catch (e: SecurityException) {
            return emptyMap()
        }
        return counts
    }
}
