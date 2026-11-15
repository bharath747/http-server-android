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
import androidx.documentfile.provider.DocumentFile

class MainActivity : Activity() {
    private val pickFolder = 100
    private val folderUris = mutableListOf<Uri>()
    private lateinit var folderList: LinearLayout
    private lateinit var urlLabel: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getPreferences(MODE_PRIVATE)
        val saved = prefs.getStringSet("folders", null)
        if (saved != null) folderUris.addAll(saved.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() })
        else prefs.getString("folder", null)?.let { folderUris.add(Uri.parse(it)) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
            setBackgroundColor(0xFFF5F7FA.toInt())
        }
        fun label(value: String, size: Float, color: Int = 0xFF172033.toInt()) =
            TextView(this).apply { text = value; textSize = size; setTextColor(color) }
        root.addView(label("HTTP File Server", 26f))
        root.addView(label("Share multiple folders from different locations with any device on the same Wi-Fi or phone hotspot.", 15f, 0xFF5C667A.toInt()).apply { setPadding(0, 8, 0, 18) })
        root.addView(label("Shared folders", 18f))
        folderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(folderList)
        root.addView(Button(this).apply {
            text = "＋ Add another folder"
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
        root.addView(label("Each selected folder appears as a separate top-level folder in the browser. You can browse, preview, and download files from all of them.", 14f, 0xFF5C667A.toInt()).apply { setPadding(0, 24, 0, 0) })
        root.addView(label("Security: anyone on your local network who knows the URL can access the selected folders. Stop the server when finished. Use a trusted Wi-Fi network or your own hotspot.", 13f, 0xFF9A4A20.toInt()).apply { setPadding(0, 24, 0, 0) })
        setContentView(root)
        refreshFolders()
    }

    private fun refreshFolders() {
        folderList.removeAllViews()
        if (folderUris.isEmpty()) {
            folderList.addView(TextView(this).apply { text = "No folders selected"; textSize = 14f; setPadding(0, 8, 0, 12) })
        }
        folderUris.forEachIndexed { index, uri ->
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: "Selected folder ${index + 1}"
            folderList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "📁 $name"
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(0, 8, 8, 8)
                })
                addView(Button(this@MainActivity).apply {
                    text = "Remove"
                    setOnClickListener {
                        folderUris.removeAt(index)
                        saveFolders()
                        refreshFolders()
                    }
                })
            })
        }
    }

    private fun saveFolders() {
        getPreferences(MODE_PRIVATE).edit()
            .putStringSet("folders", folderUris.map { it.toString() }.toSet())
            .remove("folder")
            .apply()
    }

    private fun toggleServer() {
        if (folderUris.isEmpty()) { Toast.makeText(this, "Add at least one folder first", Toast.LENGTH_SHORT).show(); return }
        if (startButton.text.toString().contains("STOP")) {
            stopService(Intent(this, FileServerService::class.java))
            startButton.text = "START SERVER"
            urlLabel.text = "Server is stopped"
        } else {
            val intent = Intent(this, FileServerService::class.java).apply {
                action = FileServerService.ACTION_START
                putStringArrayListExtra(FileServerService.EXTRA_FOLDER_URIS, ArrayList(folderUris.map { it.toString() }))
            }
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
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                if (folderUris.none { it == uri }) {
                    folderUris.add(uri)
                    saveFolders()
                    refreshFolders()
                } else Toast.makeText(this, "This folder is already selected", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(this, "Could not keep permission for this folder", Toast.LENGTH_LONG).show()
            }
        }
    }
}
