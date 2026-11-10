package com.reportnotify

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app UI (setup, report list, re-pair) is an HTML page in assets/ui.html talking to [Bridge].
 * Reports open in a separate WebView that has no bridge, so report JS can't call into the app.
 */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var ui: WebView
    private var reportView: LinearLayout? = null

    private val onNewReport = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { fitsSystemWindows = true } // keep clear of status/nav bars (edge-to-edge)
        ui = WebView(this).apply {
            settings.javaScriptEnabled = true
            webChromeClient = WebChromeClient() // enables JS confirm() dialogs
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(Bridge(), "App")
            loadUrl("file:///android_asset/ui.html")
        }
        root.addView(ui)
        setContentView(root)
        if (isPaired()) start()
        openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(onNewReport, IntentFilter(ACTION_NEW_REPORT), RECEIVER_NOT_EXPORTED)
        else registerReceiver(onNewReport, IntentFilter(ACTION_NEW_REPORT))
        refresh()
    }

    override fun onPause() {
        unregisterReceiver(onNewReport)
        super.onPause()
    }

    @Deprecated("simple back handling is enough here")
    override fun onBackPressed() {
        if (!closeReport()) super.onBackPressed()
    }

    private fun isPaired() = prefs(this).contains(KEY_PAIRING)
    private fun refresh() = ui.evaluateJavascript("window.render && render()", null)
    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, ListenerService::class.java))
    }

    /** Returns an error message, or null when paired. */
    private fun tryPair(raw: String): String? {
        val s = raw.trim()
        runCatching { Pairing.parse(s) }.exceptionOrNull()?.let { return "Μη έγκυρος κωδικός: ${it.message}" }
        prefs(this).edit().putString(KEY_PAIRING, s).remove(KEY_LAST_ID).apply()
        runOnUiThread {
            start()
            // Without this exemption Doze cuts the network and reports arrive late.
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            refresh()
        }
        return null
    }

    private fun openFromIntent(i: Intent) {
        val name = i.getStringExtra(EXTRA_FILE) ?: return
        i.removeExtra(EXTRA_FILE)
        openReport(name)
    }

    private fun openReport(name: String) {
        val f = reportFile(this, name)
        if (!f.exists()) return
        closeReport()
        markRead(this, f.name)
        val bar = TextView(this).apply {
            text = "‹   " + titles(this@MainActivity).getString(f.name, "Αναφορά")
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.fg))
            setPadding(40, 36, 40, 36)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { closeReport() }
        }
        val web = WebView(this).apply {
            // Content is authenticated by AES-GCM (only our PC holds the key), so JS (e.g. charts) is allowed.
            settings.javaScriptEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            loadDataWithBaseURL(null, f.readText(), "text/html", "utf-8", null)
        }
        reportView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            addView(bar)
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        root.addView(reportView)
    }

    private fun closeReport(): Boolean {
        val v = reportView ?: return false
        root.removeView(v)
        (v.getChildAt(1) as WebView).destroy()
        reportView = null
        refresh()
        return true
    }

    private fun versionName() = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    /** Called from ui.html (on a WebView background thread). */
    inner class Bridge {
        private val ctx = this@MainActivity

        @JavascriptInterface
        fun state(): String {
            val list = JSONArray()
            reportsDir(ctx).listFiles().orEmpty().sortedByDescending { it.name }.forEach { f ->
                val preview = previews(ctx).getString(f.name, null)
                    ?: previewText(f.readText()).also { previews(ctx).edit().putString(f.name, it).apply() }
                list.put(JSONObject()
                    .put("name", f.name)
                    .put("title", titles(ctx).getString(f.name, "Αναφορά"))
                    .put("time", f.name.substringBefore('_').toLongOrNull() ?: (f.lastModified() / 1000))
                    .put("preview", preview)
                    .put("read", readFlags(ctx).getBoolean(f.name, false)))
            }
            return JSONObject().put("paired", isPaired()).put("version", versionName()).put("reports", list).toString()
        }

        @JavascriptInterface fun open(name: String) = runOnUiThread { openReport(name) }

        @JavascriptInterface fun remove(name: String) = deleteReport(ctx, name)

        @JavascriptInterface fun pair(raw: String): String = tryPair(raw) ?: ""

        @JavascriptInterface
        fun scan() = runOnUiThread {
            // Google Play services scanner: its own camera UI, no CAMERA permission needed in this app.
            GmsBarcodeScanning.getClient(ctx, GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                .startScan()
                .addOnSuccessListener { b -> tryPair(b.rawValue.orEmpty())?.let(::toast) }
                .addOnFailureListener { e -> toast("Η σάρωση απέτυχε: ${e.message}") }
        }

        /** Forget the pairing (reports are kept) and stop listening; the UI then shows setup again. */
        @JavascriptInterface
        fun unpair() {
            prefs(ctx).edit().remove(KEY_PAIRING).remove(KEY_LAST_ID).apply()
            stopService(Intent(ctx, ListenerService::class.java))
        }
    }
}
