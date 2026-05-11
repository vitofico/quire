package io.theficos.ereader.ui.reader

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.toEpubPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val chromeVisible by viewModel.chromeVisible.collectAsState()
    var showFontSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                SyncEnqueuer.enqueue(context, expedited = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activity = LocalContext.current as Activity
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(2_500)
            viewModel.setChromeVisible(false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            ReaderUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is ReaderUiState.Error -> Text(s.message, Modifier.align(Alignment.Center))
            is ReaderUiState.Open -> {
                ReaderContent(
                    publication = s.publication,
                    initialLocator = s.initialLocator,
                    preferences = preferences,
                    onLocator = viewModel::publishLocator,
                    onNavigatorReady = viewModel::bindNavigator,
                )
                if (preferences.tapNavigationEnabled) {
                    // Inset by the system-gesture strip on each edge so left/right swipes
                    // from the very edge still trigger Android's predictive back instead of
                    // being captured by the tap zones.
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(0.33f)
                                .fillMaxHeight()
                                .tapPassthrough { viewModel.pageBackward() }
                        )
                        Box(
                            modifier = Modifier
                                .weight(0.34f)
                                .fillMaxHeight()
                                .tapPassthrough { viewModel.toggleChrome() }
                        )
                        Box(
                            modifier = Modifier
                                .weight(0.33f)
                                .fillMaxHeight()
                                .tapPassthrough { viewModel.pageForward() }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxHeight()
                            .fillMaxWidth(0.34f)
                            .tapPassthrough { viewModel.toggleChrome() }
                    )
                }

                ReaderTopBar(
                    visible = chromeVisible,
                    title = s.document.title,
                    onBack = onClose,
                    onOverflow = { showFontSheet = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                ReaderBottomBar(
                    visible = chromeVisible,
                    chapterTitle = s.initialLocator?.title,
                    percent = s.savedProgress?.percent ?: 0.0,
                    onSeek = { /* deferred — Phase 2 wires actual jump */ },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                if (showFontSheet) {
                    FontSettingsSheet(
                        prefs = preferences,
                        onChange = { next -> viewModel.updatePreferences(next) },
                        onDismiss = { showFontSheet = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderContent(
    publication: Publication,
    initialLocator: Locator?,
    preferences: ReaderPreferences,
    onLocator: (Locator) -> Unit,
    onNavigatorReady: (EpubNavigatorFragment?) -> Unit,
) {
    val activity = LocalContext.current as FragmentActivity
    val containerId = rememberSaveable { View.generateViewId() }
    val tag = "reader-${publication.metadata.identifier ?: containerId}"
    var fragment by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
    )

    DisposableEffect(publication) {
        val fm = activity.supportFragmentManager
        val factory = EpubNavigatorFactory(publication)
        fm.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = preferences.toEpubPreferences(),
        )
        val nav = (fm.fragmentFactory.instantiate(
            activity.classLoader,
            EpubNavigatorFragment::class.java.name,
        ) as EpubNavigatorFragment)
        fm.beginTransaction()
            .replace(containerId, nav, tag)
            .commitNow()
        fragment = nav
        onNavigatorReady(nav)

        val job = activity.lifecycleScope.launch {
            nav.currentLocator.collect { onLocator(it) }
        }

        onDispose {
            job.cancel()
            fragment = null
            onNavigatorReady(null)
            fm.beginTransaction()
                .remove(nav)
                .commitNowAllowingStateLoss()
        }
    }

    LaunchedEffect(preferences) {
        fragment?.submitPreferences(preferences.toEpubPreferences())
    }
}

/**
 * Tap detector that does NOT consume the down event, so swipes and other drag
 * gestures still reach the underlying view (Readium's navigator AndroidView).
 * Only the up event is consumed, and only when the gesture qualifies as a tap
 * (no movement past touchSlop, finished within long-press timeout).
 */
private fun Modifier.tapPassthrough(onTap: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val touchSlop = viewConfiguration.touchSlop
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            val pointerId = down.id
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: return@withTimeoutOrNull null
                if (!change.pressed) return@withTimeoutOrNull change
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    return@withTimeoutOrNull null
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        if (up != null) {
            up.consume()
            onTap()
        }
    }
}
