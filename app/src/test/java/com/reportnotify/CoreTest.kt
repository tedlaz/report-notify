package com.reportnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreTest {
    // Same vector as pc/test_crypto.py — proves both sides agree on the wire format.
    private val key = ByteArray(32) { it.toByte() }
    private val vector = "000000000000000000000000c02f7b6b7b954d112894cb0bd7af5f261dc66a7c4abfb6e31775cc60c3988b031d8dca805ff8"
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decryptsPythonVector() =
        assertEquals("Γειά <b>σου</b>", String(decrypt(key, hex(vector)), Charsets.UTF_8))

    @Test fun rejectsTampered() {
        val b = hex(vector).also { it[20] = (it[20] + 1).toByte() }
        assertThrows(Exception::class.java) { decrypt(key, b) }
    }

    @Test fun previewStripsMarkup() = assertEquals(
        "Τίτλος A & B κείμενο",
        previewText("<html><head><title>x</title><style>p{}</style></head><body><h2>Τίτλος</h2>\n<p>A &amp; B</p><script>alert(1)</script> κείμενο</body></html>"),
    )

    @Test fun parsesPairing() {
        val p = Pairing.parse(" https://ntfy.sh/rabc/#AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8= ")
        assertEquals("https://ntfy.sh/rabc", p.topicUrl)
        assertEquals(key.toList(), p.key.toList())
    }
}
