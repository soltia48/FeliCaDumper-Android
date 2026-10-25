package ws.nyaa.felicadumper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds screen state across configuration changes (rotation, font scale, dark mode, ...),
 * which recreate [MainActivity].
 */
internal class MainViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var appScreenState by mutableStateOf(
        savedStateHandle[KEY_APP_SCREEN] ?: AppScreen.DUMP
    )

    /** Also kept in [SavedStateHandle] so the open screen survives process death. */
    var appScreen: AppScreen
        get() = appScreenState
        set(value) {
            appScreenState = value
            savedStateHandle[KEY_APP_SCREEN] = value
        }

    var uiState by mutableStateOf(
        DumpUiState(
            nfc = NfcAvailability.AVAILABLE,
            isReading = false,
            dump = null,
            readError = null,
        )
    )
    var notice by mutableStateOf<Notice?>(null)
    var keyStore by mutableStateOf<SystemKeys?>(null)
    var keyLoadWarnings by mutableStateOf<List<String>>(emptyList())
    var keysLoaded = false

    var pendingExportJson: String? = null
    var pendingKeyExportJsonl: String? = null

    val reading = AtomicBoolean(false)
    val lastSuccessfulTagId = AtomicReference<String?>(null)
    val lastSuccessfulReadAtMs = AtomicLong(0L)

    private companion object {
        const val KEY_APP_SCREEN = "app_screen"
    }
}
