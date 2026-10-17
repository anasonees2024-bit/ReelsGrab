package com.reelsgrab

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

class PickerActivity : Activity() {

    private var url: String? = null
    private var fromClipboard = false
    private var started = false
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        if (intent?.action == Intent.ACTION_SEND) {
            url = Utils.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        } else {
            fromClipboard = true
        }
    }

    // قراءة الحافظة تتطلب أن تكون النافذة في الواجهة (قيد أندرويد)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true

        if (fromClipboard) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0)
                clip.getItemAt(0).coerceToText(this)?.toString() else null
            url = Utils.extractUrl(text)
        }

        val u = url
        if (u == null) {
            Toast.makeText(this, "ما لقيت رابط. انسخ رابط الفيديو أولاً", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loadFormats(u)
    }

    private fun loadFormats(u: String) {
        showProgress()
        Thread {
            try {
                App.awaitReady()
                val req = YoutubeDLRequest(u)
                Cookies.apply(req, this)
                val info = YoutubeDL.getInstance().getInfo(req)
                val heights = (info.formats ?: arrayListOf())
                    .map { it.height ?: 0 }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()
                val title = info.title ?: "Video"
                runOnUiThread { showQualities(u, title, heights) }
            } catch (e: Exception) {
                runOnUiThread { showError(e.message ?: "خطأ غير معروف") }
            }
        }.start()
    }

    private fun showProgress() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(56, 48, 56, 48)
        box.addView(ProgressBar(this))
        val t = TextView(this)
        t.text = "جاري جلب الجودات…"
        t.setPadding(40, 0, 0, 0)
        box.addView(t)
        dialog = AlertDialog.Builder(this)
            .setView(box)
            .setOnCancelListener { finish() }
            .create()
        dialog?.show()
    }

    private fun showQualities(u: String, title: String, heights: List<Int>) {
        if (isFinishing) return
        dialog?.dismiss()
        val labels = ArrayList<String>()
        val selectors = ArrayList<String>()
        labels.add("أعلى جودة"); selectors.add("bv*+ba/b")
        for (h in heights) {
            labels.add("${h}p")
            selectors.add("bv*[height<=$h]+ba/b[height<=$h]/b")
        }
        labels.add("صوت فقط (MP3)"); selectors.add("AUDIO")

        dialog = AlertDialog.Builder(this)
            .setTitle(title.take(70))
            .setItems(labels.toTypedArray()) { _, which ->
                startDownload(u, title, selectors[which])
            }
            .setOnCancelListener { finish() }
            .create()
        dialog?.show()
    }

    private fun showError(msg: String) {
        if (isFinishing) return
        dialog?.dismiss()
        var text = msg.take(300)
        val low = msg.lowercase()
        if (low.contains("login") || low.contains("cookie") || low.contains("rate")) {
            text += "\n\nجرّب استيراد ملف cookies.txt من داخل التطبيق."
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("تعذّر جلب الفيديو")
            .setMessage(text)
            .setPositiveButton("حسناً") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog?.show()
    }

    private fun startDownload(u: String, title: String, selector: String) {
        val i = Intent(this, DownloadService::class.java)
        i.putExtra("url", u)
        i.putExtra("title", title)
        i.putExtra("selector", selector)
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "بدأ التحميل بالخلفية ✔", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }
}
