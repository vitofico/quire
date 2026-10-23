package io.theficos.ereader.ui.bookdetail

import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiProviderException
import io.theficos.ereader.data.ai.AiQuotaException
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
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
fun insightErrorMessage(e: Throwable): UiText = when {
    e is AiQuotaException ->
        uiText(R.string.bookdetail_you_ve_reached_today_s_regeneration_limit, e.info.resetsAt.take(10))
    e is AiProviderException ->
        listOfNotNull(e.serverMessage, e.hint)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }?.let(UiText::Dynamic) ?: uiText(R.string.bookdetail_couldn_t_generate_insights, e.code)
    e is AiHttpException && e.code == 429 ->
        uiText(R.string.bookdetail_you_ve_reached_today_s_regeneration_limit_2)
    e is AiHttpException -> uiText(R.string.bookdetail_couldn_t_generate_insights, e.code)
    // SocketTimeoutException (read timeout) extends InterruptedIOException
    // (OkHttp call timeout); both mean the server may still be working.
    e is SocketTimeoutException || e is InterruptedIOException ->
        uiText(R.string.bookdetail_the_server_took_too_long_to_answer)
    e is IOException -> uiText(R.string.bookdetail_couldn_t_reach_the_server_check_the)
    else -> uiText(R.string.bookdetail_couldn_t_generate_insights_2)
}
