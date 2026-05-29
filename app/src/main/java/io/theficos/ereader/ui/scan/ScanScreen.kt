package io.theficos.ereader.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Book-scan entry screen.
 *
 * Two acquisition paths, both feeding [ScanViewModel.onIsbnSubmitted]:
 *  - Live camera: CameraX [Preview] in a [PreviewView] plus an [ImageAnalysis]
 *    stage that runs a ZXing [MultiFormatReader] restricted to EAN_13 (the
 *    barcode symbology printed on book back-covers). First clean decode fires
 *    the submit, then auto-decoding pauses until the user re-scans.
 *  - Manual entry: an always-visible "Enter ISBN" field + submit button. This
 *    is the camera-less fallback (permission denied / no camera) and a manual
 *    override.
 *
 * State mapping (see [ScanUiState]):
 *  - Working → inline spinner.
 *  - InvalidIsbn → inline error on the field.
 *  - NotFound / Failed → message + the field stays available to retry.
 *  - ReauthRequired → recoverable message (re-auth nav is driven elsewhere).
 *  - Result → register the payload and call [onResult] with the nav key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onResult: (ScanResultData) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Surface a successful scan to the caller for navigation. Done in an
    // effect so navigation is a side effect of state, not of composition.
    LaunchedEffect(state) {
        val s = state
        if (s is ScanUiState.Result) {
            onResult(
                ScanResultData(
                    isbn13 = s.bundle.isbn ?: "",
                    bundle = s.bundle,
                    affinity = s.affinity,
                    affinityUnavailable = s.affinityUnavailable,
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan a book") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hasCameraPermission) {
                CameraPreview(
                    onIsbnDecoded = { viewModel.onIsbnSubmitted(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (permissionDenied) {
                            "Camera permission denied. Enter the ISBN below, or grant " +
                                "camera access in Settings to scan the barcode."
                        } else {
                            "Camera unavailable. Enter the ISBN below."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            ManualIsbnField(
                isError = state is ScanUiState.InvalidIsbn,
                onSubmit = { viewModel.onIsbnSubmitted(it) },
            )

            StatusArea(state = state)
        }
    }
}

@Composable
private fun ManualIsbnField(
    isError: Boolean,
    onSubmit: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Enter ISBN") },
            isError = isError,
            supportingText = if (isError) {
                { Text("That doesn't look like a valid ISBN.") }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                if (value.isNotBlank()) onSubmit(value.trim())
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (value.isNotBlank()) onSubmit(value.trim()) },
            enabled = value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Look up")
        }
    }
}

@Composable
private fun StatusArea(state: ScanUiState) {
    when (state) {
        ScanUiState.Idle,
        is ScanUiState.Result,
        ScanUiState.InvalidIsbn,
        -> Unit
        ScanUiState.Working -> Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        ScanUiState.NotFound -> Text(
            "No book found for that ISBN. Try the barcode again or check the digits.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ScanUiState.ReauthRequired -> Text(
            "Your session expired. Sign in again, then re-scan.",
            style = MaterialTheme.typography.bodyMedium,
        )
        is ScanUiState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * CameraX preview + barcode analysis. Binds to the current lifecycle and
 * tears the analysis executor down on dispose. The [onIsbnDecoded] callback is
 * captured via [rememberUpdatedState] so the long-lived analyzer always calls
 * the latest lambda. A successful decode latches [decoded] so we don't fire a
 * burst of submits for the same barcode held in frame.
 */
@Composable
private fun CameraPreview(
    onIsbnDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onIsbnDecoded)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // Latch so one held barcode doesn't fire repeatedly. The VM also dedups
    // via its generation guard, but latching avoids submit spam.
    val decoded = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13),
                ),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(analysisExecutor) { proxy ->
                            decodeBarcode(proxy, reader)?.let { isbn ->
                                if (decoded.compareAndSet(false, true)) {
                                    currentOnDecoded(isbn)
                                }
                            }
                            proxy.close()
                        }
                    }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * Pull the Y (luminance) plane out of a YUV [ImageProxy] and run ZXing over
 * it. Returns the decoded barcode text, or null on no-read. Caller owns
 * closing the proxy.
 */
private fun decodeBarcode(proxy: ImageProxy, reader: MultiFormatReader): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val width = proxy.width
    val height = proxy.height
    val source = PlanarYUVLuminanceSource(
        data,
        plane.rowStride,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decode(bitmap).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
