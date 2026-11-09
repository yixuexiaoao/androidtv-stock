package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.log.LogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "logs/app.log")

    @Test fun newestFirstAndFilters() {
        val s = LogStore(null)
        s.add(1, "D", "A", "debug")
        s.add(2, "I", "B", "info hello")
        s.add(3, "W", "A", "warn")
        s.add(4, "E", "C", "error HELLO")
        assertEquals(listOf(4L, 3L, 2L, 1L), s.query().map { it.timeMs })
        assertEquals(listOf(4L, 3L), s.query(minLevel = "W").map { it.timeMs })
        assertEquals(listOf(4L, 2L), s.query(keyword = "hello").map { it.timeMs })
        assertEquals(listOf(3L, 1L), s.query(keyword = "a").filter { it.tag == "A" }.map { it.timeMs })
        assertEquals(listOf(4L), s.query(limit = 1).map { it.timeMs })
        // 未知级别等同于不过滤
        assertEquals(4, s.query(minLevel = "x").size)
    }

    @Test fun capacityIsBounded() {
        val s = LogStore(null, capacity = 3)
        (1L..5L).forEach { s.add(it, "I", "T", "m$it") }
        assertEquals(listOf(5L, 4L, 3L), s.query().map { it.timeMs })
    }

    @Test fun survivesRestartIncludingMultilineStackTraces() {
        val first = LogStore(file())
        first.add(1, "E", "CRASH", "boom\n\tat Foo.bar(Foo.kt:1)\\path")
        val second = LogStore(file())
        val entry = second.query().single()
        assertEquals("CRASH", entry.tag)
        assertEquals("boom\n\tat Foo.bar(Foo.kt:1)\\path", entry.message)
    }

    @Test fun corruptLinesAreSkipped() {
        file().parentFile!!.mkdirs()
        file().writeText("garbage\nabc\tI\tT\tm\n5\tI\tT\tgood\n7\tZ\tT\tbadlevel\n")
        assertEquals(listOf("good"), LogStore(file()).query().map { it.message })
    }

    @Test fun rotatesAndKeepsBackup() {
        val s = LogStore(file(), maxFileBytes = 100)
        (1L..20L).forEach { s.add(it, "I", "T", "message number $it") }
        val backup = File(file().parentFile, "app.log.1")
        assertTrue(backup.exists())
        assertTrue(file().length() <= 100 + 64)
        // 重启后：备份在前、当前在后，顺序不乱
        val times = LogStore(file(), maxFileBytes = 100).query().map { it.timeMs }
        assertEquals(20L, times.first())
        assertEquals(times.sortedDescending(), times)
    }

    @Test fun clearRemovesMemoryAndFiles() {
        val s = LogStore(file(), maxFileBytes = 50)
        (1L..10L).forEach { s.add(it, "I", "T", "message number $it") }
        s.clear()
        assertTrue(s.query().isEmpty())
        assertFalse(file().exists())
        assertFalse(File(file().parentFile, "app.log.1").exists())
        assertTrue(LogStore(file()).query().isEmpty())
    }

    @Test fun longMessageTruncated() {
        val s = LogStore(null)
        s.add(1, "I", "T", "x".repeat(20000))
        assertEquals(8000, s.query().single().message.length)
    }
}
