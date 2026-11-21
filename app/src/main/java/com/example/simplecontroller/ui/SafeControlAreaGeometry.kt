package com.example.simplecontroller.ui

/** Pixel coordinates within the outer window; profile positions are local to this rectangle. */
data class SafeControlPlane(val left: Int, val top: Int, val width: Int, val height: Int)

fun safeControlPlane(
    windowWidth: Int, windowHeight: Int,
    insetLeft: Int, insetTop: Int, insetRight: Int, insetBottom: Int,
    headerHeight: Int
): SafeControlPlane {
    val left = insetLeft.coerceIn(0, windowWidth.coerceAtLeast(0))
    val top = insetTop.coerceIn(0, windowHeight.coerceAtLeast(0))
    val width = (windowWidth - left - insetRight.coerceAtLeast(0)).coerceAtLeast(0)
    val available = (windowHeight - top - insetBottom.coerceAtLeast(0)).coerceAtLeast(0)
    val header = headerHeight.coerceIn(0, available)
    return SafeControlPlane(left, top + header, width, available - header)
}

/** Rendering to a smaller viewport is not an edit. Preserve canonical geometry until the user
 * moves/resizes/adds/deletes a control; source uniform fitting is not invertible across aspect ratios. */
fun captureEditedSafeGeometry(
    existing: com.example.simplecontroller.model.PageOrientationGeometry?,
    rendered: Map<String, com.example.simplecontroller.model.ControlGeometry>,
    controls: List<com.example.simplecontroller.model.Control>,
    width: Float, height: Float
): com.example.simplecontroller.model.PageOrientationGeometry {
    val current = com.example.simplecontroller.model.capturePageGeometry(controls, width, height)
    return if (existing != null && current.controls == rendered) existing else current
}
