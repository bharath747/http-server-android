package com.bharath.httpserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class FileServerService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(8)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("http_server", "HTTP File Server", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopServer(); stopSelf(); return START_NOT_STICKY }
        val uri = intent?.data ?: return START_NOT_STICKY
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "http_server") else Notification.Builder(this)
        startForeground(1, builder.setContentTitle("HTTP File Server").setContentText("Sharing on port 8080")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done).setOngoing(true).build())
        if (!running) startServer(uri)
        return START_NOT_STICKY
    }

    private fun startServer(uri: Uri) {
        running = true
        Thread {
            try {
                server = ServerSocket(8080)
                while (running) {
                    val socket = server?.accept() ?: break
                    workers.submit { handle(socket, uri) }
                }
            } catch (_: Exception) {
            } finally { running = false; stopSelf() }
        }.start()
    }

    private fun handle(socket: java.net.Socket, treeUri: Uri) {
        socket.use { client ->
            try {
                client.soTimeout = 15000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val request = readLine(input) ?: return
                val parts = request.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                var contentLength = 0
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                if (method != "GET" && method != "POST") { sendError(output, 405, "Only GET and POST are supported"); return }
                val body = if (method == "POST" && contentLength > 0) {
                    if (contentLength > 1024 * 1024) { sendError(output, 413, "Request too large"); return }
                    val bytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    if (offset == bytes.size) String(bytes, Charsets.UTF_8) else ""
                } else ""
                val path = target.substringBefore("?")
                val query = target.substringAfter("?", "")
                val params = query.split("&").mapNotNull { item ->
                    val pair = item.split("=", limit = 2)
                    if (pair.size == 2) pair[0] to decode(pair[1]) else null
                }.toMap()
                val root = DocumentFile.fromTreeUri(this, treeUri)
                if (root == null) { sendError(output, 404, "Shared folder unavailable"); return }
                when (path) {
                    "/" -> send(output, 200, "text/html; charset=utf-8", PAGE.toByteArray())
                    "/download-zip" -> {
                        if (method != "POST") { sendError(output, 405, "POST required"); return }
                        val requested = try { JSONArray(body) } catch (_: Exception) { sendError(output, 400, "Invalid file selection"); return }
                        if (requested.length() == 0 || requested.length() > 500) { sendError(output, 400, "Select between 1 and 500 items"); return }
                        val selected = ArrayList<DocumentFile>()
                        for (i in 0 until requested.length()) {
                            val item = resolve(root, requested.optString(i))
                            if (item == null || (!item.isFile && !item.isDirectory)) { sendError(output, 404, "Selected item not found"); return }
                            selected.add(item)
                        }
                        val chunks = ChunkedOutputStream(output)
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=phone-files.zip\r\nTransfer-Encoding: chunked\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n").toByteArray())
                        output.flush()
                        try {
                            ZipOutputStream(chunks).use { zip ->
                                val usedNames = HashSet<String>()
                                for (item in selected) {
                                    val baseName = item.name ?: "item"
                                    if (item.isDirectory) addDirectoryToZip(item, baseName, zip, usedNames)
                                    else addFileToZip(item, baseName, zip, usedNames)
                                }
                            }
                        } catch (_: Exception) { }
                        return
                    }
                    "/api/list" -> {
                        val dir = resolve(root, params["path"].orEmpty())
                        if (dir == null || !dir.isDirectory) { sendError(output, 404, "Folder not found"); return }
                        val files = dir.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase().orEmpty() })
                        val body = files.joinToString(prefix = "[", postfix = "]") { file ->
                            val parent = params["path"].orEmpty().trim('/')
                            val childPath = (parent + "/" + (file.name ?: "")).trim('/')
                            "{\"name\":\"" + escapeJson(file.name ?: "unnamed") + "\",\"dir\":" + file.isDirectory +
                                ",\"size\":" + file.length() + ",\"path\":\"" + escapeJson(childPath) + "\"}"
                        }
                        send(output, 200, "application/json; charset=utf-8", body.toByteArray())
                    }
                    "/download", "/view" -> {
                        val file = resolve(root, params["path"].orEmpty())
                        if (file == null || !file.isFile) { sendError(output, 404, "File not found"); return }
                        val name = file.name ?: "download"
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val mime = contentResolver.getType(file.uri) ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                        val stream = contentResolver.openInputStream(file.uri)
                        if (stream == null) { sendError(output, 404, "Cannot open file"); return }
                        val disposition = if (path == "/view") "inline" else "attachment"
                        val safeName = name.replace("\"", "")
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + file.length() +
                            "\r\nContent-Disposition: " + disposition + "; filename=\"" + safeName + "\"\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n").toByteArray())
                        stream.use { it.copyTo(output, 65536) }
                        output.flush()
                    }
                    else -> sendError(output, 404, "Not found")
                }
            } catch (_: Exception) { }
        }
    }

    private fun addDirectoryToZip(dir: DocumentFile, prefix: String, zip: ZipOutputStream, used: MutableSet<String>) {
        val children = dir.listFiles().sortedBy { it.name?.lowercase().orEmpty() }
        if (children.isEmpty()) {
            val entryName = uniqueZipName(prefix.trimEnd('/') + "/", used)
            zip.putNextEntry(ZipEntry(entryName))
            zip.closeEntry()
        }
        for (child in children) {
            val name = child.name ?: "item"
            val childPath = "$prefix/$name"
            if (child.isDirectory) addDirectoryToZip(child, childPath, zip, used)
            else if (child.isFile) addFileToZip(child, childPath, zip, used)
        }
    }

    private fun addFileToZip(file: DocumentFile, entryName: String, zip: ZipOutputStream, used: MutableSet<String>) {
        val stream = contentResolver.openInputStream(file.uri) ?: return
        zip.putNextEntry(ZipEntry(uniqueZipName(entryName, used)))
        stream.use { it.copyTo(zip, 65536) }
        zip.closeEntry()
    }

    private fun uniqueZipName(name: String, used: MutableSet<String>): String {
        var candidate = name
        var index = 2
        while (!used.add(candidate)) {
            val slash = name.lastIndexOf('/')
            val dot = name.lastIndexOf('.')
            candidate = if (dot > slash) name.substring(0, dot) + " ($index)" + name.substring(dot)
                else "$name ($index)"
            index++
        }
        return candidate
    }

    private class ChunkedOutputStream(private val target: OutputStream) : OutputStream() {
        private val buffer = ByteArrayOutputStream(16384)
        private var finished = false
        override fun write(value: Int) {
            buffer.write(value)
            if (buffer.size() >= 16384) flushChunk()
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var pos = offset
            var remaining = length
            while (remaining > 0) {
                val amount = minOf(remaining, 16384 - buffer.size())
                buffer.write(bytes, pos, amount)
                pos += amount
                remaining -= amount
                if (buffer.size() >= 16384) flushChunk()
            }
        }
        private fun flushChunk() {
            if (buffer.size() == 0) return
            val bytes = buffer.toByteArray()
            target.write((bytes.size.toString(16) + "\r\n").toByteArray())
            target.write(bytes)
            target.write("\r\n".toByteArray())
            buffer.reset()
        }
        override fun flush() { flushChunk(); target.flush() }
        override fun close() {
            if (!finished) {
                flushChunk()
                target.write("0\r\n\r\n".toByteArray())
                target.flush()
                finished = true
            }
        }
    }

    private fun resolve(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        val clean = relative.replace('\\', '/').trim('/')
        if (clean.isBlank()) return current
        for (part in clean.split('/')) {
            if (part.isBlank() || part == "." || part == "..") return null
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val value = input.read()
            if (value == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (value == 10) break
            if (value != 13) bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun decode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }
    private fun escapeJson(value: String): String = value.map { ch -> when (ch) { '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> ch.toString() } }.joinToString("")
    private fun send(out: BufferedOutputStream, status: Int, type: String, body: ByteArray) {
        val reason = if (status == 200) "OK" else "Error"
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.size +
            "\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }
    private fun sendError(out: BufferedOutputStream, status: Int, message: String) = send(out, status, "text/plain; charset=utf-8", message.toByteArray())

    private fun stopServer() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        stopForeground(true)
    }
    override fun onDestroy() { stopServer(); workers.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.bharath.httpserver.START"
        const val ACTION_STOP = "com.bharath.httpserver.STOP"

        fun findLanIp(): String {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val addresses = interfaces.nextElement().inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) return address.hostAddress ?: "127.0.0.1"
                    }
                }
            } catch (_: Exception) {}
            return "127.0.0.1"
        }

        private val PAGE = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Phone files</title>
<style>
:root{color-scheme:dark}body{font:15px system-ui;background:#101827;color:#e8eef8;margin:0;padding:22px;max-width:1000px;margin:auto}h2{margin:4px 0 18px}.bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.crumb{color:#9bb8dc;margin:12px 0}.item{display:flex;align-items:center;gap:12px;padding:14px;border-bottom:1px solid #29364a}.item a{color:#dceaff;text-decoration:none;flex:1;overflow-wrap:anywhere}.meta{color:#91a0b5;font-size:12px}button,.btn{background:#284c79;color:white;border:0;border-radius:8px;padding:10px 14px;text-decoration:none}#status{color:#9bb8dc}input[type=checkbox]{width:19px;height:19px}
</style></head><body><h2>📁 Phone File Server</h2><div class="bar"><button onclick="load(current)">Refresh</button><button onclick="downloadSelected()">Download selected as ZIP</button><button onclick="downloadFolder()">Download this folder as ZIP</button><span id="status"></span></div><div class="crumb" id="crumb">/</div><div id="progress" style="margin:10px 0;color:#9bb8dc"></div><div id="files"></div>
<script>
let current='';const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function size(n){if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';return (n/1048576).toFixed(1)+' MB'}
async function load(p){current=p;document.getElementById('crumb').textContent='/'+p;document.getElementById('status').textContent='Loading…';try{let r=await fetch('/api/list?path='+encodeURIComponent(p));if(!r.ok)throw Error('Unable to list folder');let items=await r.json();let html='';if(p)html+='<div class="item">⬆️ <a href="#" onclick="up();return false">Parent folder</a></div>';for(const f of items){let path=encodeURIComponent(f.path);html+='<div class="item">'+(f.dir?'📁':'📄')+' <input type="checkbox" class="pick" value="'+esc(f.path)+'"><a href="#" onclick="'+(f.dir?'load(decodeURIComponent(\''+path+'\'))':'preview(\''+path+'\')')+';return false">'+esc(f.name)+'</a><span class="meta">'+(f.dir?'Folder':size(f.size))+'</span>'+(f.dir?'':'<a class="btn" href="/download?path='+path+'">Download</a>')+'</div>'}document.getElementById('files').innerHTML=html||'<p class="meta">This folder is empty.</p>';document.getElementById('status').textContent=items.length+' items'}catch(e){document.getElementById('status').textContent=e.message}}
function up(){let a=current.split('/');a.pop();load(a.join('/'))}function preview(p){window.open('/view?path='+p,'_blank')}
function selected(){return Array.from(document.querySelectorAll('.pick:checked')).map(x=>x.value)}
async function zip(paths){if(!paths.length){document.getElementById('status').textContent='Select at least one file or folder';return}const progress=document.getElementById('progress');progress.textContent='Preparing ZIP…';try{const response=await fetch('/download-zip',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(paths)});if(!response.ok)throw Error(await response.text());const reader=response.body.getReader();const chunks=[];let received=0;while(true){const part=await reader.read();if(part.done)break;chunks.push(part.value);received+=part.value.length;progress.textContent='Receiving ZIP: '+size(received)}const blob=new Blob(chunks,{type:'application/zip'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='phone-files.zip';a.click();setTimeout(()=>URL.revokeObjectURL(url),60000);progress.textContent='ZIP ready · '+size(received);document.getElementById('status').textContent='Download started'}catch(e){progress.textContent='ZIP failed: '+e.message}}
function downloadSelected(){zip(selected())}function downloadFolder(){zip([current])}
load('');
</script></body></html>
        """.trimIndent()
    }
}
