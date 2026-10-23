package com.mthtamim.callshield.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.mthtamim.callshield.R

/**
 * Feature 42: every grant in one place, each with its current status and a
 * plain explanation of why it is needed.
 */
class PermissionsActivity : BaseActivity() {

    private lateinit var textRole: TextView
    private lateinit var textContacts: TextView
    private lateinit var textCallLog: TextView
    private lateinit var textNotif: TextView
    private lateinit var textBattery: TextView
    private lateinit var textSms: TextView
    private lateinit var textNotifAccess: TextView
    private lateinit var textAlarm: TextView
    private lateinit var textPhone: TextView
    private lateinit var textSendSms: TextView
    private lateinit var textCalendar: TextView

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh()
        }

    private val requestRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        textRole = findViewById(R.id.textRolePermStatus)
        textContacts = findViewById(R.id.textContactsPermStatus)
        textCallLog = findViewById(R.id.textCallLogPermStatus)
        textNotif = findViewById(R.id.textNotifPermStatus)
        textBattery = findViewById(R.id.textBatteryPermStatus)
        textSms = findViewById(R.id.textSmsPermStatus)
        textNotifAccess = findViewById(R.id.textNotifAccessStatus)
        textAlarm = findViewById(R.id.textAlarmPermStatus)
        textPhone = findViewById(R.id.textPhonePermStatus)
        textSendSms = findViewById(R.id.textSendSmsPermStatus)
        textCalendar = findViewById(R.id.textCalendarPermStatus)

        findViewById<Button>(R.id.btnRole).setOnClickListener {
            val intent = Perms.roleRequestIntent(this)
            if (intent != null) {
                requestRole.launch(intent)
            }
        }
        findViewById<Button>(R.id.btnContacts).setOnClickListener {
            requestPermission.launch(Manifest.permission.READ_CONTACTS)
        }
        findViewById<Button>(R.id.btnCallLog).setOnClickListener {
            requestPermission.launch(Manifest.permission.READ_CALL_LOG)
        }
        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        findViewById<Button>(R.id.btnSms).setOnClickListener {
            requestPermission.launch(Manifest.permission.READ_SMS)
        }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            try {
                startActivity(Perms.notificationAccessIntent())
            } catch (e: android.content.ActivityNotFoundException) {
                // No settings screen on this build; nothing else to offer.
            }
        }
        findViewById<Button>(R.id.btnAlarm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(Perms.exactAlarmIntent(this))
                } catch (e: android.content.ActivityNotFoundException) {
                    // See above.
                }
            }
        }
        findViewById<Button>(R.id.btnPhone).setOnClickListener {
            requestPermission.launch(Manifest.permission.READ_PHONE_STATE)
        }
        findViewById<Button>(R.id.btnSendSms).setOnClickListener {
            requestPermission.launch(Manifest.permission.SEND_SMS)
        }
        findViewById<Button>(R.id.btnCalendar).setOnClickListener {
            requestPermission.launch(Manifest.permission.READ_CALENDAR)
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(Perms.batteryRequestIntent(this))
            } catch (e: android.content.ActivityNotFoundException) {
                // A few OEM builds hide the direct screen; the wizard text
                // explains the manual path through system settings.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun statusText(granted: Boolean): String =
        getString(if (granted) R.string.perm_granted else R.string.perm_not_granted)

    private fun refresh() {
        textRole.text = statusText(Perms.roleHeld(this))
        textContacts.text = statusText(Perms.hasContacts(this))
        textCallLog.text = statusText(Perms.hasCallLog(this))
        textNotif.text = statusText(Perms.hasNotifications(this))
        textBattery.text = statusText(Perms.batteryExempt(this))
        textSms.text = statusText(Perms.hasSms(this))
        textNotifAccess.text = statusText(Perms.notificationAccess(this))
        textAlarm.text = statusText(Perms.exactAlarmAllowed(this))
        textPhone.text = statusText(Perms.hasPhoneState(this))
        textSendSms.text = statusText(Perms.hasSendSms(this))
        textCalendar.text = statusText(Perms.hasCalendar(this))
    }
}
