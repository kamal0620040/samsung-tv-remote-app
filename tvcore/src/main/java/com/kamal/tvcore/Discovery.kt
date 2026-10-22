package com.kamal.tvcore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

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
            .build()
    }

    suspend fun discover(timeoutMs: Long = 4_000): List<TvDevice> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, TvDevice>()
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
                    found[ip] = TvDevice(ip, null, null, headers["location"])
                }
            }
        } catch (e: Exception) {
            // discovery is best-effort; manual IP entry is the fallback
        } finally {
            try { socket?.close() } catch (e: Exception) { /* ignore */ }
        }
        if (found.isEmpty()) return@withContext emptyList()

        // Enrich in parallel under one overall deadline (previously each
        // device could add its own 2s, stretching a scan far past 4s), and
        // drop anything whose description names a different manufacturer.
        val enriched = withTimeoutOrNull(3_000) {
            coroutineScope {
                found.values.map { dev -> async { enrich(dev) } }.mapNotNull { it.await() }
            }
        }
        enriched ?: found.values.toList()
    }

    /** Returns the enriched device, or null when its description proves it is not a Samsung. */
    private fun enrich(dev: TvDevice): TvDevice? {
        val loc = dev.location ?: return dev
        return try {
            val xml = http.newCall(Request.Builder().url(loc).build()).execute()
                .body?.string().orEmpty()
            val maker = tag(xml, "manufacturer")
            if (maker != null && !maker.lowercase().contains("samsung")) return null
            dev.copy(
                name = tag(xml, "friendlyName") ?: dev.name,
                model = tag(xml, "modelName") ?: dev.model,
            )
        } catch (e: Exception) {
            dev
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>([^<]+)</$name>").find(xml)?.groupValues?.get(1)?.let { unescapeXml(it.trim()) }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun parseHeaders(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.split("\r\n", "\n").drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) out[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return out
    }
}
