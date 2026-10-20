package app.nouri.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.FoodSearchResult
import app.nouri.core.ai.AiJson
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.MealAnalysis
import app.nouri.core.ai.MealImageRequest
import app.nouri.core.diary.MealType
import app.nouri.core.scan.AddedFat
import app.nouri.core.scan.EstimationContext
import app.nouri.core.scan.MealScanResult
import app.nouri.core.scan.PrepContext
import app.nouri.core.scan.ScanEdit
import app.nouri.core.scan.ScanLogging
import app.nouri.core.util.SystemTime
import app.nouri.data.prefs.ImageRetention
import app.nouri.data.repo.LogResult
import app.nouri.ui.common.logEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import java.io.File

data class MealReviewState(
    val phase: AnalysisPhase = AnalysisPhase.PREPARING,
    val result: MealScanResult? = null,
    val error: ErrorUi? = null,
    val photos: List<File> = emptyList(),
    val providerLabel: String = "",
    val providerWarnings: List<String> = emptyList(),
    val date: LocalDate = SystemTime.diaryToday(),
    val meal: MealType = MealType.forMinuteOfDay(SystemTime.minuteOfDay()),
    val searchResults: List<FoodSearchResult> = emptyList(),
    val missingCapture: Boolean = false,
    val logged: Boolean = false,
    val duplicate: LogResult.PossibleDuplicate? = null,
)

class MealReviewViewModel(private val c: AppContainer, private val captureId: String) : ViewModel() {
    private val _state = MutableStateFlow(MealReviewState())
    val state: StateFlow<MealReviewState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        val capture = c.scanSessions[captureId]
        if (capture == null) _state.update { it.copy(missingCapture = true, phase = AnalysisPhase.FAILED) } else {
            _state.update { it.copy(photos = capture.images.map { img -> img.file }) }
            analyse()
        }
    }

    fun analyse() {
        val capture = c.scanSessions[captureId] ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val settings = c.settings.current()
            _state.update { it.copy(phase = AnalysisPhase.ANALYSING, error = null, providerLabel = settings.ai.selected.label, date = SystemTime.diaryToday(settings.dayStartMinute)) }
            // Recorded before the request so retention clean-up can always find these photos.
            c.scans.save(captureId, "meal", settings.ai.selected.name, settings.ai.selected == app.nouri.core.ai.ProviderKind.MOCK, "", capture.images.map { it.file.path })
            val personalise = settings.privacy.personaliseAiPrompts
            val request = MealImageRequest(
                images = capture.images.map { it.payload },
                hints = capture.hints,
                preferredCuisines = if (personalise) settings.preferences.favouriteCuisines else emptyList(),
                knownDishes = if (personalise) settings.correctionMemory.knownLabels() else emptyList(),
                demoScenario = capture.demoScenario,
            )
            when (val r = c.ai.analyzeMeal(request)) {
                is AiResult.Failure -> _state.update { it.copy(phase = AnalysisPhase.FAILED, error = r.error.toUi()) }
                is AiResult.Success -> {
                    _state.update { it.copy(phase = AnalysisPhase.CALCULATING, providerLabel = r.meta.provider.label, providerWarnings = r.meta.warnings) }
                    val core = c.core(settings.preferences.measurementSystem)
                    val result = withContext(Dispatchers.Default) {
                        core.estimator.estimate(
                            r.value,
                            EstimationContext(captureId, capture.hints, capture.images.size, simulated = r.meta.simulated, memory = settings.correctionMemory),
                        )
                    }
                    c.scans.save(
                        captureId, "meal", r.meta.provider.name, r.meta.simulated,
                        AiJson.json.encodeToString(MealAnalysis.serializer(), r.value), capture.images.map { it.file.path },
                    )
                    _state.update { it.copy(phase = AnalysisPhase.DONE, result = result) }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(phase = AnalysisPhase.FAILED, error = ErrorUi("Cancelled", "Analysis was cancelled. Your photos are kept until you leave this screen.", listOf(ErrorAction.RETRY, ErrorAction.DESCRIBE_INSTEAD))) }
    }

    fun edit(edit: ScanEdit) {
        val current = _state.value.result ?: return
        _state.update { it.copy(result = c.core().estimator.apply(current, edit)) }
    }

    fun setMeal(m: MealType) = _state.update { it.copy(meal = m) }

    fun search(query: String) = viewModelScope.launch {
        val results = withContext(Dispatchers.Default) { c.core().search(query, limit = 15) }
        _state.update { it.copy(searchResults = results) }
    }

    fun clearSearch() = _state.update { it.copy(searchResults = emptyList()) }

    fun addFat(itemId: String, ingredientId: String, label: String, grams: Double) = edit(ScanEdit.AddFat(itemId, AddedFat(ingredientId, grams, label)))

    fun setPrep(itemId: String, context: PrepContext) = edit(ScanEdit.SetPreparation(itemId, context))

    fun log(force: Boolean = false) = viewModelScope.launch {
        val s = _state.value
        val result = s.result ?: return@launch
        val entries = ScanLogging.toDiaryEntries(result, s.date, s.meal, SystemTime.nowEpochMs(), SystemTime.zoneId(), c::newId)
        if (entries.isEmpty()) {
            c.messages.show("Nothing to log — include at least one matched item")
            return@launch
        }
        when (val r = c.logEntries(entries, force)) {
            is LogResult.PossibleDuplicate -> _state.update { it.copy(duplicate = r) }
            is LogResult.Logged -> {
                c.scans.markLogged(captureId, r.entries.map { it.id })
                // Learn from what the user confirmed or corrected (on-device only).
                c.settings.update { st -> st.copy(correctionMemory = result.includedItems.fold(st.correctionMemory) { m, item -> m.learn(item) }) }
                finish()
            }
            LogResult.AlreadyLogged -> finish()
        }
    }

    fun confirmDuplicate() { _state.update { it.copy(duplicate = null) }; log(force = true) }
    fun dismissDuplicate() = _state.update { it.copy(duplicate = null) }

    private suspend fun finish() {
        applyRetention()
        _state.update { it.copy(logged = true) }
    }

    private suspend fun applyRetention() {
        val capture = c.scanSessions.remove(captureId) ?: return
        if (c.settings.current().privacy.imageRetention == ImageRetention.DELETE_AFTER_ANALYSIS) {
            c.images.delete(capture.images.map { it.file.path })
            c.scans.clearImages(listOf(captureId))
        }
    }

    override fun onCleared() {
        // Leaving without logging: honour "delete after analysis" straight away.
        val capture = c.scanSessions.remove(captureId)
        if (capture != null) {
            c.appScope.launch {
                if (c.settings.current().privacy.imageRetention == ImageRetention.DELETE_AFTER_ANALYSIS) {
                    c.images.delete(capture.images.map { it.file.path })
                    c.scans.clearImages(listOf(captureId))
                }
            }
        }
        super.onCleared()
    }
}
