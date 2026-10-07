package com.flexy.f1live.live

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.text.SpannableString
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import androidx.annotation.RequiresApi
import com.flexy.f1live.R
import com.flexy.f1live.data.Graph
import com.flexy.f1live.data.LiveTimingClient
import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Foreground service that mirrors the live session onto an Android 16 **Live Update**: an ongoing
 * notification requesting promotion (`setRequestPromotedOngoing`) and styled with
 * `Notification.ProgressStyle` so it shows up as a chip in the status bar and as a progress row on
 * the lock screen.
 *
 * Lifecycle: [start] launches it, optionally for a scheduled [SessionTarget]; [stop] (the Follow
 * button turned off) stops it; the notification's Stop action sends [ACTION_STOP], which ends it
 * *for this session only* - the Follow opt-in stays on and the next session starts it again (see
 * [FollowPreferences.dismissedUntil]). While alive it owns [Graph.liveTiming] - it starts the feed
 * on its first start command and stops it on destroy; the Live screen's ViewModel restarts it if
 * the user is looking at it.
 *
 * A run has two phases. It usually starts *before* anything is live - the alarm fires a few
 * minutes ahead of the scheduled start, and the feed meanwhile replays last session's finished
 * classification from disk - so until the feed reports the session running, the service shows a
 * "starts at 15:00" placeholder and ignores end-of-session statuses, which belong to the previous
 * session. It gives up quietly at [AutoFollowPlanner.waitDeadline]. Once the session was seen live
 * the finish rules apply: once the chequered flag is out ([LiveNotificationBuilder.isFinished]) the
 * final order stays up, titled FINISHED, and the Live Update removes itself 10 minutes later.
 *
 * On Android 17+ (API 37) it uses `Notification.MetricStyle` instead - the top-three gaps as three
 * metric cells, see [LiveNotificationBuilder.metrics] - unless the user switched that off in
 * Settings ([AppSettings.metricStyle]); the switch is observed, so flipping it re-renders at once.
 *
 * On API 31..35 (and on API 36 when the app is not allowed to post promoted notifications) it
 * degrades to a plain ongoing notification with the same title/text and a classic progress bar.
 */
class LiveUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notifications: NotificationManager

    /** Fingerprint of the last posted notification; used to avoid redundant notify() calls. */
    private var lastSummary: String? = null

    private var latestState: LiveSessionState = LiveSessionState.EMPTY
    private var latestContent: LiveNotificationBuilder.Content? = null

    /** Cache key of the tracker icon we asked for, so a late download can be discarded. */
    private var wantedIconKey: String? = null

    private var shuttingDown = false

    /** Collector of the live feed. */
    private var feedJob: Job? = null

    /** The scheduled session this run is for; null when started without one (none matched). */
    private var target: SessionTarget? = null

    /** Wall-clock instant after which a run that never saw the session live gives up. */
    private var waitDeadline = 0L

    /** Set once the feed reported the session running; until then the run is only waiting. */
    private var sawLive = false

    /** The feed is started by the first real start command, not by onCreate (see [ACTION_STOP]). */
    private var feedStarted = false

    /** Counts down [FINISHED_LINGER_MS] once the session is finished; null while it runs. */
    private var finishJob: Job? = null

    /** The Settings preview ([LiveDemo]) while it plays; null for a real session. */
    private var demoJob: Job? = null

    private var styleObserved = false

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveUpdateController.setServiceRunning(true)
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    /**
     * Every start other than [ACTION_STOP] came through startForegroundService(), so every one of
     * them - including a second start that only brings a [SessionTarget] - must call
     * startForeground(), or Android kills the app for breaking that promise.
     *
     * [ACTION_STOP] comes from the notification through a plain startService(); when it reaches a
     * freshly created instance nothing is shown or connected before it stops again.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            dismissForThisSession()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DEMO) {
            startDemo()
            return START_NOT_STICKY
        }
        // A real start takes over from a running demo.
        if (demoJob != null) {
            demoJob?.cancel()
            demoJob = null
            sawLive = false
            lastSummary = null
        }

        val now = System.currentTimeMillis()
        val newTarget = intent?.let { readTarget(it) }
        if (newTarget != null && newTarget != target) {
            target = newTarget
            waitDeadline = maxOf(waitDeadline, AutoFollowPlanner.waitDeadline(newTarget, now))
        } else if (waitDeadline == 0L) {
            waitDeadline = AutoFollowPlanner.waitDeadline(null, now)
        }

        val content = contentFor(latestState)
        latestContent = content
        lastSummary = content.summary
        if (!promote(content)) {
            shuttingDown = true
            stopSelf()
            return START_NOT_STICKY
        }
        // The Live Update replaces the "tap to follow" fallback, if one was posted.
        runCatching { notifications.cancel(AutoFollow.FALLBACK_NOTIFICATION_ID) }

        if (!feedStarted) {
            feedStarted = true
            val client = client()
            runCatching { client?.start() }
            if (client != null) observe(client)
            startWatchdog()
            observeStyleSetting()
        }
        return START_NOT_STICKY
    }

    /**
     * Enters the foreground as `specialUse` (see the manifest for why not `dataSync`). Returns
     * false when Android refuses - a background start that no exemption covered - after leaving
     * the "tap to follow" notification in its place.
     */
    private fun promote(content: LiveNotificationBuilder.Content): Boolean {
        val notification = buildNotification(content, icon = null)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: ForegroundServiceStartNotAllowedException) {
            AutoFollow.postSessionStarting(this, target)
            false
        } catch (_: RuntimeException) {
            AutoFollow.postSessionStarting(this, target)
            false
        }
    }

    /**
     * "Test Live Update" from Settings: plays [LiveDemo] through the very same builder and
     * notification code, without the feed, the watchdog or any auto-follow bookkeeping. Ignored
     * while a real session is being followed - that notification is the better preview.
     */
    private fun startDemo() {
        if (feedStarted && demoJob == null) return
        demoJob?.cancel()
        sawLive = true
        val first = LiveNotificationBuilder.build(LiveDemo.frame(0), connectingText())
        latestContent = first
        lastSummary = first.summary
        if (!promote(first)) {
            shuttingDown = true
            stopSelf()
            return
        }
        observeStyleSetting()
        demoJob = scope.launch {
            for (index in 0 until LiveDemo.FRAME_COUNT) {
                val content = LiveNotificationBuilder.build(LiveDemo.frame(index), connectingText())
                latestContent = content
                ensureIcon(content.leader)
                post(content)
                delay(LiveDemo.FRAME_INTERVAL_MS)
            }
            // Hold the last frame a little, then leave without a "Session finished" card.
            delay(DEMO_HOLD_MS)
            shuttingDown = true
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // The feed is shared with the Live screen: only close it when nobody is looking.
        if (feedStarted && !LiveUpdateController.uiActive) runCatching { client()?.stop() }
        LiveUpdateController.setServiceRunning(false)
        AvatarIcons.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ feed

    /** [Graph] is wired in F1App.onCreate(); be defensive in case the service outlives it. */
    private fun client(): LiveTimingClient? = runCatching { Graph.liveTiming }.getOrNull()

    /**
     * Renders the current state, then waits [UPDATE_INTERVAL_MS] before looking again. A StateFlow
     * is inherently conflated, so the sleep caps the notification at one update every two seconds
     * while still rendering the newest snapshot rather than a stale queued one.
     */
    private fun observe(client: LiveTimingClient) {
        feedJob?.cancel()
        feedJob = scope.launch {
            client.state.collect { state ->
                render(state)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * The MetricStyle switch in Settings: re-post the current content in the other style right
     * away. The summary fingerprint does not change with the style, so without this the switch
     * would only show on the next timing change - which, between sessions, can be never.
     */
    private fun observeStyleSetting() {
        if (Build.VERSION.SDK_INT < AppSettings.API_METRIC_STYLE || styleObserved) return
        styleObserved = true
        scope.launch {
            AppSettings.metricStyle.drop(1).collect { latestContent?.let { post(it) } }
        }
    }

    /** Catches the "FINISHED and nothing happened for 15 minutes" case, which produces no events. */
    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                checkAutoStop(latestState)
            }
        }
    }

    private fun render(state: LiveSessionState) {
        if (shuttingDown) return
        latestState = state
        if (!sawLive && isNetworkLive(state)) sawLive = true
        val content = contentFor(state)
        latestContent = content
        if (sawLive) ensureIcon(content.leader)
        if (content.summary != lastSummary) {
            lastSummary = content.summary
            post(content)
        }
        checkAutoStop(state)
    }

    private fun post(content: LiveNotificationBuilder.Content) {
        if (shuttingDown) return
        val icon = content.leader?.let { AvatarIcons.cached(it) ?: AvatarIcons.fallback(it) }
        runCatching { notifications.notify(NOTIFICATION_ID, buildNotification(content, icon)) }
    }

    /**
     * What the notification shows: the session itself once it was seen live, a placeholder for
     * the scheduled session before that. Last session's classification - which the feed replays
     * from disk while it connects - never reaches the Live Update.
     */
    private fun contentFor(state: LiveSessionState): LiveNotificationBuilder.Content {
        if (sawLive) return LiveNotificationBuilder.build(state, connectingText())
        val base = LiveNotificationBuilder.build(LiveSessionState.EMPTY, waitingText())
        val label = target?.label?.takeIf { it.isNotBlank() } ?: return base
        return base.copy(title = label, summary = base.summary + "|" + label)
    }

    /** "Starts at 15:00 · waiting for timing"; plain "Connecting…" without a scheduled session. */
    private fun waitingText(): String {
        val start = target?.startUtcMillis ?: return connectingText()
        val time = DateFormat.getTimeFormat(this).format(Date(start))
        return getString(R.string.live_notif_waiting, time)
    }

    /**
     * Kicks off a headshot download for the current leader. The notification is posted immediately
     * with the team-colour disc; when the real avatar arrives we re-post once.
     */
    private fun ensureIcon(driver: DriverTiming?) {
        if (driver == null) return
        val key = AvatarIcons.keyFor(driver)
        if (key == wantedIconKey) return
        wantedIconKey = key
        if (AvatarIcons.cached(driver) != null) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { AvatarIcons.load(applicationContext, driver) }.getOrNull()
            }
            if (loaded != null && wantedIconKey == key) {
                latestContent?.let { post(it) }
            }
        }
    }

    // ------------------------------------------------------------------ auto-stop

    private fun checkAutoStop(state: LiveSessionState) {
        if (shuttingDown) return
        if (!sawLive) {
            // Still waiting: end-of-session statuses here are the *previous* session's, except
            // when the network says the very session we are waiting for is already over.
            val expired = System.currentTimeMillis() >= waitDeadline
            if (expired || targetAlreadyOver(state)) quit()
            return
        }
        if (!LiveNotificationBuilder.isFinished(state)) {
            // Back to running (a restart after a red flag): the countdown no longer applies.
            finishJob?.cancel()
            finishJob = null
            return
        }
        if (finishJob != null) return
        // The final order stays up as the Live Update, titled FINISHED, then goes away by itself.
        finishJob = scope.launch {
            delay(FINISHED_LINGER_MS)
            quit()
        }
    }

    /**
     * Removes the Live Update and shuts down: the session never went live while we waited, or it
     * finished [FINISHED_LINGER_MS] ago.
     */
    private fun quit() {
        shuttingDown = true
        markHandled()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * The notification's Stop: end this run and keep every automatic start away until the end of
     * the session it was showing. The Follow opt-in itself stays on - only the button turns it off.
     */
    private fun dismissForThisSession() {
        if (demoJob != null) {
            demoJob?.cancel()
            shuttingDown = true
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        val until = AutoFollowPlanner.suppressUntil(target, System.currentTimeMillis())
        FollowPreferences.setDismissedUntil(this, until)
        shuttingDown = true
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** This session is done; app launches during the rest of its window must not restart it. */
    private fun markHandled() {
        val session = target ?: return
        FollowPreferences.setHandledUntil(this, AutoFollowPlanner.windowEnd(session))
    }

    /**
     * True when a network source (not the disk replay) reports the scheduled session itself as
     * finalised - e.g. the app was opened an hour after the chequered flag, still inside the
     * session's window. Matched on the session kind: before a session the feed still shows the
     * previous one, which within a weekend is always of another kind.
     */
    private fun targetAlreadyOver(state: LiveSessionState): Boolean {
        val session = target ?: return false
        if (session.kind == SessionKind.UNKNOWN || state.sessionKind != session.kind) return false
        if (state.source == LiveSource.CACHE || state.source == LiveSource.NONE) return false
        return LiveNotificationBuilder.isSessionOver(state.status)
    }

    private fun isNetworkLive(state: LiveSessionState): Boolean =
        state.isLive && state.source != LiveSource.CACHE

    // ------------------------------------------------------------------ notifications

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_live_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_live_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun buildNotification(
        content: LiveNotificationBuilder.Content,
        icon: Icon?,
    ): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_f1)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(content.accentColor)
            .setShowWhen(false)
            .addAction(stopAction(content.accentColor))
        contentIntent()?.let { builder.setContentIntent(it) }

        if (Build.VERSION.SDK_INT >= AppSettings.API_METRIC_STYLE && useMetricStyle(content)) {
            val metric = buildMetricNotification(builder, content)
            if (metric != null) return metric
        }
        if (Build.VERSION.SDK_INT >= API_LIVE_UPDATES) {
            applyLiveUpdate(builder, content, icon)
        } else {
            applyLegacyProgress(builder, content)
        }
        return builder.build()
    }

    /**
     * MetricStyle only once there is something to measure: while connecting or waiting for the
     * start there are no metrics, and the ProgressStyle's text ("Starts at 15:00") is the only
     * thing worth showing - MetricStyle would hide it.
     */
    private fun useMetricStyle(content: LiveNotificationBuilder.Content): Boolean =
        AppSettings.metricStyle.value && content.metrics.isNotEmpty()

    /**
     * Android 17+: the same promoted ongoing notification, styled with MetricStyle. The title,
     * colour, chip text, Stop action and tap intent are the ones the ProgressStyle path uses -
     * [builder] arrives with most of them set. The title is kept even though a promoted
     * MetricStyle could fall back to the first metric's value: it is what names the session.
     *
     * Returns null - and the caller falls back to ProgressStyle - when promotion was requested but
     * the system does not consider this MetricStyle notification promotable
     * ([Notification.hasPromotableCharacteristics]): a Live Update with a status-bar chip is worth
     * more than the nicer layout. Also null if the framework rejects the metrics outright.
     */
    @RequiresApi(AppSettings.API_METRIC_STYLE)
    private fun buildMetricNotification(
        builder: Notification.Builder,
        content: LiveNotificationBuilder.Content,
    ): Notification? {
        val promotable = runCatching { notifications.canPostPromotedNotifications() }.getOrDefault(false)
        return runCatching {
            val metrics = content.metrics.take(LiveNotificationBuilder.MAX_METRICS).map { spec ->
                Notification.Metric(
                    Notification.Metric.FixedText(spec.value),
                    spec.label,
                    semanticStyle(spec.semantic),
                )
            }
            val style = Notification.MetricStyle().setMetrics(metrics)
            val critical = content.criticalMetric
            style.setCriticalMetric(
                if (critical in metrics.indices) critical else Notification.MetricStyle.METRIC_INDEX_NONE,
            )
            if (promotable) builder.setRequestPromotedOngoing(true)
            content.shortCriticalText?.let { builder.setShortCriticalText(it) }
            builder.setStyle(style)
            val notification = builder.build()
            if (promotable && !notification.hasPromotableCharacteristics()) null else notification
        }.getOrNull().also { built ->
            // The builder is reused by the fallback: drop the style it must not inherit.
            if (built == null) builder.setStyle(null)
        }
    }

    @RequiresApi(AppSettings.API_METRIC_STYLE)
    private fun semanticStyle(semantic: LiveNotificationBuilder.MetricSemantic): Int = when (semantic) {
        LiveNotificationBuilder.MetricSemantic.UNSPECIFIED -> Notification.SEMANTIC_STYLE_UNSPECIFIED
        LiveNotificationBuilder.MetricSemantic.INFO -> Notification.SEMANTIC_STYLE_INFO
        LiveNotificationBuilder.MetricSemantic.SAFE -> Notification.SEMANTIC_STYLE_SAFE
        LiveNotificationBuilder.MetricSemantic.CAUTION -> Notification.SEMANTIC_STYLE_CAUTION
        LiveNotificationBuilder.MetricSemantic.DANGER -> Notification.SEMANTIC_STYLE_DANGER
    }

    /** Android 16+: promoted ongoing notification with a ProgressStyle bar. */
    @RequiresApi(API_LIVE_UPDATES)
    private fun applyLiveUpdate(
        builder: Notification.Builder,
        content: LiveNotificationBuilder.Content,
        icon: Icon?,
    ) {
        // Promotion is only granted to apps the system trusts for it; when it is not granted we
        // still post the very same notification, just without the status-bar chip.
        val promotable = runCatching { notifications.canPostPromotedNotifications() }.getOrDefault(false)
        if (promotable) builder.setRequestPromotedOngoing(true)

        content.shortCriticalText?.let { builder.setShortCriticalText(it) }

        val style = Notification.ProgressStyle().setStyledByProgress(true)
        icon?.let { style.setProgressTrackerIcon(it) }
        when (val progress = content.progress) {
            is LiveNotificationBuilder.Progress.Indeterminate -> {
                style.setProgressIndeterminate(true)
                style.setProgressSegments(
                    List(INDETERMINATE_SEGMENTS) { Notification.ProgressStyle.Segment(1) },
                )
                style.setProgress(0)
            }

            is LiveNotificationBuilder.Progress.Segmented -> {
                style.setProgressIndeterminate(false)
                style.setProgressSegments(
                    progress.segments.map { (length, color) ->
                        Notification.ProgressStyle.Segment(length)
                            .also { segment -> color?.let { segment.setColor(it) } }
                    },
                )
                style.setProgress(progress.current)
            }
        }
        builder.setStyle(style)
    }

    /** API 31..35: a classic determinate/indeterminate progress bar. */
    private fun applyLegacyProgress(
        builder: Notification.Builder,
        content: LiveNotificationBuilder.Content,
    ) {
        when (val progress = content.progress) {
            is LiveNotificationBuilder.Progress.Indeterminate ->
                builder.setProgress(0, 0, true)

            is LiveNotificationBuilder.Progress.Segmented ->
                builder.setProgress(progress.max, progress.current, false)
        }
    }

    private fun connectingText(): String = getString(R.string.live_notif_connecting)

    private fun stopAction(accent: Int): Notification.Action {
        val intent = Intent(this, LiveUpdateService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(
            this,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // setColor() no longer tints anything in a non-colorized notification (the system themes
        // it with the wallpaper colours), and colorizing would cost the promotion - so the accent
        // (the leader's team colour) goes into the label itself as a colour span, which survives
        // into the system UI.
        val label = SpannableString(getString(R.string.live_notif_stop)).apply {
            setSpan(ForegroundColorSpan(accent), 0, length, 0)
        }
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_f1),
            label,
            pending,
        ).build()
    }

    /**
     * Opens the app. Resolved through the package manager so this module keeps no compile-time
     * dependency on the ui package.
     */
    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_STOP = "com.flexy.f1live.live.action.STOP"
        const val ACTION_DEMO = "com.flexy.f1live.live.action.DEMO"

        private const val CHANNEL_ID = "live_timing"
        private const val NOTIFICATION_ID = 4101

        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2

        /** Android 16 (Baklava) - where Live Updates / ProgressStyle appear. */
        private const val API_LIVE_UPDATES = 36

        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        /** How long the finished classification stays up before the Live Update goes away. */
        private const val FINISHED_LINGER_MS = 10L * 60L * 1000L
        private const val INDETERMINATE_SEGMENTS = 3
        private const val DEMO_HOLD_MS = 10_000L

        private const val EXTRA_START = "com.flexy.f1live.live.extra.START"
        private const val EXTRA_KIND = "com.flexy.f1live.live.extra.KIND"
        private const val EXTRA_LABEL = "com.flexy.f1live.live.extra.LABEL"

        /**
         * Starts (or re-targets) the Live Update. Returns false when Android refuses a foreground
         * service start from the background (ForegroundServiceStartNotAllowedException, API 31+);
         * the caller decides what to show instead.
         */
        fun start(context: Context, target: SessionTarget? = null): Boolean {
            val intent = Intent(context, LiveUpdateService::class.java)
            target?.let { putTarget(intent, it) }
            return try {
                context.startForegroundService(intent) != null
            } catch (_: ForegroundServiceStartNotAllowedException) {
                false
            } catch (_: RuntimeException) {
                // SecurityException / IllegalStateException on odd OEM builds: never crash for it.
                false
            }
        }

        /** Plays the scripted [LiveDemo] race as a Live Update; started from the foreground only. */
        fun startDemo(context: Context): Boolean = try {
            val intent = Intent(context, LiveUpdateService::class.java).setAction(ACTION_DEMO)
            context.startForegroundService(intent) != null
        } catch (_: RuntimeException) {
            false
        }

        /**
         * Stops the Live Update without recording a dismissal (the opt-in was switched off).
         * stopService() rather than a Stop intent: it never creates the service just to stop it.
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveUpdateService::class.java)) }
        }

        fun putTarget(intent: Intent, target: SessionTarget) {
            intent.putExtra(EXTRA_START, target.startUtcMillis)
            intent.putExtra(EXTRA_KIND, target.kind.name)
            intent.putExtra(EXTRA_LABEL, target.label)
        }

        fun readTarget(intent: Intent): SessionTarget? {
            val start = intent.getLongExtra(EXTRA_START, 0L).takeIf { it > 0L } ?: return null
            val kind = intent.getStringExtra(EXTRA_KIND)
                ?.let { name -> SessionKind.entries.firstOrNull { it.name == name } }
                ?: SessionKind.UNKNOWN
            return SessionTarget(start, kind, intent.getStringExtra(EXTRA_LABEL).orEmpty())
        }
    }
}
