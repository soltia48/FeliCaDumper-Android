package ws.nyaa.felicadumper

typealias NodeKeys = Map<Int, ByteArray>
typealias IdmScopedKeys = Map<String, NodeKeys>
typealias SystemKeys = Map<Int, IdmScopedKeys>

data class DumpOutput(
    val reader: ReaderInfo,
    val systems: List<SystemSummary>,
    val warnings: List<String>,
    val generatedAtEpochMs: Long,
)

data class ReaderInfo(
    val vendor: String,
    val product: String,
    val chipset: String,
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
    val warnings: List<String>,
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
    var blocks: List<String>?,
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

data class LoadedKeys(
    val keys: SystemKeys,
    val warnings: List<String>,
)

data class AuthReadTarget(
    val serviceCodeRaw: Int,
    val areaCodes: List<Int>,
)

data class MutualAuthSummary(
    val idi: String,
    val pmi: String,
)

data class AuthReadResult(
    val blocks: List<String>,
    val authSummary: MutualAuthSummary,
)
