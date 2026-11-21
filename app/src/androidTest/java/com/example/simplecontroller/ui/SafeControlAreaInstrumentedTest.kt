package com.example.simplecontroller.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android measurement and reparenting; no receiver or device output is used. */
@RunWith(AndroidJUnit4::class)
class SafeControlAreaInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private class Fixture(context: Context) {
        val root = SafeControlAreaLayout(context)
        val canvas = FrameLayout(context)
        val status = TextView(context).apply { text = "Practice"; textSize = 12f }
        val edit = Button(context).apply { text = "Edit" }
        val save = Button(context).apply { text = "Save" }
        var releases = 0
        init {
            root.addView(canvas, FrameLayout.LayoutParams(-1, -1))
            canvas.addView(status, FrameLayout.LayoutParams(-2, -2).apply { topMargin = 16 })
            canvas.addView(edit, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = 20 })
            canvas.addView(save, FrameLayout.LayoutParams(-2, -2).apply { topMargin = 60 })
        }
        fun configure(safe: Boolean) = root.configure(safe, canvas, status, listOf(edit, save)) { releases++ }
        fun layout(width: Int = 1080, height: Int = 2020) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
    }
    private fun fixture() = Fixture(ContextThemeWrapper(instrumentation.targetContext,
        androidx.appcompat.R.style.Theme_AppCompat))

    @Test fun safePlaneExcludesSystemBarsAndToolsInBothOrientations() = main {
        val f = fixture(); f.configure(true); f.root.applySystemInsets(30, 64, 20, 52)
        for ((width, height) in listOf(1080 to 2020, 2020 to 1080)) {
            f.layout(width, height)
            assertEquals(30, f.canvas.left)
            assertEquals(width - 20, f.canvas.right)
            assertTrue(f.canvas.top > 64)
            assertEquals(height - 52, f.canvas.bottom)
            assertEquals(0, f.canvas.paddingTop)
            assertSame(f.root, f.canvas.parent) // no scrolling ancestor captures stick gestures
            assertTrue(f.canvas.height >= (height - 64 - 52) / 2)
        }
    }
    @Test fun practiceReadoutAndEditVisibilityCannotMoveControlPlane() = main {
        val f = fixture(); f.configure(true); f.root.updateEditorVisibility(listOf(f.save), false)
        f.layout(); val top = f.canvas.top; val height = f.canvas.height
        f.status.text = "Practice\nA LT 50% RT 70%\nL 100,100 R 50,50\nCamera ON\nKeys W Mouse LEFT\nX 500 Y 300 Scroll 20"
        f.root.updateEditorVisibility(listOf(f.save), true)
        f.layout(); assertEquals(top, f.canvas.top); assertEquals(height, f.canvas.height)
        f.root.updateEditorVisibility(listOf(f.save), false)
        f.layout(); assertEquals(top, f.canvas.top); assertEquals(height, f.canvas.height)
    }
    @Test fun legacyCanvasAndToolbarParametersAreRestoredExactly() = main {
        val f = fixture(); val min = f.edit.minHeight
        f.configure(false); f.root.applySystemInsets(30, 64, 20, 52); f.layout()
        assertEquals(2020, f.canvas.height); assertEquals(64, f.canvas.paddingTop)
        f.configure(true); f.layout(); f.configure(false); f.layout()
        assertEquals(2020, f.canvas.height); assertEquals(64, f.canvas.paddingTop)
        assertSame(f.canvas, f.edit.parent)
        assertEquals(20, (f.edit.layoutParams as FrameLayout.LayoutParams).leftMargin)
        assertEquals(min, f.edit.minHeight)
    }
    @Test fun immediateSaveDuringLegacyToSafeLoadKeepsNewProfileControls() {
        androidx.test.core.app.ActivityScenario.launch(com.example.simplecontroller.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val install = activity.javaClass.getDeclaredMethod("installProfile", String::class.java,
                    com.example.simplecontroller.model.ControllerProfile::class.java).apply { isAccessible = true }
                val old = com.example.simplecontroller.model.ControllerProfile(homePageId = "old",
                    pages = listOf(com.example.simplecontroller.model.ControllerPage("old", "Old", listOf(
                        com.example.simplecontroller.model.Control("old-control", com.example.simplecontroller.model.ControlType.BUTTON,
                            0f, 0f, 44f, 44f, "X360B")))))
                install.invoke(activity, "safe-area-old-test", old)
                val expected = com.example.simplecontroller.model.ControllerProfile(homePageId = "new",
                    pages = listOf(com.example.simplecontroller.model.ControllerPage("new", "New", listOf(
                        com.example.simplecontroller.model.Control("new-control", com.example.simplecontroller.model.ControlType.BUTTON,
                            20f, 30f, 44f, 44f, "X360A")))), safeControlArea = true)
                install.invoke(activity, "safe-area-new-test", expected)
                // No event-loop/layout turn is allowed before the same save entry point used by autosave.
                val saved = activity.controllerProfileForSave()!!
                assertEquals(listOf("new-control"), saved.pages.single().controls.map { it.id })
                assertTrue(saved.safeControlArea)
            }
        }
    }

    @Test fun releaseAllRemainsOutsideHorizontallyScrollingTools() = main {
        val f = fixture(); f.configure(true); f.layout()
        fun find(view: View): Button? {
            if (view is Button && view.text == "Release all") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val release = find(f.root)!!
        assertTrue(release.parent is android.widget.LinearLayout)
        assertFalse(release.parent?.parent is android.widget.HorizontalScrollView)
        release.performClick(); assertEquals(1, f.releases)
    }
}
