package com.kamal.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Shared state between MainActivity and MirrorService. */
object MirrorState {
    const val PORT = 8080
    @Volatile var running = false
    @Volatile var mode: String = "mirror" // "mirror" | "media"
    @Volatile var mediaUri: String? = null
    @Volatile var mediaKind: String = "video" // image | video | audio
    @Volatile var quality: Int = 1 // 0 smooth, 1 standard, 2 sharp
    @Volatile var latestJpeg: ByteArray? = null
    @Volatile var frameSeq: Long = 0

    fun localIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
        } catch (e: Exception) { /* no network */ }
        return null
    }

    fun pageUrl(): String = "http://${localIp() ?: "0.0.0.0"}:$PORT/"
    fun watchUrl(): String = "http://${localIp() ?: "0.0.0.0"}:$PORT/watch"
}

/**
 * Screen mirroring + media cast, modeled on the reference app: capture the
 * phone screen with MediaProjection, serve Motion-JPEG from a tiny built-in
 * HTTP server, and let the TV's browser open the stream page. Also serves a
 * picked photo/video/audio file to the same browser.
 */
class MirrorService : Service() {

    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private val clientSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    @Volatile private var serving = false

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MEDIA -> {
                MirrorState.mode = "media"
                MirrorState.mediaUri = intent.getStringExtra(EXTRA_URI)
                MirrorState.mediaKind = intent.getStringExtra(EXTRA_KIND) ?: "video"
                startForeground(NOTIF_ID, buildNotification("Casting media to TV"))
                startServer()
            }
            ACTION_MIRROR -> {
                MirrorState.mode = "mirror"
                MirrorState.quality = intent.getIntExtra(EXTRA_QUALITY, 1)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                        NOTIF_ID, buildNotification("Screen mirroring is on"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startForeground(NOTIF_ID, buildNotification("Screen mirroring is on"))
                }
                startServer()
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                if (data != null) startCapture(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("TV Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    // ---------------- capture ----------------

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj
        bgThread = HandlerThread("mirror-capture").apply { start() }
        val handler = Handler(bgThread!!.looper)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopEverything()
                stopSelf()
            }
        }, handler)

        val metrics = resources.displayMetrics
        val srcW = metrics.widthPixels
        val srcH = metrics.heightPixels
        val targetW = when (MirrorState.quality) { 0 -> 960; 2 -> 1600; else -> 1280 }
        val scale = if (srcW > targetW) targetW.toFloat() / srcW else 1f
        val w = ((srcW * scale).toInt() / 2) * 2
        val h = ((srcH * scale).toInt() / 2) * 2
        val jpegQuality = when (MirrorState.quality) { 0 -> 55; 2 -> 82; else -> 70 }
        val minInterval = when (MirrorState.quality) { 0 -> 66L; 2 -> 40L; else -> 50L }

