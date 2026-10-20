package app.nouri.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.nutrition.RecipeNutritionReport
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.recipes.Difficulty
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeIngredient
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recipes.RecipeStep
import app.nouri.core.units.formatAmount
import app.nouri.core.util.SystemTime
import app.nouri.ui.components.toUserDouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorFields(
    val name: String = "",
    val cuisineId: String = "international",
    val region: String = "",
    val description: String = "",
    val servings: String = "2",
    val prepMinutes: String = "10",
    val cookMinutes: String = "20",
    val ingredients: String = "",
    val steps: String = "",
    val cookedWeight: String = "",
)

/** How one typed line was understood, shown under the ingredient box. */
data class EditorLine(val parsed: ParsedIngredientLine, val matchedName: String?, val confident: Boolean)

data class EditorState(
    val loading: Boolean = true,
    val fields: EditorFields = EditorFields(),
    val lines: List<EditorLine> = emptyList(),
    val preview: RecipeNutritionReport? = null,
    val errors: List<String> = emptyList(),
    /** Editing a built-in or shared recipe saves a copy; the original is never overwritten. */
    val savesCopyOf: String? = null,
    val isNew: Boolean = true,
)

class RecipeEditorViewModel(private val c: AppContainer, private val recipeId: String?) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _savedId = MutableStateFlow<String?>(null)
    /** Set once the recipe is saved; the screen then opens it. */
    val savedId: StateFlow<String?> = _savedId.asStateFlow()

    private var original: Recipe? = null
    private var editInPlace = false
    private var previewJob: Job? = null
    private val normalizer: IngredientNormalizer get() = c.core().normalizer

    init {
        viewModelScope.launch {
            val existing = recipeId?.let { c.recipes.get(it) }
            if (existing != null) {
                original = existing
                val saved = c.recipes.saved.first().any { it.id == existing.id }
                editInPlace = saved && existing.origin != RecipeOrigin.EDITORIAL_TRADITIONAL && existing.origin != RecipeOrigin.EDITORIAL_VARIATION
                _state.update {
                    it.copy(
                        fields = EditorFields(
                            name = if (editInPlace) existing.name else "${existing.name} (my version)",
                            cuisineId = existing.cuisineId,
                            region = existing.region.orEmpty(),
                            description = existing.description,
                            servings = existing.servings.toString(),
                            prepMinutes = existing.prepMinutes.toString(),
                            cookMinutes = existing.cookMinutes.toString(),
                            ingredients = RecipeText.ingredientsText(existing.ingredients),
                            steps = RecipeText.stepsText(existing.steps),
                            cookedWeight = existing.cookedYieldGrams?.let(::formatAmount).orEmpty(),
                        ),
                        savesCopyOf = if (editInPlace) null else existing.id,
                        isNew = !editInPlace,
                    )
                }
            }
            _state.update { it.copy(loading = false) }
            refresh()
        }
    }

    fun update(transform: (EditorFields) -> EditorFields) {
        _state.update { it.copy(fields = transform(it.fields)) }
        refresh()
    }

    /** Re-parses lines immediately and recalculates nutrition shortly after typing stops. */
    private fun refresh() {
        val fields = _state.value.fields
        val parsed = RecipeLineParser.parseAll(fields.ingredients)
        val lines = parsed.map { p ->
            val match = normalizer.resolve(p.name)
            EditorLine(p, match?.ingredient?.name, match?.isConfident == true)
        }
        _state.update { it.copy(lines = lines, errors = validate(fields, parsed)) }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(300)
            val draft = buildRecipe(fields, "preview") ?: run {
                _state.update { it.copy(preview = null) }
                return@launch
            }
            val system = c.settings.current().preferences.measurementSystem
            val report = withContext(Dispatchers.Default) { c.core(system).engine.calculateRecipe(draft) }
            _state.update { it.copy(preview = report) }
        }
    }

    private fun validate(f: EditorFields, parsed: List<ParsedIngredientLine>): List<String> = buildList {
        if (f.name.isBlank()) add("Give the recipe a name")
        val servings = f.servings.trim().toIntOrNull()
        if (servings == null || servings !in 1..100) add("Servings must be a whole number from 1 to 100")
        if (f.prepMinutes.trim().toIntOrNull()?.let { it >= 0 } != true) add("Prep time must be a whole number of minutes")
        if (f.cookMinutes.trim().toIntOrNull()?.let { it >= 0 } != true) add("Cooking time must be a whole number of minutes")
        if (parsed.isEmpty()) add("Add at least one ingredient")
        if (f.cookedWeight.isNotBlank() && (f.cookedWeight.toUserDouble()?.let { it > 0 } != true)) add("Cooked weight must be a number of grams")
    }

    private fun buildRecipe(f: EditorFields, id: String): Recipe? {
        val servings = f.servings.trim().toIntOrNull()?.takeIf { it in 1..100 } ?: return null
        val parsed = RecipeLineParser.parseAll(f.ingredients)
        if (parsed.isEmpty()) return null
        val base = original
        // Lines the user didn't touch keep details the text can't express (oil left in the pan, cooked-state rice, weighed grams).
        val untouched = base?.ingredients?.associateBy { RecipeText.ingredientLine(it).trim() to it.group }.orEmpty()
        val ingredients = parsed.map { p ->
            untouched[p.raw to p.group] ?: RecipeIngredient(
                displayName = p.name,
                ingredientId = normalizer.resolve(p.name)?.takeIf { it.isConfident }?.ingredient?.id,
                quantity = p.quantity,
                preparation = p.preparation,
                optional = p.optional,
                group = p.group,
            )
        }
        val originalSteps = base?.steps?.associateBy { it.text.trim() }.orEmpty()
        val steps = f.steps.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { text ->
            originalSteps[text] ?: RecipeStep(text, RecipeLineParser.stepTimerSeconds(text))
        }
        val copyOf = _state.value.savesCopyOf
        return Recipe(
            id = id,
            name = f.name.trim().ifEmpty { "Untitled recipe" },
            cuisineId = f.cuisineId,
            region = f.region.trim().ifEmpty { null },
            description = f.description.trim(),
            servings = servings,
            ingredients = ingredients,
            steps = steps,
            prepMinutes = f.prepMinutes.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            cookMinutes = f.cookMinutes.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            difficulty = base?.difficulty ?: Difficulty.MEDIUM,
            equipment = base?.equipment.orEmpty(),
            tags = base?.tags.orEmpty(),
            // In-place edits keep their origin (an edited AI recipe is still AI-derived); new recipes and copies are the user's.
            origin = if (editInPlace) base!!.origin else RecipeOrigin.USER,
            variantOf = if (editInPlace) base?.variantOf else copyOf,
            variantSummary = when {
                editInPlace -> base?.variantSummary
                copyOf != null -> "Your edited copy of ${base?.name}"
                else -> null
            },
            attribution = if (editInPlace) base?.attribution else null,
            storage = base?.storage,
            reheating = base?.reheating,
            foodSafety = base?.foodSafety.orEmpty(),
            cookedYieldGrams = f.cookedWeight.toUserDouble()?.takeIf { it > 0 },
            notes = base?.notes.orEmpty(),
            createdAtEpochMs = SystemTime.nowEpochMs(),
        )
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (s.errors.isNotEmpty()) return@launch
        val id = if (editInPlace) original!!.id else "user:${c.newId()}"
        val recipe = buildRecipe(s.fields, id) ?: return@launch
        c.recipes.save(recipe)
        c.messages.show(if (editInPlace) "Recipe updated" else "Recipe saved")
        _savedId.value = id
    }
}
