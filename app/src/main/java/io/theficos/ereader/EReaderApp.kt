package io.theficos.ereader

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.theficos.ereader.di.AppContainer
import kotlinx.coroutines.launch

class EReaderApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Backfill any documents that haven't been pushed to /library/v1/items
        // yet. On the first run after this version installs, the migration
        // leaves `librarySyncedAt = NULL` for every existing row, so this is
        // the only thing that gets the server out of "0 books" state. Best-
        // effort: failures are logged inside the uploader; the row stays
        // unsynced and we retry on the next app start.
        //
        // PR-η / coordinator §3.17: after the upload completes (and as long as
        // it didn't 401), trigger an insight sync. Ordering matters — the
        // server has the latest library row before we ask for insights joined
        // through it. On 401 we skip; /ai/v1/insights/sync would 401 anyway.
        container.libraryUploaderScope.launch {
            val result = runCatching { container.libraryUploader.runOnce() }
                .onFailure { Log.w("EReaderApp", "library upload backfill failed", it) }
                .getOrNull()
            if (result?.abortedOnAuth != true) {
                container.insightSyncRepository.requestSync("app_start_post_upload")
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(container.opdsHttp.okHttp)
            .build()
}
