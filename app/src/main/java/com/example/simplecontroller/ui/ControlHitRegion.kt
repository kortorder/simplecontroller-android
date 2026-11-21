package com.example.simplecontroller.ui

/** The visible circular/oval surface is also the initial touch and swipe target. */
internal object ControlHitRegion {
    fun containsCenteredCircle(x: Float, y: Float, width: Float, height: Float): Boolean {
        val diameter = minOf(width, height)
        return contains(x - (width - diameter) / 2f, y - (height - diameter) / 2f,
            diameter, diameter, true)
    }

    fun contains(x: Float, y: Float, width: Float, height: Float, oval: Boolean): Boolean {
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() ||
            width <= 0f || height <= 0f || x < 0f || y < 0f || x >= width || y >= height) return false
        if (!oval) return true
        val dx = (x - width / 2f) / (width / 2f)
        val dy = (y - height / 2f) / (height / 2f)
        return dx * dx + dy * dy <= 1f
    }
}
