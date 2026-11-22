package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class ControlHitRegionTest {
    @Test fun circleRejectsInvisibleCornersButKeepsItsCenterAndRim() {
        assertFalse(ControlHitRegion.contains(1f, 1f, 44f, 44f, true))
        assertTrue(ControlHitRegion.contains(22f, 22f, 44f, 44f, true))
        assertTrue(ControlHitRegion.contains(22f, 0f, 44f, 44f, true))
    }
    @Test fun rectangularDragSurfaceKeepsItsCorners() {
        assertTrue(ControlHitRegion.contains(1f, 1f, 104f, 68f, false))
        assertFalse(ControlHitRegion.contains(104f, 10f, 104f, 68f, false))
    }
    @Test fun resizedOvalUsesBothDimensions() {
        assertTrue(ControlHitRegion.contains(60f, 10f, 120f, 44f, true))
        assertFalse(ControlHitRegion.contains(10f, 2f, 120f, 44f, true))
    }
    @Test fun resizedRecenterCircleRejectsInvisibleSideStrips() {
        assertFalse(ControlHitRegion.containsCenteredCircle(10f, 22f, 120f, 44f))
        assertTrue(ControlHitRegion.containsCenteredCircle(60f, 22f, 120f, 44f))
        assertTrue(ControlHitRegion.containsCenteredCircle(60f, 0f, 120f, 44f))
        assertFalse(ControlHitRegion.containsCenteredCircle(22f, 10f, 44f, 120f))
    }
    @Test fun rectangularStickKeepsItsVisibleCornerTargets() {
        assertTrue(ControlHitRegion.contains(1f, 1f, 104f, 104f, false))
        assertTrue(ControlHitRegion.contains(1f, 1f, 140f, 60f, false))
    }
    @Test fun invalidOrEmptyGeometryNeverCapturesTouch() {
        assertFalse(ControlHitRegion.contains(Float.NaN, 1f, 44f, 44f, true))
        assertFalse(ControlHitRegion.contains(1f, 1f, 0f, 44f, false))
        assertFalse(ControlHitRegion.contains(1f, 1f, Float.POSITIVE_INFINITY, 44f, false))
    }
}
