package com.example.simplecontroller.model

import com.example.simplecontroller.io.decodeControllerProfile
import com.example.simplecontroller.io.decodeControllerProfileTransfer
import com.example.simplecontroller.io.encodeControllerProfile
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.deepCopyControls
import com.example.simplecontroller.io.validateControllerProfileForSave
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class StickPlusRepeatProfileTest {
    @Test fun oldProfilesDefaultToRepeatOffAndOriginalShape() {
        val json = """{"formatVersion":5,"homePageId":"page","pages":[{"id":"page","name":"Base","controls":[{"id":"stick","type":"STICK","x":0.0,"y":0.0,"w":100.0,"h":100.0,"payload":"LS","stickPlusMode":true}]}]}"""
        val profile = decodeControllerProfile(json, "Old").profile
        val control = profile.pages.single().controls.single()
        assertNull(control.stickPlusRepeatIntervalMilliseconds)
        assertFalse(control.circularButton)
        assertEquals(5, profile.requiredFormatVersion())
    }

    @Test fun repeatRequiresVersionElevenEvenWhenModeTemporarilyOff() {
        val control = stick().copy(stickPlusRepeatIntervalMilliseconds = 200, stickPlusMode = false)
        val text = encodeControllerProfile(profile(control))
        assertEquals("11", Json.parseToJsonElement(text).jsonObject.getValue("formatVersion").jsonPrimitive.content)
        val restored = decodeControllerProfile(text, "Repeat").profile.pages.single().controls.single()
        assertEquals(200, restored.stickPlusRepeatIntervalMilliseconds)
        assertFalse(restored.stickPlusMode)
    }

    @Test fun circularButtonRoundTripRequiresVersionEleven() {
        val circle = stick().copy(type = ControlType.BUTTON, payload = "X360A", circularButton = true)
        val original = profile(circle)
        assertEquals(11, original.requiredFormatVersion())
        val restored = decodeControllerProfileTransfer(encodeControllerProfileTransfer("Circle", original), "Circle")
        assertTrue(restored.result.profile.pages.single().controls.single().circularButton)
    }

    @Test fun newOptionsSurviveDeepCopyAndTransfer() {
        val source = stick().copy(stickPlusRepeatIntervalMilliseconds = 200)
        val copied = deepCopyControls(listOf(source)).single()
        assertEquals(source, copied)
        assertNotSame(source, copied)
        val restored = decodeControllerProfileTransfer(encodeControllerProfileTransfer("Repeat", profile(source)), "Repeat")
        assertEquals(200, restored.result.profile.pages.single().controls.single().stickPlusRepeatIntervalMilliseconds)
    }

    @Test fun invalidIntervalsAndControlTypesAreRejectedOnSaveAndLoad() {
        for (value in listOf(-1, 0, 15, 1_001, Int.MAX_VALUE)) {
            val invalid = profile(stick().copy(stickPlusRepeatIntervalMilliseconds = value))
            assertNotNull(validateControllerProfileForSave(invalid))
            val valid = encodeControllerProfile(profile(stick().copy(stickPlusRepeatIntervalMilliseconds = 200)))
            assertTrue(runCatching { decodeControllerProfile(valid.replace("\"stickPlusRepeatIntervalMilliseconds\": 200", "\"stickPlusRepeatIntervalMilliseconds\": $value"), "Bad") }.isFailure)
        }
        assertNotNull(validateControllerProfileForSave(profile(stick().copy(type = ControlType.BUTTON, stickPlusRepeatIntervalMilliseconds = 200))))
        assertNotNull(validateControllerProfileForSave(profile(stick().copy(circularButton = true))))
    }

    @Test fun validIntervalBoundsRoundTrip() {
        for (value in listOf(16, 200, 1_000)) {
            val restored = decodeControllerProfile(encodeControllerProfile(profile(stick().copy(stickPlusRepeatIntervalMilliseconds = value))), "Bounds")
            assertEquals(value, restored.profile.pages.single().controls.single().stickPlusRepeatIntervalMilliseconds)
        }
    }

    @Test fun directionalPresetsDoNotCopyOrErasePerControlRepeat() {
        val source = stick().copy(stickPlusRepeatIntervalMilliseconds = 400)
        val preset = source.captureStickDirectionalProfile("preset", "Commands", 1)!!
        val target = stick().copy(stickPlusRepeatIntervalMilliseconds = 200)
        assertTrue(preset.applyCommandsTo(target))
        assertEquals(200, target.stickPlusRepeatIntervalMilliseconds)
        val noRepeat = stick()
        assertTrue(preset.applyAndSwitchModeTo(noRepeat))
        assertNull(noRepeat.stickPlusRepeatIntervalMilliseconds)
    }

    @Test fun stageThresholdIsStrictAndOuterIdentitySurvivesSamePayload() {
        val control = stick().copy(boostThreshold = .95f, superBoostThreshold = 1f,
            upCommand = "", upBoostCommand = "X360A", upSuperBoostCommand = "X360A")
        assertEquals("", control.directionalPayload(StickDirection.UP, .95f))
        assertEquals("X360A", control.directionalPayload(StickDirection.UP, .951f))
        assertEquals(DirectionalStickStage.BOOST, control.directionalStage(StickDirection.UP, 1f))
        control.extendedBoost = ExtendedBoostSettings(enabled = true, mode = ExtendedBoostMode.ADD_OUTER_STAGE)
        assertEquals(DirectionalStickStage.OUTER, control.directionalStage(StickDirection.UP, 1.251f))
        assertEquals("X360A", control.directionalPayload(StickDirection.UP, 1.251f))
    }

    private fun stick() = Control("stick", ControlType.STICK, 10f, 20f, 100f, 100f, "LS", stickPlusMode = true)
    private fun profile(control: Control) = ControllerProfile(homePageId = "page", pages = listOf(ControllerPage("page", "Base", listOf(control))))
}
