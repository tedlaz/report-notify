package com.reportnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Keeps a streaming connection to ntfy (<topic>/json) open and turns each attachment into a stored report. */
class ListenerService : Service() {
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("service", "Υπηρεσία", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("reports", "Αναφορές", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "service")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Σε αναμονή για αναφορές")
            .setContentIntent(openApp(null))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (!running) {
            running = true
            thread(name = "ntfy-listener") { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        conn?.disconnect() // unblocks the reader thread (e.g. on re-pair)
        super.onDestroy()
    }

    private fun loop() {
        var backoff = 1_000L
        while (running) {
            val pairing = prefs(this).getString(KEY_PAIRING, null)?.let { runCatching { Pairing.parse(it) }.getOrNull() }
            if (pairing == null) { stopSelf(); return }
            try {
                // since=<last id> replays anything missed while offline (ntfy.sh keeps ~12h).
                val since = prefs(this).getString(KEY_LAST_ID, null) ?: "all"
                val c = URL("${pairing.topicUrl}/json?since=$since").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15_000
                c.readTimeout = 120_000 // ntfy sends a keepalive every ~45s; silence longer than this = dead link
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!running) break
                        backoff = 1_000L
                        val m = JSONObject(line)
                        if (m.optString("event") != "message") continue
                        try { handle(pairing, m) } catch (e: Exception) { Log.w(TAG, "skip ${m.optString("id")}", e) }
                        prefs(this).edit().putString(KEY_LAST_ID, m.getString("id")).apply()
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "stream error", e)
            }
            if (!running) return
            Thread.sleep(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun handle(p: Pairing, m: JSONObject) {
        val url = m.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotEmpty() } ?: return
        val blob = URL(url).openStream().use { it.readBytes() }
        val html = decrypt(p.key, blob).toString(Charsets.UTF_8) // throws if tampered or wrong key
        val title = m.optString("title").ifEmpty { "Αναφορά" }
        val preview = previewText(html)
        val name = "${m.getLong("time")}_${m.getString("id")}.html"
        reportFile(this, name).writeText(html)
        titles(this).edit().putString(name, title).apply()
        previews(this).edit().putString(name, preview).apply()

        val n = Notification.Builder(this, "reports")
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(getColor(R.color.brand))
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(m.getLong("time") * 1000)
            .setShowWhen(true)
            .setContentIntent(openApp(name))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(notifId(name), n)
        sendBroadcast(Intent(ACTION_NEW_REPORT).setPackage(packageName)) // refresh the list if it is open
    }

    private fun openApp(name: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        if (name != null) i.putExtra(EXTRA_FILE, name)
        return PendingIntent.getActivity(this, name?.let(::notifId) ?: 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object { const val TAG = "ReportNotify" }
}
