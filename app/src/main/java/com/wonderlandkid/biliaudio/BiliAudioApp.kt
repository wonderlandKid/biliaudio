package com.wonderlandkid.biliaudio

import android.app.Application
import com.wonderlandkid.biliaudio.data.net.BiliConstants
import com.wonderlandkid.biliaudio.di.AppContainer
import com.wonderlandkid.biliaudio.util.Diag
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class BiliAudioApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    /** 图片加载专用 OkHttp（B 站图床同样要求 UA/Referer）。 */
    val imageHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", BiliConstants.USER_AGENT)
                    .header("Referer", BiliConstants.REFERER)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        Diag.enabled = container.settingsRepository.settings.value.diagnosticLogsEnabled
        Diag.log("App", "启动 " + Diag.deviceInfo(this))
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient }))
            }
            .crossfade(true)
            .build()
}
