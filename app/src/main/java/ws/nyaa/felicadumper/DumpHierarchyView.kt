package ws.nyaa.felicadumper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            item(key = "reader") {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Reader",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                        Text(
                            text = "${dump.reader.vendor} ${dump.reader.product}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = dump.reader.chipset,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (dump.warnings.isNotEmpty()) {
                item(key = "root:warnings") {
                    ExpandableCard(
                        title = "Warnings",
                        stateKey = "root:warnings",
                        defaultExpanded = true,
                        leadingIcon = Icons.Default.Warning,
                    ) {
                        dump.warnings.forEach { warning ->
                            Text(
                                text = "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                        text = "No systems found.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showScrollTop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
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
    val systemLabel = "System ${system.systemCodeHex}"
    ExpandableCard(
        title = systemLabel,
        subtitle = "IDm ${system.idm}",
        stateKey = stateKey,
        defaultExpanded = true,
        leadingIcon = Icons.Default.Storage,
    ) {
        DetailLine(label = "PMm", value = system.pmm, monospace = true)
        system.idi?.let { DetailLine(label = "IDi", value = it, monospace = true) }
        system.pmi?.let { DetailLine(label = "PMi", value = it, monospace = true) }
        system.systemKey?.let { key ->
            DetailLine(
                label = "System Key",
                value = formatKeyVersion(key),
                monospace = true,
            )
        }

        if (system.systemServices.isNotEmpty()) {
            SectionHeader(
                text = "System Services",
                leadingIcon = Icons.AutoMirrored.Filled.InsertDriveFile,
            )
            system.systemServices.forEachIndexed { _, group ->
                ServiceGroupNode(
                    group = group,
                    level = 1,
                    onOpenServiceGroup = onOpenServiceGroup,
                )
            }
        }

        if (system.areas.isNotEmpty()) {
            SectionHeader(
                text = "Areas",
                leadingIcon = Icons.Default.Folder,
            )
            system.areas.forEachIndexed { index, area ->
                AreaNodeView(
                    area = area,
                    stateKey = "$stateKey:area:$index:${area.areaCodeHex}",
                    level = 1,
                    onOpenServiceGroup = onOpenServiceGroup,
                )
            }
        }

        if (system.warnings.isNotEmpty()) {
            ExpandableCard(
                title = "System Warnings",
                stateKey = "$stateKey:warnings",
                defaultExpanded = false,
                level = 1,
                leadingIcon = Icons.Default.Warning,
            ) {
                system.warnings.forEach { warning ->
                    Text(
                        text = "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
        title = "Area ${area.areaCodeHex}",
        subtitle = "range ${area.areaCodeHex} .. ${area.endServiceCodeHex}",
        stateKey = stateKey,
        defaultExpanded = level < 1,
        level = level,
        leadingIcon = Icons.Default.Folder,
    ) {
        area.keyVersion?.let { version ->
            DetailLine(
                label = "Area Key",
                value = formatKeyVersion(version),
                monospace = true,
            )
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
            .padding(start = (level * UI_HIERARCHY_INDENT_DP).dp)
            .clickable { onOpenServiceGroup(detail) },
        shape = RoundedCornerShape(hierarchyCornerSize(level)),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                hierarchyElevation(
                    level
                )
            )
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
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
    val scrollState = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Surface(
            modifier = Modifier.fillMaxHeight(0.92f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader(
                        text = "Services",
                        leadingIcon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    )
                    detail.group.services.forEach { service ->
                        ServiceSummaryRow(service = service)
                    }

                    SectionHeader(
                        text = "Block Data",
                        leadingIcon = Icons.Default.Storage,
                    )
                    val blocks = detail.group.blocks
                    if (blocks.isNullOrEmpty()) {
                        Text(
                            text = "No block data was retrieved for this service group.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                blocks.forEachIndexed { index, block ->
                                    BlockRow(index = index, block = block)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("Close")
                    }
                }
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
                .clickable { expanded = !expanded }
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leadingIcon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
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
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = if (expanded) "Collapse" else "Expand",
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HorizontalDivider()
                content()
            }
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    leadingIcon: ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leadingIcon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Text(
        text = "$label: $value",
        style = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodySmall
        },
    )
}

@Composable
private fun ServiceSummaryRow(service: ServiceSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Service ${service.codeHex}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
            service.attributesDescription?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "number: ${service.number}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "attributes: ${service.attributesHex}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            service.keyVersion?.let { keyVersion ->
                Text(
                    text = "keyVersion: ${formatKeyVersion(keyVersion)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun BlockRow(index: Int, block: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "[${index.toString().padStart(4, '0')}] $block",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
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
