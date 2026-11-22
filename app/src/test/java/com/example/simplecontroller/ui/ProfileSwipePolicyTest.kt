package com.example.simplecontroller.ui

import com.example.simplecontroller.model.*
import org.junit.Assert.*
import org.junit.Test

class ProfileSwipePolicyTest {
    private val button = control(ControlType.BUTTON)
    private val stick = control(ControlType.STICK)
    private val pad = control(ControlType.TOUCHPAD)

    @Test fun offKeepsStartingControlOutsideItsBounds() {
        assertEquals(SwipeDecision.MOVE_CURRENT, decide(ProfileSwipeMode.OFF, button, false, button.copy(id = "other")))
        assertFalse(ProfileSwipePolicy.acceptsEntry(button, ProfileSwipeMode.OFF))
    }
    @Test fun buttonsReleaseAcrossGapsAndDisabledEntries() {
        assertEquals(SwipeDecision.RELEASE_CURRENT, decide(ProfileSwipeMode.BUTTONS, button, false, null))
        assertEquals(SwipeDecision.RELEASE_CURRENT, decide(ProfileSwipeMode.BUTTONS, button, false, button.copy(swipeActivate = false)))
    }
    @Test fun buttonsEnterNextEligibleButtonAfterGap() {
        assertEquals(SwipeDecision.TRANSFER, decide(ProfileSwipeMode.BUTTONS, null, false, button))
    }
    @Test fun buttonsModeKeepsSticksAndPadsCapturedOutside() {
        for (current in listOf(stick, pad)) {
            assertEquals(SwipeDecision.MOVE_CURRENT, decide(ProfileSwipeMode.BUTTONS, current, false, button))
        }
        assertEquals(SwipeDecision.RELEASE_CURRENT, decide(ProfileSwipeMode.BUTTONS, button, false, stick))
    }
    @Test fun controlsKeepButtonAndStickOutputAcrossGaps() {
        for (current in listOf(button, stick)) {
            assertEquals(SwipeDecision.KEEP_CURRENT, decide(ProfileSwipeMode.CONTROLS, current, false, null))
        }
    }
    @Test fun currentHitWinsEvenWhenAnotherControlOverlaps() {
        assertEquals(SwipeDecision.MOVE_CURRENT, decide(ProfileSwipeMode.CONTROLS, button, true, stick))
    }
    @Test fun controlsCanTransferAcrossAllOrdinaryKinds() {
        for (destination in listOf(button, stick, pad, control(ControlType.CURVED_STICK), control(ControlType.RADIAL_CURVED_STICK))) {
            assertEquals(SwipeDecision.TRANSFER, decide(ProfileSwipeMode.CONTROLS, button, false, destination))
        }
    }
    @Test fun occupiedDestinationKeepsControlsSourceButReleasesButtonsSource() {
        assertEquals(SwipeDecision.KEEP_CURRENT, ProfileSwipePolicy.decide(ProfileSwipeMode.CONTROLS, button, false, stick, false))
        assertEquals(SwipeDecision.RELEASE_CURRENT, ProfileSwipePolicy.decide(ProfileSwipeMode.BUTTONS, button, false, button, false))
    }
    @Test fun cooldownKeepsControlsSourceWhileButtonsModeDoesNotUseCooldown() {
        assertEquals(SwipeDecision.KEEP_CURRENT, ProfileSwipePolicy.decide(ProfileSwipeMode.CONTROLS, button, false, stick, true, false))
        assertEquals(SwipeDecision.TRANSFER, ProfileSwipePolicy.decide(ProfileSwipeMode.BUTTONS, button, false, button, true, false))
    }
    @Test fun padTracksGapBlockedDestinationAndCooldownRawPath() {
        for (destination in listOf(null, button.copy(swipeActivate = false))) {
            assertEquals(SwipeDecision.MOVE_CURRENT, decide(ProfileSwipeMode.CONTROLS, pad, false, destination))
        }
        assertEquals(SwipeDecision.MOVE_CURRENT, ProfileSwipePolicy.decide(ProfileSwipeMode.CONTROLS, pad, false, button, false))
        assertEquals(SwipeDecision.MOVE_CURRENT, ProfileSwipePolicy.decide(ProfileSwipeMode.CONTROLS, pad, false, button, true, false))
    }
    @Test fun localSafetyAndModeActionsNeverActivateBySliding() {
        val actions = listOf("RELEASE_ALL", "SCROLL_MODE_TOGGLE", "CAMERA_FOLLOW", "CAMERA_FOLLOW:1", "X360A, RELEASE_ALL")
        val controls = actions.map { button.copy(payload = it) } + listOf(
            control(ControlType.RECENTER), control(ControlType.TOUCH_AIM),
            button.copy(pageAction = PageAction.HOME),
            button.copy(floatingLocalAction = FloatingLocalAction.FINISH, floatingActionTargetId = "zone")
        )
        controls.forEach { control ->
            assertFalse(ProfileSwipePolicy.acceptsEntry(control, ProfileSwipeMode.BUTTONS))
            assertFalse(ProfileSwipePolicy.acceptsEntry(control, ProfileSwipeMode.CONTROLS))
            assertEquals(SwipeDecision.MOVE_CURRENT, decide(ProfileSwipeMode.CONTROLS, control, false, button))
        }
    }
    @Test fun advancedButtonSurfacesKeepTheirDirectGesture() {
        for (control in listOf(button.copy(buttonAimEnabled = true), button.copy(buttonDirectional = ButtonDirectionalSettings(enabled = true)))) {
            assertFalse(ProfileSwipePolicy.acceptsEntry(control, ProfileSwipeMode.CONTROLS))
            assertEquals(SwipeDecision.MOVE_CURRENT, decide(ProfileSwipeMode.BUTTONS, control, false, button))
        }
    }
    @Test fun entryOptOutDoesNotTrapAnAlreadyDirectlyTouchedButton() {
        assertEquals(SwipeDecision.TRANSFER, decide(ProfileSwipeMode.CONTROLS, button.copy(swipeActivate = false), false, button))
    }

    private fun decide(mode: ProfileSwipeMode, current: Control?, contains: Boolean, candidate: Control?) =
        ProfileSwipePolicy.decide(mode, current, contains, candidate)
    private fun control(type: ControlType) = Control(type.name, type, 0f, 0f, 100f, 100f, "X360A")
}
