package com.example.simplecontroller.model

import com.example.simplecontroller.io.decodeControllerProfile
import com.example.simplecontroller.io.encodeControllerProfile
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.decodeControllerProfileTransfer
import org.junit.Assert.*
import org.junit.Test

class ProfileSwipePersistenceTest {
    @Test fun historicalProfilesDefaultOffWithoutChangingLegacyFormatRequirements() {
        for (version in 1..10) {
            val text = """{"formatVersion":$version,"homePageId":"page","pages":[{"id":"page","name":"Base","controls":[]}]}"""
            val loaded = decodeControllerProfile(text, "Legacy").profile
            assertEquals(ProfileSwipeMode.OFF, loaded.swipeMode)
            assertEquals(5, loaded.requiredFormatVersion())
        }
    }
    @Test fun eachModeSurvivesProfileAndTransferRoundTrip() {
        for (mode in ProfileSwipeMode.entries) {
            val profile = ControllerProfile(homePageId = "page", pages = listOf(ControllerPage("page", "Base")), swipeMode = mode)
            val restored = decodeControllerProfile(encodeControllerProfile(profile), "Modes").profile
            assertEquals(mode, restored.swipeMode)
            assertEquals(if (mode == ProfileSwipeMode.OFF) 5 else 11, restored.requiredFormatVersion())
            assertEquals(mode, decodeControllerProfileTransfer(encodeControllerProfileTransfer("Modes", profile), "Modes").result.profile.swipeMode)
        }
    }
    @Test fun unknownModeIsRejectedInsteadOfSilentlyChangingTouchBehavior() {
        val text = """{"formatVersion":11,"homePageId":"page","pages":[{"id":"page","name":"Base","controls":[]}],"swipeMode":"FUTURE"}"""
        assertTrue(runCatching { decodeControllerProfile(text, "Future") }.isFailure)
    }
}
