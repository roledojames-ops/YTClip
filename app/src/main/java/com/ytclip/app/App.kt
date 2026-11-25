package com.ytclip.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm up python + ffmpeg (and refresh yt-dlp) in the background.
        Thread {
            try {
                Engine.ensure(this)
            } catch (e: Exception) {
                // shown in the UI through ClipBus.engine
            }
        }.start()
    }
}
