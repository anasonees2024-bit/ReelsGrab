package com.reelsgrab

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

class ReelsAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var button: ImageView? = null
    private var busy = false

    private val targetPackages = setOf(
        "com.instagram.android", "com.instagram.lite",
        "com.facebook.katana", "com.facebook.lite"
    )

    private val shareLabels = listOf("share", "send post", "send", "مشاركة", "إرسال", "ارسال")
    private val copyLabels = listOf("copy link", "نسخ الرابط", "نسخ رابط")

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            hideButton()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg in targetPackages -> showButton()
            isIgnorable(pkg) -> {}
            else -> hideButton()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideButton()
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun isIgnorable(pkg: String): Boolean =
        pkg == packageName || pkg == "android" || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") || pkg.contains("keyboard")

    // ---------- الزر العائم ----------

    private fun showButton() {
        if (button != null) return
        val w = wm ?: return
        val dm = resources.displayMetrics
        val size = (56 * dm.density).toInt()
        val type = if (Settings.canDrawOverlays(this))
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        lp.x = prefs.getInt("x", dm.widthPixels - size - (8 * dm.density).toInt())
        lp.y = prefs.getInt("y", (dm.heightPixels * 0.45f).toInt())

        val v = ImageView(this)
        v.setImageResource(R.drawable.ic_fab)
        v.alpha = 0.92f
        v.setOnTouchListener(DragListener(lp))
        try {
            w.addView(v, lp)
            button = v
        } catch (_: Exception) {
        }
    }

    private fun hideButton() {
        val v = button ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        button = null
        busy = false
    }

    private inner class DragListener(val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private val slop = ViewConfiguration.get(this@ReelsAccessibilityService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap() else {
                        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                            .putInt("x", lp.x).putInt("y", lp.y).apply()
                    }
                }
            }
            return true
        }
    }

    // ---------- التدفق عند الضغط ----------

    private fun onTap() {
        if (busy) return
        busy = true
        button?.alpha = 0.4f
        toast("جاري جلب الرابط…")

        val root = rootInActiveWindow
        val hasCopy = root?.let { findClickable(it, copyLabels) } != null
        if (hasCopy) {
            tryCopy(0)
        } else {
            val share = root?.let { findClickable(it, shareLabels) }
            share?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ tryCopy(0) }, 600)
        }
    }

    private fun tryCopy(attempt: Int) {
        val node = rootInActiveWindow?.let { findClickable(it, copyLabels) }
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            launchPicker(700)
            return
        }
        if (attempt < 4) {
            handler.postDelayed({ tryCopy(attempt + 1) }, 450)
        } else {
            toast("ما لقيت زر نسخ الرابط، بجرب الرابط المنسوخ")
            launchPicker(0)
        }
    }

    private fun launchPicker(delay: Long) {
        handler.postDelayed({
            busy = false
            button?.alpha = 0.92f
            val i = Intent(this, PickerActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            i.putExtra("from_clipboard", true)
            try {
                startActivity(i)
            } catch (_: Exception) {
                toast("فعّل «العرض فوق التطبيقات» من إعدادات Reels Grab")
            }
        }, delay)
    }

    private fun findClickable(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val low = ((n.text?.toString() ?: "") + "|" + (n.contentDescription?.toString() ?: "")).lowercase()
            if (labels.any { low.contains(it) }) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null) return c
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
