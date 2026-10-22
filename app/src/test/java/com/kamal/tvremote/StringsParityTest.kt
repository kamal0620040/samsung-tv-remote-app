package com.kamal.tvremote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The app renders every label from Strings' two hand-maintained maps; a key
 * present in one language but missing in the other would silently show the
 * raw key to users of that language. This test keeps the maps in lockstep.
 */
class StringsParityTest {

    @Suppress("UNCHECKED_CAST")
    private fun map(field: String): Map<String, String> {
        val f = Strings::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.get(Strings) as Map<String, String>
    }

    @Test
    fun `english and nepali have exactly the same keys`() {
        assertEquals(map("en").keys, map("ne").keys)
    }

    @Test
    fun `no translation is blank`() {
        for ((lang, m) in listOf("en" to map("en"), "ne" to map("ne"))) {
            for ((key, value) in m) {
                assertTrue(value.isNotBlank(), "$lang[$key] is blank")
            }
        }
    }

    @Test
    fun `lookup returns the active language`() {
        val en = map("en")
        val ne = map("ne")
        Strings.lang = "ne"
        assertEquals(ne["stop"], Strings.t("stop"))
        Strings.lang = "en"
        assertEquals(en["stop"], Strings.t("stop"))
    }
}
