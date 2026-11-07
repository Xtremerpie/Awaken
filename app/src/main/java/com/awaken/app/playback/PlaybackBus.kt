package com.awaken.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

data class NowPlaying(
    val scheduleId: Long?,
    val scheduleName: String,
    val title: String,
    val isVideo: Boolean,
    val scheduled: Boolean,
    val startedAt: Long,
    val totalMs: Long,
)

/** Process-wide playback state shared by the service and the UI (they run in the same process). */
object PlaybackBus {
    const val BANDS = 24

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _userVolume = MutableStateFlow(0.8f)
    val userVolume: StateFlow<Float> = _userVolume

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    private val _levels = MutableStateFlow(FloatArray(BANDS))
    val levels: StateFlow<FloatArray> = _levels

    fun setNowPlaying(v: NowPlaying?) { _nowPlaying.value = v; if (v == null) _levels.value = FloatArray(BANDS) }
    fun setUserVolume(v: Float) { _userVolume.value = v.coerceIn(0f, 1f) }
    fun setMuted(v: Boolean) { _muted.value = v }
    fun publishLevels(v: FloatArray) { _levels.value = v }
    fun clearLevels() { _levels.value = FloatArray(BANDS) }
}

/**
 * Pass-through ExoPlayer audio processor that measures the signal for the visualizer. It never alters the
 * audio. It reads 512 mono samples at a time, runs a small FFT and groups the result into log-spaced bands.
 * No RECORD_AUDIO permission is involved: it only sees what the app itself is about to play.
 */
class LevelTap : BaseAudioProcessor() {
    private val n = 512
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val smoothed = FloatArray(PlaybackBus.BANDS)
    private var lastPublish = 0L
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val now = System.nanoTime() / 1_000_000
        if (now - lastPublish >= 33) {
            lastPublish = now
            analyse(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        }
        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onReset() {
        smoothed.fill(0f)
        PlaybackBus.clearLevels()
    }

    private fun analyse(buf: ByteBuffer) {
        val frames = buf.remaining() / (2 * channels)
        if (frames < 64) return
        val count = minOf(n, frames)
        for (i in 0 until n) {
            if (i < count) {
                var sum = 0
                for (c in 0 until channels) sum += buf.getShort((i * channels + c) * 2 + buf.position()).toInt()
                re[i] = sum / channels / 32768f * window[i]
            } else re[i] = 0f
            im[i] = 0f
        }
        fft()
        val bins = n / 2
        val out = FloatArray(PlaybackBus.BANDS)
        for (b in 0 until PlaybackBus.BANDS) {
            // log-spaced edges from bin 1 to bin n/2
            val lo = Math.pow(bins.toDouble(), b / PlaybackBus.BANDS.toDouble()).toInt().coerceAtLeast(1)
            val hi = Math.pow(bins.toDouble(), (b + 1) / PlaybackBus.BANDS.toDouble()).toInt().coerceAtLeast(lo + 1).coerceAtMost(bins)
            var e = 0f
            for (k in lo until hi) e = maxOf(e, sqrt(re[k] * re[k] + im[k] * im[k]))
            val db = 20 * log10((e / 8f).coerceAtLeast(1e-5f))
            val level = ((db + 60f) / 60f).coerceIn(0f, 1f)
            smoothed[b] = if (level > smoothed[b]) level else smoothed[b] * 0.85f + level * 0.15f
            out[b] = smoothed[b]
        }
        PlaybackBus.publishLevels(out)
    }

    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
