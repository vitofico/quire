package io.theficos.ereader.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale

internal fun localeContext(tag: String): Context {
    val app = ApplicationProvider.getApplicationContext<Context>()
    return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(tag))
    })
}

internal fun englishContext(): Context = localeContext("en-US")
