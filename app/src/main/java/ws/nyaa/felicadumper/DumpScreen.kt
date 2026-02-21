package ws.nyaa.felicadumper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ws.nyaa.felicadumper.ui.theme.FeliCaDumperTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DumpScreen(
    uiState: DumpUiState,
    onExportJson: () -> Unit,
    onOpenKeyManagement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
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
                            contentDescription = "Save JSON",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(
                status = uiState.status,
                isReading = uiState.isReading,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.dump != null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        DumpHierarchyView(
                            dump = uiState.dump,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        )
                    }
                } else {
                    WaitingGuide(
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (uiState.isReading) {
                    ReadingOverlay()
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: String,
    isReading: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (isReading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Keep the card still.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WaitingGuide(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Hold an NFC-F card near the device",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = "The system/area/service hierarchy appears after reading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Text(
                    text = "Reading...",
                    style = MaterialTheme.typography.bodyMedium,
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
                status = "Hold an NFC-F card near the device.",
                dumpText = "{\n  \"systems\": []\n}",
                isReading = false,
                dump = null,
            ),
            onExportJson = {},
            onOpenKeyManagement = {},
        )
    }
}
