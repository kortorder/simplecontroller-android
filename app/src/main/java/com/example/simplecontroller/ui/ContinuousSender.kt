package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.UdpClient
import com.example.simplecontroller.net.PhoneOutputScope
import kotlin.math.abs

/**
 * Handles continuous sending of control positions.
 * Used primarily for analog stick positions that need to be maintained even after
 * the user lifts their finger.
 */
class ContinuousSender(
    private val model: Control,
    private val uiHandler: Handler = Handler(Looper.getMainLooper()),
    private val scopeProvider: () -> PhoneOutputScope? = { null },
    private val onRetainedReplaced: () -> Unit = {}
) {
    private var continuousSender: Runnable? = null
    private var retainedScope: PhoneOutputScope? = null

    private fun sendPosition(x: Float, y: Float, capturedScope: PhoneOutputScope? = scopeProvider()) {
        if (capturedScope != null) capturedScope.sendStick(model.payload, x, y, model.excludeFromCameraFollow)
        else if (!UdpClient.floatingSupported) UdpClient.sendStickPosition(model.payload, x, y, model.excludeFromCameraFollow)
    }

    // Store last position values
    private var lastStickX = 0f
    private var lastStickY = 0f

    private val sendIntervalMs = 16L

    /**
     * Remember the normalized position already accepted by the manual-stick owner.
     * ControlView shapes and publishes the touch update. Re-sending here used to
     * square regular-stick axes a second time, changing their strength on lift.
     */
    fun setLastPosition(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        lastStickX = x.coerceIn(-1f, 1f)
        lastStickY = y.coerceIn(-1f, 1f)
    }

    /*
   * Patch for ContinuousSender.kt – keeps stick value streaming when Auto‑center is OFF,
   * preserves old behaviour when Auto‑center is ON.
   */

    /** Start continuously sending the last stick position */
    fun startContinuousSending() {
        Log.d("DEBUG_CS", "autoCenter=${model.autoCenter}")

        // just cancel any earlier runnable – *don’t* zero the coords
        continuousSender?.let { uiHandler.removeCallbacks(it) }
        val capturedScope = scopeProvider()
        retainedScope = capturedScope
        capturedScope?.setRetained(!model.autoCenter, ::retainedReplaced)

        // Bail out early only when the stick is *meant* to snap back and is already near centre
        if (model.autoCenter &&
            abs(lastStickX) < 0.15f && abs(lastStickY) < 0.15f) {
            sendCenter()
            return
        }

        continuousSender = object : Runnable {
            private var decay = 1f                 // stays 1 → no fade when autoCenter == false
            override fun run() {
                if (capturedScope != null && !capturedScope.isActive) {
                    continuousSender = null
                    return
                }
                val curX = if (model.autoCenter) lastStickX * decay else lastStickX
                val curY = if (model.autoCenter) lastStickY * decay else lastStickY

                val minMag = if (model.autoCenter) 0.15f else 0.01f
                if (abs(curX) > minMag || abs(curY) > minMag) {
                    // The accepted position is already shaped; retained output must
                    // exactly match the final touch, including small adjustments.
                    sendPosition(curX, curY, capturedScope)
                    capturedScope?.setRetained(!model.autoCenter, ::retainedReplaced)

                    if (model.autoCenter) decay *= 0.95f      // glide only in snap‑back mode
                    uiHandler.postDelayed(this, sendIntervalMs)
                } else if (model.autoCenter) {
                    // Only snap‑back sticks send a final 0,0 frame
                    sendCenter()
                }
            }
        }
        uiHandler.post(continuousSender!!)
    }

    /* helper: single 0,0 packet + stop */
    private fun sendCenter() {
        sendPosition(0f, 0f, retainedScope ?: scopeProvider())
        continuousSender = null
    }

    /** Stop continuous sending */
    fun stopContinuousSending() {
        continuousSender?.let { uiHandler.removeCallbacks(it) }
        continuousSender = null

        // Only force‑centre sticks that are *supposed* to auto‑centre
        if (model.autoCenter) {
            sendCenter()   // unconditional – a harmless duplicate ‘0,0’ is fine
        }
        // Note: we no longer reset lastStickX/Y here – they’re kept for restart.
    }

    /** Reset the sender; ordinary cleanup still centers unless its owner releases the scope itself. */
    fun stopAndCenter(centerOutput: Boolean = true) {
        continuousSender?.let { uiHandler.removeCallbacks(it) }
        continuousSender = null
        lastStickX = 0f
        lastStickY = 0f
        if (centerOutput && (model.type == ControlType.STICK || model.type == ControlType.CURVED_STICK ||
            model.type == ControlType.RADIAL_CURVED_STICK)) {
            sendCenter()
        }
    }

    private fun retainedReplaced() {
        stopAndCenter()
        onRetainedReplaced()
    }


    /**
     * Is continuous sending currently active?
     */
    fun isActive(): Boolean {
        return continuousSender != null
    }

}
