package com.example.simplecontroller.net

import org.junit.Assert.*
import org.junit.Test

class PhoneReceiverLeaseTest {
    @Test fun inputWaitsForInitialLeaseAndOldReceiverIsExplicitFallback() {
        val lease = PhoneReceiverLease()
        assertFalse(lease.inputReady)
        val token = lease.begin()
        assertTrue(lease.preparing)
        assertEquals(PhoneReceiverLease.Result.LEGACY_RECEIVER, lease.complete(token, false))
        assertTrue(lease.inputReady)
        assertNull(lease.renew())
    }
    @Test fun confirmedLeaseRenewsOnceAndLossNeverFallsBack() {
        val lease = PhoneReceiverLease()
        val token = lease.begin()
        assertEquals(PhoneReceiverLease.Result.CONFIRMED, lease.complete(token, true))
        assertTrue(lease.inputReady)
        assertEquals(token, lease.renew())
        assertNull(lease.renew())
        assertEquals(PhoneReceiverLease.Result.LOST, lease.complete(token, false))
        assertFalse(lease.inputReady)
        assertEquals(PhoneReceiverLease.Phase.FAILED, lease.phase)
    }
    @Test fun lifecycleInvalidationRejectsOldAcknowledgements() {
        val lease = PhoneReceiverLease()
        val old = lease.begin()
        lease.invalidate()
        assertEquals(PhoneReceiverLease.Result.IGNORED, lease.complete(old, true))
        val fresh = lease.begin()
        assertNotEquals(old, fresh)
        assertEquals(PhoneReceiverLease.Result.IGNORED, lease.complete(old, false))
        assertEquals(PhoneReceiverLease.Result.CONFIRMED, lease.complete(fresh, true))
        assertEquals(PhoneReceiverLease.Result.IGNORED, lease.complete(fresh, false))
    }
    @Test fun wireFormatHasExactBoundedLease() {
        assertEquals("PHONE_LEASE_V1:session:42:3000", PhoneReceiverLease.command("session", 42))
        assertEquals("PHONE_LEASE_V1_ACK:session:42:3000", PhoneReceiverLease.acknowledgement("session", 42))
        assertTrue(PhoneReceiverLease.RENEW_MILLISECONDS < PhoneReceiverLease.MILLISECONDS / 2)
    }
}
