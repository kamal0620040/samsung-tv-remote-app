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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import com.kamal.tvcore.HttpRange
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
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

    /** Last server/capture failure: "port_busy" | "capture_failed" | null. */
    @Volatile var error: String? = null

    /**
     * Per-session access token. The HTTP server is reachable by the whole
     * LAN (the TV's browser must reach it), so every route requires this
     * token as ?t=; it is generated when the server starts and cleared when
     * it stops. Without it, anyone on the Wi-Fi could watch the stream.
     */
    @Volatile var sessionToken: String = ""

    @Synchronized
    fun token(): String {
        if (sessionToken.isEmpty()) {
            val bytes = ByteArray(9)
            SecureRandom().nextBytes(bytes)
            sessionToken = bytes.joinToString("") { "%02x".format(it) }
        }
        return sessionToken
    }

    fun localIp(): String? {
        var fallback: String? = null
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val host = a.hostAddress ?: continue
                        val name = ni.name.lowercase()
                        // Prefer Wi-Fi: VPN (tun/ppp) and cellular (rmnet)
                        // addresses are not reachable from the TV.
                        if (name.startsWith("wlan")) return host
                        if (fallback == null && !name.startsWith("tun") &&
                            !name.startsWith("ppp") && !name.startsWith("rmnet")
                        ) {
                            fallback = host
                        }
                    }
                }
            }
        } catch (e: Exception) { /* no network */ }
        return fallback
    }

    /** The address the running server is actually bound to; every URL and probe must use exactly this. */
    @Volatile var boundIp: String? = null

    fun pageUrl(): String = "http://${boundIp ?: localIp() ?: "0.0.0.0"}:$PORT/?t=${token()}"
    fun watchUrl(): String = "http://${boundIp ?: localIp() ?: "0.0.0.0"}:$PORT/watch?t=${token()}"
}

