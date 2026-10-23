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
 * could pass normal verification, so trust is anchored by pinning instead.
 * Each connection gets its own trust manager bound to that TV and that
 * connection attempt. A handshake only records a candidate fingerprint;
 * the pin is stored once the channel actually connects, so a transient or
 * wrong endpoint is never pinned silently.
 *
 * A stored pin that no longer matches does NOT fail the connection.
 * Samsung TVs roll their self-signed certificate (a reboot or power cut
 * is enough), and hard-failing on the new certificate bricked every
 * reconnect until a manual re-trust buried in the UI (Kamal's report,
 * 2026-10-23: the app showed Not connected from the moment it opened).
 * The changed certificate is accepted and re-pinned once the channel
 * connects; the real authentication gate is the pairing itself, since a
 * certificate change also drops the stored token's session and the TV
 * asks on its own screen before issuing a new token.
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

    /**
     * One-line snapshot of the current connection setup and last failure,
     * shown in the UI (banner and Settings) so a screenshot of the app is
     * enough to diagnose a refused connection: target, whether a pairing
     * token was sent, whether a certificate is saved, and the failure kind.
     */
    fun diagLine(): String {
        val host = ip ?: return ""
        val parts = mutableListOf("TV $host:$attemptPort")
        parts += if (attemptUsedToken) "pairing token sent" else "no pairing token"
        parts += if (certs?.get(host) != null) "certificate saved" else "no certificate saved"
        lastError?.let { parts += "error: $it" }
        lastFailureDetail?.let { parts += "detail: $it" }
        return parts.joinToString(" · ")
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var appsCache: List<TvApp>? = null
    @Volatile private var pendingApps: CompletableDeferred<List<TvApp>>? = null
    private val appsLock = Any()
    private val pendingIcons = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** Incremented on every connect/disconnect; callbacks from older sockets are ignored. */
    @Volatile private var generation = 0

    /** Port of the current connect() call, kept so a tokenless retry targets the same endpoint. */
    private var attemptPort = 8002

    /** True when the current attempt sent the stored pairing token. */
    @Volatile private var attemptUsedToken = false

    /** True once the current connect() has already retried without a token. */
    @Volatile private var attemptRetriedFresh = false

    /** True when the current attempt must not send the stored token (the one tokenless re-pair retry). */
    @Volatile private var attemptSkipToken = false

    /** Raw detail of the most recent failure (exception name + short message), diagnostics line only. */
    @Volatile private var lastFailureDetail: String? = null

    /** True once the current attempt reached the channel-connected event. */
    @Volatile private var attemptConnected = false

    /** A certificate seen during a handshake, not yet promoted to a stored pin. */
    private data class CandidatePin(val gen: Int, val host: String, val fingerprint: String)

    @Volatile private var candidatePin: CandidatePin? = null

    /** Fingerprint of the certificate the current TV presented but that does not match the stored pin. */
    val pendingPinFingerprint: String?
        get() = candidatePin
            ?.takeIf { it.host == ip && it.gen == generation && certs?.get(it.host) != it.fingerprint }
            ?.fingerprint

    /**
     * Trust the certificate the current connection attempt presented.
     * Callers should confirm with the user first and clear the pairing
     * token, since a new certificate means a new pairing. Returns false
     * when there is no current candidate (for example the fingerprint
     * came from a stale handshake); in that case nothing changes, because
     * silently dropping the stored pin would leave the next connection
     * unauthenticated until it happened to succeed.
     */
    fun trustPendingCertificate(): Boolean {
        val host = ip ?: return false
        val candidate = candidatePin?.takeIf { it.host == host && it.gen == generation }
            ?: return false
        certs?.put(host, candidate.fingerprint)
        candidatePin = null
        return true
    }

    private inner class PinningTrustManager(
        private val host: String,
        private val gen: Int,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val store = certs ?: return // no store supplied: accept (documented fallback)
            if (chain.isEmpty()) throw CertificateException("empty certificate chain")
            val fp = MessageDigest.getInstance("SHA-256")
                .digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
            // Record what this connection presented; promotion to a stored
            // pin happens only when the channel connects (handleMessage).
            // A late handshake from an older attempt must not overwrite
            // the current attempt's candidate.
            if (gen == generation) candidatePin = CandidatePin(gen, host, fp)
            // A fingerprint that differs from the stored pin is NOT
            // rejected here: Samsung TVs roll their certificate on
            // reboot, so a mismatch is routine, not an attack signal.
            // The new fingerprint is re-pinned on connect (handleMessage)
            // and the pairing token remains the authentication: if the
            // TV also dropped the pairing, the token self-heal in
            // failAttempt re-pairs through the TV's own Allow prompt.
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val baseHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
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
        attemptPort = port
        attemptRetriedFresh = false
        attemptSkipToken = false
        startAttempt()
    }

    private fun startAttempt() {
        val host = ip ?: return
        attemptConnected = false
        lastError = null
        _state.value = ConnState.CONNECTING
        val gen = generation
        // Close any socket left over from the attempt this one replaces
        // (the tokenless retry below reuses the same generation).
        try { ws?.close(1000, null) } catch (e: Exception) { /* already closed */ }
        ws = null
        try {
            val nameB64 = Base64.encodeToString(remoteName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val storedToken = if (attemptSkipToken) null
                else tokens.get(host)?.takeIf { it.isNotEmpty() }
            attemptUsedToken = storedToken != null
            val url = HttpUrl.Builder()
                .scheme("wss")
                .host(host)
                .port(attemptPort)
                .addPathSegments("api/v2/channels/samsung.remote.control")
                .addQueryParameter("name", nameB64)
                .apply {
                    storedToken?.let { addQueryParameter("token", it) }
                }
                .build()
            val req = Request.Builder().url(url).build()
            // A trust manager bound to this host and this attempt, so a late
            // handshake from an older socket can never verify against (or
            // record a candidate for) the wrong TV.
            val trustManager = PinningTrustManager(host, gen)
            val ssl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            val client = baseHttp.newBuilder()
                .sslSocketFactory(ssl.socketFactory, trustManager)
                // TV certificates carry no usable hostname; the pin above is
                // the authentication, so hostname verification stays off.
                .hostnameVerifier { _, _ -> true }
                .build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != generation) return // stale socket
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (gen != generation) return // stale socket
                    val msg = t.message ?: ""
                    // Diagnostics only (the small details line): keep the
                    // raw cause, with any token value scrubbed out.
                    lastFailureDetail = t.javaClass.simpleName +
                        msg.takeIf { it.isNotBlank() }?.let {
                            ": " + it.replace(Regex("token=[^&\\s]*"), "token=...").take(90)
                        }.orEmpty()
                    val kind = when {
                        isCertFailure(t) -> "cert"
                        t is java.net.ConnectException ||
                            msg.contains("failed to connect", ignoreCase = true) ||
                            msg.contains("Unable to resolve host", ignoreCase = true) -> "unreachable"
                        t is java.net.SocketTimeoutException ||
                            msg.contains("timeout", ignoreCase = true) ||
                            msg.contains("after 10000ms", ignoreCase = true) -> "timeout"
                        else -> "failed"
                    }
                    failAttempt(kind, t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (gen != generation) return // stale socket
                    if (attemptConnected) {
                        if (_state.value != ConnState.ERROR) _state.value = ConnState.DISCONNECTED
                        // Transport failure is not caller cancellation: pending
                        // requests fail with a normal exception so awaiting UI
                        // coroutines end with an error state, not a cancel.
                        failPending(IllegalStateException("connection closed"))
                    } else {
                        // Closed before the channel came up. For an attempt
                        // that used the stored token this is the classic
                        // signature of the TV no longer accepting it.
                        lastFailureDetail = "socket closed before the channel connected"
                        failAttempt("failed", IllegalStateException("connection closed"))
                    }
                }
            })
        } catch (e: Exception) {
            lastError = "failed"
            _state.value = ConnState.ERROR
        }
    }

    /**
     * A connection attempt died before the channel came up. When the
     * attempt used the stored pairing token and the failure is a
     * refusal ("failed"), the likeliest cause is the TV no longer
     * accepting that token (its pairing entry removed, or the token
     * rolled), so retry once WITHOUT sending it: the TV then shows its
     * pairing prompt and issues a fresh token, which handleMessage
     * stores over the old one. The stored token is deliberately NOT
     * deleted here: an earlier version deleted it on any refusal, which
     * could destroy a good pairing on a transient failure and strand
     * the app tokenless (the state Kamal's diagnostics showed on
     * 2026-10-23). Certificate failures never retry here: they belong
     * to the trust flow, which needs the stored candidate and the
     * ERROR state. A TV that never answered (unreachable/timeout) is
     * not retried either; a different token cannot help there.
     */
    private fun failAttempt(kind: String, cause: Throwable) {
        val host = ip
        if (!attemptConnected && kind == "failed" && attemptUsedToken &&
            !attemptRetriedFresh && host != null
        ) {
            attemptRetriedFresh = true
            attemptSkipToken = true
            failPending(cause)
            startAttempt()
            return
        }
        lastError = kind
        if (kind != "cert") candidatePin = null // keep the candidate for the trust flow
        _state.value = ConnState.ERROR
        failPending(cause)
    }

    /** Only an actual certificate validation failure counts as "cert"; other TLS errors do not. */
    private fun isCertFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is CertificateException) return true
            cur = cur.cause
        }
        return false
    }

    private fun failPending(cause: Throwable) {
        synchronized(appsLock) {
            pendingApps?.completeExceptionally(cause)
            pendingApps = null
        }
        val icons = pendingIcons.values.toList()
        pendingIcons.clear()
        icons.forEach { it.completeExceptionally(cause) }
    }

    private fun handleMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("event")) {
            "ms.channel.connect", "ms.channel.ready" -> {
                attemptConnected = true
                attemptSkipToken = false
                lastFailureDetail = null
                val token = msg.optJSONObject("data")?.optString("token").orEmpty()
                val myIp = ip
                if (token.isNotEmpty() && myIp != null) tokens.put(myIp, token)
                // The channel is really up: promote this connection's
                // candidate certificate to the stored pin.
                candidatePin?.let { candidate ->
                    if (candidate.gen == generation && candidate.host == myIp) {
                        certs?.put(candidate.host, candidate.fingerprint)
                    }
                }
                _state.value = ConnState.CONNECTED
            }
            "ms.channel.unauthorized" -> {
                // The TV rejected the pairing token outright. It may keep
                // the socket open afterwards, so do not wait for a close
                // that might never come: run the same recovery as a
                // pre-connect failure (retry once without the token).
                lastFailureDetail = "ms.channel.unauthorized"
                failAttempt("failed", IllegalStateException("unauthorized"))
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
                synchronized(appsLock) {
                    pendingApps?.complete(list)
                    pendingApps = null
                }
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
        candidatePin = null
        try { ws?.close(1000, null) } catch (e: Exception) { /* already closed */ }
        ws = null
        appsCache = null
        failPending(IllegalStateException("disconnected"))
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

    /**
     * Installed apps on the TV (cached after the first successful fetch).
     * A call that times out clears its pending request, so the next call
     * sends a fresh request instead of awaiting a dead one forever.
     * Cancellation of the caller is propagated, never swallowed.
     *
     * Transport failures (the request could not be sent, timed out, or the
     * connection dropped) throw [IllegalStateException], so callers can
     * tell "the TV has no apps" (an empty list) from "we could not ask".
     * Every waiter on a shared request is completed one way or another:
     * nobody is left awaiting a request that will never be answered.
     */
    suspend fun getApps(timeoutMs: Long = 6_000): List<TvApp> {
        appsCache?.let { return it }
        // Install-or-reuse must be atomic: two concurrent callers must
        // never create two requests (the second deferred would starve).
        var created: CompletableDeferred<List<TvApp>>? = null
        val deferred = synchronized(appsLock) {
            pendingApps ?: CompletableDeferred<List<TvApp>>().also {
                pendingApps = it
                created = it
            }
        }
        if (created != null && !send(
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
            // Fail every waiter, not just this caller, then unregister.
            synchronized(appsLock) {
                if (pendingApps === deferred) pendingApps = null
                deferred.completeExceptionally(IllegalStateException("app list request failed to send"))
            }
            throw IllegalStateException("app list request failed to send")
        }
        return try {
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) {
                // Timed out: fail the shared request (other awaiters see a
                // transport failure, not a cancellation) and clear it, so
                // the next call sends a fresh request.
                synchronized(appsLock) {
                    if (pendingApps === deferred) {
                        pendingApps = null
                        deferred.completeExceptionally(IllegalStateException("app list request timed out"))
                    }
                }
                throw IllegalStateException("app list request timed out")
            } else {
                result
            }
        } catch (e: CancellationException) {
            // The caller itself was cancelled: drop the registration so the
            // next call starts fresh, but leave the deferred for others.
            synchronized(appsLock) { if (pendingApps === deferred) pendingApps = null }
            throw e
        } catch (e: IllegalStateException) {
            // A transport failure (from the send/timeout paths above, or
            // failPending on disconnect): surface it, never disguise it
            // as an empty app list.
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch one app's icon (base64 PNG) via `ed.apps.icon` — the TV answers
     * asynchronously with the image. Concurrent calls for the same icon
     * share one in-flight request. Returns null on timeout/no icon.
     * Cancellation of the caller is propagated, never swallowed.
     */
    suspend fun getAppIcon(iconPath: String, timeoutMs: Long = 5_000): String? {
        if (iconPath.isEmpty()) return null
        val mine = CompletableDeferred<String?>()
        val existing = pendingIcons.putIfAbsent(iconPath, mine)
        val deferred = existing ?: mine
        if (existing == null) {
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
                // Fail the shared deferred before unregistering, so other
                // callers awaiting this icon do not hang until their timeout.
                mine.completeExceptionally(IllegalStateException("icon request failed to send"))
                pendingIcons.remove(iconPath, mine)
                return null
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            if (existing == null) pendingIcons.remove(iconPath, mine)
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
