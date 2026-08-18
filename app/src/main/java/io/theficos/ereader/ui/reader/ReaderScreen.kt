package io.theficos.ereader.ui.reader

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    // Reveal the system bars the instant the user leaves the reader (top-bar back button
    // or system/predictive back), before the pop animation starts. Immersive reading hides
    // them, which drops WindowInsets.systemBars to zero; if they're only re-shown when the
    // reader finishes disposing at the END of the pop, the incoming screen's Scaffold
    // re-pads the moment they reappear — a visible resize/jump. Showing them up-front lets
    // the insets settle while the destination is still fading in.
    val readerView = LocalView.current
    val insetsController = remember(activity.window, readerView) {
        WindowCompat.getInsetsController(activity.window, readerView)
    }
    val leaveReader: () -> Unit = {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        onClose()
    }
    BackHandler { leaveReader() }

    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(chromeVisible, isDragging, showFontSheet) {
        // Don't auto-hide while the settings sheet is open (bars would slide out from under
        // the modal), nor while a screen reader is exploring (immersive also hides the OS
        // navigation bar, which a TalkBack user cannot re-summon on a 2.5s timer).
        val touchExploring = (context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager)?.isTouchExplorationEnabled == true
        if (chromeVisible && !isDragging && !showFontSheet && !touchExploring) {
            delay(2_500)
            viewModel.setChromeVisible(false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            ReaderUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is ReaderUiState.Error -> Text(s.message, Modifier.align(Alignment.Center))
            is ReaderUiState.Open -> {
                // Scoped to the Open state so Loading/Error keep normal, themed system bars.
                ImmersiveWindowEffects(
                    immersive = preferences.immersiveReading,
                    lightBarsForTheme = !preferences.theme.isDark,
                    chromeVisible = chromeVisible,
                    onBeforeResize = viewModel::beginViewportResize,
                    onResizeSettled = viewModel::completeViewportResize,
                    onExit = viewModel::clearPendingResize,
                )

                // Last measured size of the reader content. Every change is reported to the
                // view model, which re-anchors the reading position across it whatever caused
                // it — see ReaderViewModel.onViewportChanged. The settle valve is keyed on the
                // size so a resize that arrives in several steps (an inset animation, say)
                // restarts the wait instead of completing mid-flight; Readium's onPageChanged
                // normally beats it to the re-anchor and makes it a no-op.
                var viewport by remember { mutableStateOf(IntSize.Zero) }
                LaunchedEffect(viewport) {
                    if (viewport != IntSize.Zero) {
                        delay(RESIZE_SETTLE_MS)
                        viewModel.completeViewportResize()
                    }
                }

                // The app is edge-to-edge (MainActivity). Immersive reading uses that full
                // bleed: content draws behind the (hidden) bars and the chrome self-insets.
                // With immersive off, the reader behaves like any normal screen — inset the
                // whole subtree (content + chrome) clear of the opaque system bars. Toggling
                // this padding is what resizes the WebView on an immersive flip; the
                // re-anchor machinery in ImmersiveWindowEffects handles that.
                val immersive = preferences.immersiveReading
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (immersive) Modifier
                            else Modifier.windowInsetsPadding(WindowInsets.systemBars),
                        ),
                ) {
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
                        onViewportChanged = { size ->
                            viewport = size
                            viewModel.onViewportChanged(size.width, size.height)
                        },
                    )

                    ReaderTopBar(
                        visible = chromeVisible,
                        title = s.document.title,
                        onBack = leaveReader,
                        onOverflow = { showFontSheet = true },
                        modifier = Modifier.align(Alignment.TopCenter),
                        edgeToEdge = immersive,
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
                        edgeToEdge = immersive,
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
    onViewportChanged: (IntSize) -> Unit,
) {
    val activity = LocalContext.current as FragmentActivity
    val containerId = rememberSaveable { View.generateViewId() }
    val tag = "reader-${publication.metadata.identifier ?: containerId}"
    var fragment by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    AndroidView(
        // This is the node whose height decides how much text fits in a Readium column, so
        // it — not the window, and not the insets — is the authoritative viewport.
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged(onViewportChanged),
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
            configuration = EpubNavigatorFragment.Configuration(
                // Quire owns the reader's insets, so Readium must not also apply them.
                //
                // Left at its default (on), Readium pads its own page container by the
                // system-bar insets whenever they are dispatched to its view. In full-screen
                // reading the bars are hidden and the reader is deliberately full-bleed, so
                // that padding is for bars that aren't there — and it arrived late: the page
                // rendered edge to edge, then the first time the window regained focus the
                // WebView finally re-measured against it and lost ~350px of height. That
                // re-paginated the chapter under the reader, and because the resize happened
                // inside Readium's own view tree, nothing here saw it coming: the post-
                // re-pagination locator was published and written over the saved position, so
                // the reader came back to the wrong page and stayed there (issue #95).
                //
                // With full-screen reading off it was simply double-inset: the whole reader
                // subtree is already padded by WindowInsets.systemBars in ReaderScreen.
                shouldApplyInsetsPadding = false,
            ),
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
 * How long to wait for Readium to re-paginate after the viewport changed before honouring the
 * anchor anyway. onPageChanged normally gets there first; this only has to cover a resize that
 * didn't re-paginate at all, so that locator publishing is never left suppressed.
 */
private const val RESIZE_SETTLE_MS = 600L

/**
 * Drives immersive full-screen: binds the OS status + navigation bars to the reader
 * chrome. Every window mutation is snapshotted once and reverted on exit so the rest of
 * the single-Activity app keeps its normal, themed bars.
 */
@Composable
private fun ImmersiveWindowEffects(
    immersive: Boolean,
    lightBarsForTheme: Boolean,
    chromeVisible: Boolean,
    onBeforeResize: () -> Unit,
    onResizeSettled: () -> Unit,
    onExit: () -> Unit,
) {
    val view = LocalView.current
    val window = (LocalContext.current as Activity).window
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }

    // Snapshot the pre-reader system-bar state ONCE, so re-runs restore the true originals
    // rather than a previously-applied transparent/immersive value.
    val original = remember {
        OriginalBarState(
            statusColor = window.statusBarColor,
            navColor = window.navigationBarColor,
            lightStatus = controller.isAppearanceLightStatusBars,
            lightNav = controller.isAppearanceLightNavigationBars,
            contrastEnforced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced
            } else {
                true
            },
            cutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode
            } else {
                0
            },
        )
    }

    // Skip arming a viewport resize on the very first application: the fragment is created
    // edge-to-edge from the start, so there's nothing to re-anchor.
    val firstRun = remember { booleanArrayOf(true) }

    // Apply the bar cosmetics whenever immersive flips, and arm the re-anchor BEFORE the
    // recomposition toggles the reader content's systemBars inset (which resizes the
    // WebView) so Readium's drifted emissions are suppressed until onPageChanged (or the
    // fallback below) restores the anchor. Skipped on first run — the fragment is created
    // at the final geometry, so no resize occurs.
    DisposableEffect(immersive) {
        if (!firstRun[0]) onBeforeResize()
        if (immersive) applyImmersive(window, controller) else restoreBars(window, controller, original)
        onDispose { }
    }

    // Exit-only cleanup (keyed on Unit so an immersive toggle never triggers it): revert
    // every window mutation so the rest of the single-Activity app keeps normal bars, and
    // clear any resize left armed by a mid-transition teardown.
    DisposableEffect(Unit) {
        onDispose {
            restoreBars(window, controller, original)
            onExit()
        }
    }

    // Re-anchor fallback for a live immersive toggle. onPageChanged normally completes the
    // resize; this guarantees the anchor is honored (and publishing un-suppressed) even if
    // the toggle didn't re-paginate. Idempotent — a no-op once already completed.
    LaunchedEffect(immersive) {
        if (firstRun[0]) {
            firstRun[0] = false
        } else {
            delay(600)
            onResizeSettled()
        }
    }

    // Bar-icon appearance follows the reader theme, but only while immersive. Kept separate
    // from the mode effect so a theme change never cycles decor-fits (which would re-paginate).
    LaunchedEffect(immersive, lightBarsForTheme) {
        if (immersive) {
            controller.isAppearanceLightStatusBars = lightBarsForTheme
            controller.isAppearanceLightNavigationBars = lightBarsForTheme
        }
    }

    // Bind system-bar visibility to the reader chrome.
    LaunchedEffect(immersive, chromeVisible) {
        if (immersive) {
            if (chromeVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Re-assert the hidden state when the window regains focus (notification shade, system
    // dialog, or app switch can reset it) so bars don't get stuck visible over the page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, immersive, chromeVisible) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && immersive && !chromeVisible) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private data class OriginalBarState(
    val statusColor: Int,
    val navColor: Int,
    val lightStatus: Boolean,
    val lightNav: Boolean,
    val contrastEnforced: Boolean,
    val cutoutMode: Int,
)

private fun applyImmersive(window: Window, controller: WindowInsetsControllerCompat) {
    // NB: decorFitsSystemWindows is set false once, app-wide, in MainActivity — never
    // toggled here. Toggling it on the shared single-Activity window resized the window
    // on reader exit and showed a visible jump. This only tweaks bar cosmetics/behavior.
    window.statusBarColor = AndroidColor.TRANSPARENT
    window.navigationBarColor = AndroidColor.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}

private fun restoreBars(
    window: Window,
    controller: WindowInsetsControllerCompat,
    original: OriginalBarState,
) {
    // Colors/appearance/contrast/cutout only — decorFitsSystemWindows stays false
    // (owned by MainActivity), so restoring bars never resizes the window.
    window.statusBarColor = original.statusColor
    window.navigationBarColor = original.navColor
    controller.isAppearanceLightStatusBars = original.lightStatus
    controller.isAppearanceLightNavigationBars = original.lightNav
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = original.contrastEnforced
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = original.cutoutMode
        }
    }
    controller.show(WindowInsetsCompat.Type.systemBars())
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
