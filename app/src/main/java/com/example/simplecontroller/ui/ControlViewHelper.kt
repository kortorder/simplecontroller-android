package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.io.LayoutManager
import com.example.simplecontroller.io.detachedControlCopy
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.PayloadModifiers
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.UdpClient
import kotlin.math.min


/**
 * Helper class for creating and managing ControlView UI elements and behavior.
 *
 * This class handles common functionality used by ControlView instances:
 * - Creating and managing UI controls (labels, buttons)
 * - Helper methods for payload handling
 * - Turbo/repeat functionality
 */
class ControlViewHelper(
    private val context: Context,
    private val model: Control,
    private val parentView: ControlView,
    private val onDeleteRequested: (Control) -> Unit
) {
    private data class StickDirectionCommand(val stickName: String, val x: Float, val y: Float)

    var pulseAlreadyFired = false
    // UI Handler for callbacks
    private val uiHandler = Handler(Looper.getMainLooper())

    var allowPulseLoop = true

    // For turbo/repeat functionality
    private var repeater: Runnable? = null
    private var cameraFollowHandledForPress = false
    private var nextExecutorSlot = 0
    private var pendingOutputCallbacks = 0
    private var outputCallbackGeneration = 0
    private var modifierExecutor: ButtonAimPayloadExecutor? = null
    private var modifierLease: ButtonAimPayloadLease? = null
    private var macroController: ButtonMacroController? = null
    private val payloadScheduler = HandlerButtonAimPayloadScheduler()

    private var timedOrdinaryProgram = false
    private var ordinaryActivationActive = false
    private val ordinaryTriggers by lazy {
        OrdinaryTriggerController(HandlerButtonAimPayloadScheduler(), emit = { channel, pressure ->
            parentView.sendOwnedCommand("$channel:$pressure", slot = "ordinary-trigger-values")
            parentView.outputScopeFor("ordinary-trigger-values")?.setRetained(
                parentView.isLatched, parentView::releaseFloatingRuntime
            )
        }, changed = { parentView.refreshLegacyOutputBusy() })
    }
    private val ordinarySequence by lazy {
        OrdinaryPayloadSequence(HandlerButtonAimPayloadScheduler(), emit = { raw ->
            val command = if (parentView.isLatched && raw.startsWith("X360", ignoreCase = true) &&
                !raw.endsWith("_HOLD", ignoreCase = true) && !raw.endsWith("_RELEASE", ignoreCase = true)) {
                "${raw}_HOLD"
            } else raw
            sendCommand(command)
        }, changed = { parentView.refreshLegacyOutputBusy() })
    }

    fun hasPendingOutputWork(): Boolean = repeater != null || ordinaryTriggers.hasWork ||
        pendingOutputCallbacks > 0 || ordinarySequence.isPending || (modifierExecutor?.activeLeaseCount() ?: 0) > 0 || macroController?.isActive == true

    fun startMacro(): String? {
        val controller = macroController ?: ButtonMacroController(
            createButtonAimPayloadExecutor("macro")
        ).also { macroController = it }
        return controller.press(model.payload).also { parentView.refreshLegacyOutputBusy() }
    }

    fun releaseMacro() {
        macroController?.release()
        parentView.refreshLegacyOutputBusy()
    }

    fun cancelMacro() {
        macroController?.cancel()
        parentView.refreshLegacyOutputBusy()
    }

    private fun postOutputRelease(handler: Handler, delayMs: Long, action: () -> Unit) {
        val generation = outputCallbackGeneration
        pendingOutputCallbacks++
        parentView.refreshLegacyOutputBusy()
        handler.postDelayed({
            if (generation == outputCallbackGeneration) {
                try { action() } finally {
                    if (generation == outputCallbackGeneration) pendingOutputCallbacks--
                    parentView.refreshLegacyOutputBusy()
                }
            }
        }, delayMs)
    }

    // UI elements
    private val label = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
        gravity = Gravity.CENTER
    }

    init {
        // Initialize UI elements
        parentView.addView(label)


    }

    /**
     * Update the label text based on the model name
     */
    fun updateLabel() {
        label.text = model.name
        label.visibility = if (model.name.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Show a transient runtime label without changing the serialized control name. */
    fun updateLabel(runtimeLabel: String?) {
        val textToShow = runtimeLabel ?: model.name
        label.text = textToShow
        label.visibility = if (textToShow.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Payload transport used only by Button Aim's explicit base/alternate ownership model. */
    fun createButtonAimPayloadExecutor(slot: String = "executor:${nextExecutorSlot++}"): ButtonAimPayloadExecutor =
        ButtonAimPayloadExecutor(
            transport = object : ButtonAimPayloadTransport {
                override fun sendCommand(command: String) = parentView.sendOwnedCommand(command, slot)

                override fun sendKey(key: String, pressed: Boolean) =
                    parentView.sendOwnedKey(key, pressed, slot)

                override fun sendStickMacro(stickName: String, x: Float, y: Float) =
                    parentView.sendOwnedMacro(stickName, x, y, slot)

                override fun onForceRelease(command: String) = parentView.sendOwnedCommand(command, slot, forceRelease = true)

                override fun onCameraFollow(command: String) {
                    if (NetworkClient.outputMode == NetworkClient.OutputMode.SCX2) {
                        Toast.makeText(context, "Camera Follow is available in Practice or through the PC receiver.", Toast.LENGTH_SHORT).show()
                        return
                    }
                    UdpClient.setCameraFollowStrength(model.cameraFollowStrengthPercent)
                    val enabled = when (command) {
                        "CAMERA_FOLLOW:1" -> true.also(UdpClient::setCameraFollowEnabled)
                        "CAMERA_FOLLOW:0" -> false.also(UdpClient::setCameraFollowEnabled)
                        else -> UdpClient.toggleCameraFollow()
                    }
                    Toast.makeText(
                        context,
                        "Camera Follow ${if (enabled) "ON" else "OFF"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onScrollToggle() {
                    GlobalSettings.scrollMode = !GlobalSettings.scrollMode
                    Toast.makeText(
                        context,
                        if (GlobalSettings.scrollMode) "Scroll mode ON" else "Scroll mode OFF",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onReleaseAll() {
                    (context as? MainActivity)?.activateReleaseAll()
                }
            },
            scheduler = ButtonAimPayloadScheduler { delayMs, task ->
                payloadScheduler.schedule(delayMs) {
                    try { task() } finally { parentView.refreshLegacyOutputBusy() }
                }
            }
        )

    fun isCameraFollowAction(): Boolean = when (model.payload.trim().uppercase()) {
        "CAMERA_FOLLOW", "CAMERA_FOLLOW:1", "CAMERA_FOLLOW:0" -> true
        else -> false
    }

    fun isReleaseAllAction(): Boolean = model.payload.trim().equals("RELEASE_ALL", ignoreCase = true)

    /**
     * Show the property sheet for this control
     */
    private fun showProperties() {
        PropertySheetBuilder(context, model) {
            // After properties are updated:
            val lp = parentView.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = model.w.toInt()
            lp.height = model.h.toInt()
            parentView.layoutParams = lp
            updateLabel()
            parentView.invalidate()

            // Stop any running senders when settings change
            parentView.stopContinuousSending()
            parentView.stopDirectionalCommands()
        }.showPropertySheet()
    }

    /**
     * Create a duplicate of this control
     */
    private fun duplicateSelf() {
        val copy = detachedControlCopy(model)
        (context as? MainActivity)?.createControlFrom(copy)
    }

    /**
     * Show delete confirmation dialog
     */
    private fun confirmDelete() {
        AlertDialog.Builder(context)
            .setMessage("Delete this control?")
            .setPositiveButton("Delete") { _, _ ->
                (parentView.parent as? FrameLayout)?.removeView(parentView)
                onDeleteRequested(model)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Send the control's payload
     */
    fun firePayload() {
        if (PayloadModifiers.hasModifiers(model.payload)) {
            ordinaryActivationActive = true
            val executor = modifierExecutor ?: createButtonAimPayloadExecutor("ordinary-modifiers")
                .also { modifierExecutor = it }
            val turboPressMs = (GlobalSettings.turboSpeed / 2L).coerceIn(1L, 50L)
            if (GlobalSettings.globalTurbo && !parentView.isLatched && modifierLease != null) {
                executor.repeatImmediatePress(modifierLease, turboPressMs)
                return
            }
            modifierLease?.let { executor.releaseNormally(it) }
            when (val result = executor.activate(
                ButtonAimPayloadOwner.BASE, model.payload,
                if (parentView.isLatched) ButtonAimPulseMode.REPEAT else ButtonAimPulseMode.ONCE,
                immediatePressDurationMs = if (GlobalSettings.globalTurbo && !parentView.isLatched) turboPressMs else null,
                preserveAuthoredPulseOnRelease = true,
                turboSession = GlobalSettings.globalTurbo && !parentView.isLatched
            )) {
                is ButtonAimPayloadActivationResult.Activated -> {
                    modifierLease = result.lease
                }
                is ButtonAimPayloadActivationResult.Invalid -> Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                ButtonAimPayloadActivationResult.ReleaseAll -> Unit
            }
            parentView.refreshLegacyOutputBusy()
            return
        }
        val plan = try { OrdinaryPayloadPlan.parse(model.payload) } catch (error: IllegalArgumentException) {
            Toast.makeText(context, error.message ?: "Invalid payload", Toast.LENGTH_SHORT).show()
            return
        }
        timedOrdinaryProgram = plan.hasWaitOrPulse
        ordinaryActivationActive = true
        ordinaryTriggers.begin(repeating = parentView.isLatched && allowPulseLoop)
        ordinarySequence.start(plan)
    }

    /**
     * Send a command using the appropriate protocol based on command type
     */
    private fun sendCommand(command: String) {
        Log.d("ControlViewHelper", "sendCommand() called with: '$command'")

        OrdinaryTriggerCommand.parse(command)?.let {
            ordinaryTriggers.apply(it)
            return
        }
        val upperCommand = command.trim().uppercase()
        if (UdpClient.floatingSupported && (upperCommand.endsWith("_RELEASE") ||
                upperCommand.startsWith("KEY_UP:") ||
                (upperCommand.startsWith("MOUSE_") && upperCommand.endsWith("_UP")))) {
            parentView.sendOwnedCommand(command, forceRelease = true)
            return
        }
        if (UdpClient.floatingSupported && upperCommand.startsWith("KEY_DOWN:")) {
            parentView.sendOwnedCommand(command)
            return
        }
        if (upperCommand == "CAMERA_FOLLOW" ||
            upperCommand == "CAMERA_FOLLOW:1" ||
            upperCommand == "CAMERA_FOLLOW:0"
        ) {
            if (NetworkClient.outputMode == NetworkClient.OutputMode.SCX2) {
                Toast.makeText(context, "Camera Follow is available in Practice or through the PC receiver.", Toast.LENGTH_SHORT).show()
                return
            }
            // Turbo and hold callbacks may call firePayload repeatedly. Camera Follow is
            // intentionally handled only once until this physical press is released.
            if (cameraFollowHandledForPress) return
            cameraFollowHandledForPress = true
            UdpClient.setCameraFollowStrength(model.cameraFollowStrengthPercent)
            val enabled = when (upperCommand) {
                "CAMERA_FOLLOW:1" -> true.also { UdpClient.setCameraFollowEnabled(it) }
                "CAMERA_FOLLOW:0" -> false.also { UdpClient.setCameraFollowEnabled(it) }
                else -> UdpClient.toggleCameraFollow()
            }
            Toast.makeText(
                context,
                "Camera Follow ${if (enabled) "ON" else "OFF"}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // ───────────────── Scroll-mode toggle ─────────────────
        if (command == "SCROLL_MODE_TOGGLE") {
            GlobalSettings.scrollMode = !GlobalSettings.scrollMode
            Toast.makeText(
                context,
                if (GlobalSettings.scrollMode) "Scroll mode ON" else "Scroll mode OFF",
                Toast.LENGTH_SHORT
            ).show()
            return // do not forward toggle to PC
        }

        parseStickDirectionCommand(command)?.let { stickCommand ->
            Log.d("ControlViewHelper", "Sending stick direction command via UdpClient: $command")
            parentView.sendOwnedMacro(stickCommand.stickName, stickCommand.x, stickCommand.y)
            return
        }

        val isXboxCommand = command.startsWith("X360")
        val isMouseCommand = command.startsWith("MOUSE_")
        val isTouchpadCommand = command.startsWith("TOUCHPAD:")
        val isStickCommand = command.startsWith("STICK")
        val isTriggerCommand = command.startsWith("LT:") || command.startsWith("RT:")

        Log.d("ControlViewHelper", "Command type analysis:")
        Log.d("ControlViewHelper", "  - isXboxCommand: $isXboxCommand")
        Log.d("ControlViewHelper", "  - isMouseCommand: $isMouseCommand")
        Log.d("ControlViewHelper", "  - isTouchpadCommand: $isTouchpadCommand")
        Log.d("ControlViewHelper", "  - isStickCommand: $isStickCommand")
        Log.d("ControlViewHelper", "  - isTriggerCommand: $isTriggerCommand")

        val cv = (parentView as? ControlView)
        val fingerPressed = cv?.isPressed == true
        val isLatchedNow = cv?.isLatched == true

        if (isXboxCommand) {
            // 👇 NEW: When finger is down (and not turbo), convert to HOLD so the server won't auto-release
            var cmdToSend = command
            if (!command.contains("_RELEASE") &&
                !command.contains("_HOLD") &&
                fingerPressed &&
                !isLatchedNow &&
                (!GlobalSettings.globalTurbo || timedOrdinaryProgram)
            ) {
                cmdToSend = "${command}_HOLD"
                Log.d("ControlViewHelper", "Press-and-hold → converting Xbox cmd to HOLD: '$cmdToSend'")
            }
            Log.d("ControlViewHelper", "Treating as Xbox command, using UdpClient.sendCommand")
            parentView.sendOwnedCommand(cmdToSend)

        } else if (isTriggerCommand) {
            Log.d("ControlViewHelper", "Sending analog trigger command via UdpClient: $command")
            parentView.sendOwnedCommand(command)

        } else if (!isMouseCommand && !isTouchpadCommand && !isStickCommand) {
            // Keyboard key path
            Log.d("ControlViewHelper", "Treating as keyboard command, using UdpClient.sendKeyCommand")
            parentView.sendOwnedKey(command, true)

            // 👇 NEW: Only auto-release if turbo is on OR the finger isn't actually held down.
            val shouldAutoRelease = !isLatchedNow && ((GlobalSettings.globalTurbo && !timedOrdinaryProgram) || !fingerPressed)
            if (shouldAutoRelease) {
                postOutputRelease(uiHandler, 100) {
                    Log.d("ControlViewHelper", "Sending delayed key release for: '$command'")
                    parentView.sendOwnedKey(command, false)
                }
            }

        } else {
            Log.d("ControlViewHelper", "Treating as special command, using UdpClient.sendCommand")
            parentView.sendOwnedCommand(command)
        }
    }


    // Improved function to handle releasing held buttons
    fun releaseLatched() {
        ordinaryActivationActive = false
        ordinarySequence.cancelPending()
        if (PayloadModifiers.hasModifiers(model.payload)) {
            releaseModifiedPayload()
            return
        }
        Log.d("DEBUG_PULSE", "releaseLatched() CALLED")
        Log.d("ControlViewHelper", "releaseLatched() called for payload: '${model.payload}'")
        cameraFollowHandledForPress = false

        ordinaryTriggers.cancelAll()
        allowPulseLoop = false
        pulseAlreadyFired = false

        releaseCommands(ordinarySequence.startedCommands.ifEmpty {
            model.payload.split(',', ' ').filter { it.isNotBlank() }
        })
    }

    /** Normal lift cancels future WAIT steps but lets already-started one-shot pulses finish. */
    fun finishMomentaryPayload() {
        if (!ordinaryActivationActive) return
        ordinaryActivationActive = false
        if (PayloadModifiers.hasModifiers(model.payload)) {
            releaseModifiedPayload()
            return
        }
        ordinarySequence.cancelPending()
        cameraFollowHandledForPress = false
        ordinaryTriggers.end()
        releaseCommands(ordinarySequence.startedCommands.filterNot { OrdinaryPayloadPlan.isPulse(it) })
    }

    private fun releaseCommands(commands: List<String>) {
        commands.forEach { raw ->
            val cmd = raw.trim()
            Log.d("ControlViewHelper", "Processing release for command: '$cmd'")
            val stickCommand = parseStickDirectionCommand(cmd)

            when {
                // Stick direction button payloads -> center the stick on lift
                stickCommand != null -> {
                    Log.d("ControlViewHelper", "Sending stick direction release: ${stickCommand.stickName}:0.00,0.00")
                    parentView.sendOwnedMacro(stickCommand.stickName, 0f, 0f)
                }

                cmd.startsWith("WAIT_", ignoreCase = true) -> Unit

                cmd.equals("RELEASE_ALL", ignoreCase = true) -> Unit

                cmd.equals("SCROLL_MODE_TOGGLE", ignoreCase = true) -> Unit

                cmd.startsWith("CAMERA_FOLLOW", ignoreCase = true) -> Unit

                // Xbox buttons → explicit *_RELEASE
                cmd.startsWith("X360") -> {
                    val releaseCmd = if (cmd.endsWith("_HOLD")) {
                        cmd.replace("_HOLD", "_RELEASE")
                    } else {
                        "${cmd}_RELEASE"
                    }
                    Log.d("ControlViewHelper", "Sending Xbox release via UdpClient: '$releaseCmd'")
                    parentView.sendOwnedCommand(releaseCmd)
                }

                // Trigger claims have their own run identities and child scope.
                cmd.startsWith("LT:", true) || cmd.startsWith("RT:", true) -> Unit

                // 👇 NEW: Mouse down payloads get matched "up" on lift
                cmd == "MOUSE_LEFT_DOWN" -> {
                    Log.d("ControlViewHelper", "Mouse release: MOUSE_LEFT_UP")
                    parentView.sendOwnedCommand("MOUSE_LEFT_UP")
                }
                cmd == "MOUSE_RIGHT_DOWN" -> {
                    Log.d("ControlViewHelper", "Mouse release: MOUSE_RIGHT_UP")
                    parentView.sendOwnedCommand("MOUSE_RIGHT_UP")
                }
                cmd == "MOUSE_MIDDLE_DOWN" -> {
                    Log.d("ControlViewHelper", "Mouse release: MOUSE_MIDDLE_UP")
                    parentView.sendOwnedCommand("MOUSE_MIDDLE_UP")
                }

                // Keyboard keys (skip mouse/touchpad/stick/trigger tokens)
                !cmd.startsWith("MOUSE_") &&
                        !cmd.startsWith("TOUCHPAD:") &&
                        !cmd.startsWith("STICK") -> {
                    Log.d("ControlViewHelper", "Sending keyboard release: '$cmd'")
                    parentView.sendOwnedKey(cmd, false)
                }
            }
        }
    }

    fun releaseModifiedPayload(suppressSendOnRelease: Boolean = false) {
        modifierExecutor?.releaseNormally(modifierLease, suppressSendOnRelease = suppressSendOnRelease)
        modifierLease = null
        cameraFollowHandledForPress = false
        parentView.refreshLegacyOutputBusy()
    }

    fun suppressModifiedSor() { modifierExecutor?.suppressSendOnRelease(modifierLease) }

    private fun parseStickDirectionCommand(command: String): StickDirectionCommand? {
        val match = Regex("""(?i)^(LS|RS):([A-Z]+)([0-9]{1,3})?$""")
            .matchEntire(command.trim())
            ?: return null
        val stickName = match.groupValues[1].uppercase()
        val direction = match.groupValues[2].uppercase()
        val amount = ((match.groupValues[3].toFloatOrNull() ?: 100f).coerceIn(0f, 100f)) / 100f
        val (x, y) = when (direction) {
            "R", "RIGHT" -> 1f to 0f
            "L", "LEFT" -> -1f to 0f
            "U", "UP" -> 0f to -1f
            "D", "DOWN" -> 0f to 1f
            "UR" -> 1f to -1f
            "UL" -> -1f to -1f
            "BR" -> 1f to 1f
            "BL" -> -1f to 1f
            else -> return null
        }
        return StickDirectionCommand(stickName, x * amount, y * amount)
    }

    /**
     * Start repeating the payload (for turbo mode)
     */
    /** Timed programs retain their ordinary Hold Toggle semantics under global Turbo. */
    fun canUseGlobalTurbo(): Boolean = PayloadModifiers.hasModifiers(model.payload) ||
        runCatching { !OrdinaryPayloadPlan.parse(model.payload).hasWaitOrPulse }.getOrDefault(false)

    fun startRepeat() {
        if (!PayloadModifiers.hasModifiers(model.payload)) {
            val plan = try { OrdinaryPayloadPlan.parse(model.payload) } catch (_: IllegalArgumentException) {
                firePayload() // Show the normal token validation error without starting a timer.
                return
            }
            if (plan.hasWaitOrPulse) {
                firePayload() // Timed programs execute once, never overlapping under global Turbo.
                return
            }
        }
        firePayload() // fire immediately
        repeater = object : Runnable {
            override fun run() {
                firePayload()
                uiHandler.postDelayed(this, GlobalSettings.turboSpeed) // Use the configurable speed
            }
        }
        uiHandler.postDelayed(repeater!!, GlobalSettings.turboSpeed)
    }

    /**
     * Stop repeating the payload
     */
    fun stopRepeat() {
        if (!parentView.isLatched) ordinarySequence.cancelPending()
        repeater?.let { uiHandler.removeCallbacks(it) }
        repeater = null
        cameraFollowHandledForPress = false
    }

    /** Cancel every delayed/repeating action owned by this helper without changing settings. */
    fun cancelPendingActionsForReleaseAll() {
        ordinaryActivationActive = false
        ordinarySequence.cancelPending()
        timedOrdinaryProgram = false
        cancelMacro()
        modifierExecutor?.releaseAllLeases()
        modifierLease = null
        outputCallbackGeneration++
        pendingOutputCallbacks = 0
        repeater?.let { uiHandler.removeCallbacks(it) }
        repeater = null
        uiHandler.removeCallbacksAndMessages(null)
        ordinaryTriggers.cancelAll()
        pulseAlreadyFired = false
        allowPulseLoop = false
        cameraFollowHandledForPress = false
    }
}

/**
 * Global settings for all control views.
 *
 * This replaces the static variables in the ControlView companion object.
 */
object GlobalSettings {
    // Whether edit mode is currently active
    var editMode = false
        set(v) {
            field = v
            // Update all controls when edit mode changes
            SwipeManager.notifyEditModeChanged(v)
        }

    // Whether auto-center is enabled for sticks/pads
    var snapEnabled = false
        set(value) {
            field = value
            if (value) {
                // When enabling snap, stop any continuous sending
                SwipeManager.stopAllContinuousSending()
            }
        }

    // Whether all buttons should use hold/latch behavior
    var globalHold = false

    var scrollMode = false        // ← false = normal mouse, true = scroll

    // Whether all buttons should use turbo/rapid-fire
    var globalTurbo = false
        set(value) {
            if (field && !value) {
                // When turning turbo off, make sure all repeaters are stopped
                SwipeManager.stopAllRepeating()
            }
            field = value
        }

    // Speed of turbo/rapid-fire in milliseconds
    var turboSpeed = 16L // Default 16ms (≈60 Hz)



    // Persisted per-profile mode. The compatibility Boolean is used by existing dispatch.
    var swipeMode = com.example.simplecontroller.model.ProfileSwipeMode.OFF
        set(value) {
            if (field == value) return
            SwipeManager.releaseEverythingLocally()
            field = value
            SwipeManager.setSwipeMode(value)
        }

    var globalSwipe: Boolean
        get() = swipeMode != com.example.simplecontroller.model.ProfileSwipeMode.OFF
        set(value) {
            swipeMode = if (!value) com.example.simplecontroller.model.ProfileSwipeMode.OFF
                else if (swipeMode == com.example.simplecontroller.model.ProfileSwipeMode.OFF)
                    com.example.simplecontroller.model.ProfileSwipeMode.CONTROLS else swipeMode
        }

}

/**
 * Central manager for swipe functionality to coordinate between all controls.
 */
object SwipeManager {
    // Reference to swipe handler that manages actual touch handling
    private val swipeHandler = SwipeHandler()

    // All registered control views
    private val allViews = mutableSetOf<ControlView>()
    private val floatingRings = mutableSetOf<FloatingRingView>()

    fun registerFloatingRing(view: FloatingRingView) { floatingRings.add(view) }
    fun unregisterFloatingRing(view: FloatingRingView) { floatingRings.remove(view) }

    /**
     * Register a control view with the manager
     */
    fun registerControl(view: ControlView) {
        allViews.add(view)
        // Touch Aim keeps the original MotionEvent so contact geometry is not discarded.
        if (view.model.type != ControlType.TOUCH_AIM) swipeHandler.registerView(view)
    }

    /**
     * Unregister a control view
     */
    fun unregisterControl(view: ControlView) {
        allViews.remove(view)
        swipeHandler.unregisterView(view)
    }

    /**
     * Process a touch event through the swipe handler
     */
    fun processTouchEvent(e: android.view.MotionEvent): Boolean {
        return swipeHandler.processTouchEvent(e)
    }

    /**
     * Set whether swipe mode is enabled
     */
    fun setSwipeEnabled(enabled: Boolean) {
        swipeHandler.setSwipeEnabled(enabled)
    }

    fun setSwipeMode(mode: com.example.simplecontroller.model.ProfileSwipeMode) {
        swipeHandler.setSwipeMode(mode)
    }

    /**
     * Notify all controls that edit mode has changed
     */
    fun notifyEditModeChanged(editMode: Boolean) {
        swipeHandler.setEditMode(editMode)
        allViews.forEach {
            if (editMode) {
                it.releaseAutoTap()
                it.stopContinuousSending()
                it.stopDirectionalCommands()
                it.releaseTouchAim()
                it.releaseButtonAim()
                it.releaseButtonDirections()
            }
        }
    }

    /**
     * Stop all continuous sending when needed
     */
    fun stopAllContinuousSending() {
        allViews.forEach {
            it.stopContinuousSending()
            it.stopDirectionalCommands()
        }
    }

    /**
     * Stop all repeating (for turbo mode)
     */
    fun stopAllRepeating() {
        allViews.forEach { it.stopRepeat() }
    }

    fun stopContinuousSendingForStick(stickName: String) {
        allViews.forEach { it.stopContinuousSendingForStick(stickName) }
    }

    fun releaseAllButtonAimSurfaces() {
        allViews.forEach {
            it.releaseButtonAim()
            it.releaseButtonDirections()
        }
    }

    fun releaseAllAutoTapButtons() {
        allViews.forEach { it.releaseAutoTap() }
    }

    fun releaseAllTouchAim() {
        allViews.forEach { it.releaseTouchAim() }
    }

    /** Release all runtime output state while preserving layouts and global settings. */
    fun releaseEverythingLocally() {
        swipeHandler.cancelActiveTouch()
        allViews.toList().forEach { it.releaseEverythingLocally() }
    }

    /**
     * Clean up any active touch state when swipe is disabled
     */
    fun cleanupActiveTouch() {
        swipeHandler.setSwipeEnabled(false)
    }

    /**
     * Re-center all stick controls
     * This is triggered by the Re-center button
     */
    fun recenterAllSticks() {
        floatingRings.toList().forEach { it.recenterMovement() }
        // Button Aim retention owns a canonical stick independently of ordinary Stick views.
        // Center it first so its generation is invalidated before any terminal packet is sent.
        allViews.forEach { it.recenterButtonAimStick() }
        allViews.forEach { it.recenterStickOutput() }
    }
}
