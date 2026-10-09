package com.flexy.f1live

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.flexy.f1live.data.CircuitMapResolver
import com.flexy.f1live.data.CircuitMapStore
import com.flexy.f1live.data.CompositeLiveTimingClient
import com.flexy.f1live.data.EspnLiveClient
import com.flexy.f1live.data.F1SignalRClient
import com.flexy.f1live.data.Graph
import com.flexy.f1live.data.JolpicaScheduleRepository
import com.flexy.f1live.data.JolpicaStandingsRepository
import com.flexy.f1live.data.JsonStore
import com.flexy.f1live.data.LastSessionStore
import com.flexy.f1live.data.SessionPersister
import com.flexy.f1live.data.SessionResultsRepository
import com.flexy.f1live.data.SessionResultsStore
import com.flexy.f1live.data.TeamLogos
import com.flexy.f1live.data.WeatherRepository
import com.flexy.f1live.live.AutoFollow
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.settings.AppSettings
import com.flexy.f1live.ui.components.flagCdnUrl
import com.flexy.f1live.update.UpdateCheckWorker
import com.flexy.f1live.widget.WidgetUpdater
import com.flexy.f1live.widget.standings.StandingsWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class F1App : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
            .connectionPool(ConnectionPool(MAX_REQUESTS_PER_HOST, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val imageHttp: OkHttpClient by lazy {
        http.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", IMAGE_USER_AGENT)
                        .build(),
                )
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        AppSettings.load(this)

        Graph.http = http
        val jsonStore = JsonStore(File(filesDir, "cache-json"))
        val lastSession = LastSessionStore(jsonStore)
        val sessionResults = SessionResultsStore(jsonStore)
        JolpicaStandingsRepository.disk = jsonStore

        Graph.schedule = JolpicaScheduleRepository(
            http,
            disk = jsonStore,
            onFetched = { _, _ ->
                AutoFollow.reschedule(this)
                WidgetUpdater.refresh(this)
                WidgetUpdater.publishPreviewsIfDue(this)
                StandingsWidgetUpdater.refresh(this)
            },
        )
        Graph.liveTiming = CompositeLiveTimingClient(
            primary = F1SignalRClient(http),
            fallback = EspnLiveClient(http),
            cache = lastSession,
        )
        Graph.sessionResults = SessionResultsRepository(
            http = http,
            store = sessionResults,
            onResults = ::prefetchImages,
        )
        Graph.circuitMaps = CircuitMapResolver(http, CircuitMapStore(jsonStore))
        Graph.weather = WeatherRepository(http)

        SessionPersister(
            scope = appScope,
            results = sessionResults,
            last = lastSession,
            schedule = Graph.schedule,
            onSaved = ::prefetchImages,
        ).attach(Graph.liveTiming.state)

        appScope.launch { lastSession.preload() }

        AutoFollow.attach(this)

        WidgetUpdater.attach(this)
        StandingsWidgetUpdater.attach(this)

        appScope.launch {
            AppSettings.checkUpdates.collect { enabled ->
                runCatching { UpdateCheckWorker.sync(this@F1App, enabled) }
            }
        }
    }

    private fun prefetchImages(state: LiveSessionState) {
        val loader = SingletonImageLoader.get(this)
        val urls = buildSet {
            for (driver in state.drivers) {
                driver.headshotUrl?.let(::add)
                flagCdnUrl(driver.countryCode)?.let(::add)
                TeamLogos.forTeamName(driver.teamName)?.let(::add)
            }
        }
        for (url in urls) {
            loader.enqueue(ImageRequest.Builder(this).data(url).build())
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()

    private companion object {
        const val MAX_REQUESTS_PER_HOST = 16

        const val IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}
