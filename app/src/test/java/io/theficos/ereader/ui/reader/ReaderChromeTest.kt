package io.theficos.ereader.ui.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader's controls hid 2.5 s after any reveal, so a reader who took longer to reach the
 * page slider tapped the page where the slider had been and turned back a page instead of
 * jumping. Only the reveal on opening a book still hides itself.
 */
@RunWith(RobolectricTestRunner::class)
// The stock Application: EReaderApp builds the whole DI container on create, which this test
// has no use for.
@Config(sdk = [33], application = android.app.Application::class)
class ReaderChromeTest {

    private lateinit var db: EReaderDatabase
    private lateinit var vm: ReaderViewModel

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Open now, on the test thread, so tearDown's close() cannot race a lazy open.
        db.openHelper.writableDatabase
        vm = ReaderViewModel(
            documentId = 1L,
            docs = DocumentRepository(db.documentDao()),
            progress = ProgressRepository(db.progressDao()),
            readium = ReadiumFactory(context),
            preferencesStore = ReaderPreferencesStore(context),
        )
    }

    @After fun tearDown() = db.close()

    @Test fun `the reveal on opening a book hides itself`() {
        assertThat(vm.chromeVisible.value).isTrue()
        assertThat(vm.chromeAutoHides.value).isTrue()
    }

    @Test fun `controls the reader shows stay until the reader hides them`() {
        vm.setChromeVisible(false) // the opening reveal timed out
        vm.toggleChrome() // the reader taps to bring the controls back

        assertThat(vm.chromeVisible.value).isTrue()
        assertThat(vm.chromeAutoHides.value).isFalse()

        vm.toggleChrome()
        vm.toggleChrome()
        assertThat(vm.chromeAutoHides.value).isFalse()
    }
}
