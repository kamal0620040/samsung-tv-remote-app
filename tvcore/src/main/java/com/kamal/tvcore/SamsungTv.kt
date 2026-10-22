package com.kamal.tvcore

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Stores pairing tokens per TV (apps back this with SharedPreferences). */
interface TokenStore {
    fun get(ip: String): String?
    fun put(ip: String, token: String)
    fun remove(ip: String)
}

/** Stores pinned TV certificate fingerprints (SHA-256 hex) per TV. */
interface CertStore {
    fun get(ip: String): String?
    fun put(ip: String, fingerprint: String)
    fun remove(ip: String)
}

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** True for dotted-quad IPv4 literals and IPv6 literals; hostnames rejected. */
fun isValidHostAddress(s: String): Boolean {
    if (s.isEmpty() || s.length > 45) return false
    if (s.contains(':')) return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    val parts = s.split('.')
    if (parts.size != 4) return false
    return parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
}

data class TvApp(
    val appId: String,
    val name: String,
    val appType: Int,
    val isVisible: Boolean,
    val iconPath: String = "",
)

/**
 * Samsung TV (2016+) remote protocol client: a clean-room implementation
 * built from reverse-engineered protocol evidence. Connects over
 * wss://<ip>:8002/api/v2/channels/samsung.remote.control, pairs by token,
 * and sends the exact message shapes the reference implementation sends.
 *
 * TLS: Samsung TVs present self-signed certificates with no hostname that
 * could pass normal verification, so trust is anchored by pinning instead:
 * the first connection to a TV stores its certificate fingerprint, and
 * later connections must present the same certificate. A changed
 * certificate fails the connection with lastError "cert" until the user
 * explicitly trusts the new one (which clears the pin and the token).
 */
