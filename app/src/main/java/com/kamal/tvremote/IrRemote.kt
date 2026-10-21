package com.kamal.tvremote

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * Samsung TV infrared fallback — the same feature the reference app falls
 * back to when there is no network TV. Uses the phone's IR blaster with
 * Samsung's long-standard BN59 code set (address 0xE0E0, NEC-style frame:
 * 4500/4500 header, 560µs base unit, ~38kHz carrier).
 */
class IrRemote(context: Context) {
    private val ir: ConsumerIrManager? =
        try {
            context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        } catch (e: Exception) {
            null
        }

    fun hasBlaster(): Boolean = try {
        ir?.hasIrEmitter() == true
    } catch (e: Exception) {
        false
    }

    /** Map a Samsung remote key name to its 16-bit IR command (cmd, ~cmd). */
    private fun codeFor(key: String): Int? = when (key) {
        "KEY_POWER" -> 0x40BF
        "KEY_VOLUP" -> 0xE01F
        "KEY_VOLDOWN" -> 0xD02F
        "KEY_CHUP" -> 0x48B7
        "KEY_CHDOWN" -> 0x08F7
        "KEY_PRECH" -> 0xC837
        "KEY_MUTE" -> 0xF00F
        "KEY_SOURCE" -> 0x807F
        "KEY_HOME" -> 0x9E61
        "KEY_MENU" -> 0x58A7
        "KEY_GUIDE" -> 0xF20D
        "KEY_INFO" -> 0xF807
        "KEY_TOOLS" -> 0xD22D
        "KEY_UP" -> 0x06F9
        "KEY_DOWN" -> 0x8679
        "KEY_LEFT" -> 0xA659
        "KEY_RIGHT" -> 0x46B9
        "KEY_ENTER" -> 0x16E9
        "KEY_RETURN" -> 0x1AE5
        "KEY_EXIT" -> 0xB44B
        "KEY_PLAY" -> 0xE21D
        "KEY_PAUSE" -> 0x52AD
        "KEY_REWIND" -> 0xA25D
        "KEY_FF" -> 0x12ED
        "KEY_RED" -> 0x36C9
        "KEY_GREEN" -> 0x28D7
        "KEY_YELLOW" -> 0xA897
        "KEY_BLUE" -> 0x6897
        "KEY_0" -> 0x8877
        "KEY_1" -> 0x20DF
        "KEY_2" -> 0xA05F
        "KEY_3" -> 0x609F
        "KEY_4" -> 0x10EF
        "KEY_5" -> 0x906F
        "KEY_6" -> 0x50AF
        "KEY_7" -> 0x30CF
        "KEY_8" -> 0xB04F
        "KEY_9" -> 0x708F
        "KEY_MINUS" -> 0xC43B
        else -> null
    }

    /** Build the on/off pattern (microseconds) for a full 32-bit Samsung frame. */
    private fun pattern(code16: Int): IntArray {
        val full = (0xE0E0 shl 16) or code16
        val out = ArrayList<Int>(72)
        out.add(4500); out.add(4500)
        for (i in 31 downTo 0) {
            out.add(560)
            out.add(if ((full shr i) and 1 == 1) 1690 else 560)
        }
        out.add(560)
        return out.toIntArray()
    }

    /** Transmit one key. Returns false when there is no blaster or no code. */
    fun send(key: String): Boolean {
        val mgr = ir ?: return false
        if (!hasBlaster()) return false
        val code = codeFor(key) ?: return false
        return try {
            mgr.transmit(38000, pattern(code))
            true
        } catch (e: Exception) {
            false
        }
    }
}
