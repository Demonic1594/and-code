package com.yugahashimoto.andcode.feature.workspace

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.ShareState
import com.yugahashimoto.andcode.ui.components.CopyButton

/**
 * Sharing one server: before it starts, what it means (anyone with the link reaches the server);
 * then the link, as text to copy or send and as a QR code to scan, and the way to end it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareServerSheet(
    port: Int,
    state: ShareState?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.share_server_title, port), style = MaterialTheme.typography.titleLarge)
            when (state) {
                null -> NotStarted(port, onStart)
                ShareState.Installing -> Waiting(stringResource(R.string.share_server_installing), onStop)
                ShareState.Starting -> Waiting(stringResource(R.string.share_server_starting), onStop)
                is ShareState.Live -> Live(state.url, onStop)
                ShareState.TooManyProcesses -> Problem(stringResource(R.string.share_server_too_many), onStart, onStop)
                is ShareState.Failed -> Problem(stringResource(R.string.share_server_failed, state.reason), onStart, onStop)
            }
        }
    }
}

@Composable
private fun NotStarted(
    port: Int,
    onStart: () -> Unit,
) {
    // Not implicit: making a server on this phone reachable from anywhere is a real decision.
    Text(stringResource(R.string.share_server_warning, port), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_server_start)) }
}

@Composable
private fun Waiting(
    text: String,
    onStop: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
    // Closing the sheet does not stop it; this does, before a link ever exists.
    TextButton(onClick = onStop) { Text(stringResource(R.string.share_server_stop)) }
}

@Composable
private fun Live(
    url: String,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_server_send)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(modifier = Modifier.weight(1f)) { Text(url, style = MaterialTheme.typography.bodyLarge) }
        CopyButton(text = { url })
    }
    val qr = remember(url) { qrBitmap(url) }
    qr?.let {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_share_server_qr),
                modifier = Modifier.size(QrDefaults.SIZE_DP.dp),
            )
        }
    }
    Text(stringResource(R.string.share_server_live_note), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                context.startActivity(Intent.createChooser(send, shareTitle))
            },
            modifier = Modifier.weight(1f),
        ) { Text(shareTitle) }
        Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share_server_stop)) }
    }
}

@Composable
private fun Problem(
    message: String,
    onRetry: () -> Unit,
    onDismissShare: () -> Unit,
) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onDismissShare) { Text(stringResource(R.string.share_server_stop)) }
        Button(onClick = onRetry) { Text(stringResource(R.string.share_server_retry)) }
    }
}

private object QrDefaults {
    const val SIZE_DP = 200
    const val PIXELS = 512
}

/** The link as a QR code; null when it cannot be drawn, so the text alone is shown. */
private fun qrBitmap(text: String): Bitmap? =
    runCatching {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QrDefaults.PIXELS, QrDefaults.PIXELS)
        val pixels =
            IntArray(matrix.width * matrix.height) { index ->
                if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()