        val imgReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = imgReader
        imgReader.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < minInterval) {
                // still need to drain the image
                try { r.acquireLatestImage()?.close() } catch (e: Exception) { /* ignore */ }
                return@setOnImageAvailableListener
            }
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val bmpW = w + rowPadding / pixelStride
                val bmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                val finalBmp = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
                val out = ByteArrayOutputStream()
                finalBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                MirrorState.latestJpeg = out.toByteArray()
                MirrorState.frameSeq++
                lastFrameAt = now
                if (finalBmp !== bmp) bmp.recycle()
                finalBmp.recycle()
            } catch (e: Exception) {
                // frame dropped — next one will come
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = proj.createVirtualDisplay(
            "KamalMirror", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imgReader.surface, null, handler
        )
    }

    // ---------------- HTTP server ----------------

    private fun startServer() {
        if (serving) return
        serving = true
        MirrorState.running = true
        serverThread = thread(name = "mirror-http") {
            try {
                val ss = ServerSocket(MirrorState.PORT)
                server = ss
                while (serving) {
                    val sock = ss.accept()
                    clientSockets.add(sock)
                    thread {
                        try { handle(sock) } finally { clientSockets.remove(sock) }
                    }
                }
            } catch (e: Exception) {
                // socket closed on stop
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val requestLine = reader.readLine() ?: run { sock.close(); return }
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) headers[line!!.substring(0, idx).trim().lowercase()] = line!!.substring(idx + 1).trim()
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = sock.getOutputStream()
            when {
                path == "/" || path == "/index.html" -> {
                    if (MirrorState.mode == "media") {
                        writeRedirect(out, "/watch")
                    } else {
                        writeHtml(out, streamPage())
                    }
                }
                path == "/stream" -> serveStream(out)
                path == "/watch" -> writeHtml(out, watchPage())
                path == "/media" -> serveMedia(out, headers["range"])
                else -> writeStatus(out, 404, "Not found")
            }
            out.flush()
            sock.close()
        } catch (e: Exception) {
            try { sock.close() } catch (ignored: Exception) { }
        }
    }

    private fun streamPage(): String = """<!doctype html><html><head><meta charset="utf-8">
<title>Screen Mirroring</title><style>html,body{margin:0;background:#000;height:100%}
img{width:100%;height:100%;object-fit:contain;display:block}</style></head>
<body><img src="/stream"></body></html>"""

    private fun watchPage(): String {
        val tag = when (MirrorState.mediaKind) {
            "image" -> """<img src="/media">"""
            "audio" -> """<audio src="/media" controls autoplay></audio>"""
            else -> """<video src="/media" controls autoplay></video>"""
        }
        return """<!doctype html><html><head><meta charset="utf-8"><title>Cast</title>
<style>html,body{margin:0;background:#000;height:100%;display:flex;align-items:center;justify-content:center}
img,video{max-width:100%;max-height:100%}audio{width:80%}</style></head><body>$tag</body></html>"""
    }

    private fun writeHtml(out: OutputStream, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
    }

    private fun writeRedirect(out: OutputStream, to: String) {
        out.write("HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun serveStream(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.flush()
        var last = -1L
        while (serving) {
            val seq = MirrorState.frameSeq
            val frame = MirrorState.latestJpeg
            if (frame != null && seq != last) {
                last = seq
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            } else {
                Thread.sleep(30)
            }
        }
    }

    private fun serveMedia(out: OutputStream, rangeHeader: String?) {
        val uriStr = MirrorState.mediaUri ?: run { writeStatus(out, 404, "No media"); return }
        val uri = Uri.parse(uriStr)
        val mime = contentResolver.getType(uri) ?: when (MirrorState.mediaKind) {
            "image" -> "image/jpeg"
            "audio" -> "audio/mpeg"
            else -> "video/mp4"
        }
        val afd = try { contentResolver.openAssetFileDescriptor(uri, "r") } catch (e: Exception) { null }
        val total = afd?.length ?: -1L
        var start = 0L
        var end = if (total > 0) total - 1 else -1L
        var partial = false
        if (rangeHeader != null && total > 0) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader)
            if (m != null) {
                if (m.groupValues[1].isNotEmpty()) start = m.groupValues[1].toLong()
                if (m.groupValues[2].isNotEmpty()) end = m.groupValues[2].toLong()
                partial = true
            }
        }
        val len = if (total > 0) (end - start + 1) else -1L
        val sb = StringBuilder()
        sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: $mime\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (total > 0) {
            sb.append("Content-Length: $len\r\n")
            if (partial) sb.append("Content-Range: bytes $start-$end/$total\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        contentResolver.openInputStream(uri)?.use { input ->
            if (start > 0) {
                var toSkip = start
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
            }
            val buf = ByteArray(64 * 1024)
            var remaining = len
            while (true) {
                val want = if (remaining in 1 until buf.size.toLong()) remaining.toInt() else buf.size
                val n = input.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (remaining > 0) {
                    remaining -= n
                    if (remaining <= 0) break
                }
            }
            out.flush()
        }
        afd?.close()
    }

    // ---------------- lifecycle ----------------

    private fun stopEverything() {
        serving = false
        MirrorState.running = false
        // Close every accepted client socket: a /stream handler blocked in a
        // socket write otherwise never wakes up, and the TV keeps its stream.
        for (sock in clientSockets) {
            try { sock.close() } catch (e: Exception) { }
        }
        clientSockets.clear()
        MirrorState.latestJpeg = null
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        try { server?.close() } catch (e: Exception) { }
        server = null
        bgThread?.quitSafely()
        bgThread = null
    }

    override fun onDestroy() {
        stopEverything()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val ACTION_MIRROR = "com.kamal.tvremote.MIRROR"
        const val ACTION_MEDIA = "com.kamal.tvremote.MEDIA"
        const val ACTION_STOP = "com.kamal.tvremote.STOP_MIRROR"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_URI = "uri"
        const val EXTRA_KIND = "kind"
        private const val CHANNEL = "mirror"
        private const val NOTIF_ID = 7
    }
}
