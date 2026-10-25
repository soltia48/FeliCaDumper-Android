package ws.nyaa.felicadumper

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ws.nyaa.felicadumper.ui.theme.FeliCaDumperTheme
import java.io.File
import java.io.IOException
import java.util.Locale

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()
    private val compactGson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    private val state: MainViewModel by viewModels()
    private val reading get() = state.reading
    private val lastSuccessfulTagId get() = state.lastSuccessfulTagId
    private val lastSuccessfulReadAtMs get() = state.lastSuccessfulReadAtMs

    private var nfcAdapter: NfcAdapter? = null
    private var appScreen
        get() = state.appScreen
        set(value) { state.appScreen = value }
    private var keyStore
        get() = state.keyStore
        set(value) { state.keyStore = value }
    private var keyLoadWarnings
        get() = state.keyLoadWarnings
        set(value) { state.keyLoadWarnings = value }
    private var pendingExportJson
        get() = state.pendingExportJson
        set(value) { state.pendingExportJson = value }
    private var pendingKeyExportJsonl
        get() = state.pendingKeyExportJsonl
        set(value) { state.pendingKeyExportJsonl = value }
    private val importKeyLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            state.viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { importKeyStore(uri) }
                }
                result.onSuccess { loaded ->
                    keyStore = loaded.keys.takeIf { it.isNotEmpty() }
                    keyLoadWarnings = loaded.warnings
                    showNotice("Keys imported")
                }.onFailure { error ->
                    showNotice("Unable to import keys. ${error.message.orEmpty()}".trim())
                }
            }
        }
    private val exportDumpLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) {
            return@registerForActivityResult
        }
        state.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { exportDumpJson(uri, json) }
            }
            result.onSuccess {
                showNotice("Dump saved")
            }.onFailure { error ->
                showNotice("Unable to save the dump. ${error.message.orEmpty()}".trim())
            }
        }
    }
    private val exportKeysLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val jsonl = pendingKeyExportJsonl
        pendingKeyExportJsonl = null
        if (uri == null || jsonl == null) {
            return@registerForActivityResult
        }
        state.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { exportDumpJson(uri, jsonl) }
            }
            result.onSuccess {
                showNotice("Keys exported")
            }.onFailure { error ->
                showNotice("Unable to export keys. ${error.message.orEmpty()}".trim())
            }
        }
    }
    private var uiState
        get() = state.uiState
        set(value) { state.uiState = value }
    private var notice
        get() = state.notice
        set(value) { state.notice = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (!state.keysLoaded) {
            val loadedKeys = loadKeyStore()
            keyStore = loadedKeys.keys.takeIf { it.isNotEmpty() }
            keyLoadWarnings = loadedKeys.warnings
            state.keysLoaded = true
        }
        uiState = uiState.copy(nfc = currentNfcAvailability())

        enableEdgeToEdge()
        setContent {
            FeliCaDumperTheme {
                if (appScreen == AppScreen.DUMP) {
                    DumpScreen(
                        uiState = uiState,
                        notice = notice,
                        onNoticeShown = { notice = null },
                        onExportJson = { launchDumpExport() },
                        onOpenKeyManagement = { appScreen = AppScreen.KEY_MANAGEMENT },
                        onOpenNfcSettings = { openNfcSettings() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    BackHandler { appScreen = AppScreen.DUMP }
                    KeyManagementScreen(
                        state = KeyManagementState(
                            keyLoadWarnings = keyLoadWarnings,
                            entries = flattenPersistedKeys(keyStore),
                            isBusy = uiState.isReading,
                        ),
                        notice = notice,
                        onNoticeShown = { notice = null },
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

        uiState = uiState.copy(nfc = currentNfcAvailability())
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
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

        state.viewModelScope.launch {
            var readSucceeded = false
            try {
                uiState = uiState.copy(isReading = true)

                val result = withContext(Dispatchers.IO) {
                    runCatching { FelicaDumpEngine.dump(tag, keyStore) }
                }

                result.onSuccess { dump ->
                    readSucceeded = true
                    uiState = uiState.copy(
                        isReading = false,
                        dump = dump,
                        readError = null,
                    )
                }.onFailure { error ->
                    uiState = uiState.copy(
                        isReading = false,
                        dump = null,
                        readError = error.message.orEmpty(),
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

    private fun currentNfcAvailability(): NfcAvailability {
        val adapter = nfcAdapter
        return when {
            adapter == null -> NfcAvailability.UNSUPPORTED
            !adapter.isEnabled -> NfcAvailability.DISABLED
            else -> NfcAvailability.AVAILABLE
        }
    }

    private fun openNfcSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    }

    private fun showNotice(text: String) {
        notice = Notice(id = SystemClock.elapsedRealtimeNanos(), text = text)
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
            return
        }
        pendingKeyExportJsonl = serializeKeysToJsonl(keys)
        exportKeysLauncher.launch("keys-${System.currentTimeMillis()}.jsonl")
    }

    private fun launchDumpExport() {
        val dump = uiState.dump
        if (dump == null) {
            return
        }
        pendingExportJson = gson.toJson(dump.toDumpFile())
        exportDumpLauncher.launch("felica-dump-${dump.generatedAtEpochMs}.json")
    }

    private fun deletePersistedKey(entry: PersistedKeyEntry) {
        val currentKeys = keyStore
        if (currentKeys.isNullOrEmpty()) {
            return
        }
        state.viewModelScope.launch {
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
                showNotice("Key deleted")
            }.onFailure { error ->
                showNotice("Unable to delete the key. ${error.message.orEmpty()}".trim())
            }
        }
    }

    private fun deleteAllPersistedKeys() {
        state.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { persistKeyStore(emptyMap()) }
            }
            result.onSuccess {
                keyStore = null
                keyLoadWarnings = emptyList()
                showNotice("All keys deleted")
            }.onFailure { error ->
                showNotice("Unable to delete keys. ${error.message.orEmpty()}".trim())
            }
        }
    }

    private fun persistKeyStore(keys: SystemKeys) {
        val destination = File(filesDir, "keys.jsonl")
        if (keys.isEmpty()) {
            if (destination.exists() && !destination.delete()) {
                throw IOException("Failed to delete key file.")
            }
            return
        }
        destination.writeText(serializeKeysToJsonl(keys), Charsets.UTF_8)
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

        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open the selected file.")

        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }

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
        applicationContext.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw IOException("Unable to open the selected destination.")
        return uri.toString()
    }
}
