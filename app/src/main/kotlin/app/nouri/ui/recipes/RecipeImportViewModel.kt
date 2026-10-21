package app.nouri.ui.recipes

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.camera.OnDeviceText
import app.nouri.core.ai.AiCapability
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.ImageRole
import app.nouri.core.ai.ImportedRecipe
import app.nouri.core.ai.ProviderKind
import app.nouri.core.ai.RecipeImportRequest
import app.nouri.core.nutrition.LineStatus
import app.nouri.core.recipes.GeneratedRecipeConverter
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recipes.RecipeTextImporter
import app.nouri.core.util.SystemTime
import app.nouri.image.ImageProcessor
import app.nouri.image.ProcessedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Text or screenshots shared into Nouri from another app, waiting for the import screen. */
data class SharedRecipeContent(val text: String?, val images: List<Uri>)

/** Hand-off from the share intent to the import screen. */
class RecipeImportInbox {
    private val _pending = MutableStateFlow<SharedRecipeContent?>(null)
    val pending: StateFlow<SharedRecipeContent?> = _pending.asStateFlow()

    fun offer(content: SharedRecipeContent) { _pending.value = content }

    fun take(): SharedRecipeContent? = _pending.value.also { _pending.value = null }
}

/** Unsaved recipes handed to the editor for review (kept in memory only). */
class RecipeDrafts {
    private val drafts = ConcurrentHashMap<String, Recipe>()
    fun put(recipe: Recipe): String = recipe.id.also { drafts[it] = recipe }
    fun take(id: String): Recipe? = drafts.remove(id)
}

data class ImportPreview(
    val recipe: Recipe,
    val notes: List<String>,
    val unmatched: List<String>,
    val usedAi: Boolean,
)

data class RecipeImportState(
    val text: String = "",
    val images: List<ProcessedImage> = emptyList(),
    val working: String? = null,
    val error: String? = null,
    val aiAvailable: Boolean = false,
    val providerLabel: String = "",
    val consentNeeded: Boolean = false,
    val preview: ImportPreview? = null,
) {
    val hasInput: Boolean get() = text.isNotBlank() || images.isNotEmpty()
}

class RecipeImportViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(RecipeImportState())
    val state: StateFlow<RecipeImportState> = _state.asStateFlow()

    /** Screenshots need more pixels than food photos for small print to stay legible. */
    private val images = ImageProcessor(c.context, maxEdgePx = 2048)
    private var counter = 0
    private val batch = c.newId()

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            val status = c.ai.activeStatus()
            _state.update {
                it.copy(
                    aiAvailable = s.ai.selected != ProviderKind.MOCK && status != null && status.connected &&
                        !status.isSimulated && AiCapability.RECIPE_IMPORT in status.capabilities,
                    providerLabel = s.ai.selected.label,
                )
            }
        }
        // Content shared from another app — also while this screen is already open.
        viewModelScope.launch {
            c.recipeInbox.pending.collect { pending ->
                if (pending == null) return@collect
                val shared = c.recipeInbox.take() ?: return@collect
                _state.update { it.copy(preview = null, text = listOfNotNull(it.text.ifBlank { null }, shared.text).joinToString("\n\n")) }
                if (shared.images.isNotEmpty()) addImages(shared.images)
            }
        }
    }

    fun setText(t: String) = _state.update { it.copy(text = t, error = null) }

    fun addImages(uris: List<Uri>) = viewModelScope.launch {
        val room = MAX_IMAGES - _state.value.images.size
        if (room <= 0 || uris.isEmpty()) return@launch
        _state.update { it.copy(working = "Preparing screenshots…", error = null) }
        val added = uris.take(room).mapNotNull { uri ->
            runCatching { images.fromUri(uri, ImageRole.OTHER, name = "recipe_${batch}_${counter++}") }.getOrNull()
        }
        _state.update {
            it.copy(
                images = it.images + added, working = null,
                error = if (added.size < uris.take(room).size) "Some images couldn't be opened" else null,
            )
        }
    }

    fun removeImage(index: Int) {
        val img = _state.value.images.getOrNull(index) ?: return
        images.delete(listOf(img.file.path))
        _state.update { s -> s.copy(images = s.images.filterIndexed { i, _ -> i != index }) }
    }

    /** No AI: pasted text plus on-device text recognition of the screenshots. Nothing leaves the phone. */
    fun importOnDevice() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(working = if (s.images.isNotEmpty()) "Reading screenshots on your phone…" else "Reading the recipe…", error = null) }
        val ocr = s.images.map { img -> runCatching { OnDeviceText.read(c.context, img.file) }.getOrDefault("") }
        val text = (listOf(s.text) + ocr).filter { it.isNotBlank() }.joinToString("\n\n")
        val imported = withContext(Dispatchers.Default) { RecipeTextImporter.parse(text) }
        show(imported, usedAi = false)
    }

    /** Sends only the pasted text and the screenshots to the selected provider, after consent for images. */
    fun importWithAi() = viewModelScope.launch {
        val s = _state.value
        val settings = c.settings.current()
        if (s.images.isNotEmpty() && settings.ai.selected != ProviderKind.MOCK && !settings.ai.photoConsent) {
            _state.update { it.copy(consentNeeded = true) }
            return@launch
        }
        _state.update { it.copy(working = "Cleaning up the recipe with ${s.providerLabel}…", error = null) }
        val request = RecipeImportRequest(text = s.text.takeIf { it.isNotBlank() }, images = s.images.map { it.payload })
        when (val r = c.ai.importRecipe(request)) {
            is AiResult.Success -> show(r.value, usedAi = true)
            is AiResult.Failure -> _state.update { it.copy(working = null, error = r.error.message + " — you can still import it on your phone without AI.") }
        }
    }

    fun grantConsent() = viewModelScope.launch {
        c.settings.update { it.copy(ai = it.ai.copy(photoConsent = true)) }
        _state.update { it.copy(consentNeeded = false) }
        importWithAi()
    }

    fun declineConsent() = _state.update { it.copy(consentNeeded = false) }

    private suspend fun show(imported: ImportedRecipe, usedAi: Boolean) {
        val structured = imported.recipe
        if (!imported.isRecipe || structured == null) {
            _state.update { it.copy(working = null, error = imported.notes.firstOrNull() ?: "No recipe was found in that text or those screenshots.") }
            return
        }
        val core = c.core(c.settings.current().preferences.measurementSystem)
        val recipe = GeneratedRecipeConverter.convert(
            structured, "user:${c.newId()}", SystemTime.nowEpochMs(), core.normalizer,
            origin = RecipeOrigin.IMPORTED,
            attribution = imported.sourceName?.let { "From $it" },
            extraNotes = emptyList(),
        )
        val report = withContext(Dispatchers.Default) { core.engine.calculateRecipe(recipe) }
        val unmatched = report.lines.filter { it.status == LineStatus.UNRESOLVED }.map { it.line.displayName }
        _state.update { it.copy(working = null, preview = ImportPreview(recipe, imported.notes, unmatched, usedAi)) }
    }

    fun discardPreview() = _state.update { it.copy(preview = null) }

    /** Saves as-is; returns the new recipe id. */
    suspend fun save(): String? {
        val recipe = _state.value.preview?.recipe ?: return null
        c.recipes.save(recipe)
        cleanUp()
        c.messages.show("Recipe saved")
        return recipe.id
    }

    /** Hands the preview to the editor for changes before saving; returns the draft id. */
    fun editFirst(): String? {
        val recipe = _state.value.preview?.recipe ?: return null
        cleanUp()
        return c.recipeDrafts.put(recipe)
    }

    /** Screenshots are only needed for reading; they are deleted once the recipe is saved or edited. */
    private fun cleanUp() {
        images.delete(_state.value.images.map { it.file.path })
        _state.update { it.copy(images = emptyList()) }
    }

    override fun onCleared() {
        images.delete(_state.value.images.map { it.file.path })
        super.onCleared()
    }

    companion object {
        const val MAX_IMAGES = 6
    }
}
