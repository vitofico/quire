package io.theficos.ereader.data.library

object LibraryApi {
    const val PATH_STATS = "/library/v1/stats"
    const val PATH_ITEMS = "/library/v1/items"
    // Phase 0 / S-2 (server) + A-4 (Android): bulk library-mirror push.
    const val PATH_SYNC = "/library/v1/sync"
}
