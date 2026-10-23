package com.mthtamim.callshield.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.mthtamim.callshield.R
import com.mthtamim.callshield.contacts.ContactsHelper
import com.mthtamim.callshield.data.DbHelper
import com.mthtamim.callshield.data.ruleActionFromStored
import com.mthtamim.callshield.engine.RuleAction
import com.mthtamim.callshield.util.Numbers

/**
 * Features 20-24: wildcard rules, the international-call rule, the
 * hotline allowlist, contact-group allow and per-number/group time windows.
 * Tap toggles where a row has an on/off state; long-press deletes.
 */
class RulesActivity : BaseActivity() {

    private lateinit var wildcardRows: LinearLayout
    private lateinit var hotlineRows: LinearLayout
    private lateinit var groupRows: LinearLayout
    private lateinit var windowRows: LinearLayout
    private lateinit var spinnerWindowGroup: Spinner
    private lateinit var editWindowNumber: EditText
    private var groupTitles: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        wildcardRows = findViewById(R.id.wildcardRows)
        hotlineRows = findViewById(R.id.hotlineRows)
        groupRows = findViewById(R.id.groupRows)
        windowRows = findViewById(R.id.windowRows)
        spinnerWindowGroup = findViewById(R.id.spinnerWindowGroup)
        editWindowNumber = findViewById(R.id.editWindowNumber)

