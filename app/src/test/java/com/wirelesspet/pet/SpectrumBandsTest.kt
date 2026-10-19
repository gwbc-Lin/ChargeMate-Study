package com.wirelesspet.pet

import org.junit.Assert.*
import org.junit.Test

class SpectrumBandsTest {

    @Test fun displayRowsFollowHeightWithTheSamePitchInBothOrientations() {
        assertEquals(32, SpectrumBands.displayCount(2670, 3f))
        assertEquals(15, SpectrumBands.displayCount(1200, 3f))
        assertEquals(1, SpectrumBands.displayCount(0, 3f))
    }

    @Test fun signedComplexFftProducesBoundedFrequencyBands() {
        val fft = ByteArray(1024)
        fft[40] = -128; fft[41] = 127 // bin 20 ~= 937.5 Hz
        val bands = SpectrumBands.fromFft(fft, 48000)
        assertTrue(bands.any { it > .8f })
        assertTrue(bands.count { it > 0 } in 1..3)
        assertTrue(bands.all { it.isFinite() && it in 0f..1f })
    }

}
