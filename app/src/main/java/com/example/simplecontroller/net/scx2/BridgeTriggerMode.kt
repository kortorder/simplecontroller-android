package com.example.simplecontroller.net.scx2

/** Output selection only. Never rewrites a profile's requested pressure or payload. */
enum class BridgeTriggerMode(val label: String, val revision: Scx2Revision) {
    OFF("Off", Scx2Revision.LEGACY),
    USB_BUTTONS("USB buttons (full press)", Scx2Revision.USB_BUTTONS),
    ADJUSTABLE("Adjustable (circuit)", Scx2Revision.PRESSURE),
    CRONUS_ZEN("USB adjustable (Cronus Zen)", Scx2Revision.USB_PRESSURE);

    fun output(state: Scx2Output, leftRequested: Boolean = state.leftTrigger > 0,
               rightRequested: Boolean = state.rightTrigger > 0): Scx2Output = when (this) {
        OFF -> state.copy(leftTrigger = 0, rightTrigger = 0)
        USB_BUTTONS -> state.copy(leftTrigger = if (leftRequested) 65535 else 0,
            rightTrigger = if (rightRequested) 65535 else 0)
        ADJUSTABLE, CRONUS_ZEN -> state
    }
    companion object {
        /** Old records carry only pressure. Unknown or contradictory new records never restore. */
        fun decode(saved: String?, pressure: Boolean): BridgeTriggerMode? {
            if (saved == null) return if (pressure) ADJUSTABLE else OFF
            val mode = entries.singleOrNull { it.name == saved } ?: return null
            return mode.takeIf { (it == ADJUSTABLE) == pressure }
        }
    }
}
