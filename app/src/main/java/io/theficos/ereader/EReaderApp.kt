package io.theficos.ereader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.theficos.ereader.di.AppContainer

class EReaderApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(container.opdsHttp.okHttp)
            .build()
}
