package com.wirelesspet.data

import com.wirelesspet.core.WallClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 有界本地诊断日志（设计文档 §12.2）。
 *
 * - 内存环形缓冲供界面展示，磁盘文件供用户主动导出；
 * - 磁盘文件超过 [maxFileBytes] 轮转一份旧文件，始终只有一个备份，不会无限增长；
 * - 不写入密码、通知正文、其他应用内容或稳定设备标识；
 * - 全部写入在单独线程完成，主线程只做一次入队。
 */
class EventLog(
    directory: File,
    private val wallClock: WallClock,
    private val maxEntries: Int = 300,
    private val maxFileBytes: Long = 512L * 1024L,
) {
    enum class Category(val tag: String) {
        POWER("POWER"),
        SESSION("SESSION"),
        POLICY("POLICY"),
        PRESENTATION("PRESENT"),
        SERVICE("SERVICE"),
        RECOVERY("RECOVER"),
        CAPABILITY("CAPABLE"),
        ERROR("ERROR"),
    }

    data class Entry(val atWallMs: Long, val category: Category, val message: String) {
        fun format(): String = "${formatTime(atWallMs)} [${category.tag}] $message"

        private fun formatTime(millis: Long): String = TIME_FORMAT.format(
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
        )
    }

    private val logDirectory = directory.apply { mkdirs() }
    private val currentFile = File(logDirectory, "events.log")
    private val rotatedFile = File(logDirectory, "events.1.log")
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pet-event-log").apply { isDaemon = true }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun log(category: Category, message: String) {
        val entry = Entry(wallClock.currentTimeMillis(), category, message.take(500))
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > maxEntries) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
        executor.execute { appendToFile(entry) }
    }

    fun recent(limit: Int = 80): List<Entry> = synchronized(lock) { buffer.toList().takeLast(limit) }

    /** 导出前调用：等待已经入队的写入落盘，再返回完整文本。 */
    fun exportText(header: String): String {
        runCatching { executor.submit { }.get(3, TimeUnit.SECONDS) }
        val body = buildString {
            appendLine(header)
            appendLine("=".repeat(60))
            val fromDisk = runCatching { currentFile.readText() }.getOrDefault("")
            if (fromDisk.isNotBlank()) append(fromDisk) else buffer.forEach { appendLine(it.format()) }
        }
        return body
    }

    fun file(): File = currentFile

    fun clearAll() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
        executor.execute {
            runCatching { currentFile.delete() }
            runCatching { rotatedFile.delete() }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun appendToFile(entry: Entry) {
        runCatching {
            if (currentFile.length() > maxFileBytes) {
                rotatedFile.delete()
                currentFile.renameTo(rotatedFile)
            }
            currentFile.appendText(entry.format() + "\n")
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}
