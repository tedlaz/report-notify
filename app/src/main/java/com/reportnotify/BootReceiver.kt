package com.reportnotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && prefs(c).contains(KEY_PAIRING))
            c.startForegroundService(Intent(c, ListenerService::class.java))
    }
}
