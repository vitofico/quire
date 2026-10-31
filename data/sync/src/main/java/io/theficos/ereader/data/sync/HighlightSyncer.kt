package io.theficos.ereader.data.sync

import io.theficos.ereader.data.local.db.HighlightSyncDao
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.local.db.compareHighlights
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reuses the progress worker/manual-sync entry point; older servers remain fully usable. */
class HighlightSyncer(
    private val client: SyncClient,
    private val dao: HighlightSyncDao,
    private val state: SyncStateDao,
    private val sessionProvider: () -> HighlightSyncSession?,
) {
    private val mutex = Mutex()

    suspend fun runOnce(): SyncResult<Unit> = mutex.withLock {
        val session = sessionProvider() ?: return@withLock SyncResult.Success(Unit)
        val key = "highlights:${session.scope}"
        try {
            var cursor = state.lastPulled(key) ?: 0L
            var page = client.pullHighlights(session, cursor)
            if (page is SyncResult.HttpFailure && page.code == 409) {
                cursor = 0
                page = client.pullHighlights(session, cursor)
            }
            // Old servers: no ownership claim, no lost dirty rows, progress still succeeds.
            if (page is SyncResult.HttpFailure && page.code in listOf(404, 405)) {
                return@withLock SyncResult.Success(Unit)
            }
            if (page !is SyncResult.Success) return@withLock page.asUnit()
            validatePage(page.value, session.scope, cursor)
            if (!isCurrent(session)) return@withLock SyncResult.Success(Unit)
            // Only a successful authenticated endpoint response can bind never-owned quotes.
            dao.claimUnowned(session.scope)

            while (true) {
                if (!isCurrent(session)) return@withLock SyncResult.Success(Unit)
                val value = (page as? SyncResult.Success)?.value ?: return@withLock page.asUnit()
                val rows = validatePage(value, session.scope, cursor)
                cursor = value.nextCursor.toLong()
                dao.applyPage(rows, key, cursor)
                if (!value.hasMore) break
                page = client.pullHighlights(session, cursor)
            }

            var pushedAny = false
            while (isCurrent(session)) {
                val dirty = dao.dirty(session.scope)
                if (dirty.isEmpty()) break
                val pushed = client.pushHighlights(session, dirty.map { it.toSyncDto() })
                if (pushed !is SyncResult.Success) return@withLock pushed.asUnit()
                val results = pushed.value.results
                require(results.size == dirty.size && results.map { it.item.id }.toSet() == dirty.map { it.id }.toSet()) {
                    "Incomplete highlight acknowledgement"
                }
                val byId = dirty.associateBy { it.id }
                val winners = results.map { result ->
                    require(result.status == "accepted" || result.status == "stale")
                    result.item.toEntity(session.scope).also { winner ->
                        val sent = requireNotNull(byId[winner.id])
                        require(winner.identityHashVersion >= sent.identityHashVersion &&
                            compareHighlights(winner, sent.copy(identityHashVersion = winner.identityHashVersion)) >= 0) {
                            "Invalid highlight winner"
                        }
                    }
                }
                // Comparing the complete authoritative payload prevents an in-flight edit from
                // being acknowledged merely because it shares the same UUID/timestamp.
                dao.merge(winners)
                pushedAny = true
            }
            if (pushedAny) {
                while (true) {
                    if (!isCurrent(session)) return@withLock SyncResult.Success(Unit)
                    val pulled = client.pullHighlights(session, cursor)
                    if (pulled !is SyncResult.Success) return@withLock pulled.asUnit()
                    val rows = validatePage(pulled.value, session.scope, cursor)
                    cursor = pulled.value.nextCursor.toLong()
                    dao.applyPage(rows, key, cursor)
                    if (!pulled.value.hasMore) break
                }
            }
            SyncResult.Success(Unit)
        } catch (_: IllegalArgumentException) {
            SyncResult.HttpFailure(0, "Invalid highlight sync response")
        }
    }

    private fun isCurrent(session: HighlightSyncSession) = sessionProvider()?.scope == session.scope

    private fun validatePage(page: HighlightPullResponse, scope: String, previous: Long) =
        page.items.map { it.toEntity(scope) }.also {
            val next = page.nextCursor.toLongOrNull()
            require(next != null && next >= previous && (!page.hasMore || next > previous))
            require(page.items.size <= 500 && page.items.map { row -> row.id }.toSet().size == page.items.size)
        }
}

private fun <T> SyncResult<T>.asUnit(): SyncResult<Unit> = when (this) {
    is SyncResult.Success -> SyncResult.Success(Unit)
    is SyncResult.Unauthorized -> this
    is SyncResult.HttpFailure -> this
    is SyncResult.NetworkFailure -> this
}
