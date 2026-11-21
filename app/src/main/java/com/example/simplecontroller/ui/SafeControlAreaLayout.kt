package com.example.simplecontroller.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Opt-in chrome separation. The original canvas object remains the parent of every game control,
 * so touch coordinates, drag positions, saved geometry and floating overlays share one plane.
 * Legacy profiles retain the original full-size canvas, padding and toolbar layout parameters.
 */
class SafeControlAreaLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var usesSafeControlArea: Boolean = false
        private set
    private var plane: FrameLayout? = null
    private var touchTargets = emptyList<View>()
    private val location = IntArray(2)
    private var header: LinearLayout? = null
    private var statusScroll: HorizontalScrollView? = null
    private var fixedTools = emptyList<View>()
    private var horizontalGrid: Boolean? = null
    private val originalParams = linkedMapOf<View, FrameLayout.LayoutParams>()
    private data class TextMetrics(val minLines: Int, val maxLines: Int, val minHeight: Int, val maxWidth: Int)
    private val originalTextMetrics = linkedMapOf<TextView, TextMetrics>()
    private var readout: TextView? = null
    private var oldReadoutMaxWidth = Int.MAX_VALUE
    private var insets = intArrayOf(0, 0, 0, 0)
    private val dp: (Int) -> Int = { (it * resources.displayMetrics.density).toInt() }

    fun configure(
        enabled: Boolean, canvas: FrameLayout, status: TextView,
        tools: List<View>, onReleaseAll: () -> Unit
    ): Boolean {
        plane = canvas
        touchTargets = tools
        if (usesSafeControlArea == enabled) return false
        usesSafeControlArea = enabled
        if (enabled) {
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            tools.forEach { view ->
                originalParams[view] = FrameLayout.LayoutParams(view.layoutParams as FrameLayout.LayoutParams)
                (view.parent as? ViewGroup)?.removeView(view)
            }
            val release = Button(context).apply {
                id = com.example.simplecontroller.R.id.toolbarRelease
                text = context.getString(com.example.simplecontroller.R.string.toolbar_release)
                contentDescription = "Release all controller input"
                AppUiStyle.button(this)
                minWidth = 0
                setPadding(dp(2), 0, dp(2), 0)
                setLines(2)
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,
                    9, 15, 1, android.util.TypedValue.COMPLEX_UNIT_DIP)
                setOnClickListener { onReleaseAll() }
            }
            fixedTools = tools + release
            horizontalGrid = null
            originalParams[status] = FrameLayout.LayoutParams(status.layoutParams as FrameLayout.LayoutParams)
            originalTextMetrics[status] = TextMetrics(status.minLines, status.maxLines, status.minHeight, status.maxWidth)
            oldReadoutMaxWidth = status.maxWidth
            (status.parent as? ViewGroup)?.removeView(status)
            // Six fixed lines cover all Practice fields. Long output scrolls horizontally instead
            // of rewrapping the controller plane while the user is holding a control.
            status.setLines(6)
            status.maxWidth = Int.MAX_VALUE
            readout = status
            statusScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = true
                addView(status, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            header = content.also { addView(it) }
            canvas.tag = "safe-control-plane"
        } else {
            originalParams.forEach { (view, params) ->
                (view.parent as? ViewGroup)?.removeView(view)
                canvas.addView(view, params)
            }
            originalTextMetrics.forEach { (view, saved) ->
                view.minLines = saved.minLines
                view.maxLines = saved.maxLines
                view.minHeight = saved.minHeight
                view.maxWidth = saved.maxWidth
            }
            readout?.maxWidth = oldReadoutMaxWidth
            header?.let(::removeView)
            header = null
            statusScroll = null
            horizontalGrid = null
            fixedTools = emptyList()
            originalParams.clear()
            originalTextMetrics.clear()
            canvas.tag = null
        }
        applyPadding()
        requestLayout()
        return true
    }

    /** The primary controls always occupy fixed, visible rows. The readout can overflow independently. */
    private fun arrangeGrid(wide: Boolean) {
        if (horizontalGrid == wide) return
        horizontalGrid = wide
        val container = header ?: return
        val readoutScroll = statusScroll ?: return
        fixedTools.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        (readoutScroll.parent as? ViewGroup)?.removeView(readoutScroll)
        container.removeAllViews()
        fun horizontalRow(views: List<View>) = LinearLayout(context).apply {
            views.forEach { addView(it, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) }) }
        }
        if (wide || fixedTools.size < 5) {
            container.orientation = LinearLayout.VERTICAL
            container.addView(horizontalRow(fixedTools), LinearLayout.LayoutParams(-1, dp(56)))
            container.addView(readoutScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            container.orientation = LinearLayout.HORIZONTAL
            val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fixedTools.take(3).forEach { view ->
                left.addView(view, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
            }
            val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            right.addView(horizontalRow(fixedTools.drop(3)), LinearLayout.LayoutParams(-1, dp(56)))
            right.addView(readoutScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            container.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
            container.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    fun isToolbarTouch(x: Float, y: Float): Boolean {
        fun contains(view: View): Boolean {
            if (!view.isShown) return false
            view.getLocationOnScreen(location)
            return x >= location[0] && x < location[0] + view.width &&
                y >= location[1] && y < location[1] + view.height
        }
        return header?.let(::contains) == true || touchTargets.any(::contains)
    }

    fun updateEditorVisibility(views: List<View>, editing: Boolean) {
        if (!usesSafeControlArea) return
        // Hidden tools reserve height, so entering/leaving Edit never moves the input plane.
        views.forEach { it.visibility = if (editing) View.VISIBLE else View.INVISIBLE }
    }

    fun applySystemInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (insets.contentEquals(intArrayOf(left, top, right, bottom))) return
        insets = intArrayOf(left, top, right, bottom)
        applyPadding()
    }

    private fun applyPadding() {
        val values = insets
        if (usesSafeControlArea) {
            setPadding(values[0], values[1], values[2], values[3])
            plane?.setPadding(0, 0, 0, 0)
        } else {
            setPadding(0, 0, 0, 0)
            plane?.setPadding(values[0], values[1], values[2], values[3])
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val canvas = plane
        val chrome = header
        if (!usesSafeControlArea || canvas == null || chrome == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        arrangeGrid(width > height || width >= dp(600))
        // The fixed line count depends only on orientation, never live output or Edit state.
        val lines = if (width > height) 3 else 6
        readout?.let { if (it.minLines != lines || it.maxLines != lines) it.setLines(lines) }
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        // The game plane remains outside the readout and keeps at least half the usable height.
        // The old scroller reserved one 56dp tool row plus this fixed-height readout.
        // Keep that exact footprint on upgrade, so existing profile geometry stays in place.
        readout?.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val headerHeight = (dp(56) + (readout?.measuredHeight ?: 0)).coerceAtMost(availableHeight / 2)
        chrome.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(headerHeight, MeasureSpec.EXACTLY))
        val bounds = safeControlPlane(width, height, paddingLeft, paddingTop, paddingRight,
            paddingBottom, chrome.measuredHeight)
        canvas.measure(MeasureSpec.makeMeasureSpec(bounds.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bounds.height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val canvas = plane
        val chrome = header
        if (!usesSafeControlArea || canvas == null || chrome == null) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        val bounds = safeControlPlane(width, height, paddingLeft, paddingTop, paddingRight,
            paddingBottom, chrome.measuredHeight)
        chrome.layout(paddingLeft, paddingTop, width - paddingRight, bounds.top)
        canvas.layout(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
    }
}
