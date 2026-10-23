package com.mthtamim.callshield.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.mthtamim.callshield.R
import com.mthtamim.callshield.data.Backup
import com.mthtamim.callshield.security.AppLock

/**
 * Features 38-40: app lock setup (PIN hash + biometric), the plain storage
 * statement, and the AES-GCM encrypted backup export / import.
 */
class SecurityActivity : BaseActivity() {

    private lateinit var textLockState: TextView
    private lateinit var switchLock: Switch
    private lateinit var switchBiometric: Switch

    private val exportFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val content = Backup.exportEncrypted(store())
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
            }
        }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val content = contentResolver.openInputStream(uri)?.use { input ->
                    String(input.readBytes(), Charsets.UTF_8)
                } ?: ""
                val ok = Backup.importEncrypted(store(), content)
                Toast.makeText(
                    this,
                    if (ok) R.string.backup_done else R.string.backup_failed,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security)

        textLockState = findViewById(R.id.textLockState)
        switchLock = findViewById(R.id.switchLock)
        switchBiometric = findViewById(R.id.switchBiometric)

        switchLock.isChecked = store().lockEnabled()
        switchBiometric.isChecked = store().biometricEnabled()

        switchLock.setOnCheckedChangeListener { _, checked ->
            if (checked && !AppLock.isSetUp(this)) {
                // Turning the lock on without a PIN would lock nobody out
                // and confuse everyone: ask for the PIN first.
                switchLock.isChecked = false
                showSetPinDialog()
            } else {
                store().setLockEnabled(checked)
                if (!checked) AppLock.lockNow()
                refresh()
            }
        }
        switchBiometric.setOnCheckedChangeListener { _, checked ->
            store().setBiometricEnabled(checked)
        }

        findViewById<Button>(R.id.btnSetPin).setOnClickListener { showSetPinDialog() }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            exportFile.launch("callshield-backup.json")
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            importFile.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        refresh()
    }

    private fun refresh() {
        textLockState.text = getString(
            if (AppLock.isSetUp(this)) R.string.lock_state_set else R.string.lock_state_unset
        )
    }

    private fun showSetPinDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(40, 8, 40, 0)
        val pinInput = EditText(this)
        pinInput.hint = getString(R.string.pin_hint)
        pinInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val confirmInput = EditText(this)
        confirmInput.hint = getString(R.string.pin_confirm_hint)
        confirmInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        container.addView(pinInput)
        container.addView(confirmInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_set_pin)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = pinInput.text.toString()
                val confirm = confirmInput.text.toString()
                if (pin.length < 4 || pin != confirm) {
                    Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                } else {
                    AppLock.setPin(this, pin)
                    // The switch listener sees the PIN is now set up and
                    // simply stores the enabled flag.
                    switchLock.isChecked = true
                    store().setLockEnabled(true)
                    Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
