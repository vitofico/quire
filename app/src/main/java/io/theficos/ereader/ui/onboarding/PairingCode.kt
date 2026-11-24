package io.theficos.ereader.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.data.opds.ServerProbe
import io.theficos.ereader.ui.scan.BarcodeCameraPreview
import io.theficos.quire.R
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What a signed-in device shows as a QR code so another device can join the same
 * Quire account: the server address plus a one-use ticket the server keeps valid
 * for two minutes. The ticket is exchanged at `/auth/v1/pairing/exchange`.
 */
data class PairingCode(val serverUrl: String, val ticket: String) {
    fun toUri(): String = "quire://pair?server=${URLEncoder.encode(serverUrl, "UTF-8")}&ticket=$ticket"
}

fun parsePairingCode(text: String): PairingCode? {
    val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "quire" || uri.host != "pair") return null
    val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
        val parts = pair.split('=', limit = 2)
        if (parts.size == 2) parts[0] to runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() else null
    }.toMap()
    val server = params["server"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
    val ticket = params["ticket"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{20,128}")) } ?: return null
    return PairingCode(server, ticket)
}

/** Ask the server for a pairing ticket on behalf of the signed-in [account]. Blocking. */
fun requestPairingCode(client: OkHttpClient, account: AccountCredentials.Bearer): PairingCode? = runCatching {
    val request = Request.Builder()
        .url("${account.baseUrl}/auth/v1/pairing")
        .post(ByteArray(0).toRequestBody())
        .header("Accept", "application/json")
        .header("User-Agent", ServerProbe.USER_AGENT)
        .header("Authorization", "Bearer ${account.token}")
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
        val ticket = (body?.get("ticket") as? JsonPrimitive)?.contentOrNull ?: return null
        parsePairingCode(PairingCode(account.baseUrl, ticket).toUri())
    }
}.getOrNull()

@Composable
fun PairingQr(code: PairingCode, modifier: Modifier = Modifier) {
    val bitmap = remember(code) {
        val size = 640
        val matrix = QRCodeWriter().encode(code.toUri(), BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, contentDescription = stringResource(R.string.quire_pairing_add_device), modifier = modifier)
}

/** A button that opens the camera, reads a [PairingCode] and hands its server and ticket to [onPairing]. */
@Composable
fun ScanPairingCodeButton(enabled: Boolean, onPairing: (String, String) -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { scanning = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.quire_pairing_scan))
    }
    if (!scanning) return

    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    // Bumped when the camera reads a QR code that is not a pairing code, so it keeps looking.
    var misreads by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = { scanning = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.quire_pairing_scan), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.quire_pairing_scan_help), style = MaterialTheme.typography.bodyMedium)
                when {
                    granted -> BarcodeCameraPreview(
                        onDecoded = { text ->
                            val code = parsePairingCode(text)
                            if (code == null) {
                                misreads++
                            } else {
                                scanning = false
                                onPairing(code.serverUrl, code.ticket)
                            }
                        },
                        rearmKey = misreads,
                        rearm = true,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        formats = listOf(BarcodeFormat.QR_CODE),
                    )
                    denied -> Text(stringResource(R.string.quire_pairing_camera_denied))
                    else -> CircularProgressIndicator()
                }
                TextButton(onClick = { scanning = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}
