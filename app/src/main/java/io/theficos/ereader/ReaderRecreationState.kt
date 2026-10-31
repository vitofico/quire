package io.theficos.ereader

import android.os.Bundle

/**
 * Readium needs an open publication before its FragmentFactory can construct a navigator.
 * ReaderContent is this app's only fragment owner and rebuilds that navigator once the book
 * opens. Drop only its FragmentManager registry entry before FragmentActivity restores it;
 * retain Compose, navigation, drafts and all other Activity/registry state.
 *
 * These AndroidX registry keys are exercised against real FragmentActivity recreation in tests.
 * Revisit this policy if another feature starts owning Activity fragments.
 */
internal fun readerRecreationState(saved: Bundle?): Bundle? = saved?.let { original ->
    Bundle(original).apply {
        val registryKey = "androidx.lifecycle.BundlableSavedStateRegistry.key"
        original.getBundle(registryKey)?.let { registry ->
            putBundle(registryKey, Bundle(registry).apply { remove("android:support:fragments") })
        }
    }
}
