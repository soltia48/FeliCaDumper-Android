package ws.nyaa.felicadumper

import androidx.annotation.Keep

typealias NodeKeys = Map<Int, ByteArray>
typealias IdmScopedKeys = Map<String, NodeKeys>
typealias SystemKeys = Map<Int, IdmScopedKeys>

// In-memory dump tree used by the UI. The file format is defined separately in DumpExport.kt.
data class DumpOutput(
    val device: DeviceInfo,
    val systems: List<SystemSummary>,
    val warnings: List<DumpWarning>,
    val generatedAtEpochMs: Long,
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
)

data class DumpWarning(
    val node: Int? = null,
    val message: String,
)

data class SystemSummary(
    val idm: String,
    val pmm: String,
    val idi: String?,
    val pmi: String?,
    val systemCodeHex: String,
    val systemKey: KeyVersionSummary?,
    val systemServices: List<ServiceGroupSummary>,
    val areas: List<AreaNode>,
    val warnings: List<DumpWarning>,
)

data class AreaNode(
    val areaCodeRaw: Int,
    val areaCodeHex: String,
    val endServiceCodeHex: String,
    var keyVersion: KeyVersionSummary?,
    val children: MutableList<AreaChild>,
)

data class AreaChild(
    val kind: String,
    val area: AreaNode? = null,
    val serviceGroup: ServiceGroupSummary? = null,
)

data class ServiceGroupSummary(
    val number: Int,
    val numberHex: String,
    val services: MutableList<ServiceSummary>,
    var blocks: BlockData?,
)

data class BlockData(
    val serviceCodeRaw: Int,
    val authenticated: Boolean,
    val blocks: List<String>,
)

data class ServiceSummary(
    val serviceCodeRaw: Int,
    val attributesRaw: Int,
    val codeHex: String,
    val number: Int,
    val attributesHex: String,
    val attributesDescription: String?,
    var keyVersion: KeyVersionSummary?,
)

data class KeyVersionSummary(
    val aesKeyVersionHex: String?,
    val desKeyVersionHex: String?,
)

@Keep
data class LoadedKeys(
    val keys: SystemKeys,
    val warnings: List<String>,
)

@Keep
data class AuthReadTarget(
    val serviceCodeRaw: Int,
    val areaCodes: List<Int>,
)

@Keep
data class MutualAuthSummary(
    val idi: String,
    val pmi: String,
)

@Keep
data class AuthReadResult(
    val blocks: List<String>,
    val authSummary: MutualAuthSummary,
)
