package io.theficos.ereader

import android.app.Application
import io.theficos.ereader.di.AppContainer

class EReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
