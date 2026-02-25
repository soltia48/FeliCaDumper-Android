package ws.nyaa.felicadumper

import androidx.annotation.Keep

typealias NodeKeys = Map<Int, ByteArray>
typealias IdmScopedKeys = Map<String, NodeKeys>
typealias SystemKeys = Map<Int, IdmScopedKeys>

@Keep
data class DumpOutput(
    val reader: ReaderInfo,
    val systems: List<SystemSummary>,
    val warnings: List<String>,
    val generatedAtEpochMs: Long,
)

@Keep
data class ReaderInfo(
    val vendor: String,
    val product: String,
    val chipset: String,
)

@Keep
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

@Keep
data class AreaNode(
    val areaCodeRaw: Int,
    val areaCodeHex: String,
    val endServiceCodeHex: String,
    var keyVersion: KeyVersionSummary?,
    val children: MutableList<AreaChild>,
)

@Keep
data class AreaChild(
    val kind: String,
    val area: AreaNode? = null,
    val serviceGroup: ServiceGroupSummary? = null,
)

@Keep
data class ServiceGroupSummary(
    val number: Int,
    val numberHex: String,
    val services: MutableList<ServiceSummary>,
    var blocks: List<String>?,
)

@Keep
data class ServiceSummary(
    val serviceCodeRaw: Int,
    val attributesRaw: Int,
    val codeHex: String,
    val number: Int,
    val attributesHex: String,
    val attributesDescription: String?,
    var keyVersion: KeyVersionSummary?,
)

@Keep
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
