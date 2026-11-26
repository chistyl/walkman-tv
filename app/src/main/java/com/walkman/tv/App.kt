package com.walkman.tv

import android.app.Application
import com.walkman.tv.di.AppContainer
import com.walkman.tv.diagnostics.AppDiagnostics

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        AppDiagnostics.install(this)
        AppDiagnostics.record("APP_STARTUP", "onCreate begin")
        instance = this
        container = AppContainer(this)
        AppDiagnostics.record(
            "APP_STARTUP",
            "container ready; elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
        )
        // ExoPlayer must be created on the main thread.
        val playbackStartedAt = android.os.SystemClock.elapsedRealtime()
        container.initPlayback()
        AppDiagnostics.record(
            "APP_STARTUP",
            "playback initialized; elapsedMs=${android.os.SystemClock.elapsedRealtime() - playbackStartedAt}",
        )
        container.bootstrap()
        AppDiagnostics.record(
            "APP_STARTUP",
            "onCreate complete; elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    companion object {
        lateinit var instance: App
            private set
        lateinit var container: AppContainer
            private set
    }
}
