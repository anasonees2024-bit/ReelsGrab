package com.reelsgrab

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                ready = true
            } catch (e: Throwable) {
                initError = e.message
            } finally {
                latch.countDown()
            }
        }.start()
    }

    companion object {
        private val latch = CountDownLatch(1)
        @Volatile var ready = false
        @Volatile var initError: String? = null

        fun awaitReady() {
            latch.await(90, TimeUnit.SECONDS)
            if (!ready) throw IllegalStateException(initError ?: "المحرك غير جاهز")
        }
    }
}
