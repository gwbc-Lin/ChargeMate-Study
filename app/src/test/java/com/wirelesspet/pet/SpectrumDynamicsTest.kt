package com.wirelesspet.pet

import org.junit.Assert.*
import org.junit.Test

class SpectrumDynamicsTest {
    @Test fun onsetRespondsWithinOneFrameAndReleasesQuickly() {
        val dynamics = SpectrumDynamics()
        val pulse = FloatArray(SpectrumBands.COUNT).apply { this[4] = .8f }
        assertTrue(dynamics.update(pulse, 33f)[4] > .95f)
        var last = 1f
        repeat(4) { last = dynamics.update(FloatArray(SpectrumBands.COUNT), 33f)[4] }
        assertTrue(last < .15f)
    }
    @Test fun stableToneDoesNotGenerateRecurringArtificialBeats() {
        val dynamics = SpectrumDynamics()
        val tone = FloatArray(SpectrumBands.COUNT) { .5f }
        val onset = dynamics.update(tone, 33f)[0]
        var sustained = onset
        repeat(20) { sustained = dynamics.update(tone, 33f)[0] }
        assertTrue(onset > sustained + .15f)
        assertEquals(.75f, sustained, .02f)
    }

}
