package app.nouri.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.camera.OnDeviceText
import app.nouri.core.ai.AiCapability
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.LabelImageRequest
import app.nouri.core.ai.ProviderKind
import app.nouri.core.barcode.BrandedProduct
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.label.LabelParseResult
import app.nouri.core.label.NutrientFields
import app.nouri.core.label.NutritionLabelParser
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.formatAmount
import app.nouri.data.prefs.ImageRetention
import app.nouri.data.repo.BarcodeOutcome
import app.nouri.ui.components.toUserDouble
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class LabelPhase { LOOKING_UP, READING, FORM, PRODUCT, NEEDS_PHOTO, FAILED }

/** Editable label values, as text so the user can correct OCR mistakes freely. */
data class LabelForm(
    val name: String = "",
    val per100g: Boolean = true,
    val servingGrams: String = "",
    val servingText: String = "",
    val energy: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val fibre: String = "",
    val sugar: String = "",
    val sodiumMg: String = "",
    val satFat: String = "",
) {
    val canSave: Boolean get() = name.isNotBlank() && energy.toUserDouble() != null && (per100g || servingGrams.toUserDouble()?.let { it > 0 } == true)
}

data class LabelReviewState(
    val phase: LabelPhase = LabelPhase.LOOKING_UP,
    val barcode: String? = null,
    val product: BrandedProduct? = null,
    val readyFoodId: String? = null,
    val fromCache: Boolean = false,
    val message: String? = null,
    val photo: File? = null,
    val form: LabelForm = LabelForm(),
    val parse: LabelParseResult? = null,
    val aiAvailable: Boolean = false,
    val aiLabel: String = "",
    val readWithAi: Boolean = false,
    val simulated: Boolean = false,
    val consentNeeded: Boolean = false,
    val savedFoodId: String? = null,
    val retryable: Boolean = false,
)

class LabelReviewViewModel(private val c: AppContainer, private val captureId: String) : ViewModel() {
    private val _state = MutableStateFlow(LabelReviewState())
    val state: StateFlow<LabelReviewState> = _state.asStateFlow()

    init {
        val capture = c.scanSessions[captureId]
        viewModelScope.launch {
            val status = c.ai.activeStatus()
            val s = c.settings.current()
            _state.update {
                it.copy(
                    aiAvailable = status != null && status.connected && AiCapability.LABEL_IMAGE_READING in status.capabilities,
                    aiLabel = s.ai.selected.label,
                )
            }
            when {
                capture == null -> _state.update { it.copy(phase = LabelPhase.FAILED, message = "The photo is no longer available. Please scan again.") }
                capture.images.isNotEmpty() -> {
                    _state.update { it.copy(barcode = capture.barcode, photo = capture.images.first().file) }
                    readPhoto()
                }
                capture.barcode != null -> lookUp(capture.barcode)
                else -> _state.update { it.copy(phase = LabelPhase.FAILED, message = "Nothing to read") }
            }
        }
    }

    fun lookUp(code: String) = viewModelScope.launch {
        _state.update { it.copy(phase = LabelPhase.LOOKING_UP, barcode = code, message = null) }
        when (val r = c.barcodes.lookup(code)) {
            is BarcodeOutcome.Ready -> _state.update { it.copy(phase = LabelPhase.PRODUCT, product = r.product, readyFoodId = r.food.id, fromCache = r.fromCache, form = it.form.copy(name = r.food.name)) }
            is BarcodeOutcome.NeedsLabel -> _state.update {
                it.copy(phase = LabelPhase.NEEDS_PHOTO, product = r.product, message = "${r.product.name} was found, but its nutrition information is incomplete. Photograph the nutrition table to add it.")
            }
            BarcodeOutcome.NotFound -> _state.update { it.copy(phase = LabelPhase.NEEDS_PHOTO, message = "This product isn't in Open Food Facts yet. Photograph the nutrition table to add it.") }
            is BarcodeOutcome.Offline -> _state.update { it.copy(phase = LabelPhase.NEEDS_PHOTO, message = "${r.reason}. You can photograph the nutrition table instead — it's read on your phone.", retryable = true) }
            is BarcodeOutcome.Invalid -> _state.update { it.copy(phase = LabelPhase.FAILED, message = r.reason) }
        }
    }

    /** On-device OCR first; the AI provider is only used if the user asks. */
    private suspend fun readPhoto() {
        val file = _state.value.photo ?: return
        _state.update { it.copy(phase = LabelPhase.READING, message = null) }
        // Tracked so the retention setting also applies to label photos.
        c.scans.save(captureId, "label", "ON_DEVICE", false, "", listOf(file.path))
        val text = runCatching { OnDeviceText.read(c.context, file) }.getOrDefault("")
        val parsed = text.takeIf { it.isNotBlank() }?.let { NutritionLabelParser.parse(it) }
        if (parsed == null || NutrientFields.ENERGY_KCAL in parsed.missingFields) {
            // Maybe this was a barcode photo rather than a nutrition table.
            if (_state.value.barcode == null) {
                val codes = runCatching { OnDeviceText.barcodes(c.context, file) }.getOrDefault(emptyList())
                codes.firstOrNull()?.let { lookUp(it); return }
            }
        }
        applyParse(parsed, fromAi = false, emptyMessage = "Couldn't read the label clearly. Retake it straight-on in good light, enter the values yourself, or try AI reading.")
    }

