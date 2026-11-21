package com.example.simplecontroller.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.simplecontroller.R
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.scx2.Scx2Output
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real owner arbitration, MotionEvents, retained main-loop resends and Canvas drawing. */
@RunWith(AndroidJUnit4::class)
class RetainedStickInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val views = mutableListOf<ControlView>()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun output() = NetworkClient.localOutput.value.gamepad

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
        NetworkClient.close()
    }

    private fun control(payload: String = "L", type: ControlType = ControlType.STICK,
                        configure: Control.() -> Unit = {}): ControlView {
        val model = Control("retained-${views.size}", type, 0f, 0f, 200f, 200f, payload).apply(configure)
        val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
        return ControlView(context, model).also { it.layout(0, 0, 200, 200); views += it }
    }

    private fun touch(view: ControlView, action: Int, x: Float = 100f, y: Float = 100f) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun assertVisual(view: ControlView, x: Float, y: Float) {
        assertEquals(x, view.displayedStickPosition.first, 0.000001f)
        assertEquals(y, view.displayedStickPosition.second, 0.000001f)
    }

    private fun pixel(view: ControlView, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)); return bitmap.getPixel(x, y) }
        finally { bitmap.recycle() }
    }

    @Test fun linearPosition_isIdenticalDuringTouchAfterLiftAndRepeatedResends() {
        lateinit var stick: ControlView
        main {
            stick = control { autoCenter = false }
            touch(stick, MotionEvent.ACTION_DOWN)
            touch(stick, MotionEvent.ACTION_MOVE, 150f, 60f)
            assertEquals(Scx2Output.axis(0.5), output().leftX)
            assertEquals(Scx2Output.axis(0.4), output().leftY)
            assertVisual(stick, 0.5f, -0.4f)
            touch(stick, MotionEvent.ACTION_UP, 150f, 60f)
        }
        // Multiple actual Handler frames used to replace .5 with .25 after lifting.
        repeat(3) {
            SystemClock.sleep(50)
            main {
                assertEquals(Scx2Output.axis(0.5), output().leftX)
                assertEquals(Scx2Output.axis(0.4), output().leftY)
                assertVisual(stick, 0.5f, -0.4f)
            }
        }
    }

    @Test fun tinyFinalAdjustment_isNotDiscardedByRetainedSender() {
        lateinit var stick: ControlView
        main {
            stick = control { autoCenter = false }
            touch(stick, MotionEvent.ACTION_DOWN)
            touch(stick, MotionEvent.ACTION_MOVE, 150f, 100f)
            touch(stick, MotionEvent.ACTION_MOVE, 150.5f, 100f)
            assertEquals(Scx2Output.axis(0.505), output().leftX)
            touch(stick, MotionEvent.ACTION_UP, 150.5f, 100f)
        }
        SystemClock.sleep(100)
        main {
            assertEquals(Scx2Output.axis(0.505), output().leftX)
            assertVisual(stick, 0.505f, 0f)
        }
    }

    @Test fun responseCurve_isAppliedOnceAndVisualShowsAcceptedOutput() {
        lateinit var stick: ControlView
        main {
            stick = control(type = ControlType.CURVED_STICK) { autoCenter = false; sensitivity = 0.5f }
            touch(stick, MotionEvent.ACTION_DOWN)
            touch(stick, MotionEvent.ACTION_MOVE, 150f, 100f)
            assertEquals(Scx2Output.axis(0.25), output().leftX)
            assertVisual(stick, 0.25f, 0f)
            touch(stick, MotionEvent.ACTION_UP, 150f, 100f)
        }
        SystemClock.sleep(100)
        main {
            assertEquals(Scx2Output.axis(0.25), output().leftX)
            assertVisual(stick, 0.25f, 0f)
        }
    }

    @Test fun sensitivitySaturation_retainsExactlyTheNormalizedAcceptedValue() {
        lateinit var stick: ControlView
        main {
            stick = control { autoCenter = false; sensitivity = 3f }
            touch(stick, MotionEvent.ACTION_DOWN)
            touch(stick, MotionEvent.ACTION_MOVE, 150f, 100f)
            assertEquals(32767, output().leftX)
            assertVisual(stick, 1f, 0f)
            touch(stick, MotionEvent.ACTION_UP, 150f, 100f)
        }
        SystemClock.sleep(100)
        main { assertEquals(32767, output().leftX); assertVisual(stick, 1f, 0f) }
    }

    @Test fun knobActuallyDrawsAtRetainedPosition_andRecenterCancelAndReleaseClearIt() = main {
        val independent = control("X360B", ControlType.BUTTON)
        touch(independent, MotionEvent.ACTION_DOWN)
        val stick = control { autoCenter = false }
        val knob = ContextCompat.getColor(stick.context, R.color.stick_blue)
        assertNotEquals(knob, pixel(stick, 175, 100))
        touch(stick, MotionEvent.ACTION_DOWN)
        touch(stick, MotionEvent.ACTION_MOVE, 200f, 100f)
        touch(stick, MotionEvent.ACTION_UP, 200f, 100f)
        assertEquals(knob, pixel(stick, 175, 100))
        val center = control("", ControlType.RECENTER)
        touch(center, MotionEvent.ACTION_DOWN)
        touch(center, MotionEvent.ACTION_UP)
        assertVisual(stick, 0f, 0f)
        assertNotEquals(knob, pixel(stick, 175, 100))
        assertEquals(0, output().leftX)
        assertEquals(2, output().buttons)
        touch(stick, MotionEvent.ACTION_DOWN)
        touch(stick, MotionEvent.ACTION_MOVE, 200f, 100f)
        touch(stick, MotionEvent.ACTION_CANCEL)
        assertVisual(stick, 0f, 0f)
        assertEquals(0, output().leftX)
        assertEquals(2, output().buttons)
        touch(stick, MotionEvent.ACTION_DOWN)
        touch(stick, MotionEvent.ACTION_MOVE, 200f, 100f)
        touch(stick, MotionEvent.ACTION_UP, 200f, 100f)
        stick.releaseEverythingLocally()
        assertVisual(stick, 0f, 0f)
        assertNotEquals(knob, pixel(stick, 175, 100))
        assertEquals(0, output().leftX)
        assertEquals(2, output().buttons)
    }

    @Test fun replacingRetainedOwner_centersOldVisualWithoutClearingNewOutput() {
        lateinit var old: ControlView
        lateinit var replacement: ControlView
        main {
            old = control { autoCenter = false }
            touch(old, MotionEvent.ACTION_DOWN)
            touch(old, MotionEvent.ACTION_MOVE, 150f, 100f)
            touch(old, MotionEvent.ACTION_UP, 150f, 100f)
            replacement = control { autoCenter = false }
            touch(replacement, MotionEvent.ACTION_DOWN)
            touch(replacement, MotionEvent.ACTION_MOVE, 30f, 100f)
            assertVisual(old, 0f, 0f)
            assertVisual(replacement, -0.7f, 0f)
            assertEquals(Scx2Output.axis(-0.7), output().leftX)
            touch(replacement, MotionEvent.ACTION_UP, 30f, 100f)
        }
        SystemClock.sleep(100)
        main {
            assertVisual(old, 0f, 0f)
            assertVisual(replacement, -0.7f, 0f)
            assertEquals(Scx2Output.axis(-0.7), output().leftX)
        }
    }

    @Test fun blockedSecondSurface_doesNotMoveItsKnobOrInterruptTheLiveOwner() = main {
        val owner = control { autoCenter = false }
        touch(owner, MotionEvent.ACTION_DOWN)
        touch(owner, MotionEvent.ACTION_MOVE, 150f, 100f)
        val blocked = control { autoCenter = false }
        touch(blocked, MotionEvent.ACTION_DOWN)
        touch(blocked, MotionEvent.ACTION_MOVE, 10f, 100f)
        assertVisual(blocked, 0f, 0f)
        assertVisual(owner, 0.5f, 0f)
        assertEquals(Scx2Output.axis(0.5), output().leftX)
        touch(blocked, MotionEvent.ACTION_CANCEL)
        assertVisual(owner, 0.5f, 0f)
        assertEquals(Scx2Output.axis(0.5), output().leftX)
    }

    @Test fun directionalKnob_tracksPhysicalTravelAndRetainsWithoutImplicitAnalog() = main {
        val independent = control("X360B", ControlType.BUTTON)
        touch(independent, MotionEvent.ACTION_DOWN)
        val stick = control {
            directionalMode = true; autoCenter = false; sensitivity = 2f
            upCommand = "X360A"; upBoostCommand = "X360A"; upSuperBoostCommand = "X360A"
        }
        touch(stick, MotionEvent.ACTION_DOWN)
        touch(stick, MotionEvent.ACTION_MOVE, 100f, 50f)
        assertVisual(stick, 0f, -0.5f)
        assertEquals(0, output().leftY)
        assertEquals(3, output().buttons)
        touch(stick, MotionEvent.ACTION_UP, 100f, 50f)
        assertVisual(stick, 0f, -0.5f)
        assertEquals(3, output().buttons)
        SwipeManager.recenterAllSticks()
        assertVisual(stick, 0f, 0f)
        assertEquals(2, output().buttons)
        assertEquals(0, output().leftY)
    }

    @Test fun autoCenterLift_centersBothOutputAndKnobImmediately() = main {
        val stick = control { autoCenter = true }
        touch(stick, MotionEvent.ACTION_DOWN)
        touch(stick, MotionEvent.ACTION_MOVE, 150f, 60f)
        assertVisual(stick, 0.5f, -0.4f)
        touch(stick, MotionEvent.ACTION_UP, 150f, 60f)
        assertVisual(stick, 0f, 0f)
        assertEquals(0, output().leftX)
        assertEquals(0, output().leftY)
    }
}
