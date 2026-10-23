package com.mthtamim.callshield.security

import android.content.Context
import com.mthtamim.callshield.data.Store
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Feature 38: app lock. The PIN is stored only as a salted SHA-256 hash in
 * app-private preferences — never in plain text. A successful unlock (PIN
 * or biometric) is held in memory for this process, with a 5-minute grace
 * timestamp so switching screens does not re-ask constantly.
 *
 * Scope, stated plainly: the lock gates entry through the launcher
 * (MainActivity). It protects the settings and the block log from casual
 * hands on an unlocked phone; it is not a barrier against someone who can
 * install tools or read the device storage with privileges.
 */
object AppLock {

    private const val GRACE_MS = 5 * 60_000L

    @Volatile
    private var unlockedAt: Long = 0L

    fun isSetUp(context: Context): Boolean {
        val store = Store(context)
        return store.pinHash().isNotEmpty()
    }

    fun isEnabled(context: Context): Boolean =
        Store(context).lockEnabled() && isSetUp(context)

    fun isSessionUnlocked(): Boolean =
        System.currentTimeMillis() - unlockedAt < GRACE_MS

    fun markUnlocked() {
        unlockedAt = System.currentTimeMillis()
    }

    fun lockNow() {
        unlockedAt = 0L
    }

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        Store(context).setPin(saltHex, hash(saltHex, pin))
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val store = Store(context)
        val stored = store.pinHash()
        if (stored.isEmpty()) return false
        return hash(store.pinSalt(), pin) == stored
    }

    private fun hash(saltHex: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((saltHex + ":" + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