        // Wildcard add (feature 20)
        findViewById<Button>(R.id.btnAddWildcard).setOnClickListener {
            val pattern = findViewById<EditText>(R.id.editWildcard).text.toString().trim()
            val clean = pattern.filter { it.isDigit() || it == '*' }
            if (clean.isEmpty() || !clean.contains('*')) {
                Toast.makeText(this, R.string.wildcard_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val block = findViewById<RadioButton>(R.id.radioWildcardBlock).isChecked
            store().addWildcardRule(
                clean,
                if (block) DbHelper.ACTION_BLOCK else DbHelper.ACTION_ALLOW,
                null
            )
            findViewById<EditText>(R.id.editWildcard).text.clear()
            refreshWildcards()
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        }

        // International rule (feature 21)
        val switchIntl = findViewById<Switch>(R.id.switchIntl)
        switchIntl.isChecked = store().intlBlock()
        switchIntl.setOnCheckedChangeListener { _, checked ->
            store().setIntlBlock(checked)
        }

        // Hotline add (feature 22)
        findViewById<Button>(R.id.btnAddHotline).setOnClickListener {
            val container = LinearLayout(this)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding(40, 8, 40, 0)
            val numberInput = EditText(this)
            numberInput.hint = getString(R.string.input_number_hint)
            numberInput.inputType = android.text.InputType.TYPE_CLASS_PHONE
            val labelInput = EditText(this)
            labelInput.hint = getString(R.string.input_label_hint)
            container.addView(numberInput)
            container.addView(labelInput)
            AlertDialog.Builder(this)
                .setTitle(R.string.add_number_title)
                .setView(container)
                .setPositiveButton(R.string.add) { _, _ ->
                    val number = numberInput.text.toString().trim()
                    if (number.isEmpty()) {
                        Toast.makeText(this, R.string.number_required, Toast.LENGTH_SHORT).show()
                    } else {
                        val label = labelInput.text.toString().trim().ifEmpty { null }
                        store().addHotline(number, label)
                        refreshHotlines()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Window target switch
        findViewById<RadioButton>(R.id.radioWindowNumber).setOnCheckedChangeListener { _, checked ->
            if (checked) {
                editWindowNumber.visibility = View.VISIBLE
                spinnerWindowGroup.visibility = View.GONE
            }
        }
        findViewById<RadioButton>(R.id.radioWindowGroup).setOnCheckedChangeListener { _, checked ->
            if (checked) {
                editWindowNumber.visibility = View.GONE
                spinnerWindowGroup.visibility = View.VISIBLE
            }
        }

        // Time window add (feature 24)
        val dayChecks = listOf(
            findViewById<CheckBox>(R.id.checkWMon),
            findViewById<CheckBox>(R.id.checkWTue),
            findViewById<CheckBox>(R.id.checkWWed),
            findViewById<CheckBox>(R.id.checkWThu),
            findViewById<CheckBox>(R.id.checkWFri),
            findViewById<CheckBox>(R.id.checkWSat),
            findViewById<CheckBox>(R.id.checkWSun)
        )
        findViewById<Button>(R.id.btnAddWindow).setOnClickListener {
            val start = TimeInputs.parse(findViewById<EditText>(R.id.editWindowStart).text.toString())
            val end = TimeInputs.parse(findViewById<EditText>(R.id.editWindowEnd).text.toString())
            var mask = 0
            for (i in dayChecks.indices) {
                if (dayChecks[i].isChecked) mask = mask or (1 shl i)
            }
            val byGroup = findViewById<RadioButton>(R.id.radioWindowGroup).isChecked
            val pattern: String
            val label: String?
            if (byGroup) {
                val title = groupTitles.getOrNull(spinnerWindowGroup.selectedItemPosition)
                if (title == null) {
                    Toast.makeText(this, R.string.groups_none, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pattern = "g:" + title
                label = title
            } else {
                val number = editWindowNumber.text.toString().trim()
                if (number.isEmpty()) {
                    Toast.makeText(this, R.string.number_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pattern = Numbers.key(number)
                label = number
            }
            if (start == null || end == null || mask == 0 || start == end) {
                Toast.makeText(this, R.string.schedule_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store().addWindowRule(pattern, start, end, mask, label)
            refreshWindows()
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshGroups()
        refreshWindows()
    }

    private fun refreshAll() {
        refreshWildcards()
        refreshHotlines()
        refreshGroups()
        refreshWindows()
    }

    private fun refreshWildcards() {
        wildcardRows.removeAllViews()
        val rows = store().advRules(DbHelper.KIND_WILDCARD)
        if (rows.isEmpty()) {
            wildcardRows.addView(captionRow(getString(R.string.empty_list)))
            return
        }
        for (row in rows) {
            var action = getString(
                if (row.action == DbHelper.ACTION_BLOCK) {
                    R.string.prefix_action_block
                } else {
                    R.string.prefix_action_allow
                }
            )
            val isBlock = row.action == DbHelper.ACTION_BLOCK
            val blockAction = ruleActionFromStored(row.blockAction)
            if (isBlock && blockAction != RuleAction.REJECT) {
                action += " · " + actionLabel(blockAction)
            }
            val tv = bodyRow(row.pattern + " · " + action)
            if (isBlock) {
                // Feature 48 (v1.1): tap chooses how this rule blocks.
                tv.setOnClickListener {
                    showActionChooser(blockAction) { chosen ->
                        store().setWildcardRuleAction(row.id, chosen)
                    }
                }
            }
            tv.setOnLongClickListener {
                confirmDelete {
                    store().removeAdvRule(row.id)
                    refreshWildcards()
                }
                true
            }
            wildcardRows.addView(tv)
        }
    }

    private fun actionLabel(action: RuleAction): String = getString(
        when (action) {
            RuleAction.REJECT -> R.string.action_reject
            RuleAction.SILENCE -> R.string.action_silence
            RuleAction.WARN_ONLY -> R.string.action_warn_only
        }
    )

    private fun showActionChooser(current: RuleAction, apply: (RuleAction) -> Unit) {
        val options = arrayOf(RuleAction.REJECT, RuleAction.SILENCE, RuleAction.WARN_ONLY)
        AlertDialog.Builder(this)
            .setTitle(R.string.block_action_choose_title)
            .setSingleChoiceItems(
                options.map { actionLabel(it) }.toTypedArray(),
                options.indexOf(current),
                null
            )
            .setPositiveButton(R.string.save) { dialog, _ ->
                val idx = (dialog as AlertDialog).listView.checkedItemPosition
                apply(options[idx.coerceIn(0, options.size - 1)])
                Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
                refreshWildcards()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshHotlines() {
        hotlineRows.removeAllViews()
        val rows = store().hotlines()
        if (rows.isEmpty()) {
            hotlineRows.addView(captionRow(getString(R.string.empty_list)))
            return
        }
        for (row in rows) {
            val state = getString(if (row.enabled) R.string.sched_on else R.string.sched_off)
            val builtin = if (row.builtin) " · " + getString(R.string.hotline_builtin) else ""
            val label = row.label ?: ""
            val tv = bodyRow(row.rawNumber + " · " + label + builtin + " · " + state)
            tv.setOnClickListener {
                store().setHotlineEnabled(row.id, !row.enabled)
                refreshHotlines()
            }
            tv.setOnLongClickListener {
                confirmDelete {
                    store().removeHotline(row.id)
                    refreshHotlines()
                }
                true
            }
            hotlineRows.addView(tv)
        }
    }

    private fun refreshGroups() {
        groupRows.removeAllViews()
        groupTitles = if (Perms.hasContacts(this)) {
            ContactsHelper.allGroupTitles(this)
        } else {
            emptyList()
        }
        spinnerWindowGroup.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, groupTitles
        )
        if (groupTitles.isEmpty()) {
            groupRows.addView(captionRow(getString(R.string.groups_none)))
            return
        }
        val allowed = store().allowedGroups()
        for (title in groupTitles) {
            val check = CheckBox(this)
            check.text = title
            check.textSize = 14f
            check.isChecked = allowed.contains(title)
            check.setOnCheckedChangeListener { _, checked ->
                val current = HashSet(store().allowedGroups())
                if (checked) current.add(title) else current.remove(title)
                store().setAllowedGroups(current)
            }
            groupRows.addView(check)
        }
    }

    private fun refreshWindows() {
        windowRows.removeAllViews()
        val rows = store().advRules(DbHelper.KIND_TIME_WINDOW)
        if (rows.isEmpty()) {
            windowRows.addView(captionRow(getString(R.string.empty_list)))
            return
        }
        val dayNames = arrayOf(
            R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu,
            R.string.day_fri, R.string.day_sat, R.string.day_sun
        )
        for (row in rows) {
            val days = StringBuilder()
            for (i in 0..6) {
                if ((row.daysMask and (1 shl i)) != 0) {
                    if (days.isNotEmpty()) days.append(" ")
                    days.append(getString(dayNames[i]))
                }
            }
            val target = row.label ?: row.pattern
            val tv = bodyRow(
                target + " · " + TimeInputs.format(row.startMin) + "–" +
                    TimeInputs.format(row.endMin) + " · " + days
            )
            tv.setOnLongClickListener {
                confirmDelete {
                    store().removeAdvRule(row.id)
                    refreshWindows()
                }
                true
            }
            windowRows.addView(tv)
        }
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

    private fun confirmDelete(action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_entry_title)
            .setMessage(R.string.delete_entry_msg)
            .setPositiveButton(R.string.delete) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
