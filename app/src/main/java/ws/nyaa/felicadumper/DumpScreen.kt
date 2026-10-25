package ws.nyaa.felicadumper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ws.nyaa.felicadumper.ui.theme.FeliCaDumperTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DumpScreen(
    uiState: DumpUiState,
    notice: Notice?,
    onNoticeShown: () -> Unit,
    onExportJson: () -> Unit,
    onOpenKeyManagement: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    NoticeEffect(notice, snackbarHostState, onNoticeShown)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "FeliCa Dumper",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                },
                actions = {
                    IconButton(
                        onClick = onExportJson,
                        enabled = !uiState.isReading && uiState.dump != null,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save dump as JSON",
                        )
                    }
                    IconButton(
                        onClick = onOpenKeyManagement,
                        enabled = !uiState.isReading,
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = "Manage keys",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.dump != null) {
                DumpHierarchyView(
                    dump = uiState.dump,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EmptyState(
                    uiState = uiState,
                    onOpenNfcSettings = onOpenNfcSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (uiState.isReading) {
                ReadingOverlay()
            }
        }
    }
}

@Composable
internal fun NoticeEffect(
    notice: Notice?,
    snackbarHostState: SnackbarHostState,
    onNoticeShown: () -> Unit,
) {
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice.text)
            onNoticeShown()
        }
    }
}

@Composable
private fun EmptyState(
    uiState: DumpUiState,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.nfc == NfcAvailability.UNSUPPORTED -> CenteredMessage(
            icon = Icons.Default.Nfc,
            title = "This device doesn’t support NFC",
            modifier = modifier,
        )

        uiState.nfc == NfcAvailability.DISABLED -> CenteredMessage(
            icon = Icons.Default.Nfc,
            title = "NFC is off",
            modifier = modifier,
        ) {
            Button(onClick = onOpenNfcSettings) {
                Text("Open NFC settings")
            }
        }

        uiState.readError != null -> CenteredMessage(
            icon = Icons.Default.ErrorOutline,
            iconTint = MaterialTheme.colorScheme.error,
            title = "Unable to read the card",
            body = listOf(
                uiState.readError,
                "Hold the card near the device again.",
            ).filter { it.isNotBlank() }.joinToString("\n"),
            modifier = modifier,
        )

        else -> CenteredMessage(
            icon = Icons.Default.Nfc,
            title = "Hold a FeliCa card near the device",
            modifier = modifier,
        )
    }
}

@Composable
private fun CenteredMessage(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
            )
            body?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            action?.let {
                Spacer(modifier = Modifier.height(24.dp))
                it()
            }
        }
    }
}

@Composable
private fun ReadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Keep the card still",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun DumpScreenPreview() {
    FeliCaDumperTheme {
        DumpScreen(
            uiState = DumpUiState(
                nfc = NfcAvailability.AVAILABLE,
                isReading = false,
                dump = null,
                readError = null,
            ),
            notice = null,
            onNoticeShown = {},
            onExportJson = {},
            onOpenKeyManagement = {},
            onOpenNfcSettings = {},
        )
    }
}
