package com.example.simplecontroller.ui

import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.ProfileSwipeMode
import com.example.simplecontroller.net.NetworkClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android touch dispatch/timers; Practice guarantees these tests open no receiver socket. */
@RunWith(AndroidJUnit4::class)
class SwipePracticeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val views = mutableListOf<ControlView>()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun output() = NetworkClient.localOutput.value

    @Before fun prepare() = main {
        GlobalSettings.editMode = false; GlobalSettings.globalHold = false
        GlobalSettings.globalTurbo = false; GlobalSettings.globalSwipe = false
        GlobalSettings.scrollMode = false
        NetworkClient.setOutputMode(NetworkClient.OutputMode.WINDOWS)
        NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE)
        NetworkClient.start()
    }
    @After fun cleanup() = main {
        views.forEach { it.releaseEverythingLocally(); SwipeManager.unregisterControl(it) }
        views.clear(); GlobalSettings.globalSwipe = false; NetworkClient.close()
    }
    private fun control(type: ControlType = ControlType.TOUCHPAD, payload: String = "", configure: Control.() -> Unit = {}): ControlView {
        val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
        return ControlView(context, Control("swipe-${views.size}", type, 0f, 0f, 200f, 200f, payload).apply(configure)).also {
            it.layout(0, 0, 200, 200); views += it
        }
    }
    private fun touch(view: ControlView, action: Int, x: Float = 100f, y: Float = 100f,
                      entered: Boolean = false, handoff: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { view.playSwipeTouch(event, entered, handoff) } finally { event.recycle() }
    }

    @Test fun slideEntryAndLiftCannotPrimeDoubleTapLock() {
        lateinit var pad: ControlView
        main {
            pad = control { doubleTapClickLock = true }
            touch(pad, MotionEvent.ACTION_DOWN, entered = true)
            touch(pad, MotionEvent.ACTION_UP)
            touch(pad, MotionEvent.ACTION_DOWN)
        }
        SystemClock.sleep(500)
        main { assertTrue(output().heldMouseButtons.isEmpty()); touch(pad, MotionEvent.ACTION_CANCEL) }
    }
    @Test fun departureCannotPrimeDoubleTapLock() {
        lateinit var pad: ControlView
        main {
            pad = control { doubleTapClickLock = true }
            touch(pad, MotionEvent.ACTION_DOWN)
            touch(pad, MotionEvent.ACTION_UP, handoff = true)
            touch(pad, MotionEvent.ACTION_DOWN)
        }
        SystemClock.sleep(500)
        main { assertTrue(output().heldMouseButtons.isEmpty()); touch(pad, MotionEvent.ACTION_CANCEL) }
    }
    @Test fun outsideAndBackPathCancelsPendingLock() {
        lateinit var pad: ControlView
        main {
            pad = control { doubleTapClickLock = true }
            touch(pad, MotionEvent.ACTION_DOWN); touch(pad, MotionEvent.ACTION_UP)
            touch(pad, MotionEvent.ACTION_DOWN)
            touch(pad, MotionEvent.ACTION_MOVE, 500f, 500f)
            touch(pad, MotionEvent.ACTION_MOVE)
        }
        SystemClock.sleep(500)
        main { assertTrue(output().heldMouseButtons.isEmpty()); touch(pad, MotionEvent.ACTION_CANCEL) }
    }
    @Test fun slideEntryDoesNotToggleTapLock() {
        lateinit var pad: ControlView
        main {
            pad = control { toggleLeftClick = true }
            touch(pad, MotionEvent.ACTION_DOWN, entered = true)
            touch(pad, MotionEvent.ACTION_UP)
        }
        SystemClock.sleep(900)
        assertTrue(output().heldMouseButtons.isEmpty())
    }
    @Test fun ordinaryTapLockPendingBeforeHandoffSurvivesButCancelClears() {
        lateinit var pad: ControlView
        main {
            pad = control { toggleLeftClick = true }
            touch(pad, MotionEvent.ACTION_DOWN)
            touch(pad, MotionEvent.ACTION_UP, handoff = true)
        }
        SystemClock.sleep(900)
        main {
            assertEquals(setOf("LEFT"), output().heldMouseButtons)
            touch(pad, MotionEvent.ACTION_CANCEL)
            assertTrue(output().heldMouseButtons.isEmpty())
        }
    }
    @Test fun holdWhileTouchingReleasesOnSwipeDeparture() = main {
        val pad = control { holdLeftWhileTouch = true }
        touch(pad, MotionEvent.ACTION_DOWN, entered = true)
        assertEquals(setOf("LEFT"), output().heldMouseButtons)
        touch(pad, MotionEvent.ACTION_UP, handoff = true)
        assertTrue(output().heldMouseButtons.isEmpty())
    }
    @Test fun disablingRouterCancelsOwnedButtonRatherThanForgettingIt() = main {
        val button = control(ControlType.BUTTON, "X360A")
        val host = FrameLayout(button.context)
        host.addView(button); host.layout(0, 0, 300, 300); button.layout(0, 0, 200, 200)
        val router = SwipeHandler().also { it.registerView(button); it.setSwipeMode(ProfileSwipeMode.CONTROLS) }
        val location = IntArray(2); button.getLocationOnScreen(location)
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, location[0] + 100f, location[1] + 100f, 0)
        try { assertTrue(router.processTouchEvent(event)) } finally { event.recycle() }
        assertEquals(1, output().gamepad.buttons)
        router.setSwipeMode(ProfileSwipeMode.OFF)
        assertEquals(0, output().gamepad.buttons)
        router.unregisterView(button); host.removeView(button)
    }
    @Test fun directionalModeNeverEmitsImplicitAnalogMotion() = main {
        val stick = control(ControlType.STICK, "L") {
            directionalMode = true; upCommand = "W"; upBoostCommand = "W"; upSuperBoostCommand = "W"
        }
        touch(stick, MotionEvent.ACTION_DOWN)
        touch(stick, MotionEvent.ACTION_MOVE, 100f, 0f)
        assertTrue("W" in output().heldKeys)
        assertEquals(0, output().gamepad.leftX); assertEquals(0, output().gamepad.leftY)
        touch(stick, MotionEvent.ACTION_CANCEL)
        assertTrue(output().heldKeys.isEmpty())
    }
}
