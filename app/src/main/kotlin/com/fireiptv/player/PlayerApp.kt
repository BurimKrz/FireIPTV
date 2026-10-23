package com.fireiptv.player

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Inject

@HiltAndroidApp
class PlayerApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var okHttpClient: OkHttpClient

    /**
     * Channel logos. Coil keeps a downloaded logo on disk and serves it from there without asking
     * the server again (cache headers are ignored), so each logo is fetched once until the 50 MB
     * cache is full and the least recently shown logos make room.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("logos").toOkioPath())
                    .maxSizeBytes(LOGO_DISK_CACHE_BYTES)
                    .build()
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.1).build() }
            .build()

    private companion object {
        const val LOGO_DISK_CACHE_BYTES = 50L * 1024 * 1024
    }
}
