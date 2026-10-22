package com.kamal.tvcore

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HttpRangeTest {

    private fun parse(header: String, total: Long = 1000L) = HttpRange.parse(header, total)

    @Test
    fun `open ended range runs to the end`() {
        assertArrayEquals(longArrayOf(0, 999), parse("bytes=0-"))
        assertArrayEquals(longArrayOf(100, 999), parse("bytes=100-"))
    }

    @Test
    fun `explicit range is honored`() {
        assertArrayEquals(longArrayOf(100, 199), parse("bytes=100-199"))
    }

    @Test
    fun `end beyond the file is clamped`() {
        assertArrayEquals(longArrayOf(100, 999), parse("bytes=100-99999"))
    }

    @Test
    fun `suffix range takes the tail`() {
        assertArrayEquals(longArrayOf(500, 999), parse("bytes=-500"))
        assertArrayEquals(longArrayOf(0, 999), parse("bytes=-2000"))
    }

    @Test
    fun `unsatisfiable ranges are rejected`() {
        assertNull(parse("bytes=1000-")) // starts at end of file
        assertNull(parse("bytes=500-400")) // start after end
        assertNull(parse("bytes=-0")) // zero-length suffix
        assertNull(parse("bytes=-")) // neither bound given
    }

    @Test
    fun `unparseable headers are ignored and yield the full range`() {
        assertArrayEquals(longArrayOf(0, 999), parse("garbage"))
        assertArrayEquals(longArrayOf(0, 999), parse("bytes=10-20 trailing"))
        assertArrayEquals(longArrayOf(0, 999), parse("xbytes=10-20"))
    }

    @Test
    fun `huge numbers never throw`() {
        assertNull(parse("bytes=99999999999999999999999-"))
        assertArrayEquals(longArrayOf(0, 999), parse("bytes=-99999999999999999999999"))
        assertArrayEquals(longArrayOf(10, 999), parse("bytes=10-99999999999999999999999"))
    }
}
