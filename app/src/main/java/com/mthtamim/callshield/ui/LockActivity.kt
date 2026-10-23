package com.mthtamim.callshield.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.mthtamim.callshield.R
import com.mthtamim.callshield.security.AppLock

/**
 * Feature 38: the unlock gate. Shown by MainActivity when the app lock is
 * on and this session has not been unlocked yet (5-minute grace). PIN or,
 * when the user enabled it and the device supports it, fingerprint / face.
 */
class LockActivity : BaseActivity() {

    private lateinit var textError: TextView
    private lateinit var btnBiometric: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        textError = findViewById(R.id.textLockError)
        btnBiometric = findViewById(R.id.btnBiometric)
        val editPin = findViewById<EditText>(R.id.editPin)

        findViewById<Button>(R.id.btnUnlock).setOnClickListener {
            val pin = editPin.text.toString()
            if (AppLock.verifyPin(this, pin)) {
                unlock()
            } else {
                textError.text = getString(R.string.lock_wrong_pin)
            }
        }

        val bioAvailable = store().biometricEnabled() &&
            BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS
        btnBiometric.visibility = if (bioAvailable) View.VISIBLE else View.GONE
        btnBiometric.setOnClickListener { showBiometric() }
        if (bioAvailable) showBiometric()
    }

    private fun showBiometric() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    textError.text = errString
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_title))
            .setSubtitle(getString(R.string.lock_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    private fun unlock() {
        AppLock.markUnlocked()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Deprecated("The lock screen is a gate; back simply leaves the app.")
    override fun onBackPressed() {
        finishAffinity()
    }
}
