package com.mthtamim.callshield.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.mthtamim.callshield.data.Store
import java.util.Locale

/**
 * Applies the saved language (feature 41: BN/EN) and theme (dark/light/system)
 * to every screen.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(Store.PREFS, Context.MODE_PRIVATE)
        val lang = prefs.getString(Store.KEY_LANG, "bn") ?: "bn"
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set before any subclass calls setContentView, so the new mode is
        // picked up by this activity's delegate right away.
        val prefs = getSharedPreferences(Store.PREFS, Context.MODE_PRIVATE)
        val mode = when (prefs.getString(Store.KEY_THEME, "system")) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    protected fun store(): Store = Store(this)
}
