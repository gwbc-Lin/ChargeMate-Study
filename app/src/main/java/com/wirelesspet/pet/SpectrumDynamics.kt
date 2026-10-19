package com.wirelesspet.pet

import kotlin.math.*

/** Time-based attack/release and real spectral onsets, without an artificial beat clock. */
class SpectrumDynamics {
    private val output = FloatArray(SpectrumBands.COUNT)
    private val previous = FloatArray(SpectrumBands.COUNT)
    private var reference = .12f
    fun update(input: FloatArray, elapsedMs: Float): FloatArray {
        val dt = elapsedMs.coerceIn(1f, 250f)
        reference = maxOf(.12f, input.maxOrNull() ?: 0f, reference * exp(-dt / 1600f))
        val attack = 1f - exp(-dt / 7f)
        val release = 1f - exp(-dt / 65f)
        for (i in output.indices) {
            val amplitude = input.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val onset = max(0f, amplitude - previous[i]) / reference
            val target = if (amplitude < .012f) 0f else
                ((amplitude / reference).pow(1.25f) * .75f + onset * .65f).coerceIn(0f, 1f)
            val blend = if (target > output[i]) attack else release
            output[i] += (target - output[i]) * blend
            if (output[i] < .006f) output[i] = 0f
            previous[i] = amplitude
        }
        return output.copyOf()
    }
}
