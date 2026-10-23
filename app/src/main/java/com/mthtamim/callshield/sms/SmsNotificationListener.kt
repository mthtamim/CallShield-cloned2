package com.mthtamim.callshield.sms

import android.app.Notification
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mthtamim.callshield.contacts.ContactsHelper
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.engine.SmsFilter
import com.mthtamim.callshield.engine.SmsMuteReason
import com.mthtamim.callshield.ui.Perms
import com.mthtamim.callshield.util.Numbers

/**
 * Features 25-28: silences notifications of the default SMS app for
 * senders that are not in Contacts.
 *
 * Android gives no app a way to truly block an SMS (short of being the
 * default SMS app), so this is the honest maximum: the message still
 * arrives in the SMS app, but its notification is cancelled before it makes
 * a sound, and a record lands in CallShield's silent inbox. OTP and
 * transactional messages are NEVER muted (feature 26) — the pure
 * [SmsFilter] enforces that above every other rule.
 *
 * The sender is read from the notification title/text. SMS apps differ in
 * how they fill those in, so a sender that cannot be identified is left
 * alone (never muted) rather than guessed at.
 */
class SmsNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val store = Store(this)
        if (!store.smsMuteEnabled()) return

        val smsPackage = try {
            Telephony.Sms.getDefaultSmsPackage(this)
        } catch (e: Exception) {
            null
        } ?: return
        if (sbn.packageName != smsPackage) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val senderId = title?.trim().orEmpty()
        if (senderId.isEmpty()) return

        val senderNumber = senderId.takeIf { Numbers.digits(it).length >= 3 }
        val inContacts = senderNumber != null && Perms.hasContacts(this) &&
            ContactsHelper.lookup(this, senderNumber).inContacts

        val reason = SmsFilter.decide(
            muteUnknown = true,
            senderInContacts = inContacts,
            senderId = senderId,
            body = text,
            keywords = store.keywords().map { it.word }
        )
        if (reason == SmsMuteReason.NOT_MUTED) return

        try {
            cancelNotification(sbn.key)
        } catch (e: Exception) {
            // The notification raced away (already read or dismissed):
            // nothing to cancel, nothing to log.
            return
        }
        val snippet = (text ?: "").take(120)
        store.logSms(senderId, snippet, reason.name, System.currentTimeMillis())
    }
}
