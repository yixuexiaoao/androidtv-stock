package io.github.yixuexiaoao.tvstock.log

import java.io.File
import java.io.IOException

data class LogEntry(val timeMs: Long, val level: String, val tag: String, val message: String)

/**
 * 日志存储：追加到文件，重启后还能看到上次运行（含崩溃）的日志；内存保留最近若干条。
 * 每条立即写盘，日志量很小，这样进程崩溃也不会丢。
 * 文件第一次查询时才读入内存，不拖慢 App 启动。
 */
class LogStore(
    private val file: File?,
    private val capacity: Int = 2000,
    private val maxFileBytes: Long = 512 * 1024,
) {
    private val entries = ArrayDeque<LogEntry>()
    private val backup: File? = file?.let { File(it.parentFile, it.name + ".1") }

    /** 没有文件时没有可读的历史，内存就是全部。 */
    private var loaded = file == null

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            listOfNotNull(backup, file).filter { it.exists() }.forEach { source ->
                source.readLines().forEach { line -> decode(line)?.let(::remember) }
            }
        } catch (_: IOException) {
            // 日志文件损坏不影响运行
        }
    }

    private fun remember(entry: LogEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun add(timeMs: Long, level: String, tag: String, message: String) {
        val entry = LogEntry(timeMs, level, tag, message.take(MAX_MESSAGE))
        // 还没读入内存时只写文件，第一次查询时会一起读出来
        if (loaded) remember(entry)
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            target.appendText(encode(entry) + "\n")
            if (target.length() > maxFileBytes) {
                backup?.delete()
                backup?.let { target.renameTo(it) }
            }
        } catch (_: IOException) {
            // 磁盘满等情况只保留内存日志
        }
    }

    /** 返回最新的在前。minLevel 为 D/I/W/E，keyword 不区分大小写。 */
    @Synchronized
    fun query(minLevel: String? = null, keyword: String? = null, limit: Int = capacity): List<LogEntry> {
        ensureLoaded()
        val floor = LEVELS.indexOf(minLevel).coerceAtLeast(0)
        val word = keyword?.trim().orEmpty()
        return entries.asReversed().asSequence()
            .filter { LEVELS.indexOf(it.level) >= floor }
            .filter { word.isEmpty() || it.message.contains(word, true) || it.tag.contains(word, true) }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    @Synchronized
    fun clear() {
        loaded = true
        entries.clear()
        file?.delete()
        backup?.delete()
    }

    private fun encode(e: LogEntry): String =
        listOf(e.timeMs.toString(), e.level, escape(e.tag), escape(e.message)).joinToString("\t")

    private fun decode(line: String): LogEntry? {
        val parts = line.split('\t', limit = 4)
        if (parts.size != 4) return null
        val time = parts[0].toLongOrNull() ?: return null
        if (parts[1] !in LEVELS) return null
        return LogEntry(time, parts[1], unescape(parts[2]), unescape(parts[3]))
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "")

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(text[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    companion object {
        val LEVELS = listOf("D", "I", "W", "E")
        private const val MAX_MESSAGE = 8000
    }
}
