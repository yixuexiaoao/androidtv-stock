package io.github.yixuexiaoao.tvstock.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import io.github.yixuexiaoao.tvstock.alert.SoundPresets
import io.github.yixuexiaoao.tvstock.log.AppLog

object SoundPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, ShortArray>()

    fun play(id: String, volumePercent: Int, repeat: Int = 1) {
        val pcm = cache.getOrPut("$id/$repeat") { SoundPresets.pcm(id, repeat) }
        if (pcm.isEmpty()) return
        try {
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SoundPresets.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                pcm.size * 2,
                AudioTrack.MODE_STATIC,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            track.write(pcm, 0, pcm.size)
            val gain = volumePercent.coerceIn(0, 100) / 100f
            track.setStereoVolume(gain, gain)
            track.play()
            val durationMs = pcm.size * 1000L / SoundPresets.SAMPLE_RATE
            handler.postDelayed({ track.release() }, durationMs + 300)
        } catch (e: RuntimeException) {
            // 部分电视没有可用音频通道
            AppLog.w("Alert", "提示音播放失败", e)
        }
    }
}
