package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class OrdinaryPayloadSequenceTest {
    private class Clock : ButtonAimPayloadScheduler {
        data class Task(val due: Long, val body: () -> Unit, var cancelled: Boolean = false)
        var now = 123456L
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val entry = Task(now + delayMs, task)
            tasks += entry
            return ButtonAimScheduledTask { entry.cancelled = true }
        }
        fun deliverNext(at: Long? = null, evenIfCancelled: Boolean = false) {
            val entry = tasks.removeAt(0)
            now = at ?: entry.due
            if (evenIfCancelled || !entry.cancelled) entry.body()
        }
    }

    @Test fun waitsSeparateOrderedCommandsAndAreNeverOutputTokens() {
        val clock = Clock(); val sent = mutableListOf<String>()
        val sequence = OrdinaryPayloadSequence(clock, sent::add)
        sequence.start(OrdinaryPayloadPlan.parse("X360A,WAIT_100,X360B, RT:0.7"))
        assertEquals(listOf("X360A"), sent)
        assertTrue(sequence.isPending)
        assertEquals(123556L, clock.tasks.single().due)
        clock.deliverNext()
        assertEquals(listOf("X360A", "X360B", "RT:0.7"), sent)
        assertEquals(sent, sequence.startedCommands)
        assertFalse(sequence.isPending)
    }

    @Test fun lateCallbackStartsNextWaitFromObservationWithoutCatchupBurst() {
        val clock = Clock(); val sent = mutableListOf<String>()
        val sequence = OrdinaryPayloadSequence(clock, sent::add)
        sequence.start(OrdinaryPayloadPlan.parse("A,WAIT_100,B,WAIT_200,C"))
        clock.deliverNext(at = 130000)
        assertEquals(listOf("A", "B"), sent)
        assertEquals(130200L, clock.tasks.single().due)
        clock.deliverNext()
        assertEquals(listOf("A", "B", "C"), sent)
    }

    @Test fun cancellationFencesAlreadyQueuedCallbackAndRetainsOnlyStartedClaimList() {
        val clock = Clock(); val sent = mutableListOf<String>()
        val sequence = OrdinaryPayloadSequence(clock, sent::add)
        sequence.start(OrdinaryPayloadPlan.parse("LT:0.5,WAIT_100,RT:0.7"))
        sequence.cancelPending()
        clock.deliverNext(evenIfCancelled = true)
        assertEquals(listOf("LT:0.5"), sent)
        assertEquals(sent, sequence.startedCommands)
        assertFalse(sequence.isPending)
    }

    @Test fun replacementCannotReceiveCommandsFromPriorActivation() {
        val clock = Clock(); val sent = mutableListOf<String>()
        val sequence = OrdinaryPayloadSequence(clock, sent::add)
        sequence.start(OrdinaryPayloadPlan.parse("A,WAIT_50,B"))
        sequence.start(OrdinaryPayloadPlan.parse("X,WAIT_100,Y"))
        clock.deliverNext(evenIfCancelled = true)
        assertEquals(listOf("A", "X"), sent)
        clock.deliverNext()
        assertEquals(listOf("A", "X", "Y"), sent)
        assertEquals(listOf("X", "Y"), sequence.startedCommands)
    }

    @Test fun synchronousReleaseAllDuringEmissionStopsRemainingCommands() {
        val clock = Clock(); val sent = mutableListOf<String>()
        lateinit var sequence: OrdinaryPayloadSequence
        sequence = OrdinaryPayloadSequence(clock, { command ->
            sent += command
            if (command == "RELEASE_ALL") sequence.cancelPending()
        })
        sequence.start(OrdinaryPayloadPlan.parse("A,RELEASE_ALL,B,WAIT_10,X"))
        assertEquals(listOf("A", "RELEASE_ALL"), sent)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun synchronousCancellationAtWaitNotificationDoesNotEnqueueNewWork() {
        val clock = Clock(); val sent = mutableListOf<String>()
        lateinit var sequence: OrdinaryPayloadSequence
        sequence = OrdinaryPayloadSequence(clock, sent::add, changed = {
            if (sequence.isPending) sequence.cancelPending()
        })
        sequence.start(OrdinaryPayloadPlan.parse("A,WAIT_10,B"))
        assertEquals(listOf("A"), sent)
        assertFalse(sequence.isPending)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun fullProgramValidationRejectsBadWaitBeforeAnyActivation() {
        for (payload in listOf("A,WAIT_bad,B", "WAIT_-1", "WAIT_60001", "WAIT_9999999999999999999999999",
                               "WAIT_60000,WAIT_60000,WAIT_1")) {
            val clock = Clock(); val sent = mutableListOf<String>()
            val sequence = OrdinaryPayloadSequence(clock, sent::add)
            try { sequence.start(OrdinaryPayloadPlan.parse(payload)); fail(payload) }
            catch (_: IllegalArgumentException) { }
            assertTrue(sent.isEmpty())
            assertTrue(clock.tasks.isEmpty())
        }
    }

    @Test fun byteAndTokenBoundsRejectBeforeExecution() {
        for (payload in listOf("é".repeat(4097), List(65) { "A" }.joinToString(","))) {
            try { OrdinaryPayloadPlan.parse(payload); fail("Expected bounded rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(64, OrdinaryPayloadPlan.parse(List(64) { "A" }.joinToString(",")).steps.size)
    }

    @Test fun zeroWaitsAreBoundedImmediateAndCaseInsensitive() {
        val clock = Clock(); val sent = mutableListOf<String>()
        val sequence = OrdinaryPayloadSequence(clock, sent::add)
        sequence.start(OrdinaryPayloadPlan.parse("A,wait_0,B,WAIT_0,C"))
        assertEquals(listOf("A", "B", "C"), sent)
        assertTrue(clock.tasks.isEmpty())
        assertFalse(sequence.isPending)
    }

    @Test fun explicitPressureAndPulseValuesArePreservedAndClassifiedAsTimed() {
        val clock = Clock(); val sent = mutableListOf<String>()
        val sequence = OrdinaryPayloadSequence(clock, sent::add)
        val plan = OrdinaryPayloadPlan.parse("LT:0.1,RT:0.7P0.3,WAIT_200,X360A")
        assertTrue(plan.hasWaitOrPulse)
        sequence.start(plan)
        assertEquals(listOf("LT:0.1", "RT:0.7P0.3"), sent)
        assertTrue(OrdinaryPayloadPlan.isPulse(sent.last()))
        assertFalse(OrdinaryPayloadPlan.isPulse(sent.first()))
        sequence.cancelPending()
        clock.deliverNext(evenIfCancelled = true)
        assertEquals(2, sent.size)
        assertFalse(OrdinaryPayloadPlan.parse("X360A,B").hasWaitOrPulse)
    }
}
