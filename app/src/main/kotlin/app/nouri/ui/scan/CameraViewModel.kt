package app.nouri.ui.scan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.ai.ImageRole
import app.nouri.core.ai.PortionHints
import app.nouri.core.ai.ProviderKind
import app.nouri.core.ai.mock.MockScenarios
import app.nouri.core.util.SystemTime
import app.nouri.image.ProcessedImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class FlashSetting(val label: String) { OFF("Flash off"), AUTO("Flash auto"), ON("Flash on") }

data class CameraUiState(
    val mode: ScanMode,
    val photos: List<ProcessedImage> = emptyList(),
    /** True once the user has finished capturing and is checking the photos. */
    val reviewing: Boolean = false,
    val processing: Boolean = false,
    val error: String? = null,
    val flash: FlashSetting = FlashSetting.AUTO,
    val barcode: String? = null,
    /** Barcode of the product this label photo belongs to (set when coming from an incomplete product). */
    val linkedBarcode: String? = null,
    val hints: PortionHints = PortionHints(),
    val demoMode: Boolean = true,
    val providerLabel: String = "",
    val demoScenario: String? = null,
    /** Non-null when the user must agree to photo analysis before continuing. */
    val consentFor: String? = null,
) {
    val maxPhotos: Int get() = if (mode == ScanMode.MEAL) 4 else if (mode == ScanMode.INGREDIENTS) 3 else 1
    val canAddMore: Boolean get() = photos.size < maxPhotos
    val demoScenarios: List<String> get() = when (mode) {
        ScanMode.MEAL -> MockScenarios.mealKeys
        ScanMode.INGREDIENTS -> MockScenarios.ingredientKeys
        ScanMode.LABEL -> emptyList()
    }
}

/** Where the camera hands off once photos are ready. */
sealed interface CameraNext {
    data class Review(val mode: ScanMode, val captureId: String) : CameraNext
}

class CameraViewModel(private val c: AppContainer, initialMode: ScanMode, knownBarcode: String? = null) : ViewModel() {
    // A barcode carried over from a product without nutrition data, so the label photo is linked to it.
    private val _state = MutableStateFlow(CameraUiState(initialMode, linkedBarcode = knownBarcode))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _next = MutableStateFlow<CameraNext?>(null)
    val next: StateFlow<CameraNext?> = _next.asStateFlow()

