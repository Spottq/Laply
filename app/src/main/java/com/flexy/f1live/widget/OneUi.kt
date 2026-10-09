package com.flexy.f1live.widget

import android.content.Context
import android.os.Build
import android.os.Bundle

// The checks follow twidget and blur-widget-demo (MIT, (c) 2026 Josh Skinner).
object OneUi {

    private val semPlatformInt: Int by lazy {
        runCatching { Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null) }.getOrDefault(0)
    }

    val majorVersion: Int
        get() = if (semPlatformInt > 90000) (semPlatformInt - 90000) / 10000 else 0

    fun isOneUi(context: Context): Boolean =
        semPlatformInt > 0 ||
            runCatching {
                context.packageManager.hasSystemFeature("com.samsung.feature.samsung_experience_mobile")
            }.getOrDefault(false)

    fun supportsHomeBlur(context: Context): Boolean = isOneUi(context) && majorVersion >= 7

    fun isOneUiHomeHost(options: Bundle): Boolean =
        options.containsKey("semAppWidgetColumnSpan") ||
            options.containsKey("semAppWidgetRowSpan") ||
            options.containsKey("semWidgetSize")
}
