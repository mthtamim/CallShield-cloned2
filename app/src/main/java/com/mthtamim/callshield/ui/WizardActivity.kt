package com.mthtamim.callshield.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.mthtamim.callshield.R

/**
 * Feature 42: first-run setup. EVERY grant the Permissions screen lists
 * gets a step here, each with a one-line explanation and its live status:
 * role, contacts, call log, phone state, SMS read, notifications,
 * notification access, battery exemption, exact alarms — plus a clearly
 * tagged OPTIONAL section (auto-SMS replies, calendar Focus) holding the
 * two grants that are otherwise only requested lazily, on enable.
 * Welcome and done frame the grant steps. Already-granted steps show OK,
 * and Next always moves on: nothing here is a dead end.
 */
class WizardActivity : BaseActivity() {

    /** One wizard step: its strings, its live status, and how to grant it. */
    private enum class Grant {
        ROLE, CONTACTS, CALL_LOG, PHONE_STATE, SMS_READ, NOTIFICATIONS,
        NOTIFICATION_ACCESS, BATTERY, EXACT_ALARM, SEND_SMS, CALENDAR
    }

    private data class Step(
        val titleRes: Int,
        val bodyRes: Int,
        val grant: Grant?,
        val optional: Boolean = false
    )

    private val steps = listOf(
        Step(R.string.w1_title, R.string.w1_body, null),
        Step(R.string.w2_title, R.string.w2_body, Grant.ROLE),
        Step(R.string.w3_title, R.string.w3_body, Grant.CONTACTS),
        Step(R.string.w4_title, R.string.w4_body, Grant.CALL_LOG),
        Step(R.string.w8_title, R.string.w8_body, Grant.PHONE_STATE),
        Step(R.string.w9_title, R.string.w9_body, Grant.SMS_READ),
        Step(R.string.w5_title, R.string.w5_body, Grant.NOTIFICATIONS),
        Step(R.string.w10_title, R.string.w10_body, Grant.NOTIFICATION_ACCESS),
        Step(R.string.w6_title, R.string.w6_body, Grant.BATTERY),
        Step(R.string.w11_title, R.string.w11_body, Grant.EXACT_ALARM),
        Step(
            R.string.w12_title, R.string.w12_body, Grant.SEND_SMS,
            optional = true
        ),
        Step(
            R.string.w13_title, R.string.w13_body, Grant.CALENDAR,
            optional = true
        ),
        Step(R.string.w7_title, R.string.w7_body, null)
    )

    private var step = 0

    private lateinit var textProgress: TextView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var textTag: TextView
    private lateinit var textTitle: TextView
    private lateinit var textBody: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnAction: Button
    private lateinit var btnNext: Button
    private lateinit var btnBack: Button

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            showStep()
        }

    private val requestRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            showStep()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        textProgress = findViewById(R.id.textWizardProgress)
        progressBar = findViewById(R.id.progressWizard)
        textTag = findViewById(R.id.textWizardTag)
        textTitle = findViewById(R.id.textWizardStepTitle)
        textBody = findViewById(R.id.textWizardBody)
        textStatus = findViewById(R.id.textWizardStatus)
        btnAction = findViewById(R.id.btnWizardAction)
        btnNext = findViewById(R.id.btnWizardNext)
        btnBack = findViewById(R.id.btnWizardBack)

        btnAction.setOnClickListener { runStepAction() }
        btnBack.setOnClickListener {
            if (step > 0) {
                step--
                showStep()
            }
        }
        btnNext.setOnClickListener {
            if (step >= steps.size - 1) {
                store().setWizardDone()
                finish()
            } else {
                step++
                showStep()
            }
        }

        showStep()
    }

    private fun statusFor(grant: Grant): Boolean = when (grant) {
        Grant.ROLE -> Perms.roleHeld(this)
        Grant.CONTACTS -> Perms.hasContacts(this)
        Grant.CALL_LOG -> Perms.hasCallLog(this)
        Grant.PHONE_STATE -> Perms.hasPhoneState(this)
        Grant.SMS_READ -> Perms.hasSms(this)
        Grant.NOTIFICATIONS -> Perms.hasNotifications(this)
        Grant.NOTIFICATION_ACCESS -> Perms.notificationAccess(this)
        Grant.BATTERY -> Perms.batteryExempt(this)
        Grant.EXACT_ALARM -> Perms.exactAlarmAllowed(this)
        Grant.SEND_SMS -> Perms.hasSendSms(this)
        Grant.CALENDAR -> Perms.hasCalendar(this)
    }

    private fun showStep() {
        val current = steps[step]
        textProgress.text = getString(R.string.wizard_step_of, step + 1, steps.size)
        progressBar.max = steps.size
        progressBar.progress = step + 1
        textTitle.text = getString(current.titleRes)
        textBody.text = getString(current.bodyRes)
        textTag.visibility = if (current.optional) View.VISIBLE else View.GONE

        val grant = current.grant
        if (grant == null) {
            textStatus.text = ""
            btnAction.visibility = View.GONE
        } else {
            textStatus.text = getString(
                if (statusFor(grant)) R.string.perm_granted else R.string.perm_not_granted
            )
            btnAction.visibility = View.VISIBLE
            btnAction.text = getString(R.string.perm_open)
        }

        btnBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        btnNext.text = getString(
            if (step == steps.size - 1) R.string.wizard_finish else R.string.wizard_next
        )
    }

    private fun runStepAction() {
        when (steps[step].grant) {
            Grant.ROLE -> {
                val intent = Perms.roleRequestIntent(this)
                if (intent != null) {
                    requestRole.launch(intent)
                }
            }
            Grant.CONTACTS -> requestPermission.launch(Manifest.permission.READ_CONTACTS)
            Grant.CALL_LOG -> requestPermission.launch(Manifest.permission.READ_CALL_LOG)
            Grant.PHONE_STATE -> requestPermission.launch(Manifest.permission.READ_PHONE_STATE)
            Grant.SMS_READ -> requestPermission.launch(Manifest.permission.READ_SMS)
            Grant.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            Grant.NOTIFICATION_ACCESS -> {
                try {
                    startActivity(Perms.notificationAccessIntent())
                } catch (e: android.content.ActivityNotFoundException) {
                    // No settings screen on this build; nothing else to offer.
                }
            }
            Grant.BATTERY -> {
                try {
                    startActivity(Perms.batteryRequestIntent(this))
                } catch (e: android.content.ActivityNotFoundException) {
                    // See PermissionsActivity: manual path is explained in text.
                }
            }
            Grant.EXACT_ALARM -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        startActivity(Perms.exactAlarmIntent(this))
                    } catch (e: android.content.ActivityNotFoundException) {
                        // See above.
                    }
                }
            }
            Grant.SEND_SMS -> requestPermission.launch(Manifest.permission.SEND_SMS)
            Grant.CALENDAR -> requestPermission.launch(Manifest.permission.READ_CALENDAR)
            null -> {}
        }
    }

    override fun onResume() {
        super.onResume()
        showStep()
    }
}
