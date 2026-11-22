package com.example.simplecontroller.ui

import com.example.simplecontroller.model.*
import com.example.simplecontroller.io.*
import org.junit.Assert.*
import org.junit.Test

class SafeControlAreaGeometryTest {
    @Test fun portraitExcludesMeasuredChromeAndBothSystemBars() {
        assertEquals(SafeControlPlane(0, 224, 1080, 1744),
            safeControlPlane(1080, 2020, 0, 64, 0, 52, 160))
    }
    @Test fun landscapeUsesMeasuredSideInsetsAndHeader() {
        assertEquals(SafeControlPlane(48, 180, 1952, 828),
            safeControlPlane(2040, 1080, 48, 24, 40, 72, 156))
    }
    @Test fun impossibleBoundsNeverProduceNegativeSizes() {
        assertEquals(SafeControlPlane(20, 30, 0, 0),
            safeControlPlane(20, 30, 40, 50, 60, 70, 500))
    }
    @Test fun safeCoordinatesRoundTripWithoutToolbarOffsetsAccumulating() {
        val c = Control("a", ControlType.BUTTON, 20f, 30f, 44f, 44f, "X360A")
        val original = ControllerPage("p", "Page", listOf(c),
            portraitGeometry = capturePageGeometry(listOf(c), 337f, 428f))
        val plane = safeControlPlane(1080, 2020, 0, 64, 0, 52, 160)
        val displayed = original.controlsForOrientation(LayoutOrientation.PORTRAIT,
            plane.width.toFloat(), plane.height.toFloat())
        val saved = original.copy(controls = displayed, portraitGeometry = capturePageGeometry(
            displayed, plane.width.toFloat(), plane.height.toFloat()))
        val reopened = saved.controlsForOrientation(LayoutOrientation.PORTRAIT,
            plane.width.toFloat(), plane.height.toFloat()).single()
        assertEquals(displayed.single().x, reopened.x, .001f)
        assertEquals(displayed.single().y, reopened.y, .001f)
        assertEquals(displayed.single().w, reopened.w, .001f)
    }
    @Test fun legacyProfilesDefaultToOriginalCanvas() {
        val p = decodeControllerProfile("""{"formatVersion":5,"homePageId":"p","pages":[{"id":"p","name":"Page","controls":[]}]}""", "old").profile
        assertFalse(p.safeControlArea)
        assertEquals(5, p.requiredFormatVersion())
    }
    @Test fun safeAreaRequiresAwareReaderAndSurvivesTransfer() {
        val p = ControllerProfile(homePageId = "p", pages = listOf(ControllerPage("p", "Page")), safeControlArea = true)
        assertEquals(11, p.requiredFormatVersion())
        val loaded = decodeControllerProfileTransfer(encodeControllerProfileTransfer("safe", p), "fallback").result.profile
        assertTrue(loaded.safeControlArea)
        assertEquals(11, loaded.formatVersion)
        assertTrue(loaded.copy(pages = listOf(ControllerPage("q", "Copy"))).safeControlArea)
    }
    @Test fun temporaryViewportShrinkAndSaveDoNotPermanentlyShrinkTargets() {
        val c = Control("a", ControlType.BUTTON, 40f, 100f, 44f, 44f, "X360A")
        val original = capturePageGeometry(listOf(c), 337f, 428f)
        val page = ControllerPage("p", "Page", listOf(c), portraitGeometry = original)
        val compact = page.controlsForOrientation(LayoutOrientation.PORTRAIT, 337f, 214f)
        assertEquals(22f, compact.single().w, .001f)
        val rendered = capturePageGeometry(compact, 337f, 214f).controls
        val kept = captureEditedSafeGeometry(original, rendered, compact, 337f, 214f)
        assertEquals(original, kept)
        val expanded = page.copy(controls = compact, portraitGeometry = kept)
            .controlsForOrientation(LayoutOrientation.PORTRAIT, 337f, 428f).single()
        assertEquals(c.x, expanded.x, .001f)
        assertEquals(c.y, expanded.y, .001f)
        assertEquals(44f, expanded.w, .001f)
    }
    @Test fun deliberateEditUsesDisplayedPlaneAndDoesNotAddHeaderOffset() {
        val c = Control("a", ControlType.BUTTON, 40f, 100f, 44f, 44f, "X360A")
        val original = capturePageGeometry(listOf(c), 337f, 428f)
        val page = ControllerPage("p", "Page", listOf(c), portraitGeometry = original)
        val shown = page.controlsForOrientation(LayoutOrientation.PORTRAIT, 337f, 214f)
        val rendered = capturePageGeometry(shown, 337f, 214f).controls
        val edited = shown.map { it.copy(x = 70f, payload = "X360B") }
        val saved = captureEditedSafeGeometry(original, rendered, edited, 337f, 214f)
        assertEquals(214f, saved.canvasHeight, .001f)
        assertEquals(70f, saved.controls.getValue("a").x, .001f)
        assertEquals(shown.single().y, saved.controls.getValue("a").y, .001f)
    }

}
