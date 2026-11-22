package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class OrdinaryTriggerControllerTest {
    private class Clock : ButtonAimPayloadScheduler {
        data class Task(val at: Long, val block: () -> Unit, var canceled: Boolean = false)
        var now = 123456L
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val scheduled = Task(now + delayMs, task)
            tasks += scheduled
            return ButtonAimScheduledTask { scheduled.canceled = true }
        }
        fun advance(ms: Long) {
            now += ms
            val due = tasks.filter { !it.canceled && it.at <= now }
            tasks.removeAll(due.toSet())
            due.forEach { it.block() }
        }
    }
    private class Fixture {
        val clock = Clock()
        val output = mutableMapOf("LT" to 0f, "RT" to 0f)
        val edges = mutableListOf<Pair<String, Float>>()
        val runtime = OrdinaryTriggerController(clock, { channel, value -> output[channel] = value; edges += channel to value })
        fun begin(repeat: Boolean = false) = runtime.begin(repeat)
        fun apply(command: String) = runtime.apply(OrdinaryTriggerCommand.parse(command)!!)
        fun pressure(channel: String, expected: Float) = assertEquals(expected, output[channel]!!, 0f)
    }

    @Test fun normalLiftKeepsStartedPulseUntilOwnDeadline() {
        val f = Fixture(); f.begin(); f.apply("RT:0.7P0.3"); f.runtime.end()
        f.clock.advance(299); f.pressure("RT", .7f)
        f.clock.advance(1); f.pressure("RT", 0f); assertFalse(f.runtime.hasWork)
    }

    @Test fun oldPulseCannotReleaseNewerContinuousPressure() {
        val f = Fixture(); f.begin(); f.apply("RT:1P0.3"); f.runtime.end()
        f.clock.advance(100); f.begin(); f.apply("RT:0.4")
        f.pressure("RT", 1f)
        f.clock.advance(200); f.pressure("RT", .4f)
        f.runtime.end(); f.pressure("RT", 0f)
    }

    @Test fun higherNewPulseEndsRestoringLowerDetachedOwner() {
        val f = Fixture(); f.begin(); f.apply("LT:0.3P1"); f.runtime.end()
        f.clock.advance(100); f.begin(); f.apply("LT:0.9P0.2"); f.runtime.end()
        f.pressure("LT", .9f); f.clock.advance(200); f.pressure("LT", .3f)
        f.clock.advance(700); f.pressure("LT", 0f)
    }

    @Test fun latestSameRunPressureReplacesPulseAndStaleCallbackIsFenced() {
        val f = Fixture(); f.begin(); f.apply("RT:1P1")
        val old = f.clock.tasks.single()
        f.apply("RT:0.2"); old.block()
        f.pressure("RT", .2f); assertTrue(old.canceled)
        f.apply("RT:0"); f.pressure("RT", 0f)
    }

    @Test fun latestSameRunPulseReplacesContinuousWithoutRestoringIt() {
        val f = Fixture(); f.begin(); f.apply("LT:0.8"); f.apply("LT:0.2P0.1")
        f.pressure("LT", .2f); f.clock.advance(100); f.pressure("LT", 0f)
        assertFalse(f.runtime.hasWork)
    }

    @Test fun explicitZeroOnlyClearsCurrentRun() {
        val f = Fixture(); f.begin(); f.apply("RT:0.3P1"); f.runtime.end()
        f.begin(); f.apply("RT:1P1"); f.apply("RT:0")
        f.pressure("RT", .3f); f.clock.advance(1000); f.pressure("RT", 0f)
    }

    @Test fun leftAndRightHaveIndependentPulseDeadlines() {
        val f = Fixture(); f.begin(); f.apply("LT:0.5P0.2"); f.apply("RT:0.7P0.4"); f.runtime.end()
        f.clock.advance(200); f.pressure("LT", 0f); f.pressure("RT", .7f)
        f.clock.advance(200); f.pressure("RT", 0f)
    }

    @Test fun normalLiftReleasesContinuousAlongsideDetachedPulse() {
        val f = Fixture(); f.begin(); f.apply("LT:0.5"); f.apply("RT:0.7P0.2")
        f.runtime.end(); f.pressure("LT", 0f); f.pressure("RT", .7f)
        f.clock.advance(200); f.pressure("RT", 0f)
    }

    @Test fun cancelAllInvalidatesEveryDetachedAndRepeatingCallback() {
        val f = Fixture(); f.begin(); f.apply("LT:0.5P1"); f.runtime.end()
        f.begin(true); f.apply("RT:0.7P0.3")
        val stale = f.clock.tasks.toList(); f.runtime.cancelAll()
        stale.forEach { it.block() }
        f.pressure("LT", 0f); f.pressure("RT", 0f); assertFalse(f.runtime.hasWork)
        assertTrue(f.clock.tasks.all { it.canceled })
    }

    @Test fun newActivationSurvivesOldCanceledCallback() {
        val f = Fixture(); f.begin(true); f.apply("RT:0.7P0.3")
        val stale = f.clock.tasks.single(); f.runtime.cancelAll()
        f.begin(); f.apply("RT:0.5"); stale.block(); f.pressure("RT", .5f)
    }

    @Test fun repeatUsesFullCycleHalfOnHalfOffAndLateDeliveryDoesNotBurst() {
        val f = Fixture(); f.begin(true); f.apply("RT:0.7P0.3")
        f.clock.advance(149); f.pressure("RT", .7f)
        f.clock.advance(1); f.pressure("RT", 0f)
        f.clock.advance(150); f.pressure("RT", .7f)
        val priorEdges = f.edges.size
        f.clock.advance(10000); f.pressure("RT", 0f); assertEquals(priorEdges + 1, f.edges.size)
        f.clock.advance(149); f.pressure("RT", 0f)
        f.clock.advance(1); f.pressure("RT", .7f)
    }

    @Test fun repeatOffGapPreservesIndependentDetachedPulse() {
        val f = Fixture(); f.begin(); f.apply("RT:0.3P1"); f.runtime.end()
        f.begin(true); f.apply("RT:0.9P0.2")
        f.clock.advance(100); f.pressure("RT", .3f)
        f.runtime.end(); f.pressure("RT", .3f)
        f.clock.advance(900); f.pressure("RT", 0f)
    }

    @Test fun synchronousOutputCancellationCannotScheduleLateWork() {
        val clock = Clock(); lateinit var runtime: OrdinaryTriggerController
        runtime = OrdinaryTriggerController(clock, { _, value -> if (value > 0) runtime.cancelAll() })
        runtime.begin(true); runtime.apply(OrdinaryTriggerCommand.parse("RT:0.5P0.2")!!)
        assertFalse(runtime.hasWork); assertTrue(clock.tasks.isEmpty())
    }

    @Test fun invalidTriggerValuesRejectWholeProgramBeforeAnyCommands() {
        listOf("RT:1.1", "LT:-0.1", "RT:NaN", "LT:0.4P0", "RT:0.5P61", "LT:0.3P.2", "RT:0.3P1.2.3").forEach {
            assertThrows(IllegalArgumentException::class.java) { OrdinaryPayloadPlan.parse("X360A,$it") }
        }
        assertEquals(10L, OrdinaryTriggerCommand.parse("rt:0.1p0.01")!!.durationMs)
        assertEquals(60000L, OrdinaryTriggerCommand.parse("LT:1P60")!!.durationMs)
    }

    @Test fun boundedDetachedWorkFailsNeutralAndStillAcceptsNewActivation() {
        val f = Fixture()
        repeat(257) { f.begin(); f.apply("RT:0.3P60"); f.runtime.end() }
        assertEquals(1, f.clock.tasks.count { !it.canceled })
        f.runtime.cancelAll(); f.pressure("RT", 0f)
    }
}
