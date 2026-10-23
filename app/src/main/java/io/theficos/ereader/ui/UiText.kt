package io.theficos.ereader.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Resolve app-owned copy at display time, including after an Android locale change. */
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val arguments: List<Any> = emptyList()) : UiText
    data class Dynamic(val value: String) : UiText

    fun asString(context: Context): String = when (this) {
        is Resource -> context.getString(id, *arguments.map {
            if (it is UiText) it.asString(context) else it
        }.toTypedArray())
        is Dynamic -> value
    }

    @Composable
    fun asString(): String {
        // Reading stringResource subscribes Compose to Configuration changes.
        return when (this) {
            is Resource -> stringResource(id, *arguments.map {
                if (it is UiText) it.asString() else it
            }.toTypedArray())
            is Dynamic -> value
        }
    }
}

fun uiText(@StringRes id: Int, vararg arguments: Any): UiText = UiText.Resource(id, arguments.toList())
