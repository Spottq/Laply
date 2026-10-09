package com.flexy.f1live.live

import android.content.Context
import com.flexy.f1live.data.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

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
     * Screens showing the live feed right now (the Live tab, the race control log). They and the
     * service share one feed connection; whoever goes away last is the one that closes it. A count
     * rather than a flag: opening the race control log from the Live tab enters the new screen
     * before the old one leaves, and the Live tab's goodbye must not close the feed under it.
     */
    private val uiUsers = AtomicInteger(0)

    val uiActive: Boolean get() = uiUsers.get() > 0

    /** A screen showing the feed appeared: make sure it is connected. */
    fun attachUi() {
        uiUsers.incrementAndGet()
        runCatching { Graph.liveTiming.start() }
    }

    /** A screen showing the feed went away: close it if nobody else needs it. */
    fun detachUi() {
        if (uiUsers.decrementAndGet() < 0) uiUsers.set(0)
        if (!uiActive && !serviceRunning.value) runCatching { Graph.liveTiming.stop() }
    }

    fun follow(context: Context) = AutoFollow.setEnabled(context, true)
    fun unfollow(context: Context) = AutoFollow.setEnabled(context, false)

    internal fun setServiceRunning(value: Boolean) {
        _serviceRunning.value = value
    }
}
