package com.example.simplecontroller.ui

import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.scx2.Scx2Output
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android MotionEvents, real main-loop timers and resolved Practice output together. */
@RunWith(AndroidJUnit4::class)
class PracticeControlInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val views = mutableListOf<ControlView>()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun output() = NetworkClient.localOutput.value

    @Before fun prepare() = main {
        GlobalSettings.editMode = false
        GlobalSettings.globalHold = false
        GlobalSettings.globalTurbo = false
        GlobalSettings.globalSwipe = false
        GlobalSettings.scrollMode = false
        NetworkClient.setOutputMode(NetworkClient.OutputMode.WINDOWS)
        NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE)
        NetworkClient.start()
    }

    @After fun cleanUp() = main {
        views.forEach { it.releaseEverythingLocally(); SwipeManager.unregisterControl(it) }
        views.clear()
        GlobalSettings.globalHold = false
        GlobalSettings.globalTurbo = false
        NetworkClient.close()
    }

    private fun control(payload: String, type: ControlType = ControlType.BUTTON,
                        configure: Control.() -> Unit = {}): ControlView {
        val model = Control("test-${views.size}", type, 0f, 0f, 200f, 200f, payload).apply(configure)
        val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
        return ControlView(context, model).also { it.layout(0, 0, 200, 200); views += it }
    }

    private fun touch(view: ControlView, action: Int, x: Float = 100f, y: Float = 100f): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        return try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun touchSize(view: ControlView, action: Int, size: Float) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val finger = MotionEvent.PointerCoords().apply {
            x = 100f; y = 100f; pressure = 1f; this.size = size
        }
        val event = MotionEvent.obtain(now, now, action, 1, arrayOf(props), arrayOf(finger),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun macroTap_finishesAfterLiftAndIgnoresGlobalHoldAndTurbo() {
        lateinit var button: ControlView
        main {
            GlobalSettings.globalHold = true
            GlobalSettings.globalTurbo = true
            button = control("LT:0.5") { macroModeEnabled = true }
            touch(button, MotionEvent.ACTION_DOWN)
            touch(button, MotionEvent.ACTION_UP)
            assertEquals(Scx2Output.pressure(0.5), output().gamepad.leftTrigger)
        }
        SystemClock.sleep(350)
        main {
            assertEquals(0, output().gamepad.leftTrigger)
            assertFalse(button.isLatched)
        }
    }

    @Test fun macroDelayedCommand_finishesAfterLiftAndPreservesAnotherButtonsHold() {
        lateinit var held: ControlView
        main {
            held = control("X360Y")
            touch(held, MotionEvent.ACTION_DOWN)
            val macro = control("RT:0.7(D150)(U300)") { macroModeEnabled = true }
            touch(macro, MotionEvent.ACTION_DOWN)
            touch(macro, MotionEvent.ACTION_UP)
            assertEquals(0, output().gamepad.rightTrigger)
        }
        SystemClock.sleep(250)
        main { assertEquals(Scx2Output.pressure(0.7), output().gamepad.rightTrigger) }
        SystemClock.sleep(300)
        main {
            assertEquals(0, output().gamepad.rightTrigger)
            assertEquals(8, output().gamepad.buttons)
            touch(held, MotionEvent.ACTION_UP)
            assertEquals(0, output().gamepad.buttons)
        }
    }

    @Test fun macroHardCleanup_cancelsBothPendingAndReleaseActions() {
        main {
            val macro = control("X360A(U500),RT:0.7(D150)(U300),X360B(SOR)") {
                macroModeEnabled = true
            }
            touch(macro, MotionEvent.ACTION_DOWN)
            assertEquals(1, output().gamepad.buttons)
            macro.releaseEverythingLocally()
            assertEquals(0, output().gamepad.buttons)
        }
        SystemClock.sleep(600)
        main {
            assertEquals(0, output().gamepad.buttons)
            assertEquals(0, output().gamepad.rightTrigger)
        }
    }

    @Test fun shrinkingTouchAimContact_preservesLtThroughFingerLiftWhileRtReleases() {
        main {
            val aim = control("", ControlType.TOUCH_AIM) {
                touchUseSize = true; touchUseMajor = false; touchUseMinor = false
                touchSizeScale = 10f; touchScoreSmoothing = 1f; touchHysteresis = 0f
                touchMediumPayload = "LT:0.5(HRD300)"; touchHighPayload = "RT:0.7"
                touchKeepLowerHolds = true
            }
            touchSize(aim, MotionEvent.ACTION_DOWN, 0.4f)
            assertEquals(Scx2Output.pressure(0.5), output().gamepad.leftTrigger)
            touchSize(aim, MotionEvent.ACTION_MOVE, 0.8f)
            assertEquals(Scx2Output.pressure(0.5), output().gamepad.leftTrigger)
            assertEquals(Scx2Output.pressure(0.7), output().gamepad.rightTrigger)
            touchSize(aim, MotionEvent.ACTION_MOVE, 0.2f)
            touchSize(aim, MotionEvent.ACTION_UP, 0.1f)
            assertEquals(Scx2Output.pressure(0.5), output().gamepad.leftTrigger)
            assertEquals(0, output().gamepad.rightTrigger)
        }
        SystemClock.sleep(450)
        main { assertEquals(0, output().gamepad.leftTrigger) }
    }

    @Test fun delayedOrdinaryTrigger_cancelsEarlyLiftAndHardReleaseClearsLingeringLt() {
        main {
            val button = control("LT:0.5(HRD1000),RT:0.7(D200)")
            touch(button, MotionEvent.ACTION_DOWN)
            assertEquals(Scx2Output.pressure(0.5), output().gamepad.leftTrigger)
            assertEquals(0, output().gamepad.rightTrigger)
            touch(button, MotionEvent.ACTION_UP)
            assertEquals(Scx2Output.pressure(0.5), output().gamepad.leftTrigger)
            button.releaseEverythingLocally()
            assertEquals(0, output().gamepad.leftTrigger)
        }
        SystemClock.sleep(300)
        main { assertEquals(0, output().gamepad.rightTrigger) }
    }

    private fun pointers(view: ControlView, action: Int, actionIndex: Int = 0,
                         points: List<Triple<Int, Float, Float>>) {
        val now = SystemClock.uptimeMillis()
        val properties = points.map { (id, _, _) -> MotionEvent.PointerProperties().apply {
            this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = points.map { (_, px, py) -> MotionEvent.PointerCoords().apply {
            x = px; y = py; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(now, now, action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            points.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun circularButton_rejectsCornerAndReleasesOnLift() = main {
        val button = control("X360A") { circularButton = true }
        assertFalse(touch(button, MotionEvent.ACTION_DOWN, 0f, 0f))
        assertEquals(0, output().gamepad.buttons)
        assertTrue(touch(button, MotionEvent.ACTION_DOWN))
        assertEquals(1, output().gamepad.buttons)
        touch(button, MotionEvent.ACTION_UP)
        assertEquals(0, output().gamepad.buttons)
    }

    @Test fun globalHold_firesImmediatelyAndSecondTapReleases() = main {
        GlobalSettings.globalHold = true
        val button = control("X360B")
        touch(button, MotionEvent.ACTION_DOWN)
        assertEquals(2, output().gamepad.buttons)
        touch(button, MotionEvent.ACTION_UP)
        assertEquals(2, output().gamepad.buttons)
        touch(button, MotionEvent.ACTION_DOWN)
        touch(button, MotionEvent.ACTION_UP)
        assertEquals(0, output().gamepad.buttons)
    }

    @Test fun holdToggle_thresholdLatchesAndCancellationClears() {
        lateinit var button: ControlView
        main {
            button = control("X360B") { holdToggle = true; holdDurationMs = 60L }
            touch(button, MotionEvent.ACTION_DOWN)
            assertEquals(0, output().gamepad.buttons)
        }
        SystemClock.sleep(150)
        main {
            touch(button, MotionEvent.ACTION_UP)
            assertTrue(button.isLatched)
            assertEquals(2, output().gamepad.buttons)
            touch(button, MotionEvent.ACTION_CANCEL)
            assertFalse(button.isLatched)
            assertEquals(0, output().gamepad.buttons)
        }
    }

    @Test fun retainedStick_liftKeepsPositionButCancelCenters() = main {
        val stick = control("L", ControlType.STICK) { autoCenter = false }
        touch(stick, MotionEvent.ACTION_DOWN)
        touch(stick, MotionEvent.ACTION_MOVE, 100f, 10f)
        touch(stick, MotionEvent.ACTION_UP, 100f, 10f)
        assertTrue(output().gamepad.leftY > 0)
        touch(stick, MotionEvent.ACTION_CANCEL)
        assertEquals(0, output().gamepad.leftX)
        assertEquals(0, output().gamepad.leftY)
    }

    @Test fun touchpadCancel_releasesWithoutSynthesizingClick() {
        main {
            val pad = control("", ControlType.TOUCHPAD) { holdLeftWhileTouch = true }
            touch(pad, MotionEvent.ACTION_DOWN)
            assertEquals(setOf("LEFT"), output().heldMouseButtons)
            touch(pad, MotionEvent.ACTION_CANCEL)
            assertTrue(output().heldMouseButtons.isEmpty())
        }
        SystemClock.sleep(400)
        assertTrue(output().heldMouseButtons.isEmpty())
    }

    @Test fun pulseRelease_restoresIndependentLowerPressure() {
        main {
            val hold = control("RT:0.3")
            touch(hold, MotionEvent.ACTION_DOWN)
            val pulse = control("RT:0.8P0.15")
            touch(pulse, MotionEvent.ACTION_DOWN)
            touch(pulse, MotionEvent.ACTION_UP)
            assertEquals(Scx2Output.pressure(0.8), output().gamepad.rightTrigger)
        }
        SystemClock.sleep(250)
        main {
            assertEquals(Scx2Output.pressure(0.3), output().gamepad.rightTrigger)
            touch(views.first(), MotionEvent.ACTION_UP)
            assertEquals(0, output().gamepad.rightTrigger)
        }
    }

    @Test fun waitLift_cancelsFutureCommandsAndClearsHeldButton() {
        main {
            val button = control("X360A,WAIT_150,X360B")
            touch(button, MotionEvent.ACTION_DOWN)
            assertEquals(1, output().gamepad.buttons)
            touch(button, MotionEvent.ACTION_UP)
            assertEquals(0, output().gamepad.buttons)
        }
        SystemClock.sleep(250)
        assertEquals(0, output().gamepad.buttons)
    }

    @Test fun holdToggleWithTurbo_releasesStartedTriggerAndMacroOnLift() = main {
        GlobalSettings.globalTurbo = true
        val button = control("LT:0.5,LS:R50") { holdToggle = true; holdDurationMs = 500L }
        touch(button, MotionEvent.ACTION_DOWN)
        assertEquals(Scx2Output.pressure(0.5), output().gamepad.leftTrigger)
        assertTrue(output().gamepad.leftX > 0)
        touch(button, MotionEvent.ACTION_UP)
        assertFalse(button.isLatched)
        assertEquals(0, output().gamepad.leftTrigger)
        assertEquals(0, output().gamepad.leftX)
    }

    @Test fun timedHoldToggleWithTurbo_keepsSilentShortTapAndThresholdLatch() {
        lateinit var button: ControlView
        main {
            GlobalSettings.globalTurbo = true
            button = control("LT:0.5,WAIT_80,RT:0.7") { holdToggle = true; holdDurationMs = 60L }
            touch(button, MotionEvent.ACTION_DOWN)
            assertEquals(0, output().gamepad.leftTrigger)
            touch(button, MotionEvent.ACTION_UP)
        }
        SystemClock.sleep(180)
        main {
            assertEquals(0, output().gamepad.leftTrigger)
            assertEquals(0, output().gamepad.rightTrigger)
            touch(button, MotionEvent.ACTION_DOWN)
        }
        // The WAIT clock starts when the main-thread hold callback actually latches, not
        // at ACTION_DOWN. A busy emulator may deliver that callback late; still require
        // the complete latched output within a bound rather than guessing its dispatch time.
        val deadline = SystemClock.uptimeMillis() + 2000
        var latchedOutputReady = false
        while (!latchedOutputReady && SystemClock.uptimeMillis() < deadline) {
            main { latchedOutputReady = button.isLatched && output().gamepad.rightTrigger == Scx2Output.pressure(0.7) }
            if (!latchedOutputReady) SystemClock.sleep(10)
        }
        main {
            assertTrue(latchedOutputReady)
            assertTrue(button.isLatched)
            assertEquals(Scx2Output.pressure(0.5), output().gamepad.leftTrigger)
            assertEquals(Scx2Output.pressure(0.7), output().gamepad.rightTrigger)
            touch(button, MotionEvent.ACTION_UP)
            assertEquals(Scx2Output.pressure(0.7), output().gamepad.rightTrigger)
            touch(button, MotionEvent.ACTION_DOWN)
            touch(button, MotionEvent.ACTION_UP)
            assertEquals(0, output().gamepad.leftTrigger)
            assertEquals(0, output().gamepad.rightTrigger)
        }
    }

    @Test fun sameControlRepress_oldPulseDeadlineCannotReleaseNewPulse() {
        lateinit var button: ControlView
        main {
            button = control("RT:0.8P0.3")
            touch(button, MotionEvent.ACTION_DOWN)
            touch(button, MotionEvent.ACTION_UP)
        }
        SystemClock.sleep(180)
        main { touch(button, MotionEvent.ACTION_DOWN); touch(button, MotionEvent.ACTION_UP) }
        SystemClock.sleep(180)
        main { assertEquals(Scx2Output.pressure(0.8), output().gamepad.rightTrigger) }
        SystemClock.sleep(200)
        main { assertEquals(0, output().gamepad.rightTrigger) }
    }

    @Test fun waitReplacesSameRunPulse_oldDeadlineCannotClearContinuousPressure() {
        lateinit var button: ControlView
        main {
            button = control("RT:1.0P0.3,WAIT_80,RT:0.2")
            touch(button, MotionEvent.ACTION_DOWN)
        }
        SystemClock.sleep(180)
        main { assertEquals(Scx2Output.pressure(0.2), output().gamepad.rightTrigger) }
        SystemClock.sleep(250)
        main {
            assertEquals(Scx2Output.pressure(0.2), output().gamepad.rightTrigger)
            touch(button, MotionEvent.ACTION_UP)
            assertEquals(0, output().gamepad.rightTrigger)
        }
    }

    @Test fun touchpadInitiatingPointerLift_releasesOnlyPadAndIgnoresRemainingFinger() = main {
        val other = control("X360B"); touch(other, MotionEvent.ACTION_DOWN)
        val pad = control("", ControlType.TOUCHPAD) { holdLeftWhileTouch = true }
        pointers(pad, MotionEvent.ACTION_DOWN, points = listOf(Triple(7, 100f, 100f)))
        pointers(pad, MotionEvent.ACTION_POINTER_DOWN, 1, listOf(Triple(7, 100f, 100f), Triple(12, 10f, 10f)))
        assertEquals(setOf("LEFT"), output().heldMouseButtons)
        // Index order is deliberately reversed: pointer ID, not index zero, owns the pad.
        pointers(pad, MotionEvent.ACTION_POINTER_UP, 1, listOf(Triple(12, 10f, 10f), Triple(7, 100f, 100f)))
        assertTrue(output().heldMouseButtons.isEmpty()); assertEquals(2, output().gamepad.buttons)
        val before = output()
        pointers(pad, MotionEvent.ACTION_MOVE, points = listOf(Triple(12, 180f, 180f)))
        pointers(pad, MotionEvent.ACTION_UP, points = listOf(Triple(12, 180f, 180f)))
        assertEquals(before, output())
    }

    @Test fun secondaryTouchpadPointerLift_doesNotReleaseInitiatingOwner() = main {
        val pad = control("", ControlType.TOUCHPAD) { holdLeftWhileTouch = true }
        pointers(pad, MotionEvent.ACTION_DOWN, points = listOf(Triple(7, 100f, 100f)))
        pointers(pad, MotionEvent.ACTION_POINTER_DOWN, 1, listOf(Triple(7, 100f, 100f), Triple(12, 10f, 10f)))
        pointers(pad, MotionEvent.ACTION_POINTER_UP, 0, listOf(Triple(12, 10f, 10f), Triple(7, 100f, 100f)))
        assertEquals(setOf("LEFT"), output().heldMouseButtons)
        pointers(pad, MotionEvent.ACTION_UP, points = listOf(Triple(7, 100f, 100f)))
        assertTrue(output().heldMouseButtons.isEmpty())
    }

    @Test fun directionalStickOwnerLift_cannotBeReactivatedByRemainingFinger() = main {
        val other = control("X360B"); touch(other, MotionEvent.ACTION_DOWN)
        val stick = control("L", ControlType.STICK) {
            directionalMode = true; autoCenter = true; upCommand = "X360A"
        }
        pointers(stick, MotionEvent.ACTION_DOWN, points = listOf(Triple(7, 100f, 100f)))
        pointers(stick, MotionEvent.ACTION_POINTER_DOWN, 1, listOf(Triple(7, 100f, 100f), Triple(12, 100f, 60f)))
        pointers(stick, MotionEvent.ACTION_MOVE, points = listOf(Triple(12, 100f, 180f), Triple(7, 100f, 60f)))
        assertEquals(3, output().gamepad.buttons)
        assertEquals(0, output().gamepad.leftY)
        pointers(stick, MotionEvent.ACTION_POINTER_UP, 1, listOf(Triple(12, 100f, 180f), Triple(7, 100f, 60f)))
        assertEquals(2, output().gamepad.buttons)
        pointers(stick, MotionEvent.ACTION_MOVE, points = listOf(Triple(12, 100f, 60f)))
        pointers(stick, MotionEvent.ACTION_UP, points = listOf(Triple(12, 100f, 60f)))
        assertEquals(2, output().gamepad.buttons)
    }
}
