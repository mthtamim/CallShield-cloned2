package com.mthtamim.callshield.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import com.mthtamim.callshield.R
import com.mthtamim.callshield.engine.BlockMode
import com.mthtamim.callshield.engine.HiddenRule

/** Rules (features 1, 2, 3, 13) plus language and theme (feature 41). */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val store = store()
        val radioContacts = findViewById<RadioButton>(R.id.radioContacts)
        val radioFavorites = findViewById<RadioButton>(R.id.radioFavorites)
        val radioHiddenBlock = findViewById<RadioButton>(R.id.radioHiddenBlock)
        val radioHiddenAllow = findViewById<RadioButton>(R.id.radioHiddenAllow)
        val radioHiddenWarn = findViewById<RadioButton>(R.id.radioHiddenWarn)
        val switchEscalation = findViewById<Switch>(R.id.switchEscalation)
        // v1.1 behaviour card (features 50, 57, 58).
        val switchSoftScreen = findViewById<Switch>(R.id.switchSoftScreen)
        val switchSilentNotif = findViewById<Switch>(R.id.switchSilentNotif)
        val spinnerAutoclean = findViewById<Spinner>(R.id.spinnerAutoclean)
        val autocleanDays = intArrayOf(0, 30, 90)
        spinnerAutoclean.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.autoclean_off),
                getString(R.string.autoclean_30),
                getString(R.string.autoclean_90)
            )
        )
        val radioBn = findViewById<RadioButton>(R.id.radioBn)
        val radioEn = findViewById<RadioButton>(R.id.radioEn)
        val radioThemeSystem = findViewById<RadioButton>(R.id.radioThemeSystem)
        val radioThemeDark = findViewById<RadioButton>(R.id.radioThemeDark)
        val radioThemeLight = findViewById<RadioButton>(R.id.radioThemeLight)

        if (store.blockMode() == BlockMode.FAVORITES) {
            radioFavorites.isChecked = true
        } else {
            radioContacts.isChecked = true
        }
        when (store.hiddenRule()) {
            HiddenRule.BLOCK -> radioHiddenBlock.isChecked = true
            HiddenRule.ALLOW -> radioHiddenAllow.isChecked = true
            HiddenRule.WARN -> radioHiddenWarn.isChecked = true
        }
        switchEscalation.isChecked = store.escalationEnabled()
        switchSoftScreen.isChecked = store.softScreenOn()
        switchSilentNotif.isChecked = store.silentNotif()
        val acIndex = autocleanDays.indexOf(store.logKeepDays())
        spinnerAutoclean.setSelection(if (acIndex >= 0) acIndex else 0)
        if (store.language() == "en") {
            radioEn.isChecked = true
        } else {
            radioBn.isChecked = true
        }
        when (store.themeMode()) {
            "dark" -> radioThemeDark.isChecked = true
            "light" -> radioThemeLight.isChecked = true
            else -> radioThemeSystem.isChecked = true
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            val s = store()
            s.setBlockMode(if (radioFavorites.isChecked) BlockMode.FAVORITES else BlockMode.CONTACTS)
            s.setHiddenRule(
                when {
                    radioHiddenAllow.isChecked -> HiddenRule.ALLOW
                    radioHiddenWarn.isChecked -> HiddenRule.WARN
                    else -> HiddenRule.BLOCK
                }
            )
            s.setEscalationEnabled(switchEscalation.isChecked)
            s.setSoftScreenOn(switchSoftScreen.isChecked)
            s.setSilentNotif(switchSilentNotif.isChecked)
            s.setLogKeepDays(autocleanDays[spinnerAutoclean.selectedItemPosition])
            s.purgeOldLog(System.currentTimeMillis())
            val newLang = if (radioEn.isChecked) "en" else "bn"
            val langChanged = newLang != s.language()
            s.setLanguage(newLang)
            s.setThemeMode(
                when {
                    radioThemeDark.isChecked -> "dark"
                    radioThemeLight.isChecked -> "light"
                    else -> "system"
                }
            )
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
            if (langChanged) {
                recreate()
            }
        }
    }
}