/**
 * Screen mirroring + media cast, modeled on the reference app: capture the
 * phone screen with MediaProjection, serve Motion-JPEG from a tiny built-in
 * HTTP server, and let the TV's browser open the stream page. Also serves a
 * picked photo/video/audio file to the same browser. All routes require the
 * per-session token (see MirrorState.sessionToken).
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

    /** Incremented whenever a capture starts or is torn down, so a stopped projection's late callback can tell it is stale. */
    @Volatile private var captureSession = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Strings.lang = getSharedPreferences("tvremote", Context.MODE_PRIVATE)
            .getString("lang", "en") ?: "en"
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MEDIA -> {
                stopCapture() // a screen capture must not leak into media mode
                closeClients() // stream clients from a previous mode must not linger
                MirrorState.mode = "media"
                MirrorState.mediaUri = intent.getStringExtra(EXTRA_URI)
                MirrorState.mediaKind = intent.getStringExtra(EXTRA_KIND) ?: "video"
                if (!enterForeground(Strings.t("notif_media"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)) return START_NOT_STICKY
                if (!startServer()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_MIRROR -> {
                stopCapture() // release any previous capture before starting a new one
                closeClients()
                MirrorState.mode = "mirror"
                MirrorState.quality = intent.getIntExtra(EXTRA_QUALITY, 1)
                if (!enterForeground(Strings.t("notif_mirror"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)) return START_NOT_STICKY
                if (!startServer()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                if (data != null) {
                    try {
                        startCapture(code, data)
                    } catch (e: Exception) {
                        MirrorState.error = "capture_failed"
                        stopEverything()
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Enter the foreground, reporting failure as a mirror error instead of
     * crashing the service when the platform refuses (missing permission,
     * background-start restrictions). Returns false when startup must abort.
     */
    private fun enterForeground(text: String, type: Int): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(text), type)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, buildNotification(text))
            }
            true
        } catch (e: Exception) {
            MirrorState.error = "capture_failed"
            stopEverything()
            stopSelf()
            false
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, Strings.t("notif_channel"), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(Strings.t("app_name"))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, Strings.t("notif_stop"), stop)
            .setOngoing(true)
            .build()
    }

    // ---------------- capture ----------------

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj
        val session = ++captureSession
        bgThread = HandlerThread("mirror-capture").apply { start() }
        val handler = Handler(bgThread!!.looper)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // A stop we triggered ourselves (mode switch, teardown)
                // bumps captureSession first, so this late callback from an
                // old projection must not kill the session that replaced it.
                if (session != captureSession) return
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
            if (session != captureSession) {
                // A frame queued by a previous capture: drain and drop it,
                // so it cannot overwrite the new session's stream.
                try { r.acquireLatestImage()?.close() } catch (e: Exception) { /* ignore */ }
                return@setOnImageAvailableListener
            }
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

    /**
     * Bind the server socket on the calling thread, then run the accept
     * loop in the background. Returns false when the port cannot be bound,
     * so the caller can abort instead of starting a capture nobody can
     * watch (and "running" is only ever claimed for a bound socket).
     */
    @Synchronized
    private fun startServer(): Boolean {
        if (serving) return true
        MirrorState.token() // make sure a token exists before anyone connects
        // Bind to the phone's LAN address (the same address the TV is given
        // in the URL), not to every interface; fall back to all interfaces
        // when no LAN address is known.
        val ss = try {
            val bindAddress = MirrorState.localIp()?.let { InetAddress.getByName(it) }
            if (bindAddress != null) {
                ServerSocket(MirrorState.PORT, 50, bindAddress)
            } else {
                ServerSocket(MirrorState.PORT)
            }
        } catch (e: Exception) {
            MirrorState.error = "port_busy"
            MirrorState.running = false
            return false
        }
        server = ss
        // Advertise exactly the address we bound, everywhere.
        val boundHost = ss.inetAddress?.hostAddress
        MirrorState.boundIp = if (boundHost == "0.0.0.0") null else boundHost
        serving = true
        MirrorState.running = true
        MirrorState.error = null
        serverThread = thread(name = "mirror-http") {
            while (serving) {
                val sock = try {
                    ss.accept()
                } catch (e: Exception) {
                    // A close from stopEverything (serving already false)
                    // is the normal stop; anything else is a real failure
                    // and must not leave the UI claiming "running".
                    if (serving) {
                        serving = false
                        MirrorState.running = false
                        MirrorState.error = "accept_failed"
                    }
                    break
                }
                if (clientSockets.size >= MAX_CLIENTS) {
                    try { sock.close() } catch (e: Exception) { }
                    continue
                }
                clientSockets.add(sock)
                thread {
                    try { handle(sock) } finally { clientSockets.remove(sock) }
                }
            }
        }
        return true
    }

    /** Read one line (LF or CRLF terminated), at most [max] bytes; null on EOF or overflow. */
    private fun readBoundedLine(input: java.io.InputStream, max: Int = 8192): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            if (buf.size() >= max) return null
            buf.write(b)
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val input = sock.getInputStream()
            val requestLine = readBoundedLine(input) ?: run { sock.close(); return }
            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            while (true) {
                val line = readBoundedLine(input) ?: run { sock.close(); return }
                if (line.isEmpty()) break
                if (++headerCount > 64) {
                    sock.close()
                    return
                }
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val target = requestLine.split(" ").getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val tok = query.split('&').firstOrNull { it.startsWith("t=") }?.substring(2)
            val out = sock.getOutputStream()
            if (tok != MirrorState.sessionToken) {
                writeStatus(out, 403, "Forbidden")
                out.flush()
                sock.close()
                return
            }
            when {
                path == "/" || path == "/index.html" -> {
                    if (MirrorState.mode == "media") {
                        writeRedirect(out, "/watch?t=${MirrorState.sessionToken}")
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
<body><img src="/stream?t=${MirrorState.sessionToken}"></body></html>"""

    private fun watchPage(): String {
        val t = MirrorState.sessionToken
        val tag = when (MirrorState.mediaKind) {
            "image" -> """<img src="/media?t=$t">"""
            "audio" -> """<audio src="/media?t=$t" controls autoplay></audio>"""
            else -> """<video src="/media?t=$t" controls autoplay></video>"""
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
        while (serving && MirrorState.mode == "mirror") {
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
        // Only a syntactically valid Range is honored; anything else is
        // ignored (full 200), never answered as a full-body 206.
        if (rangeHeader != null && total > 0 && HttpRange.isRangeHeader(rangeHeader)) {
            val r = HttpRange.parse(rangeHeader, total)
            if (r == null) {
                afd?.close()
                out.write(
                    "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                out.flush()
                return
            }
            start = r[0]
            end = r[1]
            partial = true
        }
        // Open and position the stream BEFORE writing any success header:
        // a stream that cannot open, or cannot reach the requested offset,
        // gets an error response, never a truncated or offset-wrong body.
        try {
            val input = try { contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            if (input == null) {
                writeStatus(out, 500, "Cannot read media")
                out.flush()
                return
            }
            input.use { stream ->
                if (start > 0 && !skipFully(stream, start)) {
                    out.write(
                        "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray()
                    )
                    out.flush()
                    return
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
                val buf = ByteArray(64 * 1024)
                var remaining = len
                while (true) {
                    val want = if (remaining in 1 until buf.size.toLong()) remaining.toInt() else buf.size
                    val n = stream.read(buf, 0, want)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (remaining > 0) {
                        remaining -= n
                        if (remaining <= 0) break
                    }
                }
                out.flush()
            }
        } finally {
            afd?.close()
        }
    }

    /** Skip exactly [bytes] or report failure; a short skip must not silently corrupt the stream. */
    private fun skipFully(input: java.io.InputStream, bytes: Long): Boolean {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = try { input.skip(remaining) } catch (e: Exception) { return false }
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() == -1) return false
                remaining--
            }
        }
        return true
    }

    // ---------------- lifecycle ----------------

    /** Release capture resources only; the HTTP server may keep running. */
    private fun stopCapture() {
        // Invalidate the current projection's callback first: stop() below
        // fires onStop asynchronously, and it must not tear down a session
        // that starts after this call (mode switch, restart).
        captureSession++
        MirrorState.latestJpeg = null
        lastFrameAt = 0L
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        bgThread?.quitSafely()
        bgThread = null
    }

    /** Force every connected HTTP client out, so stream handlers exit their loops and slots free up. */
    private fun closeClients() {
        for (sock in clientSockets) {
            try { sock.close() } catch (e: Exception) { }
        }
        clientSockets.clear()
    }

    @Synchronized
    private fun stopEverything() {
        serving = false
        MirrorState.running = false
        MirrorState.sessionToken = ""
        MirrorState.boundIp = null
        closeClients()
        stopCapture()
        try { server?.close() } catch (e: Exception) { }
        server = null
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
        private const val MAX_CLIENTS = 4
    }
}
