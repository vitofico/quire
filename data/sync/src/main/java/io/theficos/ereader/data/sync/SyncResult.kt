package io.theficos.ereader.data.sync

sealed interface SyncResult<out T> {
    data class Success<T>(val value: T) : SyncResult<T>
    data object Unauthorized : SyncResult<Nothing>
    data class HttpFailure(val code: Int, val body: String) : SyncResult<Nothing>
    data class NetworkFailure(val cause: Throwable) : SyncResult<Nothing>
}
