package com.mthtamim.callshield.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.mthtamim.callshield.R
import com.mthtamim.callshield.calendar.CalendarHelper
import com.mthtamim.callshield.data.DbHelper
import com.mthtamim.callshield.engine.BlockMode
import com.mthtamim.callshield.engine.HiddenRule
import com.mthtamim.callshield.engine.Profile
import com.mthtamim.callshield.engine.RuleAction
import com.mthtamim.callshield.notify.ProfileScheduler
import com.mthtamim.callshield.tile.TileUpdater
import com.mthtamim.callshield.widget.WidgetUpdater

/**
 * Features 14-17: profiles with their own rules, profile schedules,
 * emergency contacts, and the per-SIM preference screen.
 *
 * Feature 17 honesty: Android lists the phone accounts (SIMs) and this
 * screen stores a strictness wish per account — but the call-screening API
 * does not reveal which SIM an incoming call arrived on before it rings,
 * so screening always applies the global (profile) rule. The stored wish
 * is shown here as a record; it is never claimed to be in effect.
 */
class ProfilesActivity : BaseActivity() {

    private lateinit var scheduleRows: LinearLayout
    private lateinit var emergencyRows: LinearLayout
    private lateinit var simRows: LinearLayout
    private lateinit var textScheduleState: TextView

    private val profiles = Profile.values().toList()

    private lateinit var switchCalFocus: Switch
    private lateinit var spinnerCalendar: Spinner
    private lateinit var textCalState: TextView
    private var calendarIds: List<Long> = emptyList()

