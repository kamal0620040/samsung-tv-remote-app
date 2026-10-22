package com.kamal.tvcore

/**
 * HTTP Range header resolution for the mirror server's media route.
 * Pure logic (no Android dependencies) so it can be unit-tested on the JVM.
 */
object HttpRange {

    private val RANGE_REGEX = Regex("^bytes=(\\d*)-(\\d*)$")

    /**
     * True when the header is a syntactically valid single Range. Callers
     * ignore headers that fail this check entirely (answering 200 with the
     * full body, as RFC 9110 allows) instead of guessing.
     */
    fun isRangeHeader(header: String): Boolean = RANGE_REGEX.containsMatchIn(header.trim())

    /**
     * Resolve a Range header against the file size. Returns [start, end]
     * inclusive, or null when the range is unsatisfiable (the caller
     * answers 416). Supports bytes=a-b, bytes=a- and bytes=-n (suffix).
     * A header that is not a valid Range at all yields the full range;
     * callers that care should gate with [isRangeHeader] first.
     */
    fun parse(header: String, total: Long): LongArray? {
        val m = RANGE_REGEX.find(header.trim())
            ?: return longArrayOf(0, total - 1)
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        if (a.isEmpty() && b.isEmpty()) return null
        return if (a.isEmpty()) {
            // Suffix range; an absurdly large count just means "everything".
            val n = b.toLongOrNull() ?: Long.MAX_VALUE
            if (n <= 0) null else longArrayOf(maxOf(0L, total - n), total - 1)
        } else {
            val s = a.toLongOrNull() ?: return null // a start beyond Long range is unsatisfiable
            val e = if (b.isEmpty()) total - 1 else minOf(b.toLongOrNull() ?: Long.MAX_VALUE, total - 1)
            if (s >= total || s > e) null else longArrayOf(s, e)
        }
    }
}
