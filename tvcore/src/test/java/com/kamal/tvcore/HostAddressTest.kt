package com.kamal.tvcore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostAddressTest {

    @Test
    fun `ipv4 literals are accepted`() {
        assertTrue(isValidHostAddress("192.168.1.10"))
        assertTrue(isValidHostAddress("0.0.0.0"))
        assertTrue(isValidHostAddress("255.255.255.255"))
        assertTrue(isValidHostAddress("10.0.0.2"))
    }

    @Test
    fun `ipv6 literals are accepted`() {
        assertTrue(isValidHostAddress("::1"))
        assertTrue(isValidHostAddress("fe80::1"))
        assertTrue(isValidHostAddress("2001:db8::ff00:42"))
    }

    @Test
    fun `hostnames and malformed input are rejected`() {
        assertFalse(isValidHostAddress(""))
        assertFalse(isValidHostAddress("tv.local"))
        assertFalse(isValidHostAddress("192.168.1.256"))
        assertFalse(isValidHostAddress("192.168.1"))
        assertFalse(isValidHostAddress("192.168.1.1.1"))
        assertFalse(isValidHostAddress("192.168.1.x"))
        assertFalse(isValidHostAddress("not an address"))
    }
}
