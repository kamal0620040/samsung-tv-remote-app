package com.kamal.tvcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regression guard for the v1.11–v1.20 connection outage: the channel
 * URL must build without throwing (HttpUrl.Builder rejects the literal
 * scheme "wss") and must carry the pairing parameters.
 */
class SamsungTvUrlTest {

    @Test
    fun `channel url uses https scheme and carries name and token`() {
        val url = SamsungTv.buildChannelUrl("192.168.100.102", 8002, "S2FtYWwgUmVtb3Rl", "abc123")
        assertEquals("https", url.scheme)
        assertEquals("192.168.100.102", url.host)
        assertEquals(8002, url.port)
        assertEquals("/api/v2/channels/samsung.remote.control", url.encodedPath)
        assertEquals("S2FtYWwgUmVtb3Rl", url.queryParameter("name"))
        assertEquals("abc123", url.queryParameter("token"))
    }

    @Test
    fun `channel url omits the token parameter when there is none`() {
        val url = SamsungTv.buildChannelUrl("192.168.100.102", 8002, "bmFtZQ==", null)
        assertNull(url.queryParameter("token"))
        assertEquals("https", url.scheme)
    }
}
