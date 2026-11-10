package com.reportnotify

import android.app.NotificationManager
import android.content.Context
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val KEY_PAIRING = "pairing"
const val KEY_LAST_ID = "last_id"
const val EXTRA_FILE = "file"
const val ACTION_NEW_REPORT = "com.reportnotify.NEW_REPORT"

fun prefs(c: Context) = c.getSharedPreferences("main", Context.MODE_PRIVATE)
fun titles(c: Context) = c.getSharedPreferences("titles", Context.MODE_PRIVATE)
fun previews(c: Context) = c.getSharedPreferences("previews", Context.MODE_PRIVATE)
fun readFlags(c: Context) = c.getSharedPreferences("read", Context.MODE_PRIVATE)
fun reportsDir(c: Context) = File(c.filesDir, "reports").apply { mkdirs() }

/** Only the bare file name is honoured, so a crafted name can't escape reports/. */
fun reportFile(c: Context, name: String) = File(reportsDir(c), File(name).name)
fun notifId(name: String) = name.hashCode()

fun markRead(c: Context, name: String) {
    readFlags(c).edit().putBoolean(name, true).apply()
    c.getSystemService(NotificationManager::class.java).cancel(notifId(name))
}

fun deleteReport(c: Context, name: String) {
    reportFile(c, name).delete()
    listOf(titles(c), previews(c), readFlags(c)).forEach { it.edit().remove(name).apply() }
    c.getSystemService(NotificationManager::class.java).cancel(notifId(name))
}

/** Plain-text snippet of an HTML report, for the list and the notification. */
fun previewText(html: String, max: Int = 160): String =
    html.replace(Regex("(?is)<(head|script|style)\\b.*?</\\1\\s*>"), " ")
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim().take(max)

/** Pairing string from `send_report.py --genkey/--qr`: "https://ntfy.sh/<topic>#<base64 key>". */
class Pairing(val topicUrl: String, val key: ByteArray) {
    companion object {
        fun parse(s: String): Pairing {
            val parts = s.trim().split("#", limit = 2)
            require(parts.size == 2 && parts[0].startsWith("http")) { "μη έγκυρος κωδικός" }
            val key = Base64.getDecoder().decode(parts[1])
            require(key.size == 32) { "το κλειδί πρέπει να είναι 32 bytes" }
            return Pairing(parts[0].trimEnd('/'), key)
        }
    }
}

/** Wire format: nonce(12) + ciphertext + tag(16). Must match send_report.py encrypt(). */
fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
    return c.doFinal(blob, 12, blob.size - 12)
}
