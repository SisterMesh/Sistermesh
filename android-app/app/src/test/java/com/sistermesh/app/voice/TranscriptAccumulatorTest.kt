package com.sistermesh.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAccumulatorTest {
    @Test
    fun preservesFinalSegmentsAndRemovesRepeatedBoundaryWords() {
        val accumulator = TranscriptAccumulator()

        accumulator.appendFinal("I need two units of O positive")
        val result = accumulator.appendFinal("of O positive at City Hospital")

        assertEquals("I need two units of O positive at City Hospital", result)
    }

    @Test
    fun ignoresRepeatedResultsAndReplacesCumulativeDuplicates() {
        val accumulator = TranscriptAccumulator()

        accumulator.appendFinal("I need blood")
        accumulator.appendFinal("I need blood")
        val result = accumulator.appendFinal("I need blood at the hospital")

        assertEquals("I need blood at the hospital", result)
    }

    @Test
    fun partialTextDoesNotBecomeCommittedUntilFinalized() {
        val accumulator = TranscriptAccumulator()
        accumulator.appendFinal("I need")

        assertEquals("I need blood", accumulator.withPartial("I need blood"))
        assertEquals("I need", accumulator.text)
        assertEquals("I need blood", accumulator.appendFinal("blood"))
    }

    @Test
    fun clearStartsANewVoiceSession() {
        val accumulator = TranscriptAccumulator()
        accumulator.appendFinal("Old request")
        accumulator.clear()

        assertEquals("", accumulator.text)
        assertEquals("New request", accumulator.appendFinal("New request"))
    }
}
