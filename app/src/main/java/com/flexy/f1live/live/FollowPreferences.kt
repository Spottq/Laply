package com.flexy.f1live.live

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent state of the "Follow" opt-in. SharedPreferences rather than DataStore: three scalars,
 * read from a BroadcastReceiver as often as from the UI, and no new dependency for it.
 *
 * Two different things are stored, and the difference matters:
 *  * [enabled] is the user's opt-in ("auto-follow sessions"). Only the Follow button changes it;
 *    a session ending, a timeout or the notification's Stop action never do.
 *  * [dismissedUntil] / [handledUntil] are per-session suppressions that expire by themselves, so
 *    a single dismissed or finished session does not keep popping back up while the opt-in stays.
 */
object FollowPreferences {

    private const val FILE = "live_follow"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISMISSED_UNTIL = "dismissed_until"
    private const val KEY_HANDLED_UNTIL = "handled_until"

    private val _enabled = MutableStateFlow(false)

    /** The opt-in, as a flow the Follow button renders directly - correct from the first frame. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Loads [enabled]; called from F1App.onCreate, i.e. before any activity or receiver runs. */
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
        // A fresh opt-in means "yes, show it" - forget any earlier per-session dismissal.
        if (value) editor.remove(KEY_DISMISSED_UNTIL).remove(KEY_HANDLED_UNTIL)
        editor.apply()
        _enabled.value = value
        loaded = true
    }

    /** Auto-starts of any kind stay off until this instant (the user tapped Stop). */
    fun dismissedUntil(context: Context): Long = prefs(context).getLong(KEY_DISMISSED_UNTIL, 0L)

    fun setDismissedUntil(context: Context, until: Long) {
        prefs(context).edit().putLong(KEY_DISMISSED_UNTIL, until).apply()
    }

    /**
     * Launch-time auto-starts stay off until this instant: the service already ran for this session
     * and ended on its own (finished, or gave up waiting). The live-feed trigger ignores it, since a
     * feed that is actually live is better evidence than the timeout that gave up on it.
     */
    fun handledUntil(context: Context): Long = prefs(context).getLong(KEY_HANDLED_UNTIL, 0L)

    fun setHandledUntil(context: Context, until: Long) {
        val prefs = prefs(context)
        if (until > prefs.getLong(KEY_HANDLED_UNTIL, 0L)) {
            prefs.edit().putLong(KEY_HANDLED_UNTIL, until).apply()
        }
    }
}
