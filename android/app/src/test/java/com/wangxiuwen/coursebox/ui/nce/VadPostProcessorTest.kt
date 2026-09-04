package com.wangxiuwen.coursebox.ui.nce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadPostProcessorTest {
    @Test
    fun `silence separates two speech chunks`() {
        val probabilities = FloatArray(140)
        for (i in 5..20) probabilities[i] = 0.9f
        for (i in 55..80) probabilities[i] = 0.8f

        val segments = VadPostProcessor.toSegments(probabilities, 4_480)

        assertEquals(2, segments.size)
        assertTrue(segments[0].startMs <= 5 * 32L)
        assertTrue(segments[0].endMs < segments[1].startMs)
        assertTrue(segments[1].endMs <= 4_480)
    }

    @Test
    fun `brief hesitation stays inside one chunk`() {
        val probabilities = FloatArray(80) { 0.9f }
        for (i in 25..30) probabilities[i] = 0.1f

        val segments = VadPostProcessor.toSegments(probabilities, 2_560)

        assertEquals(1, segments.size)
    }

    @Test
    fun `long uninterrupted speech is split into repeatable chunks`() {
        val probabilities = FloatArray(800) { 0.9f }
        probabilities[220] = 0.4f
        probabilities[440] = 0.4f
        probabilities[660] = 0.4f

        val segments = VadPostProcessor.toSegments(probabilities, 25_600)

        assertTrue(segments.size >= 3)
        assertTrue(segments.all { it.endMs - it.startMs <= 10_000 })
    }
}
