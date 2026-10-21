package com.kamal.tvcore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * The caller must hold a WifiManager.MulticastLock while this runs.
 */
object SsdpDiscovery {

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
            search("urn:samsung.com:device:RemoteControlReceiver:1")
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
                val samsungish = server.contains("samsung") || st.contains("samsung")
                val ip = packet.address.hostAddress ?: continue
                if ((samsungish || headers["location"] != null) && !found.containsKey(ip)) {
                    found[ip] = TvDevice(ip, null, null, headers["location"])
                }
            }
        } catch (e: Exception) {
            // discovery is best-effort; manual IP entry is the fallback
        } finally {
            try { socket?.close() } catch (e: Exception) { /* ignore */ }
        }

        // Enrich with description XML where possible.
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        found.values.map { dev ->
            val loc = dev.location ?: return@map dev
            try {
                val xml = http.newCall(Request.Builder().url(loc).build()).execute()
                    .body?.string().orEmpty()
                val name = Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)?.groupValues?.get(1)
                val model = Regex("<modelName>([^<]+)</modelName>").find(xml)?.groupValues?.get(1)
                val maker = Regex("<manufacturer>([^<]+)</manufacturer>").find(xml)?.groupValues?.get(1).orEmpty()
                if (maker.lowercase().contains("samsung") || model != null) {
                    dev.copy(name = name ?: dev.name, model = model ?: dev.model)
                } else dev
            } catch (e: Exception) {
                dev
            }
        }
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
