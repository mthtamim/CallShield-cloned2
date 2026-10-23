package com.mthtamim.callshield.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.mthtamim.callshield.R
import com.mthtamim.callshield.contacts.ContactsHelper
import com.mthtamim.callshield.data.DbHelper
import com.mthtamim.callshield.engine.CallFacts
import com.mthtamim.callshield.engine.Decision
import com.mthtamim.callshield.engine.DecisionEngine
import com.mthtamim.callshield.engine.Reason
import com.mthtamim.callshield.notify.BlockActions
import com.mthtamim.callshield.util.Numbers
import java.util.Calendar

/**
 * Feature 44: rule test mode. Type a number and see exactly what would
 * happen if it called right now — ALLOW or BLOCK, with the reason — using
 * the same fact gathering and the same pure engine as the real screening
 * service. Nothing is consumed (the one-shot allow and an expected-call
 * match stay armed) and nothing is written to the log.
 */
class TestModeActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        val editNumber = findViewById<EditText>(R.id.editTestNumber)
        val checkHidden = findViewById<CheckBox>(R.id.checkHidden)
        val textResult = findViewById<TextView>(R.id.textTestResult)
        val textDetail = findViewById<TextView>(R.id.textTestDetail)

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val hidden = checkHidden.isChecked
            val rawInput = editNumber.text.toString().trim()
            if (!hidden && rawInput.isEmpty()) {
                Toast.makeText(this, R.string.number_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val raw: String? = if (hidden) null else rawInput
            val result = simulate(raw)
            textResult.text = getString(
                if (result.first.allowed) R.string.test_would_allow else R.string.test_would_block,
                getString(BlockActions.reasonLabelRes(result.first.reason))
            )
            textDetail.text = result.second
        }
    }

    private fun simulate(raw: String?): Pair<Decision, String> {
        val store = store()
        val now = System.currentTimeMillis()
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
        var prefixMatch = raw?.let { store.longestPrefixMatch(it) }
        var wildcardMatch = raw?.let { store.bestWildcardMatch(it) }
        if (prefixMatch != null && wildcardMatch != null) {
            val prefixSpec = Numbers.localForm(prefixMatch.prefix).length
            val wildSpec = wildcardMatch.pattern.count { it.isDigit() }
            if (wildSpec > prefixSpec) prefixMatch = null else wildcardMatch = null
        }
        val dayStart = startOfToday(now)
        val facts = CallFacts(
            number = raw,
            inContacts = contactInfo?.inContacts == true,
            isFavorite = contactInfo?.isFavorite == true,
            blacklisted = numKey != null && store.isListed(numKey, DbHelper.TYPE_BLACKLIST),
            whitelisted = numKey != null && store.isListed(numKey, DbHelper.TYPE_WHITELIST),
            prefixMatch = prefixMatch,
            expectedMatch = raw != null && store.expectedMatch(raw),
            tempAllowActive = store.tempAllowActive(now),
            allowNextArmed = store.allowNextArmed(),
            blockedAttemptsInRepeatWindow =
                store.blockedCountSince(numKey, now - store.repeatWindowMinutes() * 60_000L),
            callbackWithinWindow = raw != null && Perms.hasCallLog(this) &&
                ContactsHelper.calledWithin(this, raw, store.callbackHours(), now),
            emergency = numKey != null && store.isListed(numKey, DbHelper.TYPE_EMERGENCY),
            hotline = raw != null && store.isHotline(raw),
            wildcardMatch = wildcardMatch,
            isInternational = raw != null && Numbers.isInternational(raw),
            groupAllowed = groupTitles.any { store.allowedGroups().contains(it) },
            windowState = store.windowStateFor(numKey, groupTitles, now),
            repeatBypassUsedToday =
                store.countByReasonSince(Reason.REPEAT_CALLER.name, numKey, dayStart)
        )
        val decision = DecisionEngine.decide(facts, store.engineSettings())
        val profile = store.effectiveProfile(now)
        val detail = StringBuilder()
        detail.append(getString(R.string.test_profile_line, profile.name))
        if (contactInfo?.displayName != null) {
            detail.append("\n")
            detail.append(getString(R.string.test_contact_line, contactInfo.displayName))
        }
        if (facts.isInternational) {
            detail.append("\n")
            detail.append(getString(R.string.test_intl_line))
        }
        return Pair(decision, detail.toString())
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
