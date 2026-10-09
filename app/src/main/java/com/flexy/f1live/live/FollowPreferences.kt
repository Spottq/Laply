package com.flexy.f1live.live

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FollowPreferences {

    private const val FILE = "live_follow"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISMISSED_UNTIL = "dismissed_until"
    private const val KEY_HANDLED_UNTIL = "handled_until"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context) {
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
        loaded = true
    }

    fun isEnabled(context: Context): Boolean {
        if (!loaded) load(context)
        return _enabled.value
    }

    fun setEnabled(context: Context, value: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_ENABLED, value)
        if (value) editor.remove(KEY_DISMISSED_UNTIL).remove(KEY_HANDLED_UNTIL)
        editor.apply()
        _enabled.value = value
        loaded = true
    }

    fun dismissedUntil(context: Context): Long = prefs(context).getLong(KEY_DISMISSED_UNTIL, 0L)

    fun setDismissedUntil(context: Context, until: Long) {
        prefs(context).edit().putLong(KEY_DISMISSED_UNTIL, until).apply()
    }

    fun handledUntil(context: Context): Long = prefs(context).getLong(KEY_HANDLED_UNTIL, 0L)

    fun setHandledUntil(context: Context, until: Long) {
        val prefs = prefs(context)
        if (until > prefs.getLong(KEY_HANDLED_UNTIL, 0L)) {
            prefs.edit().putLong(KEY_HANDLED_UNTIL, until).apply()
        }
    }
}
