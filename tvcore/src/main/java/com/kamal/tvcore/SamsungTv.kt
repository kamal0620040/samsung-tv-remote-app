package com.kamal.tvcore

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Stores pairing tokens per TV (apps back this with SharedPreferences). */
interface TokenStore {
    fun get(ip: String): String?
    fun put(ip: String, token: String)
}

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class TvApp(
    val appId: String,
    val name: String,
    val appType: Int,
    val isVisible: Boolean,
    val iconPath: String = "",
)

/**
 * Samsung TV (2016+) remote protocol client — a clean-room implementation from
 * reverse-engineered evidence (see FINDINGS.md). Connects over
 * wss://<ip>:8002/api/v2/channels/samsung.remote.control, pairs by token,
 * and sends the exact message shapes the original app sends (verified against
 * its decompiled code). The TV's certificate is self-signed; like every
 * Samsung remote, this client trusts it for the TV endpoint only.
 */
class SamsungTv(
    private val remoteName: String = "Kamal Remote",
    private val tokens: TokenStore,
) {
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    var ip: String? = null
        private set

    /** Friendly failure kind: "unreachable" | "timeout" | "failed". Never raw exception text. */
    var lastError: String? = null
        private set
    var lastErrorRaw: String? = null
        private set

    private var ws: WebSocket? = null
    private var appsCache: List<TvApp>? = null
    private var pendingApps: CompletableDeferred<List<TvApp>>? = null
    private val pendingIcons = mutableMapOf<String, CompletableDeferred<String?>>()

    private val http: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    val connected: Boolean get() = _state.value == ConnState.CONNECTED

    fun connect(ip: String, port: Int = 8002) {
        disconnect()
        this.ip = ip
        _state.value = ConnState.CONNECTING
        val nameB64 = Base64.encodeToString(remoteName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        var url = "wss://$ip:$port/api/v2/channels/samsung.remote.control?name=$nameB64"
        tokens.get(ip)?.takeIf { it.isNotEmpty() }?.let { url += "&token=$it" }
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastErrorRaw = t.message
                val msg = t.message ?: ""
                lastError = when {
                    t is java.net.ConnectException ||
                        msg.contains("failed to connect", ignoreCase = true) ||
                        msg.contains("Unable to resolve host", ignoreCase = true) -> "unreachable"
                    t is java.net.SocketTimeoutException ||
                        msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("after 10000ms", ignoreCase = true) -> "timeout"
                    else -> "failed"
                }
                _state.value = ConnState.ERROR
                pendingApps?.completeExceptionally(t)
                pendingApps = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_state.value != ConnState.ERROR) _state.value = ConnState.DISCONNECTED
            }
        })
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
                val key = path?.takeIf { pendingIcons.containsKey(it) }
                    ?: pendingIcons.keys.firstOrNull()
                if (key != null) {
                    pendingIcons.remove(key)?.complete(b64)
                }
            }
        }
    }

    fun disconnect() {
        try { ws?.close(1000, null) } catch (e: Exception) { /* already closed */ }
        ws = null
        appsCache = null
        _state.value = ConnState.DISCONNECTED
    }

    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    private fun remoteControl(cmd: String, dataOfCmd: String) {
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
    }

    /** Single key press (Click). Key codes: KEY_UP, KEY_ENTER, KEY_VOLUP, ... */
    fun click(key: String) = remoteControl("Click", key)

    /** Begin a long-press (pair with [release]). */
    fun press(key: String) = remoteControl("Press", key)

    /** End a long-press started with [press]. */
    fun release(key: String) = remoteControl("Release", key)

    /** Type text into the focused field on the TV (exact original shape). */
    fun sendText(text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        send(
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

    /** Installed apps on the TV (cached after the first successful fetch). */
    suspend fun getApps(timeoutMs: Long = 6_000): List<TvApp> {
        appsCache?.let { return it }
        val deferred = CompletableDeferred<List<TvApp>>()
        pendingApps = deferred
        send(
            JSONObject()
                .put("method", "ms.channel.emit")
                .put(
                    "params", JSONObject()
                        .put("data", "")
                        .put("event", "ed.installedApp.get")
                        .put("to", "host")
                )
        )
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: emptyList()
    }

    /**
     * Fetch one app's icon (base64 PNG) via `ed.apps.icon` — the TV answers
     * asynchronously with the image. Returns null on timeout/no icon.
     */
    suspend fun getAppIcon(iconPath: String, timeoutMs: Long = 5_000): String? {
        if (iconPath.isEmpty()) return null
        val deferred = CompletableDeferred<String?>()
        pendingIcons[iconPath] = deferred
        send(
            JSONObject()
                .put("method", "ms.channel.emit")
                .put(
                    "params", JSONObject()
                        .put("data", JSONObject().put("iconPath", iconPath))
                        .put("event", "ed.apps.icon")
                        .put("to", "host")
                )
        )
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingIcons.remove(iconPath)
        }
    }

    /**
     * Launch an app. DEEP_LINK when the app reports app_type 2 (YouTube,
     * Netflix, ...), NATIVE_LAUNCH otherwise — same rule as the original app.
     */
    fun launchApp(appId: String, appType: Int? = null) {
        val type = appType ?: appsCache?.firstOrNull { it.appId == appId }?.appType
        val action = if (type == 2) "DEEP_LINK" else "NATIVE_LAUNCH"
        send(
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
