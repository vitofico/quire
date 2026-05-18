package io.theficos.ereader.ui.reader

import android.app.Activity
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.theficos.ereader.MainActivity
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.toEpubPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val chromeVisible by viewModel.chromeVisible.collectAsState()
    val liveLocator by viewModel.currentLocator.collectAsState()
    val positions by viewModel.positions.collectAsState()
    var dragPercent by remember { mutableStateOf<Double?>(null) }
    var dragPreview by remember { mutableStateOf<Locator?>(null) }
    val isDragging = dragPercent != null
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

    val mainActivity = LocalContext.current as MainActivity
    DisposableEffect(mainActivity) {
        mainActivity.onBeforeReaderConfigChange = { viewModel.beginViewportResize() }
        onDispose { mainActivity.onBeforeReaderConfigChange = null }
    }

    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(chromeVisible, isDragging) {
        if (chromeVisible && !isDragging) {
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
                    onPrev = viewModel::pageBackward,
                    onNext = viewModel::pageForward,
                    onToggleChrome = viewModel::toggleChrome,
                    onPageLoaded = viewModel::completeViewportResize,
                )

                ReaderTopBar(
                    visible = chromeVisible,
                    title = s.document.title,
                    onBack = onClose,
                    onOverflow = { showFontSheet = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                val positionsList = positions
                val locationTotal = positionsList?.size?.takeIf { it > 0 }
                val locationIndex = dragPercent?.let { p ->
                    locationTotal?.let { total ->
                        (p.coerceIn(0.0, 1.0) * (total - 1)).toInt().coerceIn(0, total - 1) + 1
                    }
                }
                ReaderBottomBar(
                    visible = chromeVisible,
                    chapterTitle = dragPreview?.title
                        ?: liveLocator?.title
                        ?: s.initialLocator?.title,
                    percent = dragPreview?.locations?.let { it.totalProgression ?: it.progression }
                        ?: liveLocator?.locations?.let { it.totalProgression ?: it.progression }
                        ?: s.savedProgress?.percent ?: 0.0,
                    enabled = positionsList?.isNotEmpty() == true,
                    isDragging = isDragging,
                    locationIndex = locationIndex,
                    locationTotal = locationTotal,
                    onSeekChange = { p ->
                        dragPercent = p
                        dragPreview = viewModel.previewLocator(p)
                    },
                    onSeekFinished = {
                        dragPercent?.let { viewModel.seek(it) }
                        dragPercent = null
                        dragPreview = null
                    },
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
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleChrome: () -> Unit,
    onPageLoaded: () -> Unit,
) {
    val activity = LocalContext.current as FragmentActivity
    val containerId = rememberSaveable { View.generateViewId() }
    val tag = "reader-${publication.metadata.identifier ?: containerId}"
    var fragment by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            ReaderTapDispatcher(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(
                    FragmentContainerView(ctx).apply {
                        id = containerId
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                )
            }
        },
        update = { wrapper ->
            wrapper.onPrev = onPrev
            wrapper.onNext = onNext
            wrapper.onToggleChrome = onToggleChrome
            wrapper.tapNavigationEnabled = preferences.tapNavigationEnabled
        },
    )

    DisposableEffect(publication) {
        val fm = activity.supportFragmentManager
        val factory = EpubNavigatorFactory(publication)
        // onPageChanged fires after the WebView re-paginates (both on user page turns
        // and on resize-driven re-pagination) — that's the moment the navigator can
        // honor go(anchor) precisely. onPageLoaded fires earlier, when chapter HTML
        // loads, before the WebView has settled its column geometry, so calling
        // go(anchor) there lands at chapter start instead. The VM gates the callback
        // on pendingRotationAnchor so normal page turns are no-ops.
        val paginationListener = object : EpubNavigatorFragment.PaginationListener {
            override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                onPageLoaded()
            }
            override fun onPageLoaded() {}
        }
        fm.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = preferences.toEpubPreferences(),
            paginationListener = paginationListener,
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
 * Wraps the Readium [FragmentContainerView] and detects single taps via a
 * [GestureDetector] at the View layer. Tap events fire navigation callbacks;
 * swipes, long-presses and edge gestures flow naturally to children (the
 * Readium WebView gets its swipe-to-page) and to Android's system gesture
 * handler (predictive back). Compose overlays were tried first and don't
 * coexist with the WebView's gesture handling — see commit history.
 */
private class ReaderTapDispatcher(context: Context) : FrameLayout(context) {
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onToggleChrome: () -> Unit = {}
    var tapNavigationEnabled: Boolean = true

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val w = width.toFloat()
            if (w <= 0f) return false
            val frac = e.x / w
            if (tapNavigationEnabled) {
                when {
                    frac < 0.33f -> onPrev()
                    frac > 0.67f -> onNext()
                    else -> onToggleChrome()
                }
            } else if (frac in 0.33f..0.67f) {
                onToggleChrome()
            }
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Observe the event for tap recognition without consuming — children
        // (Readium's WebView) still receive every touch they need for swipe.
        gesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
