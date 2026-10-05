package io.github.jaehun6912.remoteaccesshub.core

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class LogLevel { Debug, Info, Warn, Error }

data class LogEntry(val time: OffsetDateTime, val level: LogLevel, val message: String) {
    val levelText: String
        get() = when (level) {
            LogLevel.Debug -> "디버그"
            LogLevel.Info -> "정보"
            LogLevel.Warn -> "경고"
            LogLevel.Error -> "오류"
        }

    fun format(): String = "${time.format(HM_S)} [$levelText] $message"

    companion object {
        private val HM_S = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/**
 * 화면 로그 + 파일 로그. 모든 메시지는 [redact]를 거쳐 비밀번호·쿠키·토큰·URL 쿼리·MAC을 가린다.
 * 파일은 앱 전용 폴더(다른 앱이 읽을 수 없음)에 날짜별로 남긴다.
 */
class AppLog(logDirectory: File?) {
    private val gate = Any()
    private val entries = ArrayList<LogEntry>()
    private var writer: Writer? = null
    private val listeners = mutableListOf<(LogEntry) -> Unit>()

    init {
        if (logDirectory != null) {
            try {
                logDirectory.mkdirs()
                val file = File(logDirectory, "app-${OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.log")
                writer = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
            } catch (_: Exception) {
                writer = null // 파일 로그 실패는 무시 (화면 로그는 계속)
            }
        }
    }

    fun onAppended(listener: (LogEntry) -> Unit) {
        synchronized(gate) { listeners += listener }
    }

    fun debug(message: String) = append(LogLevel.Debug, message)
    fun info(message: String) = append(LogLevel.Info, message)
    fun warn(message: String) = append(LogLevel.Warn, message)
    fun error(message: String) = append(LogLevel.Error, message)

    private fun append(level: LogLevel, message: String) {
        val entry = LogEntry(OffsetDateTime.now(), level, redact(message))
        val ls: List<(LogEntry) -> Unit>
        synchronized(gate) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) entries.subList(0, entries.size - MAX_ENTRIES).clear()
            try {
                writer?.apply {
                    write(entry.time.format(FILE_TIME) + " [" + entry.level + "] " + entry.message + "\n")
                    flush()
                }
            } catch (_: Exception) {
                // 무시
            }
            ls = listeners.toList()
        }
        ls.forEach { it(entry) }
    }

    fun snapshot(max: Int = 500): List<LogEntry> = synchronized(gate) {
        entries.subList(maxOf(0, entries.size - max), entries.size).toList()
    }

    fun close() {
        synchronized(gate) {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
        }
    }

    companion object {
        private const val MAX_ENTRIES = 2000
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val secretRegex =
            Regex("(?i)\\b(password|passwd|pwd|pw|cookie|set-cookie|token|secret|captcha|authorization)\\b\\s*[=:]\\s*[^\\s;,&]+")
        private val queryRegex = Regex("\\?[^\\s\"'<>]*")

        /** 비밀정보 후보를 가린다. URL의 쿼리 문자열은 통째로 제거하고 MAC 주소는 마스킹한다. */
        fun redact(message: String?): String {
            if (message.isNullOrEmpty()) return ""
            var s = secretRegex.replace(message) { it.groupValues[1] + "=<가림>" }
            s = queryRegex.replace(s, "?<쿼리 제거>")
            s = InputRules.maskMac(s)
            return s
        }
    }
}
