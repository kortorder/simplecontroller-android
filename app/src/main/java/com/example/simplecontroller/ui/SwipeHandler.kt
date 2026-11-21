package com.example.simplecontroller.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.ProfileSwipeMode

/** Per-pointer swipe capture. Nothing that starts on blank space is adopted later. */
class SwipeHandler {
    private data class Contact(var current: ControlView?)
    private val contacts = linkedMapOf<Int, Contact>()
    private val lastEntered = mutableMapOf<String, Long>()
    private val allViews = mutableSetOf<ControlView>()
    private var routedGesture = false
    private var mode = ProfileSwipeMode.OFF
    private var editMode = false

    fun processTouchEvent(event: MotionEvent): Boolean {
        if (mode == ProfileSwipeMode.OFF || editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (routedGesture || contacts.isNotEmpty()) cancelActiveTouch()
                val start = topmostHit(event, event.actionIndex)
                // TouchAim needs the untouched platform contact stream and calibration data.
                if (start == null || start.model.type == ControlType.TOUCH_AIM) return false
                routedGesture = true
                begin(event, event.actionIndex, start)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!routedGesture) return false
                val start = topmostHit(event, event.actionIndex)
                if (start != null && start.model.type != ControlType.TOUCH_AIM) begin(event, event.actionIndex, start)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!routedGesture) return false
                for ((id, contact) in contacts.toList()) {
                    val index = event.findPointerIndex(id)
                    if (index >= 0 && contacts[id] === contact) move(event, index, id, contact)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (!routedGesture) return false
                val id = event.getPointerId(event.actionIndex)
                val contact = contacts.remove(id)
                contact?.current?.let { finish(it, event, event.actionIndex, handoff = false) }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // Any malformed/unbalanced residual contacts are canceled, never synthesized as taps.
                    val residual = contacts.values.mapNotNull { it.current }.distinct()
                    contacts.clear(); routedGesture = false
                    residual.forEach { it.releaseEverythingLocally() }
                    // Ordinary lift preserves the cross-contact destination cooldown.

                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = routedGesture
                cancelActiveTouch()
                return consumed
            }
        }
        return routedGesture
    }

    private fun begin(event: MotionEvent, index: Int, view: ControlView) {
        if (contacts.values.any { it.current === view } || !view.canAcceptSwipeFrom(null)) return
        val id = event.getPointerId(index)
        val contact = Contact(view)
        contacts[id] = contact
        forward(view, event, index, MotionEvent.ACTION_DOWN)
    }

    private fun move(event: MotionEvent, index: Int, id: Int, contact: Contact) {
        val current = contact.current
        val candidate = topmostHit(event, index)
        val now = SystemClock.uptimeMillis()
        val available = candidate != null && contacts.values.none { it !== contact && it.current === candidate } &&
            candidate.canAcceptSwipeFrom(current)
        val cooled = candidate == null || lastEntered[candidate.model.id]?.let { now - it >= 120L } != false
        when (ProfileSwipePolicy.decide(mode, current?.model, current?.hit(event, index) == true,
            candidate?.model, available, cooled)) {
            SwipeDecision.MOVE_CURRENT -> current?.let { movePath(it, event, index) }
            SwipeDecision.KEEP_CURRENT -> Unit
            SwipeDecision.RELEASE_CURRENT -> {
                contact.current = null
                current?.let { finish(it, event, index, handoff = true) }
            }
            SwipeDecision.TRANSFER -> {
                val next = checkNotNull(candidate)
                // Availability is checked before any source release; rejected targets cannot steal input.
                contact.current = null
                current?.let { finish(it, event, index, handoff = true) }
                if (contacts[id] !== contact || !routedGesture) return
                contact.current = next
                forward(next, event, index, MotionEvent.ACTION_DOWN, enteredBySlide = true)
                if (contacts[id] !== contact || contact.current !== next || next.parent == null) return
                // The new pad's origin is this exact position; do not replay pre-entry history.
                forward(next, event, index, MotionEvent.ACTION_MOVE)
                if (mode == ProfileSwipeMode.CONTROLS) lastEntered[next.model.id] = now
            }
        }
    }

    private fun finish(view: ControlView, event: MotionEvent, index: Int, handoff: Boolean) {
        if (view.model.type == ControlType.TOUCHPAD) movePath(view, event, index)
        forward(view, event, index, MotionEvent.ACTION_UP,
            clamp = mode == ProfileSwipeMode.CONTROLS && view.model.type != ControlType.TOUCHPAD,
            handoff = handoff)
    }

    private fun movePath(view: ControlView, event: MotionEvent, index: Int) {
        if (view.model.type == ControlType.TOUCHPAD) {
            // The whole raw path is delivered on this main-thread turn before a queued lock timer.
            for (history in 0 until event.historySize) forward(view, event, index, MotionEvent.ACTION_MOVE, history = history)
        }
        forward(view, event, index, MotionEvent.ACTION_MOVE)
    }

    fun registerView(view: ControlView) { allViews.add(view) }
    fun unregisterView(view: ControlView) {
        val removed = contacts.filterValues { it.current === view }.keys.toList()
        removed.forEach { contacts.remove(it) }
        if (removed.isNotEmpty()) view.releaseEverythingLocally()
        allViews.remove(view)
    }

    fun setSwipeEnabled(enabled: Boolean) = setSwipeMode(if (enabled) ProfileSwipeMode.CONTROLS else ProfileSwipeMode.OFF)
    fun setSwipeMode(value: ProfileSwipeMode) {
        if (mode != value) cancelActiveTouch()
        mode = value
    }
    fun setEditMode(enabled: Boolean) {
        if (enabled) cancelActiveTouch()
        editMode = enabled
    }

    /** Clear routing before scoped cleanup callbacks; canceled contacts cannot click or retain. */
    fun cancelActiveTouch() {
        val old = contacts.values.mapNotNull { it.current }.distinct()
        contacts.clear(); routedGesture = false; lastEntered.clear()
        old.forEach { it.releaseEverythingLocally() }
    }
    fun getAllViews(): Set<ControlView> = allViews

    private fun rawPoint(event: MotionEvent, index: Int): Pair<Float, Float> =
        (event.getX(index) + event.rawX - event.x) to (event.getY(index) + event.rawY - event.y)

    private fun ControlView.hit(event: MotionEvent, index: Int): Boolean {
        if (visibility != View.VISIBLE || parent == null) return false
        val location = IntArray(2); getLocationOnScreen(location)
        val (x, y) = rawPoint(event, index)
        return containsPlayPoint(x - location[0], y - location[1])
    }

    private fun topmostHit(event: MotionEvent, index: Int): ControlView? {
        val hit = allViews.filter { it.hit(event, index) }.maxByOrNull {
            (it.parent as? ViewGroup)?.indexOfChild(it) ?: -1
        } ?: return null
        val parent = hit.parent as? ViewGroup ?: return null
        val (x, y) = rawPoint(event, index)
        // A toolbar or floating overlay above an ordinary control keeps Android's normal dispatch.
        for (i in parent.indexOfChild(hit) + 1 until parent.childCount) {
            val sibling = parent.getChildAt(i)
            if (sibling is ControlView || sibling.visibility != View.VISIBLE || !sibling.isClickable) continue
            val location = IntArray(2); sibling.getLocationOnScreen(location)
            if (x >= location[0] && x < location[0] + sibling.width && y >= location[1] && y < location[1] + sibling.height) return null
        }
        return hit
    }

    private fun forward(
        view: ControlView, source: MotionEvent, index: Int, action: Int,
        clamp: Boolean = false, enteredBySlide: Boolean = false, handoff: Boolean = false,
        history: Int? = null
    ) {
        val location = IntArray(2); view.getLocationOnScreen(location)
        val properties = MotionEvent.PointerProperties().also { source.getPointerProperties(index, it) }
        val coordinates = MotionEvent.PointerCoords().also {
            if (history == null) source.getPointerCoords(index, it)
            else source.getHistoricalPointerCoords(index, history, it)
        }
        coordinates.x += source.rawX - source.x - location[0]
        coordinates.y += source.rawY - source.y - location[1]
        if (clamp) {
            coordinates.x = coordinates.x.coerceIn(0f, view.width.toFloat())
            coordinates.y = coordinates.y.coerceIn(0f, view.height.toFloat())
        }
        val event = MotionEvent.obtain(source.downTime,
            history?.let(source::getHistoricalEventTime) ?: source.eventTime, action, 1,
            arrayOf(properties), arrayOf(coordinates), source.metaState, source.buttonState,
            source.xPrecision, source.yPrecision, source.deviceId, source.edgeFlags, source.source, source.flags)
        try { view.playSwipeTouch(event, enteredBySlide, handoff) } finally { event.recycle() }
    }
}