    fun readWithAi() = viewModelScope.launch {
        val file = _state.value.photo ?: return@launch
        val s = c.settings.current()
        if (s.ai.selected != ProviderKind.MOCK && !s.ai.photoConsent) {
            _state.update { it.copy(consentNeeded = true) }
            return@launch
        }
        val capture = c.scanSessions[captureId]
        val payload = capture?.images?.firstOrNull()?.payload ?: return@launch
        _state.update { it.copy(phase = LabelPhase.READING) }
        when (val r = c.ai.readLabel(LabelImageRequest(payload))) {
            is AiResult.Success -> {
                val parsed = NutritionLabelParser.parse(r.value.panelText)
                _state.update { it.copy(simulated = r.meta.simulated) }
                applyParse(parsed, fromAi = true, emptyMessage = "The label still couldn't be read. Please enter the values.", productName = r.value.productName)
            }
            is AiResult.Failure -> _state.update { it.copy(phase = LabelPhase.FORM, message = r.error.message) }
        }
    }

    fun grantConsent() = viewModelScope.launch {
        c.settings.update { it.copy(ai = it.ai.copy(photoConsent = true)) }
        _state.update { it.copy(consentNeeded = false) }
        readWithAi()
    }

    fun declineConsent() = _state.update { it.copy(consentNeeded = false) }

    private fun applyParse(parsed: LabelParseResult?, fromAi: Boolean, emptyMessage: String, productName: String? = null) {
        val base = _state.value.form.copy(name = _state.value.form.name.ifBlank { productName ?: parsed?.productName ?: _state.value.product?.name ?: "" })
        if (parsed == null) {
            _state.update { it.copy(phase = LabelPhase.FORM, form = base, message = emptyMessage, readWithAi = fromAi) }
            return
        }
        val values = parsed.per100g ?: parsed.perServing
        val per100 = parsed.per100g != null
        fun f(v: Double?, field: String) = if (values == null || field in parsed.missingFields) "" else v?.let { formatAmount(it) } ?: ""
        val form = base.copy(
            per100g = per100,
            servingGrams = parsed.servingSize?.grams?.let { formatAmount(it) } ?: base.servingGrams,
            servingText = parsed.servingSize?.text ?: base.servingText,
            energy = f(values?.energyKcal, NutrientFields.ENERGY_KCAL),
            protein = f(values?.proteinG, NutrientFields.PROTEIN_G),
            carbs = f(values?.carbsG, NutrientFields.CARBS_G),
            fat = f(values?.fatG, NutrientFields.FAT_G),
            fibre = values?.fiberG?.let { formatAmount(it) } ?: "",
            sugar = values?.sugarG?.let { formatAmount(it) } ?: "",
            sodiumMg = values?.sodiumMg?.let { formatAmount(it) } ?: "",
            satFat = values?.saturatedFatG?.let { formatAmount(it) } ?: "",
        )
        _state.update { it.copy(phase = LabelPhase.FORM, parse = parsed, form = form, readWithAi = fromAi, message = if (NutrientFields.ENERGY_KCAL in parsed.missingFields) emptyMessage else null) }
    }

    fun updateForm(transform: (LabelForm) -> LabelForm) = _state.update { it.copy(form = transform(it.form)) }

    fun enterManually() = _state.update { it.copy(phase = LabelPhase.FORM, form = it.form.copy(name = it.form.name.ifBlank { it.product?.name ?: "" })) }

    /** Saves the label as one of the user's foods (linked to the barcode when known). */
    fun save() = viewModelScope.launch {
        val s = _state.value
        val f = s.form
        if (!f.canSave) return@launch
        val values = Nutrients(
            energyKcal = f.energy.toUserDouble() ?: return@launch,
            proteinG = f.protein.toUserDouble() ?: 0.0,
            carbsG = f.carbs.toUserDouble() ?: 0.0,
            fatG = f.fat.toUserDouble() ?: 0.0,
            fiberG = f.fibre.toUserDouble(),
            sugarG = f.sugar.toUserDouble(),
            sodiumMg = f.sodiumMg.toUserDouble(),
            saturatedFatG = f.satFat.toUserDouble(),
        )
        val servingGrams = f.servingGrams.toUserDouble()?.takeIf { it > 0 }
        val per100 = if (f.per100g) values else values * (100.0 / servingGrams!!)
        val complete = listOf(f.protein, f.carbs, f.fat).all { it.toUserDouble() != null }
        val id = "label:${s.barcode ?: c.newId()}"
        val food = FoodReference(
            id = id,
            name = f.name.trim(),
            per100g = per100,
            source = NutritionSource.MANUFACTURER_LABEL,
            quality = if (complete) DataQuality.HIGH else DataQuality.MEDIUM,
            portions = servingGrams?.let { listOf(FoodPortion(QuantityUnit.SERVING, it, label = f.servingText.ifBlank { "1 serving" })) }.orEmpty(),
            servingGrams = servingGrams,
            sourceDetail = buildString {
                append("Nutrition label")
                if (s.readWithAi) append(" (read with ${s.aiLabel}${if (s.simulated) ", demo" else ""}, checked by you)") else append(" (read on your phone, checked by you)")
            },
        )
        c.userFoods.save(food, brand = s.product?.brand, barcode = s.barcode, allergens = s.product?.allergens.orEmpty())
        _state.update { it.copy(savedFoodId = id) }
    }

    override fun onCleared() {
        val capture = c.scanSessions.remove(captureId)
        if (capture != null && capture.images.isNotEmpty()) {
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
