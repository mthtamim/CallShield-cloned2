package com.mthtamim.callshield.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.mthtamim.callshield.R

class WizardActivity : BaseActivity() {

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
        Step(R.string.w12_title, R.string.w12_body, Grant.SEND_SMS, optional = true),
        Step(R.string.w13_title, R.string.w13_body, Grant.CALENDAR, optional = true),
        Step(R.string.w7_title, R.string.w7_body, null)
    )

    private lateinit var container: LinearLayout
    private lateinit var btnFinish: Button

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    private val requestRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        container = findViewById(R.id.wizardPermissionsContainer)
        btnFinish = findViewById(R.id.btnWizardFinish)

        btnFinish.setOnClickListener {
            store().setWizardDone()
            finish()
        }

        refreshStatus()
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

    private fun refreshStatus() {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (step in steps) {
            // Skip steps that are just welcome/done messages without grants if we are showing all at once
            if (step.grant == null) continue

            val view = inflater.inflate(R.layout.item_wizard_permission, container, false)
            val textTitle = view.findViewById<TextView>(R.id.textPermTitle)
            val textBody = view.findViewById<TextView>(R.id.textPermBody)
            val textTag = view.findViewById<TextView>(R.id.textPermTag)
            val textStatus = view.findViewById<TextView>(R.id.textPermStatus)
            val btnAction = view.findViewById<Button>(R.id.btnPermAction)

            textTitle.text = getString(step.titleRes)
            textBody.text = getString(step.bodyRes)
            textTag.visibility = if (step.optional) View.VISIBLE else View.GONE

            val isGranted = statusFor(step.grant)
            textStatus.text = getString(
                if (isGranted) R.string.perm_granted else R.string.perm_not_granted
            )

            if (isGranted) {
                btnAction.visibility = View.GONE
                // Option: dim the card to show it's done
                view.alpha = 0.6f
            } else {
                btnAction.visibility = View.VISIBLE
                btnAction.setOnClickListener { runStepAction(step.grant) }
            }

            container.addView(view)
        }
    }

    private fun runStepAction(grant: Grant) {
        when (grant) {
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
                } catch (e: android.content.ActivityNotFoundException) {}
            }
            Grant.BATTERY -> {
                try {
                    startActivity(Perms.batteryRequestIntent(this))
                } catch (e: android.content.ActivityNotFoundException) {}
            }
            Grant.EXACT_ALARM -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        startActivity(Perms.exactAlarmIntent(this))
                    } catch (e: android.content.ActivityNotFoundException) {}
                }
            }
            Grant.SEND_SMS -> requestPermission.launch(Manifest.permission.SEND_SMS)
            Grant.CALENDAR -> requestPermission.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
}
