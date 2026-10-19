package com.wirelesspet.pet

import kotlin.math.*

/** Android Visualizer complex FFT (DC/Nyquist excluded), logarithmic frequency bins. */
object SpectrumBands {
    const val COUNT = 64
    fun displayCount(heightPx: Int, density: Float): Int =
        (((heightPx / density.coerceAtLeast(.1f) - 5f) / 28f).toInt() + 1).coerceIn(1, COUNT)
    fun fromFft(fft: ByteArray, sampleRateHz: Int): FloatArray = Analyzer().analyze(fft, sampleRateHz)

    /** Range calculations depend only on format, not on each audio frame. */
    class Analyzer {
        private var size = 0
        private var rate = 0
        private val starts = IntArray(COUNT)
        private val ends = IntArray(COUNT)
        fun analyze(fft: ByteArray, sampleRateHz: Int, result: FloatArray = FloatArray(COUNT)): FloatArray {
            result.fill(0f)
            if (fft.size < 4 || sampleRateHz <= 120) return result
            if (size != fft.size || rate != sampleRateHz) {
                size = fft.size; rate = sampleRateHz
                val high = min(16000.0, rate / 2.0)
                for (band in 0 until COUNT) {
                    val lowHz = 60 * (high / 60).pow(band.toDouble() / COUNT)
                    val highHz = 60 * (high / 60).pow((band + 1.0) / COUNT)
                    starts[band] = (lowHz * size / rate).toInt().coerceIn(1, size / 2 - 1)
                    ends[band] = ceil(highHz * size / rate).toInt().coerceIn(starts[band] + 1, size / 2)
                }
            }
            for (band in 0 until COUNT) {
                var peakSquared = 0
                for (bin in starts[band] until ends[band]) {
                    val real = fft[bin * 2].toInt(); val imag = fft[bin * 2 + 1].toInt()
                    peakSquared = max(peakSquared, real * real + imag * imag)
                }
                result[band] = (max(0.0, sqrt(peakSquared.toDouble()) - 1) / 180.0).pow(.7).toFloat().coerceIn(0f, 1f)
            }
            return result
        }
    }
}
