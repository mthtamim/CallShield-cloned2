package com.mthtamim.callshield.online

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Features 46 and 47 — PLUMBING ONLY, UNVERIFIED.
 *
 * Optional community layer against a Supabase project the USER provides
 * (their own URL + anon key, entered in the Online screen). It is OFF by
 * default and the core never touches the network: this object runs only
 * when the user explicitly enables the layer and taps a lookup.
 *
 * What is sent: only the SHA-256 hash of the number key — never the raw
 * number, never contacts, never the call log.
 *
 * Expected backend table (does not exist yet; the user would create it):
 *   number_reputation(
 *     number_hash  text primary key,
 *     spam_score   int,          -- 0..100
 *     report_count int,
 *     suggested_name text
 *   )
 * with a read policy for the anon key. Until such a backend exists, every
 * lookup ends in "not configured / not found / error" by design.
 */
object OnlineLookup {

    sealed class Result {
        data class Found(val score: Int, val reports: Int, val name: String?) : Result()
        object NotFound : Result()
        data class Error(val message: String) : Result()
    }

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Blocking network call — always invoke from a background thread. */
    fun lookup(baseUrl: String, anonKey: String, numberKey: String): Result {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || anonKey.isBlank()) {
            return Result.Error("not-configured")
        }
        val hash = sha256Hex(numberKey)
        val query = "number_hash=eq." + URLEncoder.encode(hash, "UTF-8") +
            "&select=spam_score,report_count,suggested_name"
        val url = URL(base + "/rest/v1/number_reputation?" + query)
        var connection: HttpURLConnection? = null
        return try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("apikey", anonKey)
            connection.setRequestProperty("Authorization", "Bearer " + anonKey)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val code = connection.responseCode
            if (code !in 200..299) {
                Result.Error("http-" + code)
            } else {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(body)
                if (array.length() == 0) {
                    Result.NotFound
                } else {
                    val obj = array.getJSONObject(0)
                    Result.Found(
                        obj.optInt("spam_score", -1),
                        obj.optInt("report_count", 0),
                        if (obj.isNull("suggested_name")) null else obj.optString("suggested_name")
                    )
                }
            }
        } catch (e: Exception) {
            Result.Error(e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
