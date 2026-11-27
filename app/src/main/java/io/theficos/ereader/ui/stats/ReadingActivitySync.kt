package io.theficos.ereader.ui.stats

import android.content.SharedPreferences
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.ReadingActivity
import io.theficos.ereader.data.library.ReadingActivityUpload
import io.theficos.ereader.data.local.db.ReadingSessionEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReadingActivityState(
    val owner: String? = null,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val cutoff: Long = 0,
    val remote: List<ReadingActivity> = emptyList(),
)

/** Exchanges the device's own rows; downloaded sessions never enter the local recorder. */
class ReadingActivitySync(
    private val repository: ReadingStatsRepository,
    private val preferences: SharedPreferences,
    private val account: StateFlow<AccountCredentials?>,
    private val ownerOf: (AccountCredentials) -> String?,
    private val clientFor: (AccountCredentials) -> LibraryClient,
    scope: CoroutineScope,
) {
    private val device = preferences.getString("device", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("device", it).apply()
    }
    private val mutex = Mutex()
    private val refreshes = MutableStateFlow(0)
    private val currentOwner: String? get() = account.value?.let(ownerOf)
    private val _state = MutableStateFlow(ReadingActivityState())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            combine(account, repository.sessions, refreshes) { credentials, _, _ -> credentials }
                .conflate()
                .collect { credentials ->
                    mutex.withLock {
                        if (account.value != credentials) return@withLock
                        val owner = credentials?.let(ownerOf)
                        var visible = state.value.takeIf { it.owner == owner } ?: ReadingActivityState(owner = owner)
                        if (credentials == null || owner == null || !enabled(owner)) {
                            _state.value = ReadingActivityState(owner = owner, failed = visible.failed)
                            return@withLock
                        }
                        try {
                            val client = clientFor(credentials)
                            val (cutoff, remote) = pull(client)
                            if (account.value != credentials) return@withLock
                            val owned = repository.syncableHistory(owner)
                            if (owned.any { it.startedAt <= cutoff }) repository.clearSyncedHistory(owner, cutoff)
                            visible = ReadingActivityState(owner, enabled = true, cutoff = cutoff, remote = remote.values.toList())
                            val pending = owned.filter { it.startedAt > cutoff }
                                .map { activity(it) }.filter { it.id !in remote }
                            for (batch in pending.chunked(100)) {
                                client.uploadReadingActivity(ReadingActivityUpload(cutoff, batch))
                            }
                            if (account.value == credentials) _state.value = visible
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            if (account.value == credentials) _state.value = visible.copy(enabled = true, failed = true)
                        }
                    }
                }
        }
    }

    fun refresh() { refreshes.update { it + 1 } }

    suspend fun setEnabled(enable: Boolean) = change { client, owner ->
        if (enable) {
            client.listReadingActivity(null)
            check(currentOwner == owner)
            repository.assignHistory(owner)
        }
        preferences.edit().putBoolean(consentKey(owner), enable).apply()
        _state.value = ReadingActivityState(owner, enabled = enable)
        refresh()
    }

    suspend fun clear() = change { client, owner ->
        val cutoff = client.clearReadingActivity().clearedBefore
        repository.clearSyncedHistory(owner, cutoff)
        if (currentOwner == owner) _state.value = ReadingActivityState(owner, enabled = true, cutoff = cutoff)
        refresh()
    }

    fun merged(local: List<ReadingSessionEntity>, snapshot: ReadingActivityState): List<ReadingSessionEntity> {
        if (snapshot.owner != currentOwner || !snapshot.enabled) return local
        val seen = local.asSequence().filter { it.owner == snapshot.owner }.map { uploadId(it) }.toSet()
        return local.filter { it.owner != snapshot.owner || it.startedAt > snapshot.cutoff } +
            snapshot.remote.filter { it.id !in seen }.map { it.toSession(snapshot.owner) }
    }

    private suspend fun change(action: suspend (LibraryClient, String) -> Unit) {
        val credentials = account.value ?: return
        val owner = ownerOf(credentials) ?: return
        mutex.withLock {
            if (account.value != credentials) return@withLock
            val before = state.value.takeIf { it.owner == owner } ?: ReadingActivityState(owner = owner)
            _state.value = before.copy(busy = true, failed = false)
            try {
                action(clientFor(credentials), owner)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (account.value == credentials) _state.value = before.copy(failed = true)
            } finally {
                if (account.value == credentials) _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun enabled(owner: String) = preferences.getBoolean(consentKey(owner), false)
    private fun consentKey(owner: String) = "consent:$owner"

    private fun uploadId(row: ReadingSessionEntity): String =
        UUID.nameUUIDFromBytes("$device:${row.id}:${row.startedAt}:${row.bookKey}".toByteArray(Charsets.UTF_8)).toString()

    private fun activity(row: ReadingSessionEntity) = ReadingActivity(
        id = uploadId(row),
        bookKey = row.bookKey,
        title = row.title.take(512),
        startedAt = row.startedAt,
        utcOffsetSeconds = row.utcOffsetSeconds,
        activeSeconds = row.activeSeconds,
        words = row.wordsRead,
    )

    private suspend fun pull(client: LibraryClient): Pair<Long, Map<String, ReadingActivity>> {
        var cursor: String? = null
        var cutoff = 0L
        val rows = mutableMapOf<String, ReadingActivity>()
        do {
            val page = client.listReadingActivity(cursor)
            check(cursor == null || cutoff == page.clearedBefore)
            cutoff = page.clearedBefore
            page.sessions.forEach { rows[it.id] = it }
            cursor = page.next
        } while (cursor != null)
        return cutoff to rows
    }
}

private fun ReadingActivity.toSession(owner: String?) = ReadingSessionEntity(
    bookKey = bookKey,
    title = title,
    author = null,
    startedAt = startedAt,
    endedAt = startedAt + activeSeconds * 1_000,
    utcOffsetSeconds = utcOffsetSeconds,
    activeSeconds = activeSeconds,
    progressionRead = 0.0,
    wordsRead = words,
    owner = owner,
)
