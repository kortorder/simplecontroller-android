package com.example.simplecontroller.net

/** Optional negotiated Windows watchdog. A confirmed lease never falls back after loss. */
internal class PhoneReceiverLease {
    enum class Phase { IDLE, NEGOTIATING, ACTIVE, LEGACY, FAILED }
    enum class Result { IGNORED, CONFIRMED, LEGACY_RECEIVER, LOST }
    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var generation = 0L
        private set
    private var pending = false
    val preparing get() = phase == Phase.NEGOTIATING
    val inputReady get() = phase == Phase.ACTIVE || phase == Phase.LEGACY
    fun begin(): Long { generation++; phase = Phase.NEGOTIATING; pending = true; return generation }
    fun renew(): Long? = if (phase == Phase.ACTIVE && !pending) { pending = true; generation } else null
    fun complete(token: Long, acknowledged: Boolean): Result {
        if (token != generation || !pending) return Result.IGNORED
        pending = false
        if (acknowledged) { phase = Phase.ACTIVE; return Result.CONFIRMED }
        return if (phase == Phase.NEGOTIATING) { phase = Phase.LEGACY; Result.LEGACY_RECEIVER }
        else { phase = Phase.FAILED; Result.LOST }
    }
    fun invalidate() { generation++; pending = false; phase = Phase.IDLE }
    companion object {
        const val MILLISECONDS = 3000
        const val RENEW_MILLISECONDS = 1000L
        fun command(session: String, sequence: Long) = "PHONE_LEASE_V1:$session:$sequence:$MILLISECONDS"
        fun acknowledgement(session: String, sequence: Long) = "PHONE_LEASE_V1_ACK:$session:$sequence:$MILLISECONDS"
    }
}
