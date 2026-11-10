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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Keeps a streaming connection to ntfy (<topic>/json) open and turns each attachment into a stored report. */
class ListenerService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("service", "Listener", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("reports", "Reports", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "service")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Listening for reports")
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
                val conn = URL("${pairing.topicUrl}/json?since=$since").openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000 // ntfy sends a keepalive every ~45s; silence longer than this = dead link
                conn.inputStream.bufferedReader().useLines { lines ->
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
                Log.w(TAG, "stream error", e)
            }
            Thread.sleep(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun handle(p: Pairing, m: JSONObject) {
        val url = m.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotEmpty() } ?: return
        val blob = URL(url).openStream().use { it.readBytes() }
        val html = decrypt(p.key, blob) // throws if tampered or wrong key
        val title = m.optString("title").ifEmpty { "Report" }
        val file = File(reportsDir(this), "${m.getLong("time")}_${m.getString("id")}.html")
        file.writeBytes(html)
        titles(this).edit().putString(file.name, title).apply()

        val open = Intent(this, MainActivity::class.java).putExtra(EXTRA_FILE, file.name)
        val pi = PendingIntent.getActivity(this, file.name.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "reports")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText("Tap to open")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(file.name.hashCode(), n)
    }

    companion object { const val TAG = "ReportNotify" }
}