    private val captureId = c.newId()
    private var counter = 0

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            _state.update { it.copy(demoMode = s.ai.selected == ProviderKind.MOCK, providerLabel = s.ai.selected.label, demoScenario = s.demoScenario) }
        }
    }

    fun setMode(mode: ScanMode) {
        if (_state.value.photos.isNotEmpty()) return // switching would mix photo types; the UI disables it
        _state.update { it.copy(mode = mode, barcode = null, error = null) }
    }

    fun setFlash(f: FlashSetting) = _state.update { it.copy(flash = f) }

    fun nextFlash() = setFlash(FlashSetting.entries[(_state.value.flash.ordinal + 1) % FlashSetting.entries.size])

    fun captureFailed(message: String) = _state.update { it.copy(error = message, processing = false) }

    fun startProcessing() = _state.update { it.copy(processing = true, error = null) }

    private fun roleFor(index: Int, mode: ScanMode): ImageRole = when (mode) {
        ScanMode.MEAL -> if (index == 0) ImageRole.TOP_DOWN else ImageRole.ANGLED
        ScanMode.INGREDIENTS -> ImageRole.OTHER
        ScanMode.LABEL -> ImageRole.LABEL
    }

    /** A photo just taken by the camera into a temporary file. */
    fun addCaptured(file: File) = viewModelScope.launch {
        val s = _state.value
        try {
            val processed = c.images.fromFile(file, roleFor(s.photos.size, s.mode), name = "${captureId}_${counter++}")
            file.delete()
            // Always show the photo right away so the user can retake, rotate or crop it.
            _state.update { st -> st.copy(photos = st.photos + processed, processing = false, reviewing = true) }
        } catch (e: Exception) {
            file.delete()
            captureFailed("Couldn't process that photo. Please try again.")
        }
    }

    fun addFromGallery(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        startProcessing()
        val room = _state.value.maxPhotos - _state.value.photos.size
        val added = mutableListOf<ProcessedImage>()
        for (uri in uris.take(room)) {
            runCatching { c.images.fromUri(uri, roleFor(_state.value.photos.size + added.size, _state.value.mode), name = "${captureId}_${counter++}") }
                .onSuccess { added += it }
        }
        _state.update { st ->
            st.copy(
                photos = st.photos + added, processing = false, reviewing = added.isNotEmpty() || st.reviewing,
                error = if (added.size < uris.take(room).size) "Some photos couldn't be opened" else null,
            )
        }
    }

    fun onBarcode(code: String) {
        if (_state.value.barcode == null) _state.update { it.copy(barcode = code) }
    }

    fun clearBarcode() = _state.update { it.copy(barcode = null) }

    fun rotate(index: Int) = edit(index) { img -> c.images.reprocess(img, 90, 1f, name = "${captureId}_${counter++}") }

    fun crop(index: Int, fraction: Float) = edit(index) { img -> c.images.reprocess(img, 0, fraction, name = "${captureId}_${counter++}") }

    private fun edit(index: Int, transform: suspend (ProcessedImage) -> ProcessedImage) = viewModelScope.launch {
        val current = _state.value.photos.getOrNull(index) ?: return@launch
        startProcessing()
        runCatching { transform(current) }
            .onSuccess { updated ->
                c.images.delete(listOf(current.file.path))
                _state.update { st -> st.copy(photos = st.photos.toMutableList().also { it[index] = updated }, processing = false) }
            }
            .onFailure { captureFailed("Couldn't edit that photo") }
    }

    fun remove(index: Int) {
        val img = _state.value.photos.getOrNull(index) ?: return
        c.images.delete(listOf(img.file.path))
        _state.update { st ->
            val left = st.photos.filterIndexed { i, _ -> i != index }
            st.copy(photos = left, reviewing = left.isNotEmpty() && st.reviewing)
        }
    }

    /** Back to the viewfinder to add another angle (meal) or more ingredients. */
    fun addAnother() = _state.update { it.copy(reviewing = false) }

    fun finishCapturing() = _state.update { it.copy(reviewing = true) }

    fun setHints(h: PortionHints) = _state.update { it.copy(hints = h) }

    fun setDemoScenario(key: String?) = _state.update { it.copy(demoScenario = key) }

    /** Checks consent, then hands the photos to the review screen. */
    fun analyse() = viewModelScope.launch {
        val s = c.settings.current()
        val st = _state.value
        val needsAi = st.mode != ScanMode.LABEL // labels are read on-device first
        if (needsAi && s.ai.selected != ProviderKind.MOCK && !s.ai.photoConsent) {
            _state.update { it.copy(consentFor = s.ai.selected.label) }
            return@launch
        }
        handOff()
    }

    fun grantConsent() = viewModelScope.launch {
        c.settings.update { it.copy(ai = it.ai.copy(photoConsent = true)) }
        _state.update { it.copy(consentFor = null) }
        handOff()
    }

    fun declineConsent() = _state.update { it.copy(consentFor = null) }

    /** Barcode found live: no photo needed. */
    fun lookUpBarcode() {
        val code = _state.value.barcode ?: return
        c.scanSessions.put(ScanCapture(captureId, ScanMode.LABEL, emptyList(), SystemTime.nowEpochMs(), barcode = code))
        _next.value = CameraNext.Review(ScanMode.LABEL, captureId)
    }

    private fun handOff() {
        val st = _state.value
        if (st.photos.isEmpty()) return
        c.scanSessions.put(
            ScanCapture(captureId, st.mode, st.photos, SystemTime.nowEpochMs(), barcode = st.barcode ?: st.linkedBarcode, hints = st.hints, demoScenario = st.demoScenario.takeIf { st.demoMode }),
        )
        _next.value = CameraNext.Review(st.mode, captureId)
    }

    /** Discards photos if the user leaves without analysing. */
    fun discardIfUnused() {
        if (_next.value == null) c.images.delete(_state.value.photos.map { it.file.path })
    }

    override fun onCleared() {
        discardIfUnused()
        super.onCleared()
    }
}
