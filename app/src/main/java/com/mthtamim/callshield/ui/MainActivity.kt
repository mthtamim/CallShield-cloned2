package com.mthtamim.callshield.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.mthtamim.callshield.R
import com.mthtamim.callshield.contacts.ContactsHelper
import com.mthtamim.callshield.data.DbHelper
import com.mthtamim.callshield.data.LogEntry
import com.mthtamim.callshield.engine.BlockMode
import com.mthtamim.callshield.engine.Profile
import com.mthtamim.callshield.engine.Reason
import com.mthtamim.callshield.notify.BlockActions
import com.mthtamim.callshield.notify.ProfileScheduler
import com.mthtamim.callshield.security.AppLock
import com.mthtamim.callshield.tile.TileUpdater
import com.mthtamim.callshield.widget.WidgetUpdater
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home (v1.1.1): a bottom navigation bar with five tabs — Dashboard,
 * Filtering, Smart Allow, Log, Settings — and every tab is a page of
 * this single activity, so the bar never disappears.
 *
 * The Smart Allow page holds the real Smart Allow controls (features
 * 7-11, 49, 52); the Log page holds the full call log (features 29, 30,
 * 33, 59) — search, filter, entry actions, CSV export. Both used to be
 * separate activities; each control now has exactly one home, here.
 *
 * Start tab: a cold start always opens Dashboard. The current tab is
 * kept across configuration changes only, in a process-lifetime holder
 * (never in savedInstanceState, which survives process death and made
 * cold relaunches land on the last tab). An explicit deep link
 * (EXTRA_OPEN_LOG, from a notification or shortcut) may still open the
 * Log page directly.
 *
 * Back behaviour: Back from any tab returns to Dashboard first; Back
 * from Dashboard exits.
 */
class MainActivity : BaseActivity() {

    // ---- Dashboard ----
    private lateinit var switchMaster: Switch
    private lateinit var textStatus: TextView
    private lateinit var textRoleStatus: TextView
    private lateinit var textProfile: TextView
    private lateinit var textBlockedToday: TextView

    // ---- Dashboard extras (v1.1): stat tiles, quick actions, recent
    // blocks, protection check. All read the same Store/Perms state the
    // Smart Allow page, Profiles screen and Permissions screen use. ----
    private lateinit var textBlockedWeek: TextView
    private lateinit var textLastBlocked: TextView
    private lateinit var textLastBlockedTime: TextView
    private lateinit var btnDashTemp: Button
    private lateinit var switchDashAllowNext: Switch
    private lateinit var chipStrict: Button
    private lateinit var chipNormal: Button
    private lateinit var chipOpen: Button
    private lateinit var layoutRecentRows: LinearLayout
    private lateinit var textRecentEmpty: TextView
    private lateinit var dotRole: View
    private lateinit var dotBattery: View
    private lateinit var dotPerms: View
    private lateinit var textCheckRole: TextView
    private lateinit var textCheckBattery: TextView
    private lateinit var textCheckPerms: TextView
    private lateinit var textCheckPermsState: TextView

    // ---- Pages and tabs ----
    private lateinit var pageDashboard: View
    private lateinit var pageFiltering: View
    private lateinit var pageSmart: View
    private lateinit var pageLog: View
    private lateinit var pageSettings: View
    private var currentPage = PAGE_DASHBOARD

    private lateinit var tabItems: List<LinearLayout>
    private lateinit var tabLabels: List<TextView>
    private lateinit var tabIcons: List<ImageView>
    private lateinit var tabDots: List<View>
    private lateinit var textHeroState: TextView
    private lateinit var textHeroPill: TextView

    // ---- Tab menu rows (v1.1.1): live status per row, refreshed on show ----
    private lateinit var textFilteringSummary: TextView
    private lateinit var textListsStatus: TextView
    private lateinit var textProfilesStatus: TextView
    private lateinit var textRulesStatus: TextView
    private lateinit var textSmsStatus: TextView
    private lateinit var textStatsStatus: TextView
    private lateinit var textSuggestionsStatus: TextView
    private lateinit var textTestStatus: TextView
    private lateinit var textSecurityStatus: TextView
    private lateinit var textOnlineStatus: TextView
    private lateinit var textSettingsStatus: TextView
    private lateinit var textPermissionsStatus: TextView
    private lateinit var textWizardStatus: TextView

    // ---- Log page (features 29, 30, 33, 59) ----
    private var logEntries: List<LogEntry> = emptyList()
    private lateinit var listLog: ListView
    private lateinit var textEmptyLog: TextView
    private lateinit var editLogSearch: EditText
    private var logFilterMode = 0

