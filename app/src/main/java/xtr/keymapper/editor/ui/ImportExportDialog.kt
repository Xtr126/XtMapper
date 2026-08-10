package xtr.keymapper.editor

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ImportExportDialog(
    code: String,
    onDismissRequest: () -> Unit,
    onImportClicked: () -> Unit,
    onExportClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Helper function to launch the system Sharesheet
    fun launchSharesheet(textToShare: String, context: Context) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, textToShare)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Export Configuration")
        context.startActivity(shareIntent)
    }

    Dialog(onDismissRequest = onDismissRequest) {
        ImportExportContent(
            code = code,
            onImportClicked = onImportClicked,
            onExportClicked = onExportClicked,
            onShareClicked = { launchSharesheet(code, context) },
            onDismissRequest = onDismissRequest,
            modifier = modifier
        )
    }
}

@Composable
fun ImportExportContent(
    code: String,
    onImportClicked: () -> Unit,
    onExportClicked: () -> Unit,
    onShareClicked: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {

        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Title & Sharesheet Quick Action ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Configuration Code",
                    style = MaterialTheme.typography.titleLarge
                )

                IconButton(
                    onClick = onShareClicked
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share code via Sharesheet"
                    )
                }
            }

            // --- Code Block Window ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                val verticalScroll = rememberScrollState()
                val horizontalScroll = rememberScrollState()

                SelectionContainer {
                    Text(
                        text = code,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = false, // <-- Prevents forced line wrapping on long config keys
                        modifier = Modifier
                            .verticalScroll(verticalScroll)
                            .horizontalScroll(horizontalScroll)
                    )
                }
            }

            // --- Main Action Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onImportClicked,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("Import")
                }

                Button(
                    onClick = onExportClicked,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("Export")
                }
            }

            // --- Bottom Bar (Share & Dismiss) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onShareClicked) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("Share")
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(onClick = onDismissRequest) {
                    Text("Close")
                }
            }
        }
    }
}

// --- Preview Component ---
@Preview(showBackground = true)
@Composable
private fun ImportExportContentPreview() {
    val sampleCode = """
        DPAD_UDLR 1366.2356 496.11465 121.5 1487.7356 617.6146 243 243 KEY_UP KEY_DOWN KEY_LEFT KEY_RIGHT
        KEY_3 6.02262 2.0246727 35.0
        DPAD 140.42352 396.53687 100.0 240.42352 496.53687 200 200 KEY_W KEY_S KEY_A KEY_D
        MOUSE_AIM 960.0 326.57364 1 176.5 227.63704 300.0 181.42978 1.0 1.0 0
        KEY_5 6.02262 3.506849 35.0
        APPLICATION xtr.keymapper.debug
        KEY_6 10.478579 8.53712 35.0
        KEY_7 5.933529 4.989026 35.0
        KEY_1 6.0582576 0.6199953 35.0
        KEY_4 6.040438 2.7415423 35.0
        ENABLED
        KEY_2 5.880074 1.3853025 35.0
        SCREENSIZE 1920 1080
    """.trimIndent()

    MaterialTheme {
        ImportExportContent(
            code = sampleCode,
            onDismissRequest = {},
            onImportClicked = {},
            onExportClicked = {},
            onShareClicked = {},
        )
    }
}

// --- Preview Component with Interactive Toggle State ---
@Preview(showBackground = true)
@Composable
private fun ImportExportDialogPreview() {
    var showDialog by remember { mutableStateOf(false) }

    val sampleCode = """
        DPAD_UDLR 1366.2356 496.11465 121.5 1487.7356 617.6146 243 243 KEY_UP KEY_DOWN KEY_LEFT KEY_RIGHT
        KEY_3 6.02262 2.0246727 35.0
        DPAD 140.42352 396.53687 100.0 240.42352 496.53687 200 200 KEY_W KEY_S KEY_A KEY_D
        MOUSE_AIM 960.0 326.57364 1 176.5 227.63704 300.0 181.42978 1.0 1.0 0
        KEY_5 6.02262 3.506849 35.0
        APPLICATION xtr.keymapper.debug
        KEY_6 10.478579 8.53712 35.0
        KEY_7 5.933529 4.989026 35.0
        KEY_1 6.0582576 0.6199953 35.0
        KEY_4 6.040438 2.7415423 35.0
        ENABLED
        KEY_2 5.880074 1.3853025 35.0
        SCREENSIZE 1920 1080
    """.trimIndent()

    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = { showDialog = true }) {
                Text("Open Dialog")
            }

            if (showDialog) {
                ImportExportDialog(
                    code = sampleCode,
                    onDismissRequest = { showDialog = false },
                    onImportClicked = { showDialog = false },
                    onExportClicked = { showDialog = false }
                )
            }
        }
    }
}
