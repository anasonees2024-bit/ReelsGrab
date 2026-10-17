package com.reelsgrab

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

object Utils {
    fun extractUrl(text: String?): String? {
        if (text == null) return null
        val m = Regex("https?://[^\\s]+").find(text) ?: return null
        return m.value.trimEnd(')', ']', '.', ',', '"', '\'')
    }
}

object Cookies {
    fun file(ctx: Context): File = File(ctx.filesDir, "cookies.txt")

    fun apply(req: YoutubeDLRequest, ctx: Context) {
        val f = file(ctx)
        if (f.exists() && f.length() > 0) req.addOption("--cookies", f.absolutePath)
    }
}
