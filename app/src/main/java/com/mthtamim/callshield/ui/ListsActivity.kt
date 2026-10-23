package com.mthtamim.callshield.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.mthtamim.callshield.R
import com.mthtamim.callshield.data.DbHelper
import com.mthtamim.callshield.data.ListEntry
import com.mthtamim.callshield.data.PrefixRule
import com.mthtamim.callshield.data.ruleActionFromStored
import com.mthtamim.callshield.engine.RuleAction

/**
 * Feature 4 and 19: the user's own whitelist and blacklist (numbers typed by
 * hand, picked from Contacts, or added from the log and notifications) and
 * the prefix rules. Long-press deletes.
 */
class ListsActivity : BaseActivity() {

    /**
     * v1.2.0: the Add button is a tinted outline, recoloured per section
     * (green whitelist, red blacklist, blue prefixes) — the tint lands on
     * the outline drawable's stroke, and the label takes the same colour.
     * Look only: the button, its id and its click action are unchanged.
     */
    private fun tintAddButton(pos: Int) {
        val btn = findViewById<Button>(R.id.btnAddManual)
        btn.setBackgroundResource(R.drawable.bg_button_outline)
        val colour = when (pos) {
            1 -> R.color.cs_red
            2 -> R.color.cs_blue
            else -> R.color.cs_accent_dark
        }
        btn.backgroundTintList = ColorStateList.valueOf(getColor(colour))
        btn.setTextColor(getColor(colour))
    }

    private var listType = 0 // 0 whitelist, 1 blacklist, 2 prefix rules
    private var entries: List<ListEntry> = emptyList()
    private var rules: List<PrefixRule> = emptyList()

    private lateinit var listView: ListView
    private lateinit var textEmpty: TextView
    private lateinit var textHint: TextView
    private lateinit var btnPick: Button