    private val exportCsv =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val csv = buildLogCsv()
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
            }
        }

    // ---- Smart Allow page (features 7-11, 49, 52) ----
    private val callbackOptions = intArrayOf(6, 12, 24, 48)
    private val repeatCountOptions = intArrayOf(2, 3)
    private val repeatWindowOptions = intArrayOf(3, 5, 10)

    private lateinit var textTempStatus: TextView
    private lateinit var switchAllowNext: Switch
    private lateinit var editExpected: EditText
    private lateinit var textExpectedStatus: TextView
    private lateinit var spinnerCallback: Spinner
    private lateinit var switchRepeat: Switch
    private lateinit var spinnerRepeatCount: Spinner
    private lateinit var spinnerRepeatWindow: Spinner
    private lateinit var switchAutoSms: Switch
    private lateinit var editAutoSmsText: EditText
    private lateinit var switchSmsKnown: Switch

    /** Feature 49: SEND_SMS is asked for only when the user enables the reply. */
    private val requestSendSms =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                store().setAutoSmsOn(true)
                store().setAutoSmsText(editAutoSmsText.text.toString().trim())
            } else {
                Toast.makeText(this, R.string.perm_not_granted, Toast.LENGTH_SHORT).show()
            }
            switchAutoSms.isChecked = store().autoSmsOn()
        }

    /** Feature 52: READ_SMS is asked for only when the user enables SMS-known. */
    private val requestReadSms =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                store().setSmsKnownOn(true)
            } else {
                Toast.makeText(this, R.string.perm_not_granted, Toast.LENGTH_SHORT).show()
            }
            switchSmsKnown.isChecked = store().smsKnownOn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Feature 38: the app-lock gate runs before anything else is shown.
        if (AppLock.isEnabled(this) && !AppLock.isSessionUnlocked()) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }

        // Part 2: keep the schedule-boundary and summary alarms armed.
        ProfileScheduler.scheduleAll(this)

        // Feature 57 (v1.1): the log auto-clean sweep also runs on app start.
        store().purgeOldLog(System.currentTimeMillis())

        // First run: go through the setup wizard once (feature 42).
        if (!store().wizardDone()) {
            startActivity(Intent(this, WizardActivity::class.java))
        }

        // ---- Dashboard views ----
        switchMaster = findViewById(R.id.switchMaster)
        textHeroState = findViewById(R.id.textHeroState)
        textHeroPill = findViewById(R.id.textHeroPill)
        textStatus = findViewById(R.id.textStatus)
        textRoleStatus = findViewById(R.id.textRoleStatus)
        textProfile = findViewById(R.id.textProfile)
        textBlockedToday = findViewById(R.id.textBlockedToday)

        switchMaster.setOnCheckedChangeListener { _, isChecked ->
            store().setMasterEnabled(isChecked)
            refresh()
        }

        bindDashboardExtras()
        bindMenuPages()
        bindLogPage()
        bindSmartPage()
        loadSmartState()

        // The Smart Allow switches apply immediately on toggle (the Save
        // button still writes the same values; the two paths agree).
        // Attached after loadSmartState() so loading never fires them.
        switchAllowNext.setOnCheckedChangeListener { _, isChecked ->
            store().setAllowNext(isChecked)
        }
        // Feature 49 (v1.1): the permission is requested only here, at the
        // moment of enabling.
        switchAutoSms.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Perms.hasSendSms(this)) {
                switchAutoSms.isChecked = false
                requestSendSms.launch(Manifest.permission.SEND_SMS)
            } else {
                store().setAutoSmsOn(isChecked)
                if (isChecked) {
                    store().setAutoSmsText(editAutoSmsText.text.toString().trim())
                }
            }
        }
        // Feature 52 (v1.1): same lazy-permission pattern for SMS access.
        switchSmsKnown.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Perms.hasSms(this)) {
                switchSmsKnown.isChecked = false
                requestReadSms.launch(Manifest.permission.READ_SMS)
            } else {
                store().setSmsKnownOn(isChecked)
            }
        }

        bindTabs()
        // Cold start always lands on Dashboard (v1.1.1) — the only
        // exceptions are a configuration change inside this process
        // (sessionPage survives it) and an explicit deep link.
        val startPage = when {
            intent.hasExtra(EXTRA_OPEN_LOG) -> PAGE_LOG
            else -> sessionPage
        }
        showPage(startPage)

        // Back from any tab goes to Dashboard first; Back there exits.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentPage != PAGE_DASHBOARD) {
                        showPage(PAGE_DASHBOARD)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_OPEN_LOG)) {
            showPage(PAGE_LOG)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshTemp()
        refreshExpected()
        // Menu rows and the log show live data; refresh whichever page
        // the user is returning to (a sub-screen may have changed it).
        when (currentPage) {
            PAGE_FILTERING -> refreshFilteringMenu()
            PAGE_LOG -> refreshLog()
            PAGE_SETTINGS -> refreshSettingsMenu()
        }
    }

    // ------------------------------------------------------------ tabs

    private fun bindTabs() {
        pageDashboard = findViewById(R.id.pageDashboard)
        pageFiltering = findViewById(R.id.pageFiltering)
        pageSmart = findViewById(R.id.pageSmart)
        pageLog = findViewById(R.id.pageLog)
        pageSettings = findViewById(R.id.pageSettings)
        tabItems = listOf(
            findViewById(R.id.tabDashboard),
            findViewById(R.id.tabFiltering),
            findViewById(R.id.tabSmart),
            findViewById(R.id.tabLog),
            findViewById(R.id.tabSettings)
        )
        tabLabels = listOf(
            findViewById(R.id.labelDashboard),
            findViewById(R.id.labelFiltering),
            findViewById(R.id.labelSmart),
            findViewById(R.id.labelLog),
            findViewById(R.id.labelSettings)
        )
        tabIcons = listOf(
            findViewById(R.id.iconDashboard),
            findViewById(R.id.iconFiltering),
            findViewById(R.id.iconSmart),
            findViewById(R.id.iconLog),
            findViewById(R.id.iconSettings)
        )
        tabDots = listOf(
            findViewById(R.id.dotTabDashboard),
            findViewById(R.id.dotTabFiltering),
            findViewById(R.id.dotTabSmart),
            findViewById(R.id.dotTabLog),
            findViewById(R.id.dotTabSettings)
        )
        tabItems[PAGE_DASHBOARD].setOnClickListener { showPage(PAGE_DASHBOARD) }
        tabItems[PAGE_FILTERING].setOnClickListener { showPage(PAGE_FILTERING) }
        tabItems[PAGE_SMART].setOnClickListener { showPage(PAGE_SMART) }
        tabItems[PAGE_LOG].setOnClickListener { showPage(PAGE_LOG) }
        tabItems[PAGE_SETTINGS].setOnClickListener { showPage(PAGE_SETTINGS) }
    }

    private fun showPage(page: Int) {
        currentPage = page
        sessionPage = page
        pageDashboard.visibility = if (page == PAGE_DASHBOARD) View.VISIBLE else View.GONE
        pageFiltering.visibility = if (page == PAGE_FILTERING) View.VISIBLE else View.GONE
        pageSmart.visibility = if (page == PAGE_SMART) View.VISIBLE else View.GONE
        pageLog.visibility = if (page == PAGE_LOG) View.VISIBLE else View.GONE
        pageSettings.visibility = if (page == PAGE_SETTINGS) View.VISIBLE else View.GONE
        val accent = getColor(R.color.cs_accent_dark)
        val muted = getColor(R.color.cs_text_secondary)
        for (i in tabLabels.indices) {
            val selected = i == page
            tabLabels[i].setTextColor(if (selected) accent else muted)
            tabLabels[i].setTypeface(
                null, if (selected) Typeface.BOLD else Typeface.NORMAL
            )
            tabIcons[i].setColorFilter(if (selected) accent else muted)
            // v1.2.0: the active tab also gets the small accent dot
            // under its label (invisible, not gone, so the bar never
            // changes height between tabs).
            tabDots[i].visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
        // Each page's live data is recomputed when the tab is shown,
        // not just onResume (a tab switch does not resume the activity).
        when (page) {
            PAGE_DASHBOARD -> refresh()
            PAGE_FILTERING -> refreshFilteringMenu()
            PAGE_LOG -> refreshLog()
            PAGE_SETTINGS -> refreshSettingsMenu()
        }
    }

    // ----------------------------------------------------- tab menus

    /** Filtering and Settings pages: compact rows into each sub-screen,
     *  each row carrying a live status pulled from Store/Perms. */
    private fun bindMenuPages() {
        textFilteringSummary = findViewById(R.id.textFilteringSummary)
        textListsStatus = findViewById(R.id.textListsStatus)
        textProfilesStatus = findViewById(R.id.textProfilesStatus)
        textRulesStatus = findViewById(R.id.textRulesStatus)
        textSmsStatus = findViewById(R.id.textSmsStatus)
        textStatsStatus = findViewById(R.id.textStatsStatus)
        textSuggestionsStatus = findViewById(R.id.textSuggestionsStatus)
        textTestStatus = findViewById(R.id.textTestStatus)
        textSecurityStatus = findViewById(R.id.textSecurityStatus)
        textOnlineStatus = findViewById(R.id.textOnlineStatus)
        textSettingsStatus = findViewById(R.id.textSettingsStatus)
        textPermissionsStatus = findViewById(R.id.textPermissionsStatus)
        textWizardStatus = findViewById(R.id.textWizardStatus)

        findViewById<View>(R.id.btnLists).setOnClickListener {
            startActivity(Intent(this, ListsActivity::class.java))
        }
        findViewById<View>(R.id.btnProfiles).setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        findViewById<View>(R.id.btnRules).setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        findViewById<View>(R.id.btnSms).setOnClickListener {
            startActivity(Intent(this, SmsActivity::class.java))
        }
        findViewById<View>(R.id.btnStats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        findViewById<View>(R.id.btnSuggestions).setOnClickListener {
            startActivity(Intent(this, SuggestionsActivity::class.java))
        }
        findViewById<View>(R.id.btnTest).setOnClickListener {
            startActivity(Intent(this, TestModeActivity::class.java))
        }
        findViewById<View>(R.id.btnSecurity).setOnClickListener {
            startActivity(Intent(this, SecurityActivity::class.java))
        }
        findViewById<View>(R.id.btnOnline).setOnClickListener {
            startActivity(Intent(this, OnlineActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnPermissions).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        findViewById<View>(R.id.btnWizard).setOnClickListener {
            startActivity(Intent(this, WizardActivity::class.java))
        }
    }

    private fun refreshFilteringMenu() {
        val store = store()
        val now = System.currentTimeMillis()
        textFilteringSummary.text = getString(
            R.string.filtering_summary,
            getString(if (store.masterEnabled()) R.string.menu_state_on else R.string.menu_state_off),
            profileLabel(store.effectiveProfile(now))
        )
        textListsStatus.text = getString(
            R.string.menu_status_lists,
            store.listEntries(DbHelper.TYPE_WHITELIST).size,
            store.listEntries(DbHelper.TYPE_BLACKLIST).size,
            store.prefixRules().size
        )
        textProfilesStatus.text = getString(
            R.string.profile_line, profileLabel(store.effectiveProfile(now))
        )
        textRulesStatus.text = getString(
            R.string.menu_status_rules,
            store.advRules(DbHelper.KIND_WILDCARD).size +
                store.advRules(DbHelper.KIND_TIME_WINDOW).size,
            store.hotlines().size
        )
        textSmsStatus.text = getString(
            if (store.smsMuteEnabled()) R.string.menu_status_on else R.string.menu_status_off
        )
    }

    private fun refreshSettingsMenu() {
        val store = store()
        textStatsStatus.text = getString(
            R.string.menu_status_blocked_total, store.blockedCountSince(0L)
        )
        textSuggestionsStatus.text = getString(
            R.string.menu_status_suggestions, suggestionsCount()
        )
        textTestStatus.text = getString(
            R.string.profile_line,
            profileLabel(store.effectiveProfile(System.currentTimeMillis()))
        )
        textSecurityStatus.text = getString(
            if (AppLock.isEnabled(this)) R.string.menu_status_on else R.string.menu_status_off
        )
        textOnlineStatus.text = getString(
            if (store.onlineEnabled()) R.string.menu_status_on else R.string.menu_status_off
        )
        textSettingsStatus.text = getString(
            if (store.blockMode() == BlockMode.FAVORITES) R.string.mode_favorites
            else R.string.mode_contacts
        )
        val missing = grantsMissing()
        textPermissionsStatus.text = if (missing == 0) {
            getString(R.string.menu_status_perms_all)
        } else {
            getString(R.string.menu_status_perms_missing, missing)
        }
        textWizardStatus.text = getString(
            if (store.wizardDone()) R.string.menu_status_wizard_done
            else R.string.menu_status_wizard_pending
        )
    }

    /** Every grant the Permissions screen lists, still missing. */
    private fun grantsMissing(): Int = listOf(
        Perms.roleHeld(this),
        Perms.hasContacts(this),
        Perms.hasCallLog(this),
        Perms.hasPhoneState(this),
        Perms.hasSms(this),
        Perms.hasNotifications(this),
        Perms.notificationAccess(this),
        Perms.batteryExempt(this),
        Perms.exactAlarmAllowed(this),
        Perms.hasSendSms(this),
        Perms.hasCalendar(this)
    ).count { !it }

    /** Mirrors SuggestionsActivity's two patterns (feature 45), as a count. */
    private fun suggestionsCount(): Int {
        val store = store()
        var count = 0
        if (Perms.hasCallLog(this) && Perms.hasContacts(this)) {
            for ((key, pair) in ContactsHelper.outgoingCounts(this)) {
                val (raw, calls) = pair
                if (calls < 3) continue
                if (ContactsHelper.lookup(this, raw).inContacts) continue
                if (store.isListed(key, DbHelper.TYPE_WHITELIST)) continue
                if (store.isListed(key, DbHelper.TYPE_BLACKLIST)) continue
                count++
            }
        }
        val since = System.currentTimeMillis() - 30 * 86_400_000L
        for (row in store.topByReason(Reason.REPEAT_CALLER.name, 10, since)) {
            if (row.count < 2) continue
            if (row.rawNumber == null) continue
            if (store.isListed(row.numKey, DbHelper.TYPE_WHITELIST)) continue
            count++
        }
        return count
    }

    // ------------------------------------------------------- dashboard

    private fun refresh() {
        val store = store()
        val now = System.currentTimeMillis()

        switchMaster.setOnCheckedChangeListener(null)
        switchMaster.isChecked = store.masterEnabled()
        switchMaster.setOnCheckedChangeListener { _, isChecked ->
            store().setMasterEnabled(isChecked)
            refresh()
        }

        textStatus.text = getString(
            if (store.masterEnabled()) R.string.status_on else R.string.status_off
        )
        // v1.2.0 hero state: the word and the pill mirror the same
        // master flag the status line prints — no separate state.
        val heroOn = store.masterEnabled()
        textHeroState.text = getString(
            if (heroOn) R.string.hero_active else R.string.hero_off
        )
        textHeroPill.text = getString(
            if (heroOn) R.string.pill_on else R.string.pill_off
        )
        textHeroPill.setBackgroundResource(
            if (heroOn) R.drawable.bg_pill_on else R.drawable.bg_pill_off
        )
        textHeroPill.setTextColor(
            getColor(if (heroOn) R.color.cs_on_accent else R.color.cs_red)
        )
        textRoleStatus.text = getString(
            if (Perms.roleHeld(this)) R.string.role_ok else R.string.role_missing
        )
        val effective = store.effectiveProfile(now)
        val scheduled = store.scheduledProfile(now) != null
        textProfile.text = getString(
            if (scheduled) R.string.profile_line_scheduled else R.string.profile_line,
            profileLabel(effective)
        )

        refreshDashboardExtras()
    }

    // ------------------------------------------------ dashboard extras

    private fun bindDashboardExtras() {
        textBlockedWeek = findViewById(R.id.textBlockedWeek)
        textLastBlocked = findViewById(R.id.textLastBlocked)
        textLastBlockedTime = findViewById(R.id.textLastBlockedTime)
        btnDashTemp = findViewById(R.id.btnDashTemp)
        switchDashAllowNext = findViewById(R.id.switchDashAllowNext)
        chipStrict = findViewById(R.id.chipStrict)
        chipNormal = findViewById(R.id.chipNormal)
        chipOpen = findViewById(R.id.chipOpen)
        layoutRecentRows = findViewById(R.id.layoutRecentRows)
        textRecentEmpty = findViewById(R.id.textRecentEmpty)
        dotRole = findViewById(R.id.dotRole)
        dotBattery = findViewById(R.id.dotBattery)
        dotPerms = findViewById(R.id.dotPerms)
        textCheckRole = findViewById(R.id.textCheckRole)
        textCheckBattery = findViewById(R.id.textCheckBattery)
        textCheckPerms = findViewById(R.id.textCheckPerms)
        textCheckPermsState = findViewById(R.id.textCheckPermsState)

        // Same Store calls the Smart Allow page makes (startTempAllow /
        // cancelTempAllow): one temp-allow flag, two front doors.
        btnDashTemp.setOnClickListener {
            val store = store()
            if (store.tempAllowActive(System.currentTimeMillis())) {
                store.cancelTempAllow()
            } else {
                store.startTempAllow(15, System.currentTimeMillis())
            }
            refresh()
            refreshTemp()
        }
        // Same allow-next flag the Smart Allow switch edits; refresh()
        // re-syncs both switches from the store.
        switchDashAllowNext.setOnCheckedChangeListener { _, isChecked ->
            store().setAllowNext(isChecked)
            refresh()
        }
        // Same store + tile/widget refresh ProfilesActivity runs when the
        // active-profile radio changes. "Open" is the OFF profile: every
        // call rings.
        chipStrict.setOnClickListener { setProfileFromChip(Profile.STRICT) }
        chipNormal.setOnClickListener { setProfileFromChip(Profile.NORMAL) }
        chipOpen.setOnClickListener { setProfileFromChip(Profile.OFF) }

        // The recent-blocks card switches to the Log tab page.
        findViewById<View>(R.id.cardRecent).setOnClickListener {
            showPage(PAGE_LOG)
        }
        findViewById<View>(R.id.cardProtection).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    private fun setProfileFromChip(profile: Profile) {
        store().setActiveProfile(profile)
        TileUpdater.requestRefresh(this)
        WidgetUpdater.updateAll(this)
        refresh()
    }

    /** Live dashboard data: runs from refresh() (onResume, state changes)
     *  and again when the Dashboard tab is shown. */
    private fun refreshDashboardExtras() {
        val store = store()
        val now = System.currentTimeMillis()

        // ---- stat tiles (the internal call log is the only source) ----
        val dayStart = startOfDay(now)
        textBlockedToday.text = store.blockedCountSince(dayStart).toString()
        textBlockedWeek.text = store.blockedCountSince(startOfWeek(now)).toString()
        val blocked = store.logEntries(100).filter { !it.allowed }
        val last = blocked.firstOrNull()
        if (last == null) {
            textLastBlocked.text = getString(R.string.dash_none)
            textLastBlockedTime.text = ""
        } else {
            textLastBlocked.text = last.displayName ?: last.rawNumber
                ?: getString(R.string.hidden_number_label)
            val fmt = if (last.time >= dayStart) {
                SimpleDateFormat("HH:mm", Locale.getDefault())
            } else {
                SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            }
            textLastBlockedTime.text = fmt.format(Date(last.time))
        }

        // ---- quick actions, mirrored from the store ----
        btnDashTemp.text = if (store.tempAllowActive(now)) {
            val until = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(store.tempAllowUntil()))
            getString(R.string.dash_temp_until, until)
        } else {
            getString(R.string.dash_allow_15)
        }
        switchDashAllowNext.setOnCheckedChangeListener(null)
        switchDashAllowNext.isChecked = store.allowNextArmed()
        switchDashAllowNext.setOnCheckedChangeListener { _, isChecked ->
            store().setAllowNext(isChecked)
            refresh()
        }
        // Keep the Smart Allow page switch in step too.
        switchAllowNext.isChecked = store.allowNextArmed()
        highlightChip(chipStrict, store.activeProfile() == Profile.STRICT)
        highlightChip(chipNormal, store.activeProfile() == Profile.NORMAL)
        highlightChip(chipOpen, store.activeProfile() == Profile.OFF)

        // ---- recent blocks (last 3, same reason labels as the Log) ----
        layoutRecentRows.removeAllViews()
        val recent = blocked.take(3)
        textRecentEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        val timeFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        for (entry in recent) {
            layoutRecentRows.addView(recentRow(entry, timeFmt))
        }

        // ---- protection check, computed live on every show ----
        val roleOk = Perms.roleHeld(this)
        val batteryOk = Perms.batteryExempt(this)
        val missing = missingRuntimePermissions()
        setDot(dotRole, roleOk)
        setDot(dotBattery, batteryOk)
        setDot(dotPerms, missing == 0)
        textCheckRole.text = getString(
            if (roleOk) R.string.dash_check_ok else R.string.dash_check_attention
        )
        textCheckBattery.text = getString(
            if (batteryOk) R.string.dash_check_ok else R.string.dash_check_attention
        )
        textCheckPerms.text = getString(R.string.dash_check_perms, missing)
        textCheckPermsState.text = getString(
            if (missing == 0) R.string.dash_check_ok else R.string.dash_check_attention
        )
    }

    private fun recentRow(entry: LogEntry, timeFmt: SimpleDateFormat): LinearLayout {
        // v1.2.0: recent rows are cards with a tinted block tile, the
        // same row language as the Lists/Log screens.
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setBackgroundResource(R.drawable.bg_row)
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(8)
        row.layoutParams = params

        val tile = LinearLayout(this)
        tile.gravity = android.view.Gravity.CENTER
        tile.setBackgroundResource(R.drawable.bg_tile_red)
        tile.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_block)
        icon.setColorFilter(getColor(R.color.cs_red))
        icon.layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        tile.addView(icon)
        row.addView(tile)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val textParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        textParams.marginStart = dp(10)
        texts.layoutParams = textParams

        val title = TextView(this)
        title.setTextColor(getColor(R.color.cs_text))
        title.setTypeface(null, Typeface.BOLD)
        title.textSize = 14f
        title.text = entry.displayName ?: entry.rawNumber
            ?: getString(R.string.hidden_number_label)
        val reasonEnum = try {
            Reason.valueOf(entry.reason)
        } catch (e: IllegalArgumentException) {
            Reason.NOT_IN_CONTACTS
        }
        val sub = TextView(this)
        sub.setTextColor(getColor(R.color.cs_text_secondary))
        sub.textSize = 13f
        sub.text = timeFmt.format(Date(entry.time)) + " · " +
            getString(BlockActions.reasonLabelRes(reasonEnum))
        texts.addView(title)
        texts.addView(sub)
        row.addView(texts)
        return row
    }

    private fun highlightChip(chip: Button, active: Boolean) {
        if (active) {
            chip.setBackgroundResource(R.drawable.bg_button)
            chip.setTextColor(getColor(R.color.cs_on_accent))
            chip.setTypeface(null, Typeface.BOLD)
        } else {
            // v1.2.0: unselected chips are pill outlines on the card
            // surface (bg_chip); the selected one stays filled accent.
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setTextColor(getColor(R.color.cs_text_body))
            chip.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun setDot(dot: View, ok: Boolean) {
        dot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (ok) R.color.cs_accent else R.color.cs_warning)
        )
    }

    /**
     * Runtime permissions still missing, out of the set the Permissions
     * screen offers as runtime grants (contacts, call log, notifications,
     * phone state, SMS read, SMS send, calendar). The role, battery
     * exemption, notification access and exact alarms are not runtime
     * permissions; the role and battery have their own rows above.
     * SEND_SMS / READ_SMS / READ_CALENDAR stay optional in the app (they
     * are only requested when their feature is enabled) — the count is a
     * status readout, not a demand.
     */
    private fun missingRuntimePermissions(): Int = listOf(
        Perms.hasContacts(this),
        Perms.hasCallLog(this),
        Perms.hasNotifications(this),
        Perms.hasPhoneState(this),
        Perms.hasSms(this),
        Perms.hasSendSms(this),
        Perms.hasCalendar(this)
    ).count { !it }

    private fun startOfDay(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Monday 00:00 of the current week. */
    private fun startOfWeek(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val mondayOffset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        cal.add(Calendar.DAY_OF_YEAR, -mondayOffset)
        return cal.timeInMillis
    }

    private fun profileLabel(profile: Profile): String = when (profile) {
        Profile.NORMAL -> getString(R.string.profile_normal)
        Profile.STRICT -> getString(R.string.profile_strict)
        Profile.FOCUS -> getString(R.string.profile_focus)
        Profile.NIGHT -> getString(R.string.profile_night)
        Profile.OFF -> getString(R.string.profile_off)
    }

    // ---------------------------------------------------------- Log page

    /** Features 29 and 30: every screened call with its reason, and
     *  one-tap actions from a log entry — whitelist, blacklist, call
     *  back, SMS, delete. Moved here from the former LogActivity. */
    private fun bindLogPage() {
        listLog = findViewById(R.id.listLog)
        textEmptyLog = findViewById(R.id.textEmptyLog)
        editLogSearch = findViewById(R.id.editLogSearch)

        val spinner = findViewById<Spinner>(R.id.spinnerLogFilter)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf(
                getString(R.string.log_filter_all),
                getString(R.string.log_filter_blocked),
                getString(R.string.log_filter_allowed),
                getString(R.string.log_filter_warned)
            )
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                logFilterMode = pos
                refreshLog()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        editLogSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshLog()
            }
        })
        findViewById<Button>(R.id.btnExportCsv).setOnClickListener {
            exportCsv.launch("callshield-log.csv")
        }

        listLog.setOnItemClickListener { _, _, position, _ ->
            val entry = logEntries.getOrNull(position) ?: return@setOnItemClickListener
            showLogActions(entry)
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_log_title)
                .setMessage(R.string.clear_log_msg)
                .setPositiveButton(R.string.clear_action) { _, _ ->
                    store().clearLog()
                    refreshLog()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showLogActions(entry: LogEntry) {
        val raw = entry.rawNumber
        val actions = ArrayList<String>()
        actions.add(getString(R.string.act_whitelist))
        actions.add(getString(R.string.act_blacklist))
        if (raw != null) {
            actions.add(getString(R.string.act_callback))
            actions.add(getString(R.string.act_sms))
        }
        actions.add(getString(R.string.act_delete_entry))

        AlertDialog.Builder(this)
            .setTitle(entry.displayName ?: raw ?: getString(R.string.hidden_number_label))
            .setItems(actions.toTypedArray()) { _, which ->
                val chosen = actions[which]
                when (chosen) {
                    getString(R.string.act_whitelist) -> {
                        if (raw != null) {
                            store().addToList(raw, entry.displayName, DbHelper.TYPE_WHITELIST, "log")
                            Toast.makeText(this, R.string.added_white, Toast.LENGTH_SHORT).show()
                        }
                    }
                    getString(R.string.act_blacklist) -> {
                        if (raw != null) {
                            store().addToList(raw, entry.displayName, DbHelper.TYPE_BLACKLIST, "log")
                            Toast.makeText(this, R.string.added_black, Toast.LENGTH_SHORT).show()
                        }
                    }
                    getString(R.string.act_callback) -> {
                        if (raw != null) {
                            if (entry.allowed) {
                                dial(raw)
                            } else {
                                // Feature 59 (v1.1): callback guard — this
                                // entry was blocked, so confirm first.
                                AlertDialog.Builder(this)
                                    .setTitle(R.string.callback_guard_title)
                                    .setMessage(
                                        getString(R.string.callback_guard_msg, raw)
                                    )
                                    .setPositiveButton(R.string.callback_guard_call) { _, _ ->
                                        dial(raw)
                                    }
                                    .setNegativeButton(R.string.cancel, null)
                                    .show()
                            }
                        }
                    }
                    getString(R.string.act_sms) -> {
                        if (raw != null) {
                            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$raw")))
                        }
                    }
                    getString(R.string.act_delete_entry) -> {
                        store().removeLogEntry(entry.id)
                        refreshLog()
                    }
                }
            }
            .show()
    }

    private fun dial(raw: String) {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$raw")))
    }

    private fun refreshLog() {
        // Feature 33: search + filter run against the same log table.
        logEntries = store().logSearch(editLogSearch.text.toString().trim(), logFilterMode, 300)
        val timeFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        val rows = logEntries.map { entry ->
            val title = entry.displayName ?: entry.rawNumber
                ?: getString(R.string.hidden_number_label)
            val state = getString(if (entry.allowed) R.string.log_allowed else R.string.log_blocked)
            val reasonEnum = try {
                Reason.valueOf(entry.reason)
            } catch (e: IllegalArgumentException) {
                Reason.NOT_IN_CONTACTS
            }
            val reasonText = getString(BlockActions.reasonLabelRes(reasonEnum))
            val sub = state + " · " + reasonText + " · " + timeFmt.format(Date(entry.time))
            Pair(title, sub)
        }
        listLog.adapter = RowAdapter(this, rows)
        textEmptyLog.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Feature 33: the currently shown (searched / filtered) entries as CSV. */
    private fun buildLogCsv(): String {
        val sb = StringBuilder()
        sb.append("time,number,name,allowed,reason,warn\n")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        for (entry in logEntries) {
            sb.append(iso.format(Date(entry.time))).append(",")
            sb.append(csvCell(entry.rawNumber ?: "")).append(",")
            sb.append(csvCell(entry.displayName ?: "")).append(",")
            sb.append(if (entry.allowed) "allowed" else "blocked").append(",")
            sb.append(entry.reason).append(",")
            sb.append(if (entry.warn) "1" else "0")
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun csvCell(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    // ----------------------------------------------------- Smart Allow

    private fun bindSmartPage() {
        textTempStatus = findViewById(R.id.textTempStatus2)
        switchAllowNext = findViewById(R.id.switchAllowNext)
        editExpected = findViewById(R.id.editExpected)
        textExpectedStatus = findViewById(R.id.textExpectedStatus)
        spinnerCallback = findViewById(R.id.spinnerCallback)
        switchRepeat = findViewById(R.id.switchRepeat)
        spinnerRepeatCount = findViewById(R.id.spinnerRepeatCount)
        spinnerRepeatWindow = findViewById(R.id.spinnerRepeatWindow)
        switchAutoSms = findViewById(R.id.switchAutoSms)
        editAutoSmsText = findViewById(R.id.editAutoSmsText)
        switchSmsKnown = findViewById(R.id.switchSmsKnown)

        spinnerCallback.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            callbackOptions.map { getString(R.string.hours_unit, it) }
        )
        spinnerRepeatCount.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            repeatCountOptions.map { it.toString() }
        )
        spinnerRepeatWindow.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            repeatWindowOptions.map { getString(R.string.minutes_unit, it) }
        )

        findViewById<Button>(R.id.btnT15).setOnClickListener { startTemp(15) }
        findViewById<Button>(R.id.btnT30).setOnClickListener { startTemp(30) }
        findViewById<Button>(R.id.btnT60).setOnClickListener { startTemp(60) }
        findViewById<Button>(R.id.btnTempCancel).setOnClickListener {
            store().cancelTempAllow()
            refreshTemp()
        }
        findViewById<Button>(R.id.btnExpectedSet).setOnClickListener {
            val value = editExpected.text.toString().trim()
            if (value.isEmpty()) {
                Toast.makeText(this, R.string.number_required, Toast.LENGTH_SHORT).show()
            } else {
                store().setExpected(value)
                refreshExpected()
            }
        }
        findViewById<Button>(R.id.btnExpectedClear).setOnClickListener {
            store().clearExpected()
            editExpected.setText("")
            refreshExpected()
        }
        findViewById<Button>(R.id.btnSaveSmart).setOnClickListener { saveSmart() }
    }

    private fun startTemp(minutes: Int) {
        store().startTempAllow(minutes, System.currentTimeMillis())
        refreshTemp()
    }

    private fun loadSmartState() {
        val store = store()
        switchAllowNext.isChecked = store.allowNextArmed()
        switchRepeat.isChecked = store.repeatEnabled()
        val cbIndex = callbackOptions.indexOf(store.callbackHours())
        spinnerCallback.setSelection(if (cbIndex >= 0) cbIndex else 2)
        val rcIndex = repeatCountOptions.indexOf(store.repeatCount())
        spinnerRepeatCount.setSelection(if (rcIndex >= 0) rcIndex else 0)
        val rwIndex = repeatWindowOptions.indexOf(store.repeatWindowMinutes())
        spinnerRepeatWindow.setSelection(if (rwIndex >= 0) rwIndex else 0)
        switchAutoSms.isChecked = store.autoSmsOn()
        editAutoSmsText.setText(store.autoSmsText())
        switchSmsKnown.isChecked = store.smsKnownOn()
        refreshTemp()
        refreshExpected()
    }

    private fun saveSmart() {
        val store = store()
        store.setAllowNext(switchAllowNext.isChecked)
        store.setRepeatEnabled(switchRepeat.isChecked)
        store.setCallbackHours(callbackOptions[spinnerCallback.selectedItemPosition])
        store.setRepeatCount(repeatCountOptions[spinnerRepeatCount.selectedItemPosition])
        store.setRepeatWindowMinutes(
            repeatWindowOptions[spinnerRepeatWindow.selectedItemPosition]
        )
        store.setAutoSmsText(editAutoSmsText.text.toString().trim())
        Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
    }

    private fun refreshTemp() {
        val store = store()
        val now = System.currentTimeMillis()
        textTempStatus.text = if (store.tempAllowActive(now)) {
            val until = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(store.tempAllowUntil()))
            getString(R.string.temp_active_until, until)
        } else {
            getString(R.string.temp_inactive)
        }
    }

    private fun refreshExpected() {
        val value = store().expectedValue()
        textExpectedStatus.text = if (value.isEmpty()) {
            getString(R.string.expected_inactive)
        } else {
            getString(R.string.expected_active, value)
        }
        if (editExpected.text.toString() != value) {
            editExpected.setText(value)
        }
    }

    companion object {
        const val PAGE_DASHBOARD = 0
        const val PAGE_FILTERING = 1
        const val PAGE_SMART = 2
        const val PAGE_LOG = 3
        const val PAGE_SETTINGS = 4

        /** Deep link: open MainActivity on the Log page (notifications,
         *  shortcuts). The only exception to "cold start = Dashboard". */
        const val EXTRA_OPEN_LOG = "com.mthtamim.callshield.OPEN_LOG"

        /**
         * The tab to restore after a configuration change. Process
         * lifetime only: a process death resets it to Dashboard, which
         * is exactly the v1.1.1 rule (cold start = Dashboard). Never
         * persist this in savedInstanceState or preferences.
         */
        private var sessionPage = PAGE_DASHBOARD
    }
}
