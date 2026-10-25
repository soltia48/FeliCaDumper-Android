package ws.nyaa.felicadumper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

data class KeyManagementState(
    val keyLoadWarnings: List<String>,
    val entries: List<PersistedKeyEntry>,
    val isBusy: Boolean,
)

data class PersistedKeyEntry(
    val systemCode: Int,
    val idmHex: String,
    val nodeCode: Int,
    val keyHex: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyManagementScreen(
    state: KeyManagementState,
    notice: Notice?,
    onNoticeShown: () -> Unit,
    onBack: () -> Unit,
    onImportKeys: () -> Unit,
    onExportKeys: () -> Unit,
    onDeleteKey: (PersistedKeyEntry) -> Unit,
    onDeleteAllKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteAllDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteEntry by remember { mutableStateOf<PersistedKeyEntry?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    NoticeEffect(notice, snackbarHostState, onNoticeShown)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onImportKeys,
                        enabled = !state.isBusy,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Import keys",
                        )
                    }
                    IconButton(
                        onClick = onExportKeys,
                        enabled = !state.isBusy && state.entries.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Export keys",
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = !state.isBusy && state.entries.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Delete all keys",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteAllDialog = true
                                },
                            )
                        }
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
            if (state.entries.isEmpty() && state.keyLoadWarnings.isEmpty()) {
                EmptyKeys(
                    isBusy = state.isBusy,
                    onImportKeys = onImportKeys,
                )
            } else {
                KeyList(
                    state = state,
                    onDeleteKey = { pendingDeleteEntry = it },
                )
            }
        }
    }

    pendingDeleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = { Text("Delete key ${hexCode(entry.nodeCode)}?") },
            text = {
                Text(
                    "System ${hexCode(entry.systemCode)}, ${formatIdm(entry.idmHex)}. " +
                        "This can’t be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteEntry = null
                        onDeleteKey(entry)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete key")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEntry = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = {
                Text(
                    if (state.entries.size == 1) {
                        "Delete 1 key?"
                    } else {
                        "Delete all ${state.entries.size} keys?"
                    }
                )
            },
            text = { Text("This can’t be undone. Export the keys first to keep a copy.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllDialog = false
                        onDeleteAllKeys()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EmptyKeys(
    isBusy: Boolean,
    onImportKeys: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No keys yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Import a keys.jsonl file to read protected services.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onImportKeys,
                enabled = !isBusy,
            ) {
                Text("Import keys")
            }
        }
    }
}

private sealed interface KeyListItem {
    val key: String

    data class SystemHeader(val systemCode: Int) : KeyListItem {
        override val key = "system:$systemCode"
    }

    data class IdmGroup(
        val systemCode: Int,
        val idmHex: String,
        val entries: List<PersistedKeyEntry>,
    ) : KeyListItem {
        override val key = "idm:$systemCode:$idmHex"
    }
}

private fun groupKeyList(entries: List<PersistedKeyEntry>): List<KeyListItem> {
    return buildList {
        entries.groupBy { it.systemCode }.forEach { (systemCode, systemEntries) ->
            add(KeyListItem.SystemHeader(systemCode))
            systemEntries.groupBy { it.idmHex }.forEach { (idmHex, idmEntries) ->
                add(KeyListItem.IdmGroup(systemCode, idmHex, idmEntries))
            }
        }
    }
}

@Composable
private fun KeyList(
    state: KeyManagementState,
    onDeleteKey: (PersistedKeyEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val showScrollTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }
    val items = remember(state.entries) { groupKeyList(state.entries) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        ) {
            if (state.keyLoadWarnings.isNotEmpty()) {
                item(key = "warnings") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                state.keyLoadWarnings.forEach { warning ->
                                    Text(
                                        text = warning,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            items(items = items, key = { it.key }) { item ->
                when (item) {
                    is KeyListItem.SystemHeader -> Text(
                        text = "System ${hexCode(item.systemCode)}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .padding(top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )

                    is KeyListItem.IdmGroup -> IdmKeyCard(
                        group = item,
                        isBusy = state.isBusy,
                        onDeleteKey = onDeleteKey,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showScrollTop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Back to top",
                )
            }
        }
    }
}

@Composable
private fun IdmKeyCard(
    group: KeyListItem.IdmGroup,
    isBusy: Boolean,
    onDeleteKey: (PersistedKeyEntry) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                text = formatIdm(group.idmHex),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            group.entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = hexCode(entry.nodeCode),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.keyHex,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    IconButton(
                        onClick = { onDeleteKey(entry) },
                        enabled = !isBusy,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete key ${hexCode(entry.nodeCode)}",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun hexCode(value: Int): String {
    return String.format(Locale.US, "0x%04X", value and 0xFFFF)
}

private fun formatIdm(idmHex: String): String {
    return if (idmHex.isBlank()) "Any card" else "IDm $idmHex"
}
