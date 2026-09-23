package com.flexy.f1live.widget

import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * Samsung One UI detection for the widgets' blur style.
 *
 * One UI Home (One UI 7+) blurs the wallpaper behind a widget by itself when the widget's
 * `@android:id/background` view has a background with an alpha of 1..254 and its provider declares
 * Samsung's `widgetStyle="colorful"` and a `widgetSize` (see res/values/attrs.xml). Nothing here
 * is public API: the checks follow twidget and blur-widget-demo (MIT, (c) 2026 Josh Skinner,
 * github.com/thatjoshguy67), and every one of them fails closed to the normal opaque widget.
 */
object OneUi {

    /**
     * `Build.VERSION.SEM_PLATFORM_INT` on Samsung builds, 0 elsewhere. One UI N reports
     * 90000 + N * 10000 (One UI 7 = 160000).
     */
    private val semPlatformInt: Int by lazy {
        runCatching { Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null) }.getOrDefault(0)
    }

    /** Major One UI version, or 0 when this is not One UI. */
    val majorVersion: Int
        get() = if (semPlatformInt > 90000) (semPlatformInt - 90000) / 10000 else 0

    fun isOneUi(context: Context): Boolean =
        semPlatformInt > 0 ||
            runCatching {
                context.packageManager.hasSystemFeature("com.samsung.feature.samsung_experience_mobile")
            }.getOrDefault(false)

    /** One UI 7 or newer, where One UI Home blurs behind translucent widgets. */
    fun supportsHomeBlur(context: Context): Boolean = isOneUi(context) && majorVersion >= 7

    /**
     * Whether the widget with these options is hosted by One UI Home: only Samsung's launcher puts
     * its grid spans in the options bundle. Any other launcher on a Samsung phone gets the opaque
     * style, since it would not blur and the glass would just be see-through.
     */
    fun isOneUiHomeHost(options: Bundle): Boolean =
        options.containsKey("semAppWidgetColumnSpan") ||
            options.containsKey("semAppWidgetRowSpan") ||
            options.containsKey("semWidgetSize")
}
