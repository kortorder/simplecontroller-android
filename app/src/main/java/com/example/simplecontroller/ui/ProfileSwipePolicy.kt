package com.example.simplecontroller.ui

import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.FloatingLocalAction
import com.example.simplecontroller.model.PageAction
import com.example.simplecontroller.model.ProfileSwipeMode

internal enum class SwipeDecision { MOVE_CURRENT, KEEP_CURRENT, RELEASE_CURRENT, TRANSFER }

/** Pure routing rules; ownership checks and actual touch delivery stay in SwipeHandler. */
internal object ProfileSwipePolicy {
    fun isDirectOnly(control: Control): Boolean = control.type == ControlType.RECENTER ||
        control.type == ControlType.TOUCH_AIM ||
        (control.type == ControlType.BUTTON && (
            control.buttonAimEnabled || control.buttonDirectional.enabled ||
                control.pageAction != PageAction.NONE || control.floatingLocalAction != FloatingLocalAction.NONE ||
                control.payload.split(Regex("[,\\s]+")).any {
                    val token = it.uppercase()
                    token == "RELEASE_ALL" || token == "SCROLL_MODE_TOGGLE" ||
                        token == "CAMERA_FOLLOW" || token.startsWith("CAMERA_FOLLOW:")
                }))

    fun acceptsEntry(control: Control, mode: ProfileSwipeMode): Boolean {
        if (mode == ProfileSwipeMode.OFF || isDirectOnly(control)) return false
        return when (control.type) {
            ControlType.BUTTON -> control.swipeActivate
            ControlType.STICK, ControlType.CURVED_STICK, ControlType.RADIAL_CURVED_STICK,
            ControlType.TOUCHPAD -> mode == ProfileSwipeMode.CONTROLS
            else -> false
        }
    }

    fun decide(
        mode: ProfileSwipeMode,
        current: Control?,
        currentContainsPoint: Boolean,
        candidate: Control?,
        candidateAvailable: Boolean = true,
        cooldownReady: Boolean = true
    ): SwipeDecision {
        if (current != null) {
            val captured = mode == ProfileSwipeMode.OFF ||
                (mode == ProfileSwipeMode.BUTTONS && (current.type != ControlType.BUTTON ||
                    current.buttonAimEnabled || current.buttonDirectional.enabled)) ||
                (mode == ProfileSwipeMode.CONTROLS && isDirectOnly(current))
            if (captured || currentContainsPoint) return SwipeDecision.MOVE_CURRENT
        }
        if (candidate != null && acceptsEntry(candidate, mode) && candidateAvailable &&
            (mode != ProfileSwipeMode.CONTROLS || cooldownReady)) return SwipeDecision.TRANSFER
        if (mode == ProfileSwipeMode.BUTTONS) return SwipeDecision.RELEASE_CURRENT
        // Preserve every pad movement, including outside/blocked/cooldown travel. That path
        // must invalidate a pending click-lock even if the finger returns to its start.
        return if (current?.type == ControlType.TOUCHPAD) SwipeDecision.MOVE_CURRENT else SwipeDecision.KEEP_CURRENT
    }
}
