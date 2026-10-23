package com.mthtamim.callshield.notify

import android.content.Context
import android.telephony.SmsManager
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.ui.Perms

/**
 * Feature 49 (v1.1): an automatic SMS reply to a number whose call was
 * REJECTED. Every guard the feature promises lives here:
 *  - the toggle is off by default and this is only called when it is on;
 *  - hidden numbers never get a reply (there is nobody to send to);
 *  - the SEND_SMS permission is required (it is requested only when the
 *    user enables the feature in the UI);
 *  - one reply per number per 24 hours (Store.claimAutoSms), so two phones
 *    with auto-replies can never loop, and the SIM is never charged twice
 *    in a day for the same caller;
 *  - the message is the user's own text; the SMS charge, if any, comes
 *    from the user's own SIM — the app has no server and sends nothing
 *    anywhere else.
 */
object AutoSms {

    fun maybeReply(context: Context, rawNumber: String?, numKey: String?, store: Store) {
        if (rawNumber.isNullOrBlank() || numKey == null) return
        if (!store.autoSmsOn()) return
        val text = store.autoSmsText().trim()
        if (text.isEmpty()) return
        if (!Perms.hasSendSms(context)) return
        val now = System.currentTimeMillis()
        if (!store.claimAutoSms(numKey, now)) return
        try {
            val sms = context.getSystemService(SmsManager::class.java) ?: return
            val parts = sms.divideMessage(text)
            if (parts.size <= 1) {
                sms.sendTextMessage(rawNumber, null, text, null, null)
            } else {
                sms.sendMultipartTextMessage(rawNumber, null, parts, null, null)
            }
        } catch (e: Exception) {
            // No default SMS app, no SIM, airplane mode: the reply is a
            // courtesy, never a reason to disturb the screening flow.
        }
    }
}
