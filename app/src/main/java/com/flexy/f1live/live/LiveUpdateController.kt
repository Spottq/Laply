package com.flexy.f1live.live

import android.content.Context
import com.flexy.f1live.data.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object LiveUpdateController {
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    val followEnabled: StateFlow<Boolean> get() = FollowPreferences.enabled

    private val uiUsers = AtomicInteger(0)

    val uiActive: Boolean get() = uiUsers.get() > 0

    fun attachUi() {
        uiUsers.incrementAndGet()
        runCatching { Graph.liveTiming.start() }
    }

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
