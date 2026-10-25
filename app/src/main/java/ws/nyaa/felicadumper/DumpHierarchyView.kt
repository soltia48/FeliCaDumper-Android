package ws.nyaa.felicadumper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

private const val UI_HIERARCHY_INDENT_DP = 6

private fun hierarchyCornerSize(level: Int): Dp {
    return (14 - level).coerceAtLeast(8).dp
}

private fun hierarchyElevation(level: Int): Dp {
    return (level + 1).coerceAtMost(5).dp
}

@Composable
internal fun DumpHierarchyView(
    dump: DumpOutput,
    modifier: Modifier = Modifier,
) {
    var selectedServiceGroup by remember(dump.generatedAtEpochMs) {
        mutableStateOf<ServiceGroupDetailDialogState?>(null)
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val showScrollTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 1 ||
                    (listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset > 120)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        ) {
            if (dump.warnings.isNotEmpty()) {
                item(key = "root:warnings") {
                    ExpandableCard(
                        title = "Warnings",
                        stateKey = "root:warnings",
                        defaultExpanded = true,
                        leadingIcon = Icons.Default.Warning,
                        leadingIconTint = MaterialTheme.colorScheme.error,
                    ) {
                        WarningList(dump.warnings)
                    }
                }
            }

            itemsIndexed(
                items = dump.systems,
                key = { index, system -> "system:$index:${system.systemCodeHex}:${system.idm}" },
            ) { index, system ->
                SystemNode(
                    system = system,
                    stateKey = "system:$index:${system.systemCodeHex}:${system.idm}",
                    onOpenServiceGroup = { selectedServiceGroup = it },
                )
            }
            if (dump.systems.isEmpty()) {
                item(key = "systems:empty") {
                    Text(
                        text = "No systems found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    selectedServiceGroup?.let { detail ->
        ServiceGroupDetailsDialog(
            detail = detail,
            onDismissRequest = { selectedServiceGroup = null },
        )
    }
}

@Composable
private fun SystemNode(
    system: SystemSummary,
    stateKey: String,
    onOpenServiceGroup: (ServiceGroupDetailDialogState) -> Unit,
) {
    ExpandableCard(
        title = "System ${system.systemCodeHex}",
        subtitle = "IDm ${system.idm}",
        stateKey = stateKey,
        defaultExpanded = true,
        leadingIcon = Icons.Default.Storage,
    ) {
        DetailTable(
            rows = listOfNotNull(
                "PMm" to system.pmm,
                system.idi?.let { "IDi" to it },
                system.pmi?.let { "PMi" to it },
                system.systemKey?.let { "Key version" to formatKeyVersion(it) },
            ),
        )

        system.systemServices.forEach { group ->
            ServiceGroupNode(
                group = group,
                level = 1,
                onOpenServiceGroup = onOpenServiceGroup,
            )
        }

        system.areas.forEachIndexed { index, area ->
            AreaNodeView(
                area = area,
                stateKey = "$stateKey:area:$index:${area.areaCodeHex}",
                level = 1,
                onOpenServiceGroup = onOpenServiceGroup,
            )
        }

        if (system.warnings.isNotEmpty()) {
            ExpandableCard(
                title = "Warnings",
                stateKey = "$stateKey:warnings",
                defaultExpanded = false,
                level = 1,
                leadingIcon = Icons.Default.Warning,
                leadingIconTint = MaterialTheme.colorScheme.error,
            ) {
                WarningList(system.warnings)
            }
        }
    }
}

@Composable
private fun AreaNodeView(
    area: AreaNode,
    stateKey: String,
    level: Int,
    onOpenServiceGroup: (ServiceGroupDetailDialogState) -> Unit,
) {
    ExpandableCard(
        title = "Area ${area.areaCodeHex}\u2013${area.endServiceCodeHex}",
        stateKey = stateKey,
        defaultExpanded = level < 1,
        level = level,
        leadingIcon = Icons.Default.Folder,
    ) {
        area.keyVersion?.let { version ->
            DetailTable(rows = listOf("Key version" to formatKeyVersion(version)))
        }
        area.children.forEachIndexed { index, child ->
            child.area?.let {
                AreaNodeView(
                    area = it,
                    stateKey = "$stateKey:area:$index:${it.areaCodeHex}",
                    level = level + 1,
                    onOpenServiceGroup = onOpenServiceGroup,
                )
            }
            child.serviceGroup?.let {
                ServiceGroupNode(
                    group = it,
                    level = level + 1,
                    onOpenServiceGroup = onOpenServiceGroup,
                )
            }
        }
    }
}

@Composable
private fun ServiceGroupNode(
    group: ServiceGroupSummary,
    level: Int,
    onOpenServiceGroup: (ServiceGroupDetailDialogState) -> Unit,
) {
    val detail = ServiceGroupDetailDialogState(
        title = "Service Group ${group.numberHex}",
        group = group,
    )
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (level * UI_HIERARCHY_INDENT_DP).dp),
        onClick = { onOpenServiceGroup(detail) },
        shape = RoundedCornerShape(hierarchyCornerSize(level)),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                hierarchyElevation(
                    level
                )
            )
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class ServiceGroupDetailDialogState(
    val title: String,
    val group: ServiceGroupSummary,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceGroupDetailsDialog(
    detail: ServiceGroupDetailDialogState,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.group.services.forEach { service ->
                    ServiceSummaryRow(service = service)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            val blocks = detail.group.blocks?.blocks
            if (blocks.isNullOrEmpty()) {
                Text(
                    text = "No blocks were read",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BlockTable(blocks = blocks)
            }
        }
    }
}

@Composable
private fun ExpandableCard(
    title: String,
    subtitle: String? = null,
    stateKey: String,
    defaultExpanded: Boolean,
    level: Int = 0,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(defaultExpanded) }
    val cornerSize = hierarchyCornerSize(level)
    val elevation = hierarchyElevation(level)

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (level * UI_HIERARCHY_INDENT_DP).dp),
        shape = RoundedCornerShape(cornerSize),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(elevation)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Collapse" else "Expand",
                ) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leadingIcon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = leadingIconTint,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun WarningList(warnings: List<DumpWarning>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        warnings.forEach { warning ->
            Text(
                text = warning.node?.let { "${hexCode(it)} ${warning.message}" } ?: warning.message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun hexCode(value: Int): String = String.format(Locale.US, "0x%04X", value and 0xFFFF)

@Composable
private fun DetailTable(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp),
                )
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceSummaryRow(service: ServiceSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = service.codeHex,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                text = service.attributesDescription ?: "Attributes ${service.attributesHex}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            service.keyVersion?.let { keyVersion ->
                Text(
                    text = "Key version ${formatKeyVersion(keyVersion)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BlockTable(blocks: List<String>) {
    val indexDigits = (blocks.size - 1).toString().length.coerceAtLeast(2)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                blocks.forEachIndexed { index, block ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = index.toString().padStart(indexDigits, '0'),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = block,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}

private fun formatKeyVersion(key: KeyVersionSummary): String {
    return buildString {
        key.desKeyVersionHex?.let {
            append("DES=")
            append(it)
        }
        key.aesKeyVersionHex?.let {
            if (isNotEmpty()) {
                append(", ")
            }
            append("AES=")
            append(it)
        }
    }
}
