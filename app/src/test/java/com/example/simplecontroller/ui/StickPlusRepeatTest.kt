package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class StickPlusRepeatTest {
    @Test fun startsImmediatelyAndHasRealOffPhase() {
        val f = Fixture(); f.stage("X360A")
        assertEquals(listOf("X360A_HOLD"), f.events)
        f.scheduler.advanceTo(49); assertEquals(1, f.events.size)
        f.scheduler.advanceTo(50); assertEquals("X360A_RELEASE", f.events.last())
        f.scheduler.advanceTo(199); assertEquals(2, f.events.size)
        f.scheduler.advanceTo(200); assertEquals("X360A_HOLD", f.events.last())
        f.controller.clear(); assertEquals("X360A_RELEASE", f.events.last())
    }

    @Test fun sameStageMovementDoesNotRestartClock() {
        val f = Fixture(); f.stage("X360A")
        f.scheduler.advanceTo(40); f.stage("X360A")
        f.scheduler.advanceTo(50); assertEquals(listOf("X360A_HOLD", "X360A_RELEASE"), f.events)
    }

    @Test fun lateCallbackReleasesOnceThenWaitsFullOffInterval() {
        val f = Fixture(); f.stage("X360A")
        f.scheduler.wakeLate(1_000)
        assertEquals(listOf("X360A_HOLD", "X360A_RELEASE"), f.events)
        f.scheduler.advanceTo(1_149); assertEquals(2, f.events.size)
        f.scheduler.advanceTo(1_150); assertEquals("X360A_HOLD", f.events.last())
        f.scheduler.wakeLate(5_000); assertEquals("X360A_RELEASE", f.events.last())
        assertEquals(4, f.events.size)
    }

    @Test fun leavingStageDuringOffCancelsFuturePressAndStaleCallback() {
        val f = Fixture(); f.stage("X360A"); f.scheduler.advanceTo(50)
        val stale = f.scheduler.pending.single().action
        f.controller.reconcile(emptyMap()); stale()
        f.scheduler.advanceTo(3_000)
        assertEquals(listOf("X360A_HOLD", "X360A_RELEASE"), f.events)
        assertFalse(f.controller.hasRuntimeState())
    }

    @Test fun clearingHeldStageCancelsFutureReleaseAndPress() {
        val f = Fixture(); f.stage("X360A")
        val stale = f.scheduler.pending.single().action
        f.controller.clear(); stale(); f.scheduler.advanceTo(3_000)
        assertEquals(listOf("X360A_HOLD", "X360A_RELEASE"), f.events)
    }

    @Test fun retainedStageContinuesUntilExplicitRecenterCleanup() {
        val f = Fixture(); f.stage("X360A")
        // A normal lift with auto-center disabled leaves the same selected stage.
        f.stage("X360A"); f.scheduler.advanceTo(450)
        assertEquals(6, f.events.size)
        f.controller.clear(); f.scheduler.advanceTo(5_000)
        assertEquals(6, f.events.size)
    }

    @Test fun identicalPayloadInNewStageStartsFreshPhase() {
        val f = Fixture(); f.stage("X360A", "BOOST"); f.scheduler.advanceTo(60)
        f.stage("X360A", "SUPER")
        assertEquals("X360A_HOLD", f.events.last())
        f.scheduler.advanceTo(109); assertEquals(3, f.events.size)
        f.scheduler.advanceTo(110); assertEquals("X360A_RELEASE", f.events.last())
    }

    @Test fun stageReentryStartsImmediatelyAndOldCallbackCannotAffectIt() {
        val f = Fixture(); f.stage("X360A")
        val stale = f.scheduler.pending.single().action
        f.controller.clear(); f.stage("X360A"); stale()
        assertEquals(listOf("X360A_HOLD", "X360A_RELEASE", "X360A_HOLD"), f.events)
        f.scheduler.advanceTo(50); assertEquals("X360A_RELEASE", f.events.last())
    }

    @Test fun independentHeldButtonMasksRepeatWithoutBeingReleased() {
        val f = Fixture()
        val independent = (f.executor.activate(ButtonAimPayloadOwner.BASE, "X360A") as ButtonAimPayloadActivationResult.Activated).lease
        f.stage("X360A"); f.scheduler.advanceTo(400); f.controller.clear()
        assertEquals(listOf("X360A_HOLD"), f.events)
        f.executor.release(independent)
        assertEquals(listOf("X360A_HOLD", "X360A_RELEASE"), f.events)
    }

    @Test fun twoAxesMaintainSeparateRepeatOwners() {
        val f = Fixture()
        f.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "X360A", DirectionalCommandSlot.RIGHT to "X360B"), 200)
        f.scheduler.advanceTo(50)
        f.controller.reconcile(mapOf(DirectionalCommandSlot.RIGHT to "X360B"), 200)
        f.scheduler.advanceTo(200)
        assertEquals(listOf("X360A_HOLD", "X360B_HOLD", "X360A_RELEASE", "X360B_RELEASE", "X360B_HOLD"), f.events)
    }

    @Test fun onlyDigitalTokensRepeatAndOtherClaimsStaySustained() {
        val f = Fixture(); f.stage("X360A,RT:0.7,W,MOUSE_LEFT,LS:R50")
        val start = f.events.toList()
        assertTrue(start.containsAll(listOf("RT:0.7", "KEY:W:true", "MOUSE_LEFT_DOWN", "STICK:LS:0.5:0.0", "X360A_HOLD")))
        f.scheduler.advanceTo(200)
        assertEquals(listOf("X360A_RELEASE", "X360A_HOLD"), f.events.drop(start.size))
        f.controller.clear()
        assertTrue(f.events.containsAll(listOf("RT:0.0", "KEY:W:false", "MOUSE_LEFT_UP", "STICK:LS:0.0:0.0")))
    }

    @Test fun triggerPulseRemainsOneShotRatherThanBeingRepeated() {
        val f = Fixture(); f.stage("X360A,RT:0.7P0.1")
        f.scheduler.advanceTo(600)
        assertEquals(1, f.events.count { it == "RT:0.7" })
        assertEquals(1, f.events.count { it == "RT:0.0" })
    }

    @Test fun defaultOffAndWasdCallPathHoldInsteadOfRepeat() {
        val f = Fixture()
        f.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "X360A"))
        f.scheduler.advanceTo(10_000)
        assertEquals(listOf("X360A_HOLD"), f.events)
        assertTrue(f.scheduler.pending.isEmpty())
        f.controller.clear(); assertEquals("X360A_RELEASE", f.events.last())
    }

    @Test fun sixteenMillisecondIntervalHasEightMillisecondPhases() {
        val f = Fixture(); f.stage("X360A", interval = 16)
        f.scheduler.advanceTo(7); assertEquals(1, f.events.size)
        f.scheduler.advanceTo(8); assertEquals("X360A_RELEASE", f.events.last())
        f.scheduler.advanceTo(16); assertEquals("X360A_HOLD", f.events.last())
    }

    @Test fun longestIntervalCapsPressAtFiftyMilliseconds() {
        val f = Fixture(); f.stage("X360A", interval = 1_000)
        f.scheduler.advanceTo(50); assertEquals("X360A_RELEASE", f.events.last())
        f.scheduler.advanceTo(999); assertEquals(2, f.events.size)
        f.scheduler.advanceTo(1_000); assertEquals("X360A_HOLD", f.events.last())
    }

    @Test fun invalidIntervalFailsClosedWithoutOutputs() {
        for (interval in listOf(-1, 0, 15, 1_001, Int.MAX_VALUE)) {
            val f = Fixture(); f.stage("X360A", interval = interval)
            assertTrue(f.events.isEmpty()); assertTrue(f.scheduler.pending.isEmpty())
        }
    }

    @Test fun malformedMixedPayloadDoesNotPartiallyActivateDigitalRepeat() {
        val f = Fixture(); f.stage("X360A,RT:3.0")
        assertTrue(f.events.isEmpty()); assertTrue(f.scheduler.pending.isEmpty())
    }

    @Test fun localActionsRunOnceNotOnEveryRepeatPhase() {
        val f = Fixture(); f.stage("X360A,CAMERA_FOLLOW")
        f.scheduler.advanceTo(1_000)
        assertEquals(1, f.events.count { it == "CAMERA:CAMERA_FOLLOW" })
    }

    @Test fun releaseAllStageStopsOldRepeatWithoutReactivation() {
        val f = Fixture(); f.stage("X360A")
        f.stage("RELEASE_ALL"); f.scheduler.advanceTo(1_000)
        assertEquals(1, f.events.count { it == "RELEASE_ALL" })
        assertEquals(1, f.events.count { it == "X360A_HOLD" })
    }

    @Test fun supportedStickClickAliasesAndDigitalTriggersRepeat() {
        val f = Fixture(); f.stage("X360LS,X360RSC,X360LT,X360RIGHT_HOLD")
        f.scheduler.advanceTo(50)
        assertTrue(f.events.containsAll(listOf("X360LS_RELEASE", "X360RSC_RELEASE", "X360LT_RELEASE", "X360RIGHT_RELEASE")))
    }

    @Test fun synchronousCleanupDuringFirstPressCannotLeaveARepeatingOrphan() {
        val f = Fixture()
        f.onCommand = { if (it == "X360A_HOLD") f.controller.clear() }
        f.stage("X360A")
        f.scheduler.advanceTo(2_000)
        assertEquals(listOf("X360A_HOLD", "X360A_RELEASE"), f.events)
        assertFalse(f.controller.hasRuntimeState())
    }

    @Test fun synchronousCleanupDuringStageReplacementReleasesNewOwnerToo() {
        val f = Fixture(); f.stage("X360A", "BOOST")
        f.onCommand = { if (it == "X360A_RELEASE") f.controller.clear() }
        f.stage("X360B", "SUPER")
        f.scheduler.advanceTo(2_000)
        assertEquals(listOf("X360A_HOLD", "X360B_HOLD", "X360A_RELEASE", "X360B_RELEASE"), f.events)
        assertFalse(f.controller.hasRuntimeState())
    }

    private class Fixture {
        val scheduler = FakeScheduler()
        val events = mutableListOf<String>()
        var onCommand: (String) -> Unit = {}
        val executor = ButtonAimPayloadExecutor(object : ButtonAimPayloadTransport {
            override fun sendCommand(command: String) { events += command; onCommand(command) }
            override fun sendKey(key: String, pressed: Boolean) { events += "KEY:$key:$pressed" }
            override fun sendStickMacro(stickName: String, x: Float, y: Float) { events += "STICK:$stickName:$x:$y" }
            override fun onCameraFollow(command: String) { events += "CAMERA:$command" }
            override fun onScrollToggle() { events += "SCROLL" }
            override fun onReleaseAll() { events += "RELEASE_ALL" }
        }, scheduler)
        val controller = DirectionalCommandStateController(executor, scheduler)
        fun stage(payload: String, key: String = "SUPER", interval: Int = 200) = controller.reconcile(
            mapOf(DirectionalCommandSlot.UP to payload), interval, mapOf(DirectionalCommandSlot.UP to key)
        )
    }

    private class FakeScheduler : ButtonAimPayloadScheduler {
        data class Item(val due: Long, val action: () -> Unit, var canceled: Boolean = false)
        var now = 0L
        private val tasks = mutableListOf<Item>()
        val pending get() = tasks.filterNot { it.canceled }
        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val item = Item(now + delayMs, task); tasks += item
            return ButtonAimScheduledTask { item.canceled = true }
        }
        fun advanceTo(target: Long) {
            while (true) {
                val next = pending.filter { it.due <= target }.minByOrNull { it.due } ?: break
                tasks.remove(next); now = next.due; next.action()
            }
            now = target
        }
        fun wakeLate(target: Long) {
            now = target
            val overdue = pending.filter { it.due <= target }.sortedBy { it.due }
            overdue.forEach { tasks.remove(it); if (!it.canceled) it.action() }
        }
    }
}
