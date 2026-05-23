package io.theficos.ereader.sideload

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-unit tests for [extractImportUri]. Kept as a top-level function (not
 * an Activity method) precisely so we can cover both intent shapes without
 * launching an Activity — Robolectric activity launches are flaky around
 * `lifecycleScope` + suspend imports, and there is no mocking framework on
 * the `:app` test classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ImportIntentsTest {

    @Test fun `ACTION_VIEW pulls Uri from intent_data`() {
        val uri = Uri.parse("content://com.example/foo.epub")
        val intent = Intent(Intent.ACTION_VIEW).setData(uri)
        assertThat(extractImportUri(intent)).isEqualTo(uri)
    }

    @Test fun `ACTION_SEND pulls Uri from EXTRA_STREAM`() {
        val uri = Uri.parse("content://com.example/share/bar.epub")
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        assertThat(extractImportUri(intent)).isEqualTo(uri)
    }

    @Test fun `ACTION_SEND without EXTRA_STREAM returns null`() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/epub+zip")
        assertThat(extractImportUri(intent)).isNull()
    }

    @Test fun `unknown action returns null even with intent data`() {
        val intent = Intent("io.theficos.ereader.UNRELATED")
            .setData(Uri.parse("content://com.example/x.epub"))
        assertThat(extractImportUri(intent)).isNull()
    }

    @Test fun `null intent returns null`() {
        assertThat(extractImportUri(null)).isNull()
    }
}
