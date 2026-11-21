package com.example.simplecontroller.net.scx2

import java.util.Locale
import java.net.URLDecoder

/** Observable local state, also useful in Practice. Desktop fields never enter SCX2 packets. */
data class Scx2PracticeOutput(
    val gamepad: Scx2Output = Scx2Output.NEUTRAL,
    val heldKeys: Set<String> = emptySet(), val heldMouseButtons: Set<String> = emptySet(),
    val pointerX: Double = 0.0, val pointerY: Double = 0.0, val scroll: Double = 0.0,
    val leftTriggerRequested: Boolean = false, val rightTriggerRequested: Boolean = false
)

/** Consumes already-resolved coordinator commands synchronously, before any network queue. */
class Scx2CanonicalOutput {
    var state = Scx2PracticeOutput(); private set
    private val directions = mutableSetOf<String>()

    fun clear() { directions.clear(); state = Scx2PracticeOutput() }

    /** The existing coordinator emits these frames synchronously after resolving all owners. */
    fun acceptFrame(frame: String, onTransition: (Scx2PracticeOutput) -> Unit): Boolean {
        val fields = frame.split(':')
        val command = when (fields.firstOrNull()) {
            "PHONE_STATE_V1", "PHONE_EVENT_V1" -> runCatching { URLDecoder.decode(fields.last(), "UTF-8") }.getOrNull() ?: return false
            "PHONE_END_V1" -> null
            else -> return false
        }
        // The preceding resolved STATE already releases this owner. A force event must not clear a survivor.
        if (command != null && !command.startsWith("PHONE_FORCE:")) {
            if (command.startsWith("PHONE_REPLAY:")) {
                val key = command.substringAfter("PHONE_REPLAY:").split('|')
                if (key.size == 3 && key[1] == "XBOX") {
                    accept("X360${key[2]}_RELEASE"); onTransition(state)
                    accept("X360${key[2]}_HOLD")
                }
            } else accept(command)
        }
        onTransition(state)
        return true
    }

    /** No keyboard/mouse text is forwarded to the Pico. Unsupported commands leave state intact. */
    fun accept(command: String): Boolean {
        val raw = command.trim()
        val upper = raw.uppercase(Locale.ROOT)
        Regex("^X360([A-Z0-9]+)_(HOLD|RELEASE)$").matchEntire(upper)?.let {
            val name = it.groupValues[1]; val pressed = it.groupValues[2] == "HOLD"
            if (name in DIRECTION_NAMES) {
                if (pressed) directions.add(name) else directions.remove(name)
                state = state.copy(gamepad = state.gamepad.copy(hat = hat()))
                return true
            }
            val index = BUTTONS.indexOf(name)
            if (index < 0) return false
            val bit = 1 shl index
            state = state.copy(gamepad = state.gamepad.copy(buttons = if (pressed)
                state.gamepad.buttons or bit else state.gamepad.buttons and bit.inv()))
            return true
        }
        // The ownership coordinator may render a tiny Float in scientific notation.
        Regex("^(LT|RT):([0-9]+(?:\\.[0-9]+)?(?:E[+-]?[0-9]+)?)$").matchEntire(upper)?.let {
            val value = it.groupValues[2].toDoubleOrNull()?.takeIf { p -> p.isFinite() && p in 0.0..1.0 } ?: return false
            val pressure = Scx2Output.pressure(value)
            state = state.copy(gamepad = if (it.groupValues[1] == "LT") state.gamepad.copy(leftTrigger = pressure)
                else state.gamepad.copy(rightTrigger = pressure),
                leftTriggerRequested = if (it.groupValues[1] == "LT") value > 0 else state.leftTriggerRequested,
                rightTriggerRequested = if (it.groupValues[1] == "RT") value > 0 else state.rightTriggerRequested)
            return true
        }
        Regex("^STICK_(?:MACRO_)?([LR])(?:_NO_FOLLOW)?:([^,]+),([^,]+)$").matchEntire(upper)?.let {
            val x = it.groupValues[2].toDoubleOrNull()?.takeIf { v -> v.isFinite() && v in -1.0..1.0 } ?: return false
            val y = it.groupValues[3].toDoubleOrNull()?.takeIf { v -> v.isFinite() && v in -1.0..1.0 } ?: return false
            // Android gestures are positive-down; SCX2 is positive-up. Convert exactly once here.
            state = state.copy(gamepad = if (it.groupValues[1] == "L") state.gamepad.copy(leftX = Scx2Output.axis(x), leftY = Scx2Output.axis(-y))
                else state.gamepad.copy(rightX = Scx2Output.axis(x), rightY = Scx2Output.axis(-y)))
            return true
        }
        if (upper.startsWith("KEY_DOWN:") || upper.startsWith("KEY_UP:")) {
            val key = upper.substringAfter(':').takeIf { it.isNotBlank() && it.length <= 80 } ?: return false
            state = state.copy(heldKeys = if (upper.startsWith("KEY_DOWN:")) state.heldKeys + key else state.heldKeys - key)
            return true
        }
        Regex("^MOUSE_(LEFT|RIGHT|MIDDLE)_(DOWN|UP)$").matchEntire(upper)?.let {
            val button = it.groupValues[1]
            state = state.copy(heldMouseButtons = if (it.groupValues[2] == "DOWN") state.heldMouseButtons + button else state.heldMouseButtons - button)
            return true
        }
        Regex("^(?:GAME_)?DELTA:([^,]+),([^,]+)$").matchEntire(upper)?.let {
            val x = it.groupValues[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return false
            val y = it.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return false
            state = state.copy(pointerX = (state.pointerX + x).coerceIn(-1e12, 1e12),
                pointerY = (state.pointerY + y).coerceIn(-1e12, 1e12))
            return true
        }
        if (upper.startsWith("SCROLL:")) {
            val value = upper.substringAfter(':').toDoubleOrNull()?.takeIf(Double::isFinite) ?: return false
            state = state.copy(scroll = (state.scroll + value).coerceIn(-1e12, 1e12)); return true
        }
        return false
    }

    /** Opposing directions cancel independently instead of inventing an arbitrary direction. */
    private fun hat(): Int {
        val x = (if ("RIGHT" in directions) 1 else 0) - (if ("LEFT" in directions) 1 else 0)
        val y = (if ("UP" in directions) 1 else 0) - (if ("DOWN" in directions) 1 else 0)
        return when (x to y) { 0 to 1 -> 0; 1 to 1 -> 1; 1 to 0 -> 2; 1 to -1 -> 3;
            0 to -1 -> 4; -1 to -1 -> 5; -1 to 0 -> 6; -1 to 1 -> 7; else -> 8 }
    }

    companion object {
        val BUTTONS = listOf("A", "B", "X", "Y", "LB", "RB", "BACK", "START", "LS", "RS")
        private val DIRECTION_NAMES = setOf("UP", "RIGHT", "DOWN", "LEFT")
    }
}
