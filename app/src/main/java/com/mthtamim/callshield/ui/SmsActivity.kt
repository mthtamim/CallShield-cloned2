package com.mthtamim.callshield.ui

import android.os.Bundle
import android.provider.Telephony
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.mthtamim.callshield.R
import com.mthtamim.callshield.contacts.ContactsHelper
import com.mthtamim.callshield.engine.SmsFilter
import com.mthtamim.callshield.engine.SmsMuteReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Features 25-28: SMS notification muting.
 *
 * Android cannot truly block an SMS for an app that is not the default SMS
 * app — the message always arrives. What this screen controls is the
 * honest maximum: notifications from non-contact senders are silenced by
 * the notification listener, OTP / transactional messages are never
 * silenced, keyword rules add extra mutes, and everything silenced is
 * listed here in the silent inbox so nothing is lost.
 */
class SmsActivity : BaseActivity() {

    private lateinit var keywordRows: LinearLayout
    private lateinit var smsRows: LinearLayout
    private lateinit var textSmsAccess: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms)

        keywordRows = findViewById(R.id.keywordRows)
        smsRows = findViewById(R.id.smsRows)
        textSmsAccess = findViewById(R.id.textSmsAccess)

        val switchSms = findViewById<Switch>(R.id.switchSmsMute)
        switchSms.isChecked = store().smsMuteEnabled()
        switchSms.setOnCheckedChangeListener { _, checked ->
            store().setSmsMuteEnabled(checked)
        }

        findViewById<Button>(R.id.btnSmsAccess).setOnClickListener {
            try {
                startActivity(Perms.notificationAccessIntent())
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.perm_not_granted, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnAddKeyword).setOnClickListener {
            val input = findViewById<EditText>(R.id.editKeyword)
            val word = input.text.toString().trim()
            if (word.isEmpty()) {
                Toast.makeText(this, R.string.keyword_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store().addKeyword(word)
            input.text.clear()
            refreshKeywords()
        }

        findViewById<Button>(R.id.btnClearSms).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_log_title)
                .setMessage(R.string.clear_log_msg)
                .setPositiveButton(R.string.clear_action) { _, _ ->
                    store().clearSmsLog()
                    refreshInbox()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        textSmsAccess.text = getString(
            R.string.sms_access_status,
            getString(
                if (Perms.notificationAccess(this)) {
                    R.string.perm_granted
                } else {
                    R.string.perm_not_granted
                }
            )
        )
        refreshKeywords()
        refreshInbox()
    }

    private fun refreshKeywords() {
        keywordRows.removeAllViews()
        val rows = store().keywords()
        if (rows.isEmpty()) {
            keywordRows.addView(captionRow(getString(R.string.empty_list)))
            return
        }
        for (row in rows) {
            val tv = bodyRow(row.word)
            tv.setOnLongClickListener {
                store().removeKeyword(row.id)
                refreshKeywords()
                true
            }
            keywordRows.addView(tv)
        }
    }

    private fun refreshInbox() {
        smsRows.removeAllViews()
        val timeFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        var shown = 0
        for (entry in store().smsLog(100)) {
            val reasonText = getString(
                if (entry.reason == SmsMuteReason.KEYWORD.name) {
                    R.string.sms_reason_keyword
                } else {
                    R.string.sms_reason_unknown
                }
            )
            val sender = entry.sender ?: ""
            val snippet = entry.snippet ?: ""
            val tv = bodyRow(
                sender + " · " + reasonText + " · " + timeFmt.format(Date(entry.time)) +
                    if (snippet.isNotEmpty()) "\n" + snippet else ""
            )
            smsRows.addView(tv)
            shown++
        }
        // Preview: recent inbox messages that the same rules WOULD mute.
        // Shown only when nothing has actually been muted yet, so the user
        // can see the filter working before trusting it.
        if (shown == 0 && Perms.hasSms(this)) {
            val previews = previewMuted()
            if (previews.isEmpty()) {
                smsRows.addView(captionRow(getString(R.string.empty_list)))
            } else {
                smsRows.addView(captionRow(getString(R.string.sms_preview_label)))
                for (row in previews) {
                    smsRows.addView(bodyRow(row))
                }
            }
        } else if (shown == 0) {
            smsRows.addView(captionRow(getString(R.string.empty_list)))
        }
    }

    /** Reads the newest inbox SMS and applies the same pure filter, read-only. */
    private fun previewMuted(): List<String> {
        val out = ArrayList<String>()
        val keywords = store().keywords().map { it.word }
        try {
            contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE
                ),
                null, null,
                Telephony.Sms.DATE + " DESC"
            )?.use { c ->
                var read = 0
                while (c.moveToNext() && read < 100 && out.size < 20) {
                    read++
                    val address = c.getString(0) ?: continue
                    val body = c.getString(1) ?: ""
                    val inContacts = Perms.hasContacts(this) &&
                        ContactsHelper.lookup(this, address).inContacts
                    val reason = SmsFilter.decide(true, inContacts, address, body, keywords)
                    if (reason != SmsMuteReason.NOT_MUTED) {
                        val shown = address + " · " + body.take(60).replace("\n", " ")
                        out.add(shown)
                    }
                }
            }
        } catch (e: SecurityException) {
            // READ_SMS was revoked after the check: no preview.
        }
        return out
    }

    private fun bodyRow(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setPadding(0, 10, 0, 10)
        return tv
    }

    private fun captionRow(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.setPadding(0, 6, 0, 6)
        return tv
    }
}