class SamsungTv(
    private val remoteName: String = "Kamal Remote",
    private val tokens: TokenStore,
    private val certs: CertStore? = null,
) {
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    var ip: String? = null
        private set

    /** Friendly failure kind: "unreachable" | "timeout" | "cert" | "failed". Never raw exception text. */
    var lastError: String? = null
        private set

    @Volatile private var ws: WebSocket? = null
    @Volatile private var appsCache: List<TvApp>? = null
    @Volatile private var pendingApps: CompletableDeferred<List<TvApp>>? = null
    private val pendingIcons = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** Incremented on every connect/disconnect; callbacks from older sockets are ignored. */
    @Volatile private var generation = 0

    /** Host the trust manager should pin/verify; set before each connection attempt. */
    @Volatile private var targetHost: String? = null

    private val http: OkHttpClient by lazy {
        val pinning = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val store = certs ?: return // no store supplied: accept (documented fallback)
                val host = targetHost ?: throw CertificateException("no target host")
                if (chain.isEmpty()) throw CertificateException("empty certificate chain")
                val fp = MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                val stored = store.get(host)
                when {
                    stored == null -> store.put(host, fp) // first sight: pin it
                    stored != fp -> throw CertificateException("TV certificate changed")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(pinning), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, pinning)
            // TV certificates carry no usable hostname; the pin above is the
            // authentication, so hostname verification stays off by design.
            .hostnameVerifier { _, _ -> true }
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    val connected: Boolean get() = _state.value == ConnState.CONNECTED

    fun connect(ip: String, port: Int = 8002) {
        disconnect()
        this.ip = ip
        if (!isValidHostAddress(ip)) {
            lastError = "failed"
            _state.value = ConnState.ERROR
            return
        }
        _state.value = ConnState.CONNECTING
        targetHost = ip
        val gen = generation
        try {
            val nameB64 = Base64.encodeToString(remoteName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val url = HttpUrl.Builder()
                .scheme("wss")
                .host(ip)
                .port(port)
                .addPathSegments("api/v2/channels/samsung.remote.control")
                .addQueryParameter("name", nameB64)
                .apply {
                    tokens.get(ip)?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("token", it) }
                }
                .build()
            val req = Request.Builder().url(url).build()
            ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != generation) return // stale socket
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (gen != generation) return // stale socket
                    val msg = t.message ?: ""
                    lastError = when {
                        isCertFailure(t) -> "cert"
                        t is java.net.ConnectException ||
                            msg.contains("failed to connect", ignoreCase = true) ||
                            msg.contains("Unable to resolve host", ignoreCase = true) -> "unreachable"
                        t is java.net.SocketTimeoutException ||
                            msg.contains("timeout", ignoreCase = true) ||
                            msg.contains("after 10000ms", ignoreCase = true) -> "timeout"
                        else -> "failed"
                    }
                    _state.value = ConnState.ERROR
                    failPending(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (gen != generation) return // stale socket
                    if (_state.value != ConnState.ERROR) _state.value = ConnState.DISCONNECTED
                    failPending(CancellationException("connection closed"))
                }
            })
        } catch (e: Exception) {
            lastError = "failed"
            _state.value = ConnState.ERROR
        }
    }

    private fun isCertFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is CertificateException || cur is javax.net.ssl.SSLException) return true
            cur = cur.cause
        }
        return false
    }

    private fun failPending(cause: Throwable) {
        pendingApps?.completeExceptionally(cause)
        pendingApps = null
        val icons = pendingIcons.values.toList()
        pendingIcons.clear()
        icons.forEach { it.completeExceptionally(cause) }
    }

    private fun handleMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("event")) {
            "ms.channel.connect", "ms.channel.ready" -> {
                val token = msg.optJSONObject("data")?.optString("token").orEmpty()
                val myIp = ip
                if (token.isNotEmpty() && myIp != null) tokens.put(myIp, token)
                _state.value = ConnState.CONNECTED
            }
            "ed.installedApp.get" -> {
                val data = msg.optJSONObject("data")
                val arr = data?.optJSONArray("data") ?: data?.optJSONArray("apps")
                val list = mutableListOf<TvApp>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        list.add(
                            TvApp(
                                appId = a.optString("appId"),
                                name = a.optString("name"),
                                appType = a.optInt("app_type", -1),
                                isVisible = a.optBoolean("isVisible", true),
                                iconPath = a.optString("icon"),
                            )
                        )
                    }
                }
                appsCache = list
                pendingApps?.complete(list)
                pendingApps = null
            }
            "ed.apps.icon" -> {
                val data = msg.optJSONObject("data")
                val b64 = data?.optString("iconBase64")?.takeIf { it.isNotEmpty() }
                    ?: data?.optString("imageBase64")?.takeIf { it.isNotEmpty() }
                    ?: data?.optJSONObject("data")?.optString("iconBase64")?.takeIf { it.isNotEmpty() }
                val path = data?.optString("iconPath")
                    ?: data?.optJSONObject("data")?.optString("iconPath")
                // Only use the single-pending fallback when it cannot mismatch.
                val key = path?.takeIf { pendingIcons.containsKey(it) }
                    ?: if (pendingIcons.size == 1) pendingIcons.keys.first() else null
                if (key != null) {
                    pendingIcons.remove(key)?.complete(b64)
                }
            }
        }
    }

    fun disconnect() {
        generation++
        try { ws?.close(1000, null) } catch (e: Exception) { /* already closed */ }
        ws = null
        appsCache = null
        failPending(CancellationException("disconnected"))
        if (_state.value != ConnState.ERROR) _state.value = ConnState.DISCONNECTED
    }

    private fun send(obj: JSONObject): Boolean =
        try { ws?.send(obj.toString()) ?: false } catch (e: Exception) { false }

    private fun remoteControl(cmd: String, dataOfCmd: String): Boolean =
        send(
            JSONObject()
                .put("method", "ms.remote.control")
                .put(
                    "params", JSONObject()
                        .put("Cmd", cmd)
                        .put("DataOfCmd", dataOfCmd)
                        .put("Option", "false")
                        .put("TypeOfRemote", "SendRemoteKey")
                )
        )

    /** Single key press (Click). Key codes: KEY_UP, KEY_ENTER, KEY_VOLUP, ... */
    fun click(key: String): Boolean = remoteControl("Click", key)

    /** Begin a long-press (pair with [release]). */
    fun press(key: String): Boolean = remoteControl("Press", key)

    /** End a long-press started with [press]. */
    fun release(key: String): Boolean = remoteControl("Release", key)

    /** Type text into the focused field on the TV (exact original shape). */
    fun sendText(text: String): Boolean {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return send(
            JSONObject()
                .put("method", "ms.remote.control")
                .put(
                    "params", JSONObject()
                        .put("Cmd", b64)
                        .put("DataOfCmd", "base64")
                        .put("TypeOfRemote", "SendInputString")
                )
        )
    }

    /** Installed apps on the TV (cached after the first successful fetch). Never throws. */
    suspend fun getApps(timeoutMs: Long = 6_000): List<TvApp> {
        appsCache?.let { return it }
        val deferred = pendingApps ?: CompletableDeferred<List<TvApp>>().also { created ->
            pendingApps = created
            if (!send(
                    JSONObject()
                        .put("method", "ms.channel.emit")
                        .put(
                            "params", JSONObject()
                                .put("data", "")
                                .put("event", "ed.installedApp.get")
                                .put("to", "host")
                        )
                )
            ) {
                pendingApps = null
                return emptyList()
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch one app's icon (base64 PNG) via `ed.apps.icon` — the TV answers
     * asynchronously with the image. Returns null on timeout/no icon. Never throws.
     */
    suspend fun getAppIcon(iconPath: String, timeoutMs: Long = 5_000): String? {
        if (iconPath.isEmpty()) return null
        val deferred = CompletableDeferred<String?>()
        pendingIcons[iconPath] = deferred
        if (!send(
                JSONObject()
                    .put("method", "ms.channel.emit")
                    .put(
                        "params", JSONObject()
                            .put("data", JSONObject().put("iconPath", iconPath))
                            .put("event", "ed.apps.icon")
                            .put("to", "host")
                    )
            )
        ) {
            pendingIcons.remove(iconPath)
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            null
        } finally {
            pendingIcons.remove(iconPath)
        }
    }

    /**
     * Launch an app. DEEP_LINK when the app reports app_type 2 (YouTube,
     * Netflix, ...), NATIVE_LAUNCH otherwise — same rule as the original app.
     */
    fun launchApp(appId: String, appType: Int? = null): Boolean {
        val type = appType ?: appsCache?.firstOrNull { it.appId == appId }?.appType
        val action = if (type == 2) "DEEP_LINK" else "NATIVE_LAUNCH"
        return send(
            JSONObject()
                .put("method", "ms.channel.emit")
                .put(
                    "params", JSONObject()
                        .put(
                            "data", JSONObject()
                                .put("action_type", action)
                                .put("appId", appId)
                        )
                        .put("event", "ed.apps.launch")
                        .put("to", "host")
                )
        )
    }

    companion object {
        const val APP_YOUTUBE = "111299001912"
        const val APP_NETFLIX = "11101200001"
        const val APP_BROWSER = "org.tizen.browser"
    }
}
