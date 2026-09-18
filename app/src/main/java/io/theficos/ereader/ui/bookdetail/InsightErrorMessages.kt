package io.theficos.ereader.ui.bookdetail

import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiProviderException
import io.theficos.ereader.data.ai.AiQuotaException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * The one sentence shown under the Insights header when generation fails.
 * Shared by the book, catalog, and scan screens.
 *
 * Issue #102: the server now explains provider failures; show that instead
 * of a status code, and tell "took too long" apart from "unreachable".
 * Quota wording is unchanged from before.
 */
fun insightErrorMessage(e: Throwable): String = when {
    e is AiQuotaException ->
        "You've reached today's regeneration limit. Try again after ${e.info.resetsAt.take(10)}."
    e is AiProviderException ->
        listOfNotNull(e.serverMessage, e.hint).joinToString(" ")
    e is AiHttpException && e.code == 429 ->
        "You've reached today's regeneration limit. Try again tomorrow."
    e is AiHttpException -> "Couldn't generate insights (${e.code})."
    // SocketTimeoutException (read timeout) extends InterruptedIOException
    // (OkHttp call timeout); both mean the server may still be working.
    e is SocketTimeoutException || e is InterruptedIOException ->
        "The server took too long to answer. It may still be generating; try again in a minute."
    e is IOException -> "Couldn't reach the server. Check the sync URL and your connection."
    else -> "Couldn't generate insights."
}
