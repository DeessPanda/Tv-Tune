package com.tvtune.music

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.coreModule
import com.metrolist.shared.youtube.di.sharedModule
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import com.tvtune.music.playback.TvPlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.io.File

class TvApp : Application(), SingletonImageLoader.Factory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AndroidContextProvider.applicationContext = this@TvApp

        val tvModule = module {
            single<CoroutineScope> { applicationScope }
            single<MusicDatabase> {
                getDatabaseBuilder(androidContext())
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            }
            single<DatabaseDao> {
                get<MusicDatabase>().dao()
            }
            single<com.tvtune.music.history.TvHistoryManager> {
                com.tvtune.music.history.TvHistoryManager(androidContext(), get())
            }
            single<TvPlaybackManager> {
                TvPlaybackManager(
                    context = androidContext(),
                    streamResolver = get(),
                    historyManager = get(),
                    lyricsRepository = getOrNull(),
                    scope = get()
                )
            }
        }

        startKoin {
            androidContext(this@TvApp)
            modules(coreModule, sharedModule, tvModule)
        }

        // Apply content region
        GlobalYouTube.applyContentRegion("US", "en", "US", "en")
    }

    override fun newImageLoader(context: Context): ImageLoader {
        // Optimized for low-memory Android TV (~20MB memory cache, RGB_565 decoding)
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { sharedOkHttpClient }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(20L * 1024L * 1024L) // 20 MB max memory cache
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
