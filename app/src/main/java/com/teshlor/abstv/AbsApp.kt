package com.teshlor.abstv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

/** Application class: owns the app-wide Coil [ImageLoader] used for book covers. */
class AbsApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        // Audiobookshelf sends no Cache-Control / ETag for resized covers (they are streamed from its
        // thumbnail cache), so with Coil's default header handling every memory-cache miss was a full
        // network fetch. Give cover responses a long freshness lifetime so the disk cache is used.
        // Staleness is bounded: a changed cover shows up within a week (or after clearing app data).
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                if (resp.isSuccessful && resp.request.url.encodedPath.endsWith("/cover")) {
                    resp.newBuilder()
                        .removeHeader("Pragma")
                        .header("Cache-Control", "public, max-age=$COVER_MAX_AGE_SECONDS")
                        .build()
                } else resp
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("covers"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    private companion object {
        const val COVER_MAX_AGE_SECONDS = 7 * 24 * 60 * 60
    }
}
