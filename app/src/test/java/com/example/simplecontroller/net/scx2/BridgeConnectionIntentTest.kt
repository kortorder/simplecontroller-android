package com.example.simplecontroller.net.scx2

import org.junit.Assert.*
import org.junit.Test

class BridgeConnectionIntentTest {
    private val endpoint = BridgeConnectionIntent.Endpoint("192.0.2.10", 42736, true)
    @Test fun backgroundCutoffIsStrictAndCannotBeExtendedByRepeatedLifecycleCallbacks() {
        for (away in listOf(299_999L, 300_000L, 300_001L)) {
            val policy = BridgeConnectionIntent()
            assertTrue(policy.connect(endpoint, 1_000))
            policy.background(2_000)
            policy.background(200_000) // onStop cannot restart the away timer
            policy.heartbeat(201_000) // neither may a stale foreground task
            assertFalse(policy.mayConnect)
            assertEquals(away < 300_000, policy.foreground(2_000 + away))
        }
    }
    @Test fun disconnectInvalidatesEveryFutureResumeAndSavedPermission() {
        val policy = BridgeConnectionIntent(); policy.connect(endpoint, 100)
        policy.background(101); policy.disconnect()
        assertFalse(policy.foreground(102)); assertFalse(policy.active); assertNull(policy.snapshot())
        assertFalse(BridgeConnectionIntent().restore(policy.snapshot(), 103))
    }
    @Test fun processRestartRestoresOnlyRecentValidatedEndpointAndNeverInputState() {
        val policy = BridgeConnectionIntent(); policy.connect(endpoint, 10_000, 1_000)
        policy.heartbeat(20_000, 11_000)
        val copy = BridgeConnectionIntent()
        assertTrue(copy.restore(policy.snapshot(), 319_999, 310_999))
        assertEquals(endpoint, copy.endpoint); assertFalse(copy.mayConnect)
        assertFalse(copy.foreground(320_000, 311_000))
        assertFalse(copy.restore(policy.snapshot(), 320_000, 311_000))
    }
    @Test fun wallClockChangesCannotExtendRealAwayTimeAndRebootsFailClosed() {
        val snapshot = BridgeConnectionIntent.Snapshot(endpoint, 1_000_000, 50_000)
        val copy = BridgeConnectionIntent()
        // Six real minutes, with the wall clock set back two minutes, is still expired.
        assertFalse(copy.restore(snapshot, 1_240_000, 410_000))
        assertFalse(copy.restore(snapshot, 999_999, 50_010))
        assertFalse(copy.restore(snapshot, 1_000_010, 100))
        assertFalse(copy.restore(snapshot.copy(lastPresentMillis = -1), 10, 50_010))
    }
    @Test fun invalidEndpointCannotReplaceExistingIntent() {
        val policy = BridgeConnectionIntent(); policy.connect(endpoint, 100)
        for (bad in listOf(endpoint.copy(host = ""), endpoint.copy(host = "bad host"),
            endpoint.copy(host = "\nhost"), endpoint.copy(port = 0), endpoint.copy(port = 65536))) {
            assertFalse(policy.connect(bad, 101)); assertEquals(endpoint, policy.endpoint)
        }
    }
    @Test fun retriesBackOffWithinBoundAndSuccessfulNeutralResetsBackoff() {
        val policy = BridgeConnectionIntent(); policy.connect(endpoint, 100)
        assertEquals(listOf(500L, 1000L, 2000L, 5000L, 5000L, 5000L), List(6) { policy.retryDelayMillis() })
        policy.ready(); assertEquals(500L, policy.retryDelayMillis())
        policy.background(200); assertFalse(policy.mayConnect)
    }
    @Test fun restoredIntentCannotSendUntilForegroundRechecksOriginalDeadline() {
        val snapshot = BridgeConnectionIntent.Snapshot(endpoint, 1000, 1000)
        val policy = BridgeConnectionIntent()
        assertTrue(policy.restore(snapshot, 300_999, 300_999))
        assertTrue(policy.active); assertFalse(policy.mayConnect)
        policy.heartbeat(300_999, 300_999)
        assertFalse(policy.foreground(301_000, 301_000))
        assertFalse(policy.active)
        assertTrue(policy.restore(snapshot, 1001, 1001))
        assertTrue(policy.foreground(1002, 1002))
    }

}
