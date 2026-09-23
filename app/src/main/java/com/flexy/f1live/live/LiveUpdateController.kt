package com.flexy.f1live.live

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-facing switch for the "Follow" button. Two states that used to be one, and are not the same:
 *
 *  * [followEnabled] - the persisted opt-in ("auto-follow sessions"). This is what the button
 *    shows, from the very first frame after launch and also between sessions.
 *  * [serviceRunning] - [LiveUpdateService] is up right now and posting a Live Update (Android 16
 *    ProgressStyle promoted ongoing notification). The service flips it in onCreate/onDestroy.
 *
 * With the opt-in on, the service runs during sessions and not in between; see [AutoFollow].
 */
object LiveUpdateController {
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    val followEnabled: StateFlow<Boolean> get() = FollowPreferences.enabled

    /**
     * True while the Live screen is on screen. The screen and the service share one feed
     * connection; whoever goes away last is the one that closes it.
     */
    @Volatile
    var uiActive: Boolean = false

    fun follow(context: Context) = AutoFollow.setEnabled(context, true)
    fun unfollow(context: Context) = AutoFollow.setEnabled(context, false)

    internal fun setServiceRunning(value: Boolean) {
        _serviceRunning.value = value
    }
}
