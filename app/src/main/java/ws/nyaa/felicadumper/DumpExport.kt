package ws.nyaa.felicadumper

import androidx.annotation.Keep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// File format for exported dumps. Property names are the JSON keys, so they are snake_case
// and must not be renamed without bumping DUMP_FORMAT_VERSION. Every number is a hex string:
// node codes as "0x%04X", byte strings (IDm, PMm, block data) as plain upper-case hex.

internal const val DUMP_FORMAT_VERSION = 1

@Keep
internal data class DumpFileV1(
    val format_version: Int,
    val generated_at: String,
    val device: DumpFileDevice,
    val systems: List<DumpFileSystem>,
    val warnings: List<DumpFileWarning>?,
)

@Keep
internal data class DumpFileDevice(
    val manufacturer: String,
    val model: String,
)

@Keep
internal data class DumpFileSystem(
    val system_code: String,
    val idm: String,
    val pmm: String,
    val idi: String?,
    val pmi: String?,
    val key_version: DumpFileKeyVersion?,
    val children: List<DumpFileNode>,
    val warnings: List<DumpFileWarning>?,
)

/** An area (`type` = "area") or a service group (`type` = "service_group"). */
@Keep
internal data class DumpFileNode(
    val type: String,
    // area
    val code: String? = null,
    val end: String? = null,
    val key_version: DumpFileKeyVersion? = null,
    val children: List<DumpFileNode>? = null,
    // service_group
    val number: String? = null,
    val services: List<DumpFileService>? = null,
    val blocks: DumpFileBlocks? = null,
)

@Keep
internal data class DumpFileService(
    val code: String,
    val attributes: String?,
    val key_version: DumpFileKeyVersion?,
)

@Keep
internal data class DumpFileKeyVersion(
    val des: String?,
    val aes: String?,
)

@Keep
internal data class DumpFileBlocks(
    val via: String,
    val authenticated: Boolean,
    val data: List<String>,
)

@Keep
internal data class DumpFileWarning(
    val node: String?,
    val message: String,
)

internal fun DumpOutput.toDumpFile(): DumpFileV1 {
    return DumpFileV1(
        format_version = DUMP_FORMAT_VERSION,
        generated_at = formatIsoUtc(generatedAtEpochMs),
        device = DumpFileDevice(
            manufacturer = device.manufacturer,
            model = device.model,
        ),
        systems = systems.map { it.toDumpFile() },
        warnings = warnings.toDumpFile(),
    )
}

private fun SystemSummary.toDumpFile(): DumpFileSystem {
    return DumpFileSystem(
        system_code = systemCodeHex,
        idm = idm,
        pmm = pmm,
        idi = idi,
        pmi = pmi,
        key_version = systemKey?.toDumpFile(),
        children = areas.map { it.toDumpFile() } + systemServices.map { it.toDumpFile() },
        warnings = warnings.toDumpFile(),
    )
}

private fun AreaNode.toDumpFile(): DumpFileNode {
    return DumpFileNode(
        type = "area",
        code = areaCodeHex,
        end = endServiceCodeHex,
        key_version = keyVersion?.toDumpFile(),
        children = children.mapNotNull { child ->
            child.area?.toDumpFile() ?: child.serviceGroup?.toDumpFile()
        },
    )
}

private fun ServiceGroupSummary.toDumpFile(): DumpFileNode {
    return DumpFileNode(
        type = "service_group",
        number = numberHex,
        services = services.map { service ->
            DumpFileService(
                code = service.codeHex,
                attributes = service.attributesDescription?.toSnakeCase(),
                key_version = service.keyVersion?.toDumpFile(),
            )
        },
        blocks = blocks?.let {
            DumpFileBlocks(
                via = hexCode(it.serviceCodeRaw),
                authenticated = it.authenticated,
                data = it.blocks,
            )
        },
    )
}

private fun KeyVersionSummary.toDumpFile(): DumpFileKeyVersion {
    return DumpFileKeyVersion(des = desKeyVersionHex, aes = aesKeyVersionHex)
}

private fun List<DumpWarning>.toDumpFile(): List<DumpFileWarning>? {
    return takeIf { it.isNotEmpty() }?.map { warning ->
        DumpFileWarning(
            node = warning.node?.let(::hexCode),
            message = warning.message,
        )
    }
}

// "Random read/write with key" -> "random_read_write_with_key"
private fun String.toSnakeCase(): String {
    return lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

private fun hexCode(value: Int): String = String.format(Locale.US, "0x%04X", value and 0xFFFF)

private fun formatIsoUtc(epochMs: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(epochMs))
}
