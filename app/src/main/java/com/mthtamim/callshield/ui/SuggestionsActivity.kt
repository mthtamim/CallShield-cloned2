package com.mthtamim.callshield.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.mthtamim.callshield.R
import com.mthtamim.callshield.contacts.ContactsHelper
import com.mthtamim.callshield.data.DbHelper
import com.mthtamim.callshield.engine.Reason

/**
 * Feature 45: smart suggestions — SUGGEST ONLY. Two patterns are worth a
 * nudge: numbers the user themselves calls again and again without saving
 * them (probably worth whitelisting or adding to Contacts), and numbers
 * that keep getting through on the repeat-caller bypass (probably worth
 * whitelisting properly). Tapping a suggestion asks first; nothing here
 * ever changes a list on its own.
 */
class SuggestionsActivity : BaseActivity() {

    private lateinit var dialedRows: LinearLayout
    private lateinit var repeatRows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_suggestions)

        dialedRows = findViewById(R.id.dialedRows)
        repeatRows = findViewById(R.id.repeatRows)
        refresh()
    }

    private fun refresh() {
        val store = store()
        dialedRows.removeAllViews()
        repeatRows.removeAllViews()

        // Numbers the user dials often but has not saved.
        var dialedShown = 0
        if (Perms.hasCallLog(this) && Perms.hasContacts(this)) {
            val counts = ContactsHelper.outgoingCounts(this)
            val sorted = counts.entries.sortedByDescending { it.value.second }
            for ((key, pair) in sorted) {
                if (dialedShown >= 10) break
                val (raw, count) = pair
                if (count < 3) continue
                if (ContactsHelper.lookup(this, raw).inContacts) continue
                if (store.isListed(key, DbHelper.TYPE_WHITELIST)) continue
                if (store.isListed(key, DbHelper.TYPE_BLACKLIST)) continue
                val tv = bodyRow(getString(R.string.suggest_dialed_row, raw, count))
                tv.setOnClickListener { confirmWhitelist(raw) }
                dialedRows.addView(tv)
                dialedShown++
            }
        }
        if (dialedShown == 0) {
            dialedRows.addView(captionRow(getString(R.string.suggest_none)))
        }

        // Numbers repeatedly let through by the repeat-caller bypass.
        var repeatShown = 0
        val since = System.currentTimeMillis() - 30 * 86_400_000L
        for (row in store.topByReason(Reason.REPEAT_CALLER.name, 10, since)) {
            if (row.count < 2) continue
            val raw = row.rawNumber ?: continue
            if (store.isListed(row.numKey, DbHelper.TYPE_WHITELIST)) continue
            val tv = bodyRow(getString(R.string.suggest_repeat_row, raw, row.count))
            tv.setOnClickListener { confirmWhitelist(raw) }
            repeatRows.addView(tv)
            repeatShown++
        }
        if (repeatShown == 0) {
            repeatRows.addView(captionRow(getString(R.string.suggest_none)))
        }
    }

    private fun confirmWhitelist(raw: String) {
        AlertDialog.Builder(this)
            .setTitle(raw)
            .setMessage(R.string.suggest_confirm_msg)
            .setPositiveButton(R.string.act_whitelist) { _, _ ->
                store().addToList(raw, null, DbHelper.TYPE_WHITELIST, "suggestion")
                Toast.makeText(this, R.string.added_white, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
