package com.bharath.httpserver

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val pickFolder = 100
    private var folderUri: Uri? = null
    private lateinit var folderLabel: TextView
    private lateinit var urlLabel: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderUri = getPreferences(MODE_PRIVATE).getString("folder", null)?.let(Uri::parse)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
            setBackgroundColor(0xFFF5F7FA.toInt())
        }
        fun label(value: String, size: Float, color: Int = 0xFF172033.toInt()) =
            TextView(this).apply { text = value; textSize = size; setTextColor(color) }
        root.addView(label("HTTP File Server", 26f))
        root.addView(label("Share a folder with any device on the same Wi-Fi or phone hotspot.", 15f, 0xFF5C667A.toInt()).apply { setPadding(0, 8, 0, 24) })
        folderLabel = label("No folder selected", 15f)
        root.addView(folderLabel)
        root.addView(Button(this).apply {
            text = "Choose shared folder"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), pickFolder) }
        })
        startButton = Button(this).apply { text = "START SERVER"; setOnClickListener { toggleServer() } }
        root.addView(startButton)
        urlLabel = label("Server is stopped", 16f, 0xFF246B4A.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, 20, 0, 12) }
        root.addView(urlLabel)
        root.addView(Button(this).apply {
            text = "Copy URL"
            setOnClickListener {
                val url = urlLabel.text.toString()
                if (url.startsWith("http")) {
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Server URL", url))
                    Toast.makeText(this@MainActivity, "URL copied", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(label("Open the URL on a laptop, tablet, or another phone. Browse folders, preview common files, and download them in the browser.", 14f, 0xFF5C667A.toInt()).apply { setPadding(0, 24, 0, 0) })
        root.addView(label("Security: anyone on your local network who knows the URL can access the selected folder. Stop the server when finished. Use a trusted Wi-Fi network or your own hotspot.", 13f, 0xFF9A4A20.toInt()).apply { setPadding(0, 24, 0, 0) })
        setContentView(root)
        refreshFolder()
    }

    private fun refreshFolder() { folderLabel.text = if (folderUri == null) "No folder selected" else "Shared folder selected ✓" }

    private fun toggleServer() {
        if (folderUri == null) { Toast.makeText(this, "Choose a folder first", Toast.LENGTH_SHORT).show(); return }
        if (startButton.text.toString().contains("STOP")) {
            stopService(Intent(this, FileServerService::class.java))
            startButton.text = "START SERVER"
            urlLabel.text = "Server is stopped"
        } else {
            val intent = Intent(this, FileServerService::class.java).apply { action = FileServerService.ACTION_START; data = folderUri }
            ContextCompat.startForegroundService(this, intent)
            urlLabel.text = "http://" + FileServerService.findLanIp() + ":8080"
            startButton.text = "STOP SERVER"
        }
    }

    @Deprecated("Deprecated API retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFolder && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            folderUri = uri
            getPreferences(MODE_PRIVATE).edit().putString("folder", uri.toString()).apply()
            refreshFolder()
        }
    }
}
