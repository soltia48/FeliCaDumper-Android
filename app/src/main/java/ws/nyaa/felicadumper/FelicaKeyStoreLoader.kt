package ws.nyaa.felicadumper

import com.google.gson.Gson
import java.io.File
import java.util.Locale

private const val KEY_LENGTH_BYTES = 8
private const val IDM_LENGTH = 8

private data class JsonlKeyRecord(
    val system_code: String?,
    val node: String?,
    val algo: String?,
    val version: String?,
    val idm: String?,
    val key: String?,
)

object FelicaKeyStoreLoader {
    private val gson = Gson()

    fun loadFromJsonlFile(file: File): LoadedKeys {
        val warnings = mutableListOf<String>()
        if (!file.exists()) {
            return LoadedKeys(emptyMap(), warnings)
        }

        val keysBySystem = mutableMapOf<Int, MutableMap<String, MutableMap<Int, ByteArray>>>()

        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val lineNum = index + 1
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    return@forEachIndexed
                }

                val record = runCatching { gson.fromJson(line, JsonlKeyRecord::class.java) }
                    .getOrElse {
                        warnings += "Invalid key JSON at line $lineNum: ${it.message}"
                        return@forEachIndexed
                    }

                if (record.algo?.equals("DES", ignoreCase = true) != true) {
                    warnings += "Unsupported algo '${record.algo}' at line $lineNum; only DES is supported"
                    return@forEachIndexed
                }

                val systemCode = parseHexU16(record.system_code)
                if (systemCode == null) {
                    warnings += "Invalid system_code '${record.system_code}' at line $lineNum"
                    return@forEachIndexed
                }

                val nodeCode = parseHexU16(record.node)
                if (nodeCode == null) {
                    warnings += "Invalid node '${record.node}' at line $lineNum"
                    return@forEachIndexed
                }

                val idm = parseIdmHex(record.idm)
                if (!record.idm.isNullOrBlank() && idm == null) {
                    warnings += "Invalid idm '${record.idm}' at line $lineNum"
                    return@forEachIndexed
                }

                val key = parseHexBytes(record.key.orEmpty())
                if (key == null || key.size != KEY_LENGTH_BYTES) {
                    warnings += "Invalid key at line $lineNum (expected 8-byte hex)"
                    return@forEachIndexed
                }

                keysBySystem
                    .getOrPut(systemCode) { mutableMapOf() }
                    .getOrPut(idm ?: "") { mutableMapOf() }[nodeCode] = key
            }
        }

        val immutableKeys = keysBySystem
            .mapValues { (_, idmScoped) ->
                idmScoped.mapValues { (_, nodeKeys) ->
                    nodeKeys.mapValues { (_, key) -> key.copyOf() }
                }
            }

        return LoadedKeys(immutableKeys, warnings)
    }
}

private fun parseHexU16(text: String?): Int? {
    if (text.isNullOrBlank()) {
        return null
    }
    val trimmed = text.trim()
    val normalized = when {
        trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2)
        else -> trimmed
    }
    return normalized.toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
}

private fun parseHexBytes(text: String): ByteArray? {
    val normalized = text
        .trim()
        .removePrefix("0x")
        .removePrefix("0X")
        .filter { !it.isWhitespace() && it != ':' && it != '-' }
    if (normalized.length % 2 != 0) {
        return null
    }
    val bytes = ByteArray(normalized.length / 2)
    for (i in bytes.indices) {
        val part = normalized.substring(i * 2, i * 2 + 2)
        val value = part.toIntOrNull(16) ?: return null
        bytes[i] = value.toByte()
    }
    return bytes
}

private fun parseIdmHex(value: String?): String? {
    if (value == null) {
        return null
    }
    val bytes = parseHexBytes(value) ?: return null
    if (bytes.size != IDM_LENGTH) {
        return null
    }
    return bytes.joinToString(separator = "") { byte ->
        String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
    }
}
