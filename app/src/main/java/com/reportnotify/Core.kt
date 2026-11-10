package com.reportnotify

import android.content.Context
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val KEY_PAIRING = "pairing"
const val KEY_LAST_ID = "last_id"
const val EXTRA_FILE = "file"

fun prefs(c: Context) = c.getSharedPreferences("main", Context.MODE_PRIVATE)
fun titles(c: Context) = c.getSharedPreferences("titles", Context.MODE_PRIVATE)
fun reportsDir(c: Context) = File(c.filesDir, "reports").apply { mkdirs() }

/** Pairing string printed by `send_report.py --genkey`: "https://ntfy.sh/<topic>#<base64 key>". */
class Pairing(val topicUrl: String, val key: ByteArray) {
    companion object {
        fun parse(s: String): Pairing {
            val parts = s.trim().split("#", limit = 2)
            require(parts.size == 2 && parts[0].startsWith("http")) { "bad pairing string" }
            val key = Base64.getDecoder().decode(parts[1])
            require(key.size == 32) { "key must be 32 bytes" }
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
