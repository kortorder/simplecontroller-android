package com.example.simplecontroller.community

import com.example.simplecontroller.io.decodeControllerProfileTransfer
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.validateControllerProfileForSave
import com.example.simplecontroller.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Uses the real Android transfer decoder, not the Python converter's schema assumptions. */
class GtaPrepImportTest {
    private fun imported() = decodeControllerProfileTransfer(
        listOf(File("profiles/GTA-6-Prep.simplecontroller-profile.json"),
               File("../profiles/GTA-6-Prep.simplecontroller-profile.json"))
            .first { it.isFile }.readText(), "GTA 6 Prep")

    @Test fun addingNewTimingToExistingGtaProfilePreservesControllerGeometryAndStickRepeat() {
        val original = imported().result.profile
        val page = original.pages.first { it.name == "Walk" }
        val target = page.controls.first { it.circularButton && it.type == ControlType.BUTTON &&
            it.pageAction == PageAction.NONE && it.floatingLocalAction == FloatingLocalAction.NONE &&
            !it.buttonAimEnabled && it.payload.startsWith("X360") }
        val modified = target.copy(payload = "LT:0.5(HRD200),RT:0.7(D100)(R200)")
        val updated = original.copy(pages = original.pages.map {
            if (it.id != page.id) it else it.copy(controls = it.controls.map { c ->
                if (c.id == target.id) modified else c
            })
        })
        assertEquals(16, updated.requiredFormatVersion())
        val restored = decodeControllerProfileTransfer(encodeControllerProfileTransfer("GTA updated", updated), "GTA").result
        assertTrue(restored.warnings.isEmpty())
        assertEquals(updated.copy(formatVersion = 16), restored.profile)
    }

    @Test fun acceptedFixtureDecodesWithoutRepairsAndRoundTrips() {
        val loaded = imported()
        assertEquals("GTA 6 Prep", loaded.suggestedName)
        assertTrue(loaded.result.warnings.joinToString(), loaded.result.warnings.isEmpty())
        val profile = loaded.result.profile
        assertEquals(11, profile.formatVersion)
        assertEquals(11, profile.requiredFormatVersion())
        assertEquals(ProfileSwipeMode.OFF, profile.swipeMode)
        assertTrue(profile.safeControlArea)
        assertNull(validateControllerProfileForSave(profile))
        assertEquals(listOf("Walk", "Aim", "Drive", "Extras"), profile.pages.map { it.name })
        assertEquals(50, profile.pages.sumOf { it.controls.size })
        assertEquals(profile, decodeControllerProfileTransfer(
            encodeControllerProfileTransfer("GTA 6 Prep", profile), "fallback").result.profile)
    }

    @Test fun decoderKeepsRepeatShapePressureAndScopedRingValues() {
        val profile = imported().result.profile
        val controls = profile.pages.flatMap { it.controls }
        assertEquals(41, controls.count { it.circularButton })
        assertEquals(2, controls.count { it.type == ControlType.RECENTER })
        assertEquals(5, controls.count { it.buttonAimEnabled && !it.circularButton })
        val walk = profile.pages.first { it.name == "Walk" }
        val move = walk.controls.first { it.name == "Move - stays" }
        assertEquals(200, move.stickPlusRepeatIntervalMilliseconds)
        assertTrue(move.stickPlusMode)
        assertFalse(move.autoCenter)
        assertEquals(0.95f, move.superBoostThreshold)
        assertEquals("X360A", move.upSuperBoostCommand)
        assertEquals("", move.upCommand)
        val pad = walk.controls.first { it.name == "Aim + fire" }
        assertEquals("LT:0.5,RT:0.7", pad.payload)
        assertEquals(ButtonAimOutput.RIGHT_STICK, pad.buttonAimOutput)
        assertEquals(60f, pad.buttonAimStickFullDisplacementPx)
        assertEquals(4f, pad.buttonAimStickDeadzonePx)
        assertTrue(pad.buttonAimAutoCenter)
        val aim = profile.pages.first { it.name == "Aim" }
        val zone = aim.floatingZones.single()
        assertEquals(FloatingZoneKind.RING, zone.kind)
        val fire = zone.ring!!.knobs.single().fire
        assertEquals("LT:0.5", fire.adsPayload)
        assertEquals("RT:0.7", fire.firePayload)
        assertTrue(fire.retainAdsOnLift && fire.retainFireOnLift)
        assertEquals(zone.id, aim.controls.first { it.name == "Stop fire" }.floatingActionTargetId)
    }
}
