package com.reelsgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    private val pool = Executors.newFixedThreadPool(2)
    private val active = AtomicInteger(0)
    private val ids = AtomicInteger(100)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "التحميل الجاري", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "نتيجة التحميل", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url")
        val selector = intent?.getStringExtra("selector")
        val title = intent?.getStringExtra("title") ?: "Video"

        startForeground(
            FG_ID,
            progressNotif(title, "جاري التحضير…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (url == null || selector == null) {
            if (active.get() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        active.incrementAndGet()
        pool.execute { download(url, selector, title) }
        return START_NOT_STICKY
    }

    private fun download(url: String, selector: String, title: String) {
        val audio = selector == "AUDIO"
        val tmp = File(cacheDir, "dl_${System.currentTimeMillis()}_${ids.incrementAndGet()}")
        tmp.mkdirs()
        try {
            App.awaitReady()
            val req = YoutubeDLRequest(url)
            req.addOption("-o", tmp.absolutePath + "/%(id)s.%(ext)s")
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")
            if (audio) {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
            } else {
                req.addOption("-f", selector)
                req.addOption("--merge-output-format", "mp4")
            }
            Cookies.apply(req, this)

            var last = -1
            YoutubeDL.getInstance().execute(req, "dl_${System.nanoTime()}") { p, _, _ ->
                val pct = p.toInt().coerceIn(0, 100)
                if (pct != last) {
                    last = pct
                    nm.notify(FG_ID, progressNotif(title, "$pct%", pct, false))
                }
            }

            val out = tmp.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("لم يتم إنشاء ملف")
            val uri = saveToGallery(out)
            notifyDone(title, uri)
        } catch (e: Throwable) {
            notifyFail(title, e.message ?: "خطأ غير معروف")
        } finally {
            tmp.deleteRecursively()
            if (active.decrementAndGet() <= 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun saveToGallery(f: File): Uri? {
        val ext = f.extension.lowercase()
        val audio = ext in setOf("mp3", "m4a", "opus", "ogg", "aac", "wav", "flac")
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "ReelsGrab_${System.currentTimeMillis()}.$ext")
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/ReelsGrab"
        )
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)

        val collection = if (audio)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val uri = contentResolver.insert(collection, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { out ->
            f.inputStream().use { it.copyTo(out) }
        }
        val done = ContentValues()
        done.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, done, null, null)
        return uri
    }

    private fun progressNotif(title: String, text: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.take(60))
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notifyDone(title: String, uri: Uri?) {
        val b = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("تم التحميل ✔")
            .setContentText(title.take(60))
            .setAutoCancel(true)
        if (uri != null) {
            val open = Intent(Intent.ACTION_VIEW)
            open.setDataAndType(uri, contentResolver.getType(uri))
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pi = PendingIntent.getActivity(
                this, ids.incrementAndGet(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.setContentIntent(pi)
        }
        nm.notify(ids.incrementAndGet(), b.build())
    }

    private fun notifyFail(title: String, msg: String) {
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("فشل التحميل")
            .setContentText(msg.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(title.take(60) + "\n" + msg.take(300)))
            .setAutoCancel(true)
            .build()
        nm.notify(ids.incrementAndGet(), n)
    }

    override fun onDestroy() {
        pool.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val FG_ID = 1
    }
}
