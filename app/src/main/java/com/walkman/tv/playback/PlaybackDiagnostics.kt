package com.walkman.tv.playback

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object PlaybackDiagnostics {
 private const val FILE_NAME = "playback-diagnostics.txt"
 private const val MAX_BYTES = 1500000L
 private val running = AtomicBoolean(false)
 @Volatile private var job: Job? = null
 @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
 @Synchronized fun start(context: Context, controller: PlaybackController, scope: CoroutineScope) {
  val file = File(context.applicationContext.filesDir, FILE_NAME)
  append(file, "\n===== 診斷啟動 " + stamp() + " =====\n裝置：" + Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid：" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n")
  if (running.getAndSet(true)) { append(file, "診斷監測已在執行\n"); return }
  previousHandler = Thread.getDefaultUncaughtExceptionHandler()
  Thread.setDefaultUncaughtExceptionHandler { thread, error ->
   runCatching { append(file, "\n===== 未捕捉例外 =====\n時間：" + stamp() + "\n執行緒：" + thread.name + "\n" + error.stackTraceToString() + "\n") }
   previousHandler?.uncaughtException(thread, error)
  }
  job?.cancel()
  job = scope.launch(Dispatchers.IO) {
   while (isActive) {
    runCatching {
     val state = controller.state.value
     val player = controller.player
     val rt = Runtime.getRuntime()
     val used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
     val max = rt.maxMemory() / 1024 / 1024
     val native = Debug.getNativeHeapAllocatedSize() / 1024 / 1024
     append(file, "[" + stamp() + "] playbackState=" + player.playbackState + ", playWhenReady=" + player.playWhenReady + ", isPlaying=" + player.isPlaying + ", index=" + state.index + ", queueSize=" + state.queue.size + ", resolving=" + state.resolving + ", positionMs=" + state.positionMs + ", durationMs=" + state.durationMs + ", heap=" + used + "/" + max + "MB, nativeHeap=" + native + "MB\n")
     captureLogcat(file)
    }
    delay(15000)
   }
  }
 }
 fun shareReport(context: Context): Boolean {
  val file = File(context.applicationContext.filesDir, FILE_NAME)
  if (!file.exists() || file.length() == 0L) return false
  val report = runCatching { file.readText().takeLast(100_000) }.getOrDefault("無法讀取診斷紀錄")
  val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Walkman-TV 播放穩定性診斷報告"); putExtra(Intent.EXTRA_TEXT, report); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
  return runCatching { context.startActivity(Intent.createChooser(send, "分享播放診斷報告").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
 }
 private fun captureLogcat(file: File) {
  val p = runCatching { ProcessBuilder("logcat", "-d", "-t", "350").redirectErrorStream(true).start() }.getOrNull() ?: return
  val lines = runCatching { p.inputStream.bufferedReader().useLines { seq -> seq.filter { line -> val l = line.lowercase(Locale.ROOT); listOf("fatal exception", "fatal signal", "am_crash", "androidruntime", "mediacodec", "audiotrack", "exoplayer", "playbackcontroller", "playbackservice", "sourcemanager", "othersourcefinder", "alternative_search", "alternative_resolve", "script_resolve", "builtin_fallback", "outofmemory").any { l.contains(it) } }.toList() } }.getOrDefault(emptyList())
  runCatching { p.destroy() }
  if (lines.isNotEmpty()) append(file, "---- 系統播放器／崩潰相關紀錄 ----\n" + lines.takeLast(120).joinToString("\n", postfix = "\n"))
 }
 @Synchronized private fun append(file: File, value: String) { runCatching { if (file.exists() && file.length() > MAX_BYTES) file.writeText(file.readText().takeLast((MAX_BYTES / 2).toInt())); file.appendText(value) } }
 private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
