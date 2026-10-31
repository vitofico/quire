package io.theficos.ereader.ui.reader

import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.readerRecreationState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReaderRecreationTest {
    @Test fun `recreation rebuilds publication dependent fragment and preserves other saved state`() {
        val controller = Robolectric.buildActivity(RecreationHarness::class.java).setup()
        controller.get().draft = "Why is the narrator avoiding this?"
        controller.get().destination = "reader/42"
        controller.recreate()
        val recreated = controller.get()
        assertThat(recreated.draft).isEqualTo("Why is the narrator avoiding this?")
        assertThat(recreated.destination).isEqualTo("reader/42")
        assertThat(recreated.supportFragmentManager.fragments).hasSize(1)
        assertThat(recreated.supportFragmentManager.fragments.single()).isInstanceOf(ConstructorFragment::class.java)
        controller.pause().stop().destroy()
    }

    @Test fun `saved state cleanup copies only fragment registry entry and keeps original intact`() {
        val registry = Bundle().apply {
            putBundle("android:support:fragments", Bundle().apply { putString("old", "navigator") })
            putBundle("compose-and-navigation", Bundle().apply { putString("draft", "keep") })
        }
        val original = Bundle().apply {
            putBundle("androidx.lifecycle.BundlableSavedStateRegistry.key", registry)
            putString("activity-value", "keep")
        }
        val cleaned = readerRecreationState(original)!!
        val cleanedRegistry = cleaned.getBundle("androidx.lifecycle.BundlableSavedStateRegistry.key")!!
        assertThat(cleanedRegistry.containsKey("android:support:fragments")).isFalse()
        assertThat(cleanedRegistry.getBundle("compose-and-navigation")?.getString("draft")).isEqualTo("keep")
        assertThat(cleaned.getString("activity-value")).isEqualTo("keep")
        assertThat(registry.containsKey("android:support:fragments")).isTrue()
        assertThat(readerRecreationState(null)).isNull()
    }

    /** Uses real AndroidX registry save/restore keys, rather than testing invented Bundle shape only. */
    class RecreationHarness : FragmentActivity() {
        var draft = ""
        var destination = "library"
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(readerRecreationState(savedInstanceState))
            savedStateRegistry.consumeRestoredStateForKey("compose-and-navigation")?.let {
                draft = it.getString("draft").orEmpty()
                destination = it.getString("destination").orEmpty()
            }
            savedStateRegistry.registerSavedStateProvider("compose-and-navigation") {
                Bundle().apply { putString("draft", draft); putString("destination", destination) }
            }
            // Like Readium, this fragment needs runtime publication input and has no no-arg constructor.
            supportFragmentManager.beginTransaction().add(ConstructorFragment("publication"), "reader").commitNow()
        }
    }
    class ConstructorFragment(val publication: String) : Fragment()
}
