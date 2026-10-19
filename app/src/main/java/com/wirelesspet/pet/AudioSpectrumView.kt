package com.wirelesspet.pet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.os.Handler
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Output-mix visualization only: no microphone, recording, files or network. */
class AudioSpectrumView(context: Context, private val report: (String) -> Unit) : View(context) {
    private var worker: android.os.HandlerThread? = null
    private var handler: Handler? = null
    private var effectHolder: Array<Visualizer?>? = null
    @Volatile private var generation = 0
    @Volatile private var delayMs = 33L
    private var requested = false
    private val levels = FloatArray(SpectrumBands.COUNT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }

    fun configure(active: Boolean, fps: Int) {
        delayMs = 1000L / fps.coerceIn(1, 30)
        if (requested == active) return
        requested = active
        if (!active) { stop(); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val token = ++generation
        val thread = android.os.HandlerThread("playback-spectrum").also { it.start() }
        val background = Handler(thread.looper)
        val holder = arrayOfNulls<Visualizer>(1)
        worker = thread; handler = background; effectHolder = holder
        background.post {
            try {
                val effect = Visualizer(0)
                holder[0] = effect
                effect.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                val fft = ByteArray(effect.captureSize)
                // Preserve musical dynamics; slow gain control is applied after FFT instead.
                effect.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                check(effect.setEnabled(true) == Visualizer.SUCCESS)
                post { if (generation == token) report("音频频谱：独立线程采集已启动") }
                val dynamics = SpectrumDynamics()
                val analyzer = SpectrumBands.Analyzer()
                val inputBuffer = FloatArray(SpectrumBands.COUNT)
                val published = FloatArray(SpectrumBands.COUNT)
                val latest = java.util.concurrent.atomic.AtomicReference<FloatArray>()
                val pending = java.util.concurrent.atomic.AtomicBoolean(false)
                val audio = context.getSystemService(android.media.AudioManager::class.java)
                var checkedAudioAt = 0L
                var musicActive = true
                var silentSince = 0L
                var previousTime = android.os.SystemClock.elapsedRealtime()
                var signaled = false
                var uiSignaled = false
                val tick = object : Runnable {
                    override fun run() {
                        if (generation != token) return
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        try {
                            check(effect.getFft(fft) == Visualizer.SUCCESS)
                            val input = analyzer.analyze(fft, effect.samplingRate / 1000, inputBuffer)
                            if (startedAt - checkedAudioAt >= 500L) {
                                musicActive = audio.isMusicActive; checkedAudioAt = startedAt
                            }
                            if (input.any { it > .012f }) silentSince = 0L
                            else if (silentSince == 0L) silentSince = startedAt
                            val next = dynamics.update(input, (startedAt - previousTime).toFloat())
                            previousTime = startedAt
                            val firstSignal = !signaled && input.any { it > .025f }
                            if (firstSignal) signaled = true
                            if (firstSignal || next.indices.any { abs(next[it] - published[it]) >= .006f }) {
                                next.copyInto(published); latest.set(next)
                                if (pending.compareAndSet(false, true)) post {
                                    pending.set(false)
                                    if (generation == token) {
                                        if (!uiSignaled && signaled) { uiSignaled = true; report("音频频谱：已收到非静音频段") }
                                        latest.get()?.copyInto(levels); postInvalidateOnAnimation()
                                    }
                                }
                            }
                            val quiet = !musicActive && silentSince != 0L && startedAt - silentSince >= 1500L
                            val interval = if (quiet) maxOf(delayMs, 250L) else delayMs
                            val spent = android.os.SystemClock.elapsedRealtime() - startedAt
                            background.postDelayed(this, (interval - spent).coerceAtLeast(1))
                        } catch (failure: Exception) {
                            post { if (generation == token) { stop(); report("音频频谱已停止：${failure.message?.take(100)}") } }
                        }
                    }
                }
                background.post(tick)
            } catch (failure: Exception) {
                post {
                    if (generation == token) {
                        stop(); report("音频频谱不可用：${failure.message?.take(120)}")
                        android.widget.Toast.makeText(context, "当前系统暂不支持读取播放频谱", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun stop() {
        generation++
        val thread = worker
        val background = handler
        val holder = effectHolder
        background?.removeCallbacksAndMessages(null)
        background?.post {
            holder?.get(0)?.let { runCatching { it.setEnabled(false) }; runCatching { it.release() } }
            thread?.quitSafely()
        }
        worker = null; handler = null; effectHolder = null
        levels.fill(0f); invalidate()
    }
    fun release() { requested = false; stop() }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val step = 28f * density
        val barHeight = 5f * density
        val count = SpectrumBands.displayCount(height, density)
        val top = (height - (count - 1) * step - barHeight) / 2
        val maxWidth = min(width * .085f, 64f * density)
        val inset = 0f
        for (i in 0 until count) {
            val start = i * levels.size / count
            val end = ((i + 1) * levels.size / count).coerceAtLeast(start + 1).coerceAtMost(levels.size)
            var level = 0f
            for (bin in start until end) level = maxOf(level, levels[bin])
            if (level <= .01f) continue
            val y = top + (count - 1 - i) * step
            val length = maxWidth * level
            paint.color = Color.rgb(160 + i * 60 / count, 210, 240)
            paint.alpha = (100 + level * 135).toInt()
            canvas.drawRoundRect(inset, y, inset + length, y + barHeight, barHeight / 2, barHeight / 2, paint)
            canvas.drawRoundRect(width - inset - length, y, width - inset, y + barHeight, barHeight / 2, barHeight / 2, paint)
        }
    }
}