    /** Feature 54: READ_CALENDAR is asked for only when the user enables it. */
    private val requestCalendar =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                store().setCalendarFocusOn(true)
            } else {
                Toast.makeText(this, R.string.perm_not_granted, Toast.LENGTH_SHORT).show()
            }
            refreshCalendarCard()
        }

    private val pickEmergency =
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
                            store().addToList(number, name, DbHelper.TYPE_EMERGENCY, "contact")
                            refreshEmergency()
                        }
                    }
                }
            } catch (e: SecurityException) {
                Toast.makeText(this, R.string.perm_not_granted, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        scheduleRows = findViewById(R.id.scheduleRows)
        emergencyRows = findViewById(R.id.emergencyRows)
        simRows = findViewById(R.id.simRows)
        textScheduleState = findViewById(R.id.textScheduleState)

        val store = store()

        // ---- active profile radios (feature 14) ----
        val group = findViewById<RadioGroup>(R.id.radioActiveProfile)
        val activeRadios = mapOf(
            Profile.NORMAL to findViewById<RadioButton>(R.id.radioActiveNormal),
            Profile.STRICT to findViewById<RadioButton>(R.id.radioActiveStrict),
            Profile.FOCUS to findViewById<RadioButton>(R.id.radioActiveFocus),
            Profile.NIGHT to findViewById<RadioButton>(R.id.radioActiveNight),
            Profile.OFF to findViewById<RadioButton>(R.id.radioActiveOff)
        )
        activeRadios[store.activeProfile()]?.isChecked = true
        group.setOnCheckedChangeListener { _, checkedId ->
            val chosen = activeRadios.entries.firstOrNull { it.value.id == checkedId }?.key
            if (chosen != null) {
                store.setActiveProfile(chosen)
                afterStateChange()
            }
        }

        // ---- per-profile editor ----
        val spinnerEdit = findViewById<Spinner>(R.id.spinnerEditProfile)
        spinnerEdit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            profiles.map { profileLabel(it) }
        )
        val radioEditContacts = findViewById<RadioButton>(R.id.radioEditContacts)
        val radioEditFavorites = findViewById<RadioButton>(R.id.radioEditFavorites)
        val radioEditHiddenBlock = findViewById<RadioButton>(R.id.radioEditHiddenBlock)
        val radioEditHiddenAllow = findViewById<RadioButton>(R.id.radioEditHiddenAllow)
        val radioEditHiddenWarn = findViewById<RadioButton>(R.id.radioEditHiddenWarn)
        // Feature 48: per-profile block action.
        val radioActionReject = findViewById<RadioButton>(R.id.radioActionReject)
        val radioActionSilence = findViewById<RadioButton>(R.id.radioActionSilence)
        val radioActionWarn = findViewById<RadioButton>(R.id.radioActionWarn)

        fun loadEditor(profile: Profile) {
            if (store.profileMode(profile) == BlockMode.FAVORITES) {
                radioEditFavorites.isChecked = true
            } else {
                radioEditContacts.isChecked = true
            }
            when (store.profileHidden(profile)) {
                HiddenRule.BLOCK -> radioEditHiddenBlock.isChecked = true
                HiddenRule.ALLOW -> radioEditHiddenAllow.isChecked = true
                HiddenRule.WARN -> radioEditHiddenWarn.isChecked = true
            }
            when (store.profileAction(profile)) {
                RuleAction.REJECT -> radioActionReject.isChecked = true
                RuleAction.SILENCE -> radioActionSilence.isChecked = true
                RuleAction.WARN_ONLY -> radioActionWarn.isChecked = true
            }
        }
        spinnerEdit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                loadEditor(profiles[pos])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        loadEditor(profiles[0])

        findViewById<Button>(R.id.btnSaveProfile).setOnClickListener {
            val profile = profiles[spinnerEdit.selectedItemPosition]
            store.setProfileMode(
                profile,
                if (radioEditFavorites.isChecked) BlockMode.FAVORITES else BlockMode.CONTACTS
            )
            store.setProfileHidden(
                profile,
                when {
                    radioEditHiddenAllow.isChecked -> HiddenRule.ALLOW
                    radioEditHiddenWarn.isChecked -> HiddenRule.WARN
                    else -> HiddenRule.BLOCK
                }
            )
            store.setProfileAction(
                profile,
                when {
                    radioActionSilence.isChecked -> RuleAction.SILENCE
                    radioActionWarn.isChecked -> RuleAction.WARN_ONLY
                    else -> RuleAction.REJECT
                }
            )
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
            afterStateChange()
        }

        // ---- schedules (feature 15) ----
        val spinnerScheduleProfile = findViewById<Spinner>(R.id.spinnerScheduleProfile)
        spinnerScheduleProfile.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            profiles.map { profileLabel(it) }
        )
        val dayChecks = listOf(
            findViewById<CheckBox>(R.id.checkMon),
            findViewById<CheckBox>(R.id.checkTue),
            findViewById<CheckBox>(R.id.checkWed),
            findViewById<CheckBox>(R.id.checkThu),
            findViewById<CheckBox>(R.id.checkFri),
            findViewById<CheckBox>(R.id.checkSat),
            findViewById<CheckBox>(R.id.checkSun)
        )
        findViewById<Button>(R.id.btnAddSchedule).setOnClickListener {
            val start = TimeInputs.parse(findViewById<EditText>(R.id.editSchedStart).text.toString())
            val end = TimeInputs.parse(findViewById<EditText>(R.id.editSchedEnd).text.toString())
            var mask = 0
            for (i in dayChecks.indices) {
                if (dayChecks[i].isChecked) mask = mask or (1 shl i)
            }
            if (start == null || end == null || mask == 0 || start == end) {
                Toast.makeText(this, R.string.schedule_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.addSchedule(
                profiles[spinnerScheduleProfile.selectedItemPosition], start, end, mask
            )
            ProfileScheduler.scheduleAll(this)
            refreshSchedules()
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        }

        // ---- emergency contacts (feature 16) ----
        findViewById<Button>(R.id.btnEmergencyPick).setOnClickListener {
            val intent = Intent(
                Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            )
            pickEmergency.launch(intent)
        }
        findViewById<Button>(R.id.btnEmergencyAdd).setOnClickListener {
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
                        store().addToList(number, null, DbHelper.TYPE_EMERGENCY, "manual")
                        refreshEmergency()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // ---- calendar Focus (feature 54, v1.1) ----
        switchCalFocus = findViewById(R.id.switchCalFocus)
        spinnerCalendar = findViewById(R.id.spinnerCalendar)
        textCalState = findViewById(R.id.textCalState)
        // The switch listener is (re)attached by refreshCalendarCard(),
        // which runs below via refreshAll().
        spinnerCalendar.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val calId = calendarIds.getOrNull(pos) ?: return
                val name = parent?.getItemAtPosition(pos)?.toString() ?: ""
                if (store().calendarFocusId() != calId) {
                    store().setCalendarFocus(calId, name)
                    refreshCalendarCard()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    /** Feature 54: calendar picker contents + current Focus state line. */
    private fun refreshCalendarCard() {
        val store = store()
        // Programmatic set only: detach first so a stored ON state with a
        // since-revoked permission never auto-fires a permission request.
        switchCalFocus.setOnCheckedChangeListener(null)
        switchCalFocus.isChecked = store.calendarFocusOn()
        switchCalFocus.setOnCheckedChangeListener { _, checked ->
            if (checked && !Perms.hasCalendar(this)) {
                switchCalFocus.isChecked = false
                requestCalendar.launch(Manifest.permission.READ_CALENDAR)
            } else {
                store().setCalendarFocusOn(checked)
                refreshCalendarCard()
            }
        }
        val calendars = CalendarHelper.calendars(this)
        calendarIds = calendars.map { it.id }
        spinnerCalendar.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            calendars.map { it.name }
        )
        val chosenIdx = calendarIds.indexOf(store.calendarFocusId())
        if (chosenIdx >= 0) spinnerCalendar.setSelection(chosenIdx)
        val now = System.currentTimeMillis()
        textCalState.text = when {
            !Perms.hasCalendar(this) -> getString(R.string.cal_perm_missing)
            store.calendarFocusOn() &&
                CalendarHelper.hasOngoingEvent(this, store.calendarFocusId(), now) ->
                getString(R.string.cal_focus_active_now)
            store.calendarFocusOn() ->
                getString(
                    R.string.cal_focus_waiting,
                    store.calendarFocusName().ifEmpty { "—" }
                )
            else -> getString(R.string.cal_focus_off)
        }
        afterStateChange()
    }

    private fun afterStateChange() {
        TileUpdater.requestRefresh(this)
        WidgetUpdater.updateAll(this)
        refreshScheduleState()
    }

    private fun refreshAll() {
        refreshScheduleState()
        refreshSchedules()
        refreshEmergency()
        refreshSims()
        refreshCalendarCard()
    }

    private fun refreshScheduleState() {
        val now = System.currentTimeMillis()
        val scheduled = store().scheduledProfile(now)
        textScheduleState.text = if (scheduled != null) {
            getString(R.string.schedule_active_now, profileLabel(scheduled))
        } else {
            getString(R.string.schedule_none_now)
        }
    }

    private fun refreshSchedules() {
        scheduleRows.removeAllViews()
        val rows = store().schedules()
        if (rows.isEmpty()) {
            scheduleRows.addView(captionRow(getString(R.string.empty_list)))
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
            val state = if (row.enabled) {
                getString(R.string.sched_on)
            } else {
                getString(R.string.sched_off)
            }
            val text = profileLabel(row.profile) + " · " +
                TimeInputs.format(row.startMin) + "–" + TimeInputs.format(row.endMin) +
                " · " + days + " · " + state
            val tv = bodyRow(text)
            tv.setOnClickListener {
                store().setScheduleEnabled(row.id, !row.enabled)
                ProfileScheduler.scheduleAll(this)
                refreshSchedules()
            }
            tv.setOnLongClickListener {
                confirmDelete {
                    store().removeSchedule(row.id)
                    ProfileScheduler.scheduleAll(this)
                    refreshSchedules()
                }
                true
            }
            scheduleRows.addView(tv)
        }
    }

    private fun refreshEmergency() {
        emergencyRows.removeAllViews()
        val entries = store().listEntries(DbHelper.TYPE_EMERGENCY)
        if (entries.isEmpty()) {
            emergencyRows.addView(captionRow(getString(R.string.empty_list)))
            return
        }
        for (entry in entries) {
            val title = entry.label ?: entry.rawNumber
            val tv = bodyRow(title + " · " + entry.rawNumber)
            tv.setOnLongClickListener {
                confirmDelete {
                    store().removeListEntry(entry.id)
                    refreshEmergency()
                }
                true
            }
            emergencyRows.addView(tv)
        }
    }

    private fun refreshSims() {
        simRows.removeAllViews()
        if (!Perms.hasPhoneState(this)) {
            simRows.addView(captionRow(getString(R.string.sim_perm_missing)))
            return
        }
        val tm = getSystemService(TelecomManager::class.java)
        val accounts = try {
            tm?.callCapablePhoneAccounts ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (accounts.isEmpty()) {
            simRows.addView(captionRow(getString(R.string.sim_none_found)))
            return
        }
        val options = arrayOf(
            getString(R.string.sim_opt_global),
            getString(R.string.sim_opt_contacts),
            getString(R.string.sim_opt_favorites)
        )
        val optionValues = arrayOf("GLOBAL", "CONTACTS", "FAVORITES")
        for (handle in accounts) {
            val account = try {
                tm?.getPhoneAccount(handle)
            } catch (e: SecurityException) {
                null
            }
            val label = account?.label?.toString() ?: handle.id
            val accountKey = handle.id.hashCode().toString()
            val title = TextView(this)
            title.text = label
            title.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            simRows.addView(title)
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, options
            )
            val current = store().simMode(accountKey)
            spinner.setSelection(optionValues.indexOf(current).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, pos: Int, id: Long
                ) {
                    store().setSimMode(accountKey, optionValues[pos])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            simRows.addView(spinner)
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

    private fun profileLabel(profile: Profile): String = when (profile) {
        Profile.NORMAL -> getString(R.string.profile_normal)
        Profile.STRICT -> getString(R.string.profile_strict)
        Profile.FOCUS -> getString(R.string.profile_focus)
        Profile.NIGHT -> getString(R.string.profile_night)
        Profile.OFF -> getString(R.string.profile_off)
    }
}
