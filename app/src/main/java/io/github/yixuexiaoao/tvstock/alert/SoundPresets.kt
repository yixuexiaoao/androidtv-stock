package io.github.yixuexiaoao.tvstock.alert

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 提示音预设：实时合成，不依赖系统铃声，在不同电视上听起来一致。 */
object SoundPresets {
    private data class Note(val freq: Int, val ms: Int)

    const val SAMPLE_RATE = 22050
    const val RISE = "RISE"
    const val FALL = "FALL"
    const val NONE = "NONE"

    private val NOTES = linkedMapOf(
        RISE to listOf(Note(523, 130), Note(659, 130), Note(784, 260)),
        FALL to listOf(Note(784, 130), Note(659, 130), Note(523, 260)),
        "COIN" to listOf(Note(988, 90), Note(1319, 320)),
        "LOW" to listOf(Note(330, 220), Note(0, 40), Note(262, 360)),
        "DING" to listOf(Note(880, 420)),
        "DOUBLE" to listOf(Note(988, 150), Note(0, 90), Note(988, 150)),
        "ALARM" to listOf(Note(1200, 110), Note(0, 60), Note(1200, 110), Note(0, 60), Note(1200, 110)),
        NONE to emptyList(),
    )

    val ALL: List<String> = NOTES.keys.toList()

    fun label(id: String) = when (id) {
        RISE -> "三音上升"
        FALL -> "三音下降"
        "COIN" -> "清脆两声"
        "LOW" -> "低沉两声"
        "DING" -> "单响"
        "DOUBLE" -> "双响"
        "ALARM" -> "急促警报"
        NONE -> "无声"
        else -> id
    }

    /** 生成 16 位单声道采样；每个音加淡入淡出避免爆音。无声或未知预设返回空数组。 */
    fun pcm(id: String, repeat: Int = 1): ShortArray {
        val notes = NOTES[id].orEmpty()
        if (notes.isEmpty()) return ShortArray(0)
        val gapMs = 180
        val out = ArrayList<Short>()
        repeat(repeat.coerceIn(1, 5)) { round ->
            if (round > 0) repeat(SAMPLE_RATE * gapMs / 1000) { out.add(0) }
            notes.forEach { note ->
                val count = SAMPLE_RATE * note.ms / 1000
                val fade = min(count / 4, SAMPLE_RATE / 200)
                for (i in 0 until count) {
                    val envelope = if (fade == 0) 1.0 else min(1.0, min(i, count - 1 - i).toDouble() / fade)
                    val value = if (note.freq == 0) 0.0 else sin(2 * PI * note.freq * i / SAMPLE_RATE) * envelope * 0.85
                    out.add((value * Short.MAX_VALUE).toInt().toShort())
                }
            }
        }
        return out.toShortArray()
    }
}
