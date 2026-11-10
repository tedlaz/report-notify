package com.reportnotify

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
import java.util.Date

/** One activity, three screens: pairing setup, report list, report viewer. Views built in code, no XML. */
class MainActivity : Activity() {
    private var showingReport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (prefs(this).contains(KEY_PAIRING)) start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val file = intent.getStringExtra(EXTRA_FILE)
        when {
            !prefs(this).contains(KEY_PAIRING) -> showSetup()
            file != null -> showReport(File(reportsDir(this), file))
            else -> showList()
        }
    }

    @Deprecated("simple back handling is enough here")
    override fun onBackPressed() {
        if (showingReport) { intent.removeExtra(EXTRA_FILE); showList() } else super.onBackPressed()
    }

    private fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, ListenerService::class.java))
    }

    private fun showSetup() {
        showingReport = false
        val scan = Button(this).apply { text = "Scan QR" }
        // Google Play services scanner: its own camera UI, no CAMERA permission needed in this app.
        scan.setOnClickListener {
            GmsBarcodeScanning.getClient(this, GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                .startScan()
                .addOnSuccessListener { b -> pair(b.rawValue.orEmpty()) }
                .addOnFailureListener { e -> Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        val input = EditText(this).apply { hint = "https://ntfy.sh/<topic>#<key>" }
        val save = Button(this).apply { text = "Save" }
        save.setOnClickListener { pair(input.text.toString()) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(TextView(this@MainActivity).apply {
                text = "On the PC run:\nuv run send_report.py --genkey  (or --qr)\nthen scan the QR, or paste the string."
            })
            addView(scan)
            addView(input)
            addView(save)
        })
    }

    private fun pair(raw: String) {
        val s = raw.trim()
        val err = runCatching { Pairing.parse(s) }.exceptionOrNull()
        if (err != null) { Toast.makeText(this, "Not a pairing code: ${err.message}", Toast.LENGTH_LONG).show(); return }
        prefs(this).edit().putString(KEY_PAIRING, s).remove(KEY_LAST_ID).apply()
        start()
        // Without this exemption Doze cuts the network and reports arrive late.
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        showList()
    }

    private fun showList() {
        showingReport = false
        val files = reportsDir(this).listFiles().orEmpty().sortedByDescending { it.name }
        if (files.isEmpty()) {
            setContentView(TextView(this).apply { text = "No reports yet"; gravity = Gravity.CENTER })
            return
        }
        val t = titles(this)
        val labels = files.map { f ->
            val time = f.name.substringBefore('_').toLongOrNull()?.let { DateFormat.format("dd/MM HH:mm", Date(it * 1000)) }
            "${t.getString(f.name, "Report")}\n$time"
        }
        setContentView(ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels)
            setOnItemClickListener { _, _, i, _ -> showReport(files[i]) }
            setOnItemLongClickListener { _, _, i, _ ->
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Delete \"${labels[i].substringBefore('\n')}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        files[i].delete()
                        t.edit().remove(files[i].name).apply()
                        showList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        })
    }

    private fun showReport(f: File) {
        if (!f.exists()) { showList(); return }
        showingReport = true
        setContentView(WebView(this).apply {
            // Content is authenticated by AES-GCM (only our PC holds the key), so JS (e.g. charts) is allowed.
            settings.javaScriptEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            loadDataWithBaseURL(null, f.readText(), "text/html", "utf-8", null)
        })
    }
}
