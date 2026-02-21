package ws.nyaa.felicadumper

import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ws.nyaa.felicadumper.ui.theme.FeliCaDumperTheme
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()
    private val compactGson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    private val reading = AtomicBoolean(false)
    private val lastSuccessfulTagId = AtomicReference<String?>(null)
    private val lastSuccessfulReadAtMs = AtomicLong(0L)

    private var nfcAdapter: NfcAdapter? = null
    private var appScreen by mutableStateOf(AppScreen.DUMP)
    private var keyStore: SystemKeys? = null
    private var loadedKeyPath: String? = null
    private var keyLoadWarnings: List<String> = emptyList()
    private var pendingExportJson: String? = null
    private var pendingKeyExportJsonl: String? = null
    private val importKeyLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                uiState = uiState.copy(status = "Importing key file...")
                val result = withContext(Dispatchers.IO) {
                    runCatching { importKeyStore(uri) }
                }
                result.onSuccess { loaded ->
                    keyStore = loaded.keys.takeIf { it.isNotEmpty() }
                    keyLoadWarnings = loaded.warnings
                    uiState = uiState.copy(
                        status = "Key file imported.",
                        dumpText = waitingText(),
                        dump = null,
                    )
                }.onFailure { error ->
                    uiState = uiState.copy(
                        status = "Failed to import key file.",
                        dumpText = buildString {
                            appendLine("Error: ${error.message ?: "unknown"}")
                            appendLine()
                            append(waitingText())
                        },
                        dump = null,
                    )
                }
            }
        }
    private val exportDumpLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null) {
            uiState = uiState.copy(status = "JSON export canceled.")
            return@registerForActivityResult
        }
        if (json == null) {
            uiState = uiState.copy(status = "No JSON available to save.")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            uiState = uiState.copy(status = "Saving JSON...")
            val result = withContext(Dispatchers.IO) {
                runCatching { exportDumpJson(uri, json) }
            }
            result.onSuccess { location ->
                uiState = uiState.copy(status = "JSON saved: $location")
            }.onFailure { error ->
                uiState =
                    uiState.copy(status = "Failed to save JSON: ${error.message ?: "unknown"}")
            }
        }
    }
    private val exportKeysLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val jsonl = pendingKeyExportJsonl
        pendingKeyExportJsonl = null
        if (uri == null) {
            uiState = uiState.copy(status = "Key file export canceled.")
            return@registerForActivityResult
        }
        if (jsonl == null) {
            uiState = uiState.copy(status = "No keys available to save.")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            uiState = uiState.copy(status = "Saving key file...")
            val result = withContext(Dispatchers.IO) {
                runCatching { exportDumpJson(uri, jsonl) }
            }
            result.onSuccess { location ->
                uiState = uiState.copy(status = "Key file saved: $location")
            }.onFailure { error ->
                uiState = uiState.copy(
                    status = "Failed to save key file: ${error.message ?: "unknown"}"
                )
            }
        }
    }
    private var uiState by mutableStateOf(
        DumpUiState(
            status = "Hold an NFC-F card near the device.",
            dumpText = "Waiting...",
            isReading = false,
            dump = null,
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val loadedKeys = loadKeyStore()
        keyStore = loadedKeys.keys.takeIf { it.isNotEmpty() }
        keyLoadWarnings = loadedKeys.warnings
        uiState = uiState.copy(status = initialStatusText())
        uiState = uiState.copy(dumpText = waitingText())

        enableEdgeToEdge()
        setContent {
            FeliCaDumperTheme {
                if (appScreen == AppScreen.DUMP) {
                    DumpScreen(
                        uiState = uiState,
                        onExportJson = { launchDumpExport() },
                        onOpenKeyManagement = { appScreen = AppScreen.KEY_MANAGEMENT },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    KeyManagementScreen(
                        state = KeyManagementState(
                            status = uiState.status,
                            keyFilePath = loadedKeyPath,
                            keyLoadWarnings = keyLoadWarnings,
                            summary = buildKeyManagementSummary(keyStore),
                            entries = flattenPersistedKeys(keyStore),
                            isBusy = uiState.isReading,
                        ),
                        onBack = { appScreen = AppScreen.DUMP },
                        onImportKeys = { launchKeyImport() },
                        onExportKeys = { launchKeyExport() },
                        onDeleteKey = { entry -> deletePersistedKey(entry) },
                        onDeleteAllKeys = { deleteAllPersistedKeys() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            uiState = uiState.copy(status = "NFC is disabled. Enable NFC in settings.")
            return
        }

        val readerFlags = NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 200)
        }
        adapter.enableReaderMode(this, this, readerFlags, options)
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        if (appScreen != AppScreen.DUMP) {
            return
        }
        val tagIdHex = tag.id.toHexUpper()
        if (shouldSuppressImmediateRescan(tagIdHex)) {
            return
        }

        if (!reading.compareAndSet(false, true)) {
            return
        }

        lifecycleScope.launch {
            var readSucceeded = false
            try {
                uiState = uiState.copy(
                    status = "Reading...",
                    isReading = true,
                )

                val result = withContext(Dispatchers.IO) {
                    runCatching { FelicaDumpEngine.dump(tag, keyStore) }
                }

                result.onSuccess { dump ->
                    readSucceeded = true
                    uiState = uiState.copy(
                        status = "Read complete (${dump.systems.size} system)",
                        dumpText = "Read result is shown. Use the save icon to export JSON.",
                        isReading = false,
                        dump = dump,
                    )
                }.onFailure { error ->
                    uiState = uiState.copy(
                        status = "Read failed",
                        dumpText = buildString {
                            appendLine("Error: ${error.message ?: "unknown"}")
                            appendLine()
                            appendLine("Hold the NFC-F (FeliCa) card near the device again.")
                        },
                        isReading = false,
                        dump = null,
                    )
                }
            } finally {
                if (readSucceeded) {
                    markTagReadSuccess(tagIdHex)
                    suppressTagRediscovery(tag, tagIdHex)
                }
                if (uiState.isReading) {
                    uiState = uiState.copy(isReading = false)
                }
                reading.set(false)
            }
        }
    }

    private fun initialStatusText(): String {
        val adapter = nfcAdapter
        return when {
            adapter == null -> "This device does not support NFC."
            !adapter.isEnabled -> "NFC is disabled. Enable NFC in settings."
            else -> "Hold an NFC-F card near the device."
        }
    }

    private fun waitingText(): String {
        return buildString {
            appendLine("Waiting...")
            appendLine()
            if (loadedKeyPath != null && keyStore != null) {
                appendLine("Authentication keys: ${keyStore!!.size} systems loaded")
                appendLine("key file: $loadedKeyPath")
            } else {
                appendLine("Authentication keys: Not configured")
                appendLine("Place keys.jsonl in one of the locations below to enable authenticated reads:")
                appendLine("- ${File(filesDir, "keys.jsonl").absolutePath}")
                getExternalFilesDir(null)?.let {
                    appendLine("- ${File(it, "keys.jsonl").absolutePath}")
                }
            }
            if (keyLoadWarnings.isNotEmpty()) {
                appendLine()
                appendLine("Key load warnings:")
                keyLoadWarnings.forEach { warning ->
                    appendLine("- $warning")
                }
            }
        }
    }

    private fun loadKeyStore(): LoadedKeys {
        val candidates = buildList {
            add(File(filesDir, "keys.jsonl"))
            getExternalFilesDir(null)?.let { add(File(it, "keys.jsonl")) }
        }
        for (file in candidates) {
            if (!file.exists()) {
                continue
            }
            loadedKeyPath = file.absolutePath
            return runCatching { FelicaDumpEngine.loadKeysFromJsonlFile(file) }
                .getOrElse { error ->
                    LoadedKeys(
                        keys = emptyMap(),
                        warnings = listOf("Failed to load key file '${file.absolutePath}': ${error.message}"),
                    )
                }
        }
        return LoadedKeys(emptyMap(), emptyList())
    }

    private fun launchKeyImport() {
        importKeyLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun launchKeyExport() {
        val keys = keyStore
        if (keys.isNullOrEmpty()) {
            uiState = uiState.copy(status = "No keys available to save.")
            return
        }
        pendingKeyExportJsonl = serializeKeysToJsonl(keys)
        exportKeysLauncher.launch("keys-${System.currentTimeMillis()}.jsonl")
    }

    private fun launchDumpExport() {
        val dump = uiState.dump
        if (dump == null) {
            uiState = uiState.copy(status = "No dump available to save.")
            return
        }
        pendingExportJson = gson.toJson(dump)
        exportDumpLauncher.launch("felica-dump-${dump.generatedAtEpochMs}.json")
    }

    private fun deletePersistedKey(entry: PersistedKeyEntry) {
        val currentKeys = keyStore
        if (currentKeys.isNullOrEmpty()) {
            uiState = uiState.copy(status = "No key available to delete.")
            return
        }
        lifecycleScope.launch {
            uiState = uiState.copy(status = "Deleting key...")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val updated = removePersistedKey(currentKeys, entry)
                    persistKeyStore(updated)
                    updated
                }
            }
            result.onSuccess { updated ->
                keyStore = updated.takeIf { it.isNotEmpty() }
                keyLoadWarnings = emptyList()
                uiState = uiState.copy(status = "Key deleted.")
            }.onFailure { error ->
                uiState =
                    uiState.copy(status = "Failed to delete key: ${error.message ?: "unknown"}")
            }
        }
    }

    private fun deleteAllPersistedKeys() {
        lifecycleScope.launch {
            uiState = uiState.copy(status = "Deleting keys...")
            val result = withContext(Dispatchers.IO) {
                runCatching { persistKeyStore(emptyMap()) }
            }
            result.onSuccess {
                keyStore = null
                keyLoadWarnings = emptyList()
                uiState = uiState.copy(status = "All keys deleted.")
            }.onFailure { error ->
                uiState =
                    uiState.copy(status = "Failed to delete keys: ${error.message ?: "unknown"}")
            }
        }
    }

    private fun persistKeyStore(keys: SystemKeys) {
        val destination = File(filesDir, "keys.jsonl")
        if (keys.isEmpty()) {
            if (destination.exists() && !destination.delete()) {
                throw IOException("Failed to delete key file.")
            }
            loadedKeyPath = null
            return
        }
        destination.writeText(serializeKeysToJsonl(keys), Charsets.UTF_8)
        loadedKeyPath = destination.absolutePath
    }

    private fun removePersistedKey(keys: SystemKeys, entry: PersistedKeyEntry): SystemKeys {
        val mutableSystems = mutableMapOf<Int, MutableMap<String, MutableMap<Int, ByteArray>>>()
        keys.forEach { (systemCode, idmScoped) ->
            val mutableIdmScoped = mutableMapOf<String, MutableMap<Int, ByteArray>>()
            idmScoped.forEach { (idm, nodeKeys) ->
                val mutableNodeKeys = mutableMapOf<Int, ByteArray>()
                nodeKeys.forEach { (nodeCode, keyBytes) ->
                    mutableNodeKeys[nodeCode] = keyBytes.copyOf()
                }
                mutableIdmScoped[idm] = mutableNodeKeys
            }
            mutableSystems[systemCode] = mutableIdmScoped
        }

        val nodeKeys = mutableSystems[entry.systemCode]?.get(entry.idmHex)
        nodeKeys?.remove(entry.nodeCode)
        if (nodeKeys != null && nodeKeys.isEmpty()) {
            mutableSystems[entry.systemCode]?.remove(entry.idmHex)
        }
        if (mutableSystems[entry.systemCode]?.isEmpty() == true) {
            mutableSystems.remove(entry.systemCode)
        }

        return mutableSystems.mapValues { (_, idmScoped) ->
            idmScoped.mapValues { (_, nodeKeysMap) ->
                nodeKeysMap.mapValues { (_, keyBytes) -> keyBytes.copyOf() }
            }
        }
    }

    private fun flattenPersistedKeys(keys: SystemKeys?): List<PersistedKeyEntry> {
        if (keys.isNullOrEmpty()) {
            return emptyList()
        }
        return buildList {
            keys.forEach { (systemCode, idmScoped) ->
                idmScoped.forEach { (idm, nodeKeys) ->
                    nodeKeys.forEach { (nodeCode, keyBytes) ->
                        add(
                            PersistedKeyEntry(
                                systemCode = systemCode,
                                idmHex = idm,
                                nodeCode = nodeCode,
                                keyHex = keyBytes.toHexUpper(),
                            )
                        )
                    }
                }
            }
        }.sortedWith(
            compareBy<PersistedKeyEntry> { it.systemCode }
                .thenBy { it.idmHex }
                .thenBy { it.nodeCode }
        )
    }

    private fun buildKeyManagementSummary(keys: SystemKeys?): KeyManagementSummary {
        if (keys.isNullOrEmpty()) {
            return KeyManagementSummary(0, 0, 0)
        }
        val systems = keys.size
        var idmScopes = 0
        var totalKeys = 0
        keys.forEach { (_, idmScoped) ->
            idmScopes += idmScoped.size
            idmScoped.forEach { (_, nodeKeys) ->
                totalKeys += nodeKeys.size
            }
        }
        return KeyManagementSummary(systems, idmScopes, totalKeys)
    }

    private fun serializeKeysToJsonl(keys: SystemKeys): String {
        val lines = buildList {
            keys.toSortedMap().forEach { (systemCode, idmScoped) ->
                idmScoped.toSortedMap().forEach { (idm, nodeKeys) ->
                    nodeKeys.toSortedMap().forEach { (nodeCode, keyBytes) ->
                        add(
                            compactGson.toJson(
                                KeyJsonlRecord(
                                    system_code = hexU16(systemCode),
                                    node = hexU16(nodeCode),
                                    algo = "DES",
                                    version = "0x0000",
                                    idm = idm.takeIf { it.isNotBlank() },
                                    key = keyBytes.toHexUpper(),
                                )
                            )
                        )
                    }
                }
            }
        }
        return if (lines.isEmpty()) {
            ""
        } else {
            lines.joinToString(separator = "\n", postfix = "\n")
        }
    }

    private fun shouldSuppressImmediateRescan(tagIdHex: String): Boolean {
        if (tagIdHex.isBlank()) {
            return false
        }
        val lastId = lastSuccessfulTagId.get() ?: return false
        if (lastId != tagIdHex) {
            return false
        }
        val elapsed = SystemClock.elapsedRealtime() - lastSuccessfulReadAtMs.get()
        return elapsed in 0 until SAME_TAG_RESCAN_SUPPRESSION_MS
    }

    private fun markTagReadSuccess(tagIdHex: String) {
        if (tagIdHex.isBlank()) {
            return
        }
        lastSuccessfulTagId.set(tagIdHex)
        lastSuccessfulReadAtMs.set(SystemClock.elapsedRealtime())
    }

    private fun suppressTagRediscovery(tag: Tag, tagIdHex: String) {
        val adapter = nfcAdapter ?: return
        runCatching {
            adapter.ignore(
                tag,
                TAG_IGNORE_DEBOUNCE_MS,
                NfcAdapter.OnTagRemovedListener {
                    if (tagIdHex == lastSuccessfulTagId.get()) {
                        lastSuccessfulTagId.compareAndSet(tagIdHex, null)
                        lastSuccessfulReadAtMs.set(0L)
                    }
                },
                null,
            )
        }
    }

    @Throws(IOException::class)
    private fun importKeyStore(uri: Uri): LoadedKeys {
        val destination = File(filesDir, "keys.jsonl")
        val temp = File(filesDir, "keys.jsonl.tmp")

        contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open the selected file.")

        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }

        loadedKeyPath = destination.absolutePath
        return FelicaDumpEngine.loadKeysFromJsonlFile(destination)
    }

    private fun ByteArray.toHexUpper(): String {
        if (isEmpty()) {
            return ""
        }
        return joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
        }
    }

    private fun hexU16(value: Int): String {
        return String.format(Locale.US, "0x%04X", value and 0xFFFF)
    }

    @Throws(IOException::class)
    private fun exportDumpJson(uri: Uri, json: String): String {
        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw IOException("Unable to open the selected destination.")
        return uri.toString()
    }
}