    private val pickContact =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val number = c.getString(0)
                        val name = c.getString(1)
                        if (number != null) {
                            addNumber(number, name, "contact")
                        }
                    }
                }
            } catch (e: SecurityException) {
                Toast.makeText(this, R.string.perm_not_granted, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lists)

        listView = findViewById(R.id.listItems)
        textEmpty = findViewById(R.id.textEmptyList)
        textHint = findViewById(R.id.textListHint)
        btnPick = findViewById(R.id.btnPickContact)

        val spinner = findViewById<Spinner>(R.id.spinnerListType)
        val types = arrayOf(
            getString(R.string.list_type_white),
            getString(R.string.list_type_black),
            getString(R.string.list_type_prefix)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                listType = pos
                tintAddButton(pos)
                refresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnAddManual).setOnClickListener {
            if (listType == 2) showAddPrefixDialog() else showAddNumberDialog()
        }
        btnPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickContact.launch(intent)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(position)
            true
        }

        // Feature 48 (v1.1): tapping a blacklist entry or a blocking
        // prefix rule chooses how it blocks (reject / silence / warn only).
        listView.setOnItemClickListener { _, _, position, _ ->
            when (listType) {
                1 -> entries.getOrNull(position)?.let { entry ->
                    showActionChooser(ruleActionFromStored(entry.blockAction)) { action ->
                        store().setListEntryAction(entry.id, action)
                    }
                }
                2 -> rules.getOrNull(position)?.let { rule ->
                    if (rule.action == DbHelper.ACTION_BLOCK) {
                        showActionChooser(ruleActionFromStored(rule.blockAction)) { action ->
                            store().setPrefixRuleAction(rule.id, action)
                        }
                    }
                }
            }
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
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addNumber(number: String, label: String?, source: String) {
        val type = if (listType == 1) DbHelper.TYPE_BLACKLIST else DbHelper.TYPE_WHITELIST
        store().addToList(number, label, type, source)
        Toast.makeText(
            this,
            if (type == DbHelper.TYPE_BLACKLIST) R.string.added_black else R.string.added_white,
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    private fun showAddNumberDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.input_number_hint)
        input.inputType = android.text.InputType.TYPE_CLASS_PHONE
        AlertDialog.Builder(this)
            .setTitle(R.string.add_number_title)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val number = input.text.toString().trim()
                if (number.isEmpty()) {
                    Toast.makeText(this, R.string.number_required, Toast.LENGTH_SHORT).show()
                } else {
                    addNumber(number, null, "manual")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddPrefixDialog() {
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(48, 24, 48, 8)
        val inputPrefix = EditText(this)
        inputPrefix.hint = getString(R.string.input_prefix_hint)
        inputPrefix.inputType = android.text.InputType.TYPE_CLASS_PHONE
        val inputLabel = EditText(this)
        inputLabel.hint = getString(R.string.input_label_hint)
        layout.addView(inputPrefix)
        layout.addView(inputLabel)

        val actions = arrayOf(
            getString(R.string.prefix_action_allow),
            getString(R.string.prefix_action_block)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_prefix_title)
            .setView(layout)
            .setSingleChoiceItems(actions, 0, null)
            .setPositiveButton(R.string.add) { dialog, _ ->
                val prefix = inputPrefix.text.toString().trim()
                if (prefix.isEmpty()) {
                    Toast.makeText(this, R.string.number_required, Toast.LENGTH_SHORT).show()
                } else {
                    val checked = (dialog as AlertDialog).listView.checkedItemPosition
                    val action = if (checked == 1) DbHelper.ACTION_BLOCK else DbHelper.ACTION_ALLOW
                    val label = inputLabel.text.toString().trim().ifEmpty { null }
                    store().addPrefixRule(prefix, action, label)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(position: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_entry_title)
            .setMessage(R.string.delete_entry_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (listType == 2) {
                    val rule = rules.getOrNull(position) ?: return@setPositiveButton
                    store().removePrefixRule(rule.id)
                } else {
                    val entry = entries.getOrNull(position) ?: return@setPositiveButton
                    store().removeListEntry(entry.id)
                }
                Toast.makeText(this, R.string.removed_item, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sourceLabel(source: String?): String = when (source) {
        "log" -> getString(R.string.source_log)
        "contact" -> getString(R.string.source_contact)
        "notification" -> getString(R.string.source_notification)
        else -> getString(R.string.source_manual)
    }

    private fun refresh() {
        val store = store()
        btnPick.visibility = if (listType == 2) View.GONE else View.VISIBLE
        textHint.text = getString(
            when (listType) {
                0 -> R.string.hint_white
                1 -> R.string.hint_black
                else -> R.string.hint_prefix
            }
        )
        val rows: List<Pair<String, String>> = if (listType == 2) {
            rules = store.prefixRules()
            rules.map { rule ->
                var actionText = getString(
                    if (rule.action == DbHelper.ACTION_BLOCK) {
                        R.string.prefix_action_block
                    } else {
                        R.string.prefix_action_allow
                    }
                )
                if (rule.action == DbHelper.ACTION_BLOCK) {
                    val blockAction = ruleActionFromStored(rule.blockAction)
                    if (blockAction != RuleAction.REJECT) {
                        actionText += " · " + actionLabel(blockAction)
                    }
                }
                val title = (rule.label ?: rule.prefix) + "  (" + rule.prefix + ")"
                Pair(title, actionText)
            }
        } else {
            val type = if (listType == 1) DbHelper.TYPE_BLACKLIST else DbHelper.TYPE_WHITELIST
            entries = store.listEntries(type)
            entries.map { entry ->
                val title = entry.label ?: entry.rawNumber
                var sub = entry.rawNumber + " · " + sourceLabel(entry.source)
                if (listType == 1) {
                    val blockAction = ruleActionFromStored(entry.blockAction)
                    if (blockAction != RuleAction.REJECT) {
                        sub += " · " + actionLabel(blockAction)
                    }
                }
                Pair(title, sub)
            }
        }
        listView.adapter = RowAdapter(this, rows)
        textEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }
}
