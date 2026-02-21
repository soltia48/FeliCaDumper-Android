package ws.nyaa.felicadumper

internal enum class AppScreen {
    DUMP,
    KEY_MANAGEMENT,
}

internal data class DumpUiState(
    val status: String,
    val dumpText: String,
    val isReading: Boolean,
    val dump: DumpOutput?,
)

internal data class KeyJsonlRecord(
    val system_code: String,
    val node: String,
    val algo: String,
    val version: String,
    val idm: String?,
    val key: String,
)

internal const val SAME_TAG_RESCAN_SUPPRESSION_MS = 2000L
internal const val TAG_IGNORE_DEBOUNCE_MS = 1000
