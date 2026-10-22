package com.kamal.tvcore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN magic packet (the device must be on the TV's home LAN). */
object WakeOnLan {

    /** True when [mac] is six hex byte pairs, with or without separators. */
    fun isValidMac(mac: String): Boolean =
        mac.replace(Regex("[^0-9a-fA-F]"), "").length == 12

    /**
     * Send the magic packet. Returns false for an invalid MAC or any socket
     * failure instead of throwing, so UI callers can report and move on.
     */
    suspend fun wake(mac: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val clean = mac.replace(Regex("[^0-9a-fA-F]"), "")
            if (clean.length != 12) return@withContext false
            val macBytes = ByteArray(6) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val packet = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
            for (i in 0 until 16) macBytes.copyInto(packet, 6 + i * 6)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), 9))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
