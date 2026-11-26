package com.walkman.tv.diagnostics

import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device crash and playback diagnostics; export from Settings without ADB. */
object AppDiagnostics {
    private const val MAX_LOG_BYTES = 1024 * 1024
    private const val KEEP_TAIL_BYTES = 512 * 1024
    @Volatile private var logFile: File? = null
    @Volatile private var installed = false

    fun install(context: Context) {
        logFile = File(File(context.applicationContext.filesDir, "diagnostics"), "walkman-tv.log").also {
            it.parentFile?.mkdirs()
        }
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("FATAL", "Uncaught exception on thread ${thread.name}", error)
            if (previous != null) previous.uncaughtException(thread, error) else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        record("APP", "Application started")
    }

    @Synchronized
    fun record(category: String, message: String, error: Throwable? = null) {
        val file = logFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                val old = file.readBytes()
                val tail = old.copyOfRange((old.size - KEEP_TAIL_BYTES).coerceAtLeast(0), old.size)
                file.writeBytes(tail)
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val detail = buildString {
                append(timestamp).append(" [").append(category).append("] ").append(message).append('\n')
                if (error != null) {
                    val writer = StringWriter()
                    error.printStackTrace(PrintWriter(writer))
                    append(writer.toString()).append('\n')
                }
            }
            file.appendText(detail, Charsets.UTF_8)
        }
    }

    fun readAll(): String {
        val file = logFile ?: return "診斷紀錄尚未初始化"
        return runCatching {
            if (file.exists()) file.readText(Charsets.UTF_8) else "目前沒有閃退或播放錯誤紀錄"
        }.getOrDefault("讀取診斷紀錄失敗")
    }

    fun fileName(): String = "Walkman-TV-診斷紀錄.txt"
}
