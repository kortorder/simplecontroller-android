package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class HelpOnboardingPolicyTest {
    @Test fun freshInstallGetsTourWithoutChangingExistingProfileRules() {
        assertTrue(HelpOnboardingPolicy.shouldOffer(true, false, false))
        assertFalse(HelpOnboardingPolicy.shouldOffer(false, false, false))
    }
    @Test fun interruptedFirstTourResumesEvenAfterDefaultProfileWasSaved() {
        assertTrue(HelpOnboardingPolicy.shouldOffer(false, true, false))
    }
    @Test fun completedOrSkippedTourNeverAutomaticallyReopens() {
        for (fresh in listOf(false, true)) for (started in listOf(false, true))
            assertFalse(HelpOnboardingPolicy.shouldOffer(fresh, started, true))
    }
    @Test fun invalidSavedPageIsBounded() {
        assertEquals(0, HelpOnboardingPolicy.page(-100))
        assertEquals(7, HelpOnboardingPolicy.page(Int.MAX_VALUE))
    }
    @Test fun navigationStopsAtBothEnds() {
        assertEquals(0, HelpOnboardingPolicy.previous(0))
        assertEquals(7, HelpOnboardingPolicy.next(7))
        assertEquals(1, HelpOnboardingPolicy.next(0))
        assertEquals(6, HelpOnboardingPolicy.previous(7))
    }
}
