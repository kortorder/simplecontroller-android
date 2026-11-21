package com.example.simplecontroller.net.scx2

/** Connection permission is separate from a wire session; it never contains input state. */
class BridgeConnectionIntent {
    data class Endpoint(val host: String, val port: Int, val pressure: Boolean, val usbButtons: Boolean = false,
                        val usbPressure: Boolean = false) {
        constructor(host: String, port: Int, mode: BridgeTriggerMode) : this(host, port,
            mode == BridgeTriggerMode.ADJUSTABLE, mode == BridgeTriggerMode.USB_BUTTONS,
            mode == BridgeTriggerMode.CRONUS_ZEN)
        val triggerMode: BridgeTriggerMode get() = when {
            usbPressure -> BridgeTriggerMode.CRONUS_ZEN
            usbButtons -> BridgeTriggerMode.USB_BUTTONS
            pressure -> BridgeTriggerMode.ADJUSTABLE
            else -> BridgeTriggerMode.OFF
        }
        val valid: Boolean get() = listOf(pressure, usbButtons, usbPressure).count { it } <= 1 && host.isNotBlank() && host == host.trim() && host.length <= 253 &&
            host.none { it.isWhitespace() || it.isISOControl() } && port in 1..65535
    }
    data class Snapshot(val endpoint: Endpoint, val lastPresentMillis: Long, val lastPresentMonotonicMillis: Long)
    var endpoint: Endpoint? = null; private set
    var foreground = true; private set
    private var lastPresentMillis: Long? = null
    private var lastPresentMonotonicMillis: Long? = null
    private var failures = 0
    val active get() = endpoint != null
    val mayConnect get() = active && foreground

    fun connect(value: Endpoint, now: Long, monotonicNow: Long = now): Boolean {
        if (!value.valid || now < 0 || monotonicNow < 0) return false
        endpoint = value; foreground = true; lastPresentMillis = now; lastPresentMonotonicMillis = monotonicNow; failures = 0
        return true
    }
    fun disconnect() { endpoint = null; lastPresentMillis = null; lastPresentMonotonicMillis = null; failures = 0 }
    fun heartbeat(now: Long, monotonicNow: Long = now) {
        if (mayConnect && now >= 0 && monotonicNow >= 0) {
            lastPresentMillis = now; lastPresentMonotonicMillis = monotonicNow
        }
    }
    fun background(now: Long, monotonicNow: Long = now) {
        if (foreground) heartbeat(now, monotonicNow)
        foreground = false
    }
    fun foreground(now: Long, monotonicNow: Long = now): Boolean {
        foreground = true
        if (!recent(now, monotonicNow)) disconnect() else heartbeat(now, monotonicNow)
        return mayConnect
    }
    fun restore(snapshot: Snapshot?, now: Long, monotonicNow: Long = now): Boolean {
        disconnect()
        foreground = false
        if (snapshot == null || !snapshot.endpoint.valid || !(isRecent(snapshot.lastPresentMillis, now) && isRecent(snapshot.lastPresentMonotonicMillis, monotonicNow))) return false
        endpoint = snapshot.endpoint
        lastPresentMillis = snapshot.lastPresentMillis
        lastPresentMonotonicMillis = snapshot.lastPresentMonotonicMillis
        return true
    }
    fun snapshot(): Snapshot? = endpoint?.let { Snapshot(it, lastPresentMillis ?: return null, lastPresentMonotonicMillis ?: return null) }
    fun ready() { failures = 0 }
    fun retryDelayMillis(): Long {
        val delay = RETRY_DELAYS[failures.coerceAtMost(RETRY_DELAYS.lastIndex)]
        failures = (failures + 1).coerceAtMost(RETRY_DELAYS.lastIndex)
        return delay
    }
    private fun recent(now: Long, monotonicNow: Long) =
        lastPresentMillis?.let { isRecent(it, now) } == true &&
            lastPresentMonotonicMillis?.let { isRecent(it, monotonicNow) } == true
    companion object {
        const val AWAY_LIMIT_MILLIS = 300_000L
        private val RETRY_DELAYS = longArrayOf(500, 1_000, 2_000, 5_000)
        private fun isRecent(saved: Long, now: Long) = saved >= 0 && now >= saved && now - saved < AWAY_LIMIT_MILLIS
    }
}
