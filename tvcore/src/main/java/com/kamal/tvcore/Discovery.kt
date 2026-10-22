package com.kamal.tvcore

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class TvDevice(val ip: String, val name: String?, val model: String?, val location: String?)

/**
 * SSDP discovery of Samsung TVs — the same mechanism the original app uses
 * through ConnectSDK: M-SEARCH multicast to 239.255.255.250:1900, then the
 * UPnP description XML at each device's LOCATION for its friendly name/model.
 * Only devices that identify as Samsung (the Samsung search target, or
 * Samsung in SERVER/USN, confirmed again by the description's manufacturer)
 * are returned, so routers and printers on the LAN stay out of the TV list.
 * The caller must hold a WifiManager.MulticastLock while this runs.
 */
object SsdpDiscovery {

    private const val SAMSUNG_ST = "urn:samsung.com:device:RemoteControlReceiver:1"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    /** A discovered candidate; [strong] means it answered the Samsung-specific search target or named Samsung in its USN. */
    private class Candidate(val device: TvDevice, val strong: Boolean)

    suspend fun discover(timeoutMs: Long = 4_000): List<TvDevice> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Candidate>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            val target = InetAddress.getByName("239.255.255.250")
            fun search(st: String) {
                val body = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 3\r\nST: $st\r\n\r\n"
                val bytes = body.toByteArray(Charsets.UTF_8)
                socket?.send(DatagramPacket(bytes, bytes.size, target, 1900))
            }
            search(SAMSUNG_ST)
            search("ssdp:all")

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                socket.soTimeout = 400
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    continue // receive timeout slice — keep listening until deadline
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val headers = parseHeaders(text)
                val server = headers["server"]?.lowercase().orEmpty()
                val st = headers["st"]?.lowercase().orEmpty()
                val usn = headers["usn"]?.lowercase().orEmpty()
                // Admit only devices that identify as Samsung in the response
                // itself; a bare LOCATION from a router or printer is not enough.
                val samsungish = st == SAMSUNG_ST.lowercase() ||
                    server.contains("samsung") || st.contains("samsung") || usn.contains("samsung")
                val ip = packet.address.hostAddress ?: continue
                if (samsungish && headers["location"] != null && !found.containsKey(ip)) {
                    val strong = st == SAMSUNG_ST.lowercase() || usn.contains("samsung")
                    found[ip] = Candidate(TvDevice(ip, null, null, headers["location"]), strong)
                }
            }
        } catch (e: Exception) {
            // discovery is best-effort; manual IP entry is the fallback
        } finally {
            try { socket?.close() } catch (e: Exception) { /* ignore */ }
        }
        if (found.isEmpty()) return@withContext emptyList()

        // Enrich in parallel under one overall deadline. The fetches are
        // cancellable OkHttp calls, so the deadline really bounds the wait
        // (a blocking execute() would ignore cancellation and overrun it).
        val candidates = found.values.toList()
        val deferreds: List<Deferred<TvDevice?>> = candidates.map { candidate ->
            async { enrich(candidate) }
        }
        val completed = withTimeoutOrNull(3_000) { deferreds.awaitAll() }
        if (completed != null) {
            return@withContext completed.filterNotNull()
        }
        // Timed out: keep finished results, and keep unfinished candidates
        // only when their own response already proved they are Samsung.
        val out = ArrayList<TvDevice>()
        deferreds.forEachIndexed { i, deferred ->
            val candidate = candidates[i]
            val enriched: TvDevice? = if (deferred.isCompleted) {
                try {
                    deferred.await()
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            when {
                enriched != null -> out.add(enriched)
                deferred.isCompleted -> Unit // finished as non-Samsung, or failed without proving anything
                candidate.strong -> out.add(candidate.device)
            }
        }
        out
    }

    /**
     * Returns the enriched device, or null when its description proves it is
     * not a Samsung. A device whose description cannot be fetched keeps its
     * admission signal and is returned unchanged.
     */
    private suspend fun enrich(candidate: Candidate): TvDevice? {
        val dev = candidate.device
        val loc = dev.location ?: return dev
        val xml = fetchDescription(loc) ?: return dev
        val (name, model, maker) = parseDescription(xml)
        if (maker != null && !maker.lowercase().contains("samsung")) return null
        return dev.copy(
            name = name ?: dev.name,
            model = model ?: dev.model,
        )
    }

    /** Cancellable description fetch: cancelling the coroutine cancels the HTTP call. */
    private suspend fun fetchDescription(location: String): String? =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(Request.Builder().url(location).build())
            cont.invokeOnCancellation {
                try { call.cancel() } catch (e: Exception) { /* already done */ }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = try {
                        response.use { it.body?.string() }
                    } catch (e: Exception) {
                        null
                    }
                    if (cont.isActive) cont.resume(body)
                }
            })
        }

    /** Extracts (friendlyName, modelName, manufacturer) with a real XML parser. */
    private fun parseDescription(xml: String): Triple<String?, String?, String?> {
        var name: String? = null
        var model: String? = null
        var maker: String? = null
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "friendlyName" -> name = parser.nextText()?.trim()?.ifEmpty { null }
                        "modelName" -> model = parser.nextText()?.trim()?.ifEmpty { null }
                        "manufacturer" -> maker = parser.nextText()?.trim()?.ifEmpty { null }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // malformed description: keep whatever was parsed before the error
        }
        return Triple(name, model, maker)
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.split("\r\n", "\n").drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) out[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return out
    }
}
