package com.mthtamim.callshield.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mthtamim.callshield.R
import com.mthtamim.callshield.online.OnlineLookup
import com.mthtamim.callshield.util.Numbers

/**
 * Features 46 and 47 — optional community layer, PLUMBING ONLY.
 *
 * OFF by default. Nothing in the blocking flow uses the network; this
 * screen exists so that, if the user one day runs their own Supabase
 * backend with the documented number_reputation table, they can check a
 * number's community spam score and suggested name by hand. Only the
 * SHA-256 hash of the number is sent. Until such a backend exists, lookups
 * end in "not found" or an error — that is expected, and the screen says
 * the layer is unverified.
 */
class OnlineActivity : BaseActivity() {

    private lateinit var textResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_online)

        textResult = findViewById(R.id.textOnlineResult)
        val switchOnline = findViewById<Switch>(R.id.switchOnline)
        val editUrl = findViewById<EditText>(R.id.editOnlineUrl)
        val editKey = findViewById<EditText>(R.id.editOnlineKey)
        val editNumber = findViewById<EditText>(R.id.editOnlineNumber)

        switchOnline.isChecked = store().onlineEnabled()
        editUrl.setText(store().onlineUrl())
        editKey.setText(store().onlineKey())

        switchOnline.setOnCheckedChangeListener { _, checked ->
            store().setOnlineEnabled(checked)
        }
        findViewById<Button>(R.id.btnSaveOnline).setOnClickListener {
            store().setOnlineUrl(editUrl.text.toString().trim())
            store().setOnlineKey(editKey.text.toString().trim())
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnOnlineLookup).setOnClickListener {
            if (!store().onlineEnabled()) {
                textResult.text = getString(R.string.online_off_msg)
                return@setOnClickListener
            }
            val number = editNumber.text.toString().trim()
            if (number.isEmpty()) {
                Toast.makeText(this, R.string.number_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = store().onlineUrl()
            val key = store().onlineKey()
            val numberKey = Numbers.key(number)
            textResult.text = getString(R.string.online_working)
            Thread {
                val result = OnlineLookup.lookup(url, key, numberKey)
                runOnUiThread {
                    textResult.text = when (result) {
                        is OnlineLookup.Result.Found -> getString(
                            R.string.online_found,
                            result.score,
                            result.reports,
                            result.name ?: getString(R.string.online_no_name)
                        )
                        OnlineLookup.Result.NotFound -> getString(R.string.online_not_found)
                        is OnlineLookup.Result.Error ->
                            getString(R.string.online_error, result.message)
                    }
                }
            }.start()
        }
    }
}
