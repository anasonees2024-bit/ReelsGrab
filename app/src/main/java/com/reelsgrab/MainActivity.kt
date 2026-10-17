package com.reelsgrab

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(px(20), px(36), px(20), px(24))
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        fun label(s: String, size: Float, bold: Boolean = false): TextView {
            val t = TextView(this)
            t.text = s
            t.textSize = size
            if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
            t.setPadding(0, px(6), 0, px(6))
            return t
        }

        fun button(s: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = s
            b.setOnClickListener { onClick() }
            return b
        }

        root.addView(label("Reels Grab", 28f, true))
        status = label("", 15f)
        root.addView(status)

        root.addView(button("1) تفعيل خدمة إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("2) السماح بالعرض فوق التطبيقات") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("لو التفعيل رمادي: معلومات التطبيق") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("استيراد ملف cookies.txt (اختياري)") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, 7)
        })
        root.addView(button("حذف الكوكيز") {
            Cookies.file(this).delete()
            refresh()
        })

        root.addView(
            label(
                "الخطوات:\n" +
                    "1) فعّل الخدمة من الزر الأول.\n" +
                    "2) اسمح بالعرض فوق التطبيقات.\n" +
                    "3) افتح إنستجرام أو فيسبوك وادخل أي ريلز: يظهر زر التحميل العائم (تقدر تسحبه).\n" +
                    "4) اضغط الزر، اختر الجودة، ويبدأ التحميل بالخلفية مع إشعار تقدم.\n\n" +
                    "الفيديوهات تُحفظ في المعرض داخل مجلد ReelsGrab.\n\n" +
                    "بديل: من قائمة المشاركة اختر Reels Grab.\n\n" +
                    "إذا فشل إنستجرام برسالة تسجيل دخول، استورد ملف cookies.txt لحسابك.",
                14f
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        status.text = "الخدمة: ${mark(isAccessibilityOn())}\n" +
            "العرض فوق التطبيقات: ${mark(Settings.canDrawOverlays(this))}\n" +
            "المحرك: ${if (App.ready) "✅ جاهز" else "… جاري التجهيز"}\n" +
            "الكوكيز: ${if (Cookies.file(this).exists()) "✅" else "—"}"
    }

    private fun isAccessibilityOn(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("$packageName/")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    Cookies.file(this).outputStream().use { out -> input.copyTo(out) }
                }
                Toast.makeText(this, "تم استيراد الكوكيز", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل الاستيراد", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }
    }
}
