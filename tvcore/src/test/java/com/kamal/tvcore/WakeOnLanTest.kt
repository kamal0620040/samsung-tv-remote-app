package com.kamal.tvcore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeOnLanTest {

    @Test
    fun `accepts the common mac formats`() {
        assertTrue(WakeOnLan.isValidMac("AA:BB:CC:DD:EE:FF"))
        assertTrue(WakeOnLan.isValidMac("aa:bb:cc:dd:ee:ff"))
        assertTrue(WakeOnLan.isValidMac("AA-BB-CC-DD-EE-FF"))
        assertTrue(WakeOnLan.isValidMac("AABBCCDDEEFF"))
        assertTrue(WakeOnLan.isValidMac("0123456789ab"))
    }

    @Test
    fun `rejects malformed macs`() {
        assertFalse(WakeOnLan.isValidMac(""))
        assertFalse(WakeOnLan.isValidMac("AA:BB:CC:DD:EE")) // too short
        assertFalse(WakeOnLan.isValidMac("AA:BB:CC:DD:EE:FF:00")) // too long
        assertFalse(WakeOnLan.isValidMac("ZZ:BB:CC:DD:EE:FF")) // non-hex
        assertFalse(WakeOnLan.isValidMac("not-a-mac-address"))
    }
}
