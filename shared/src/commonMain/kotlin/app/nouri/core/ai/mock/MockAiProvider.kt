package app.nouri.core.ai.mock

import app.nouri.core.ai.AiCallMeta
import app.nouri.core.ai.AiCapability
import app.nouri.core.ai.AiError
import app.nouri.core.ai.AiProvider
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.AiValidator
import app.nouri.core.ai.BillingMode
import app.nouri.core.ai.ClarifyingQuestion
import app.nouri.core.ai.DetectedFoodItem
import app.nouri.core.ai.DetectedIngredient
import app.nouri.core.ai.DishCandidate
import app.nouri.core.ai.GeneratedRecipes
import app.nouri.core.ai.GramRange
import app.nouri.core.ai.IngredientAnalysis
import app.nouri.core.ai.IngredientCertainty
import app.nouri.core.ai.IngredientImageRequest
import app.nouri.core.ai.LabelImageRequest
import app.nouri.core.ai.LabelReading
import app.nouri.core.ai.Likelihood
import app.nouri.core.ai.MealAnalysis
import app.nouri.core.ai.MealImageRequest
import app.nouri.core.ai.MealTextRequest
import app.nouri.core.ai.ParsedMealText
import app.nouri.core.ai.ProviderKind
import app.nouri.core.ai.ProviderStatus
import app.nouri.core.ai.RecipeGenerationRequest
import app.nouri.core.ai.ReferenceObject
import kotlinx.coroutines.delay

/**
 * Deterministic simulated provider. It cannot see images: it returns one of the
 * fixed demo scenarios (chosen by the user, or derived from the image hash), and
 * every result is flagged `simulated` so the UI can label it as a demo.
 */
class MockAiProvider(
    private val latencyMs: Long = 700,
    private val recipeSource: (RecipeGenerationRequest) -> GeneratedRecipes? = { null },
    private val textParser: (MealTextRequest) -> ParsedMealText? = { null },
) : AiProvider {
    override val kind = ProviderKind.MOCK

    override suspend fun status() = ProviderStatus(
        kind = kind,
        connected = true,
        accountLabel = "Demo mode",
        capabilities = AiCapability.entries.toSet(),
        billing = BillingMode.NONE,
        model = "nouri-demo-fixtures",
        restrictions = listOf("Simulated results from fixed demo scenarios; photos are not analysed."),
        isSimulated = true,
    )

    override suspend fun analyzeMeal(request: MealImageRequest): AiResult<MealAnalysis> {
        simulateLatency()
        val key = request.demoScenario ?: pickByHash(request.images.firstOrNull()?.sha256Hex, MockScenarios.mealKeys)
        val analysis = MockScenarios.meals[key] ?: MockScenarios.meals.getValue(MockScenarios.mealKeys.first())
        val validated = AiValidator.meal(analysis).getOrElse { return AiResult.Failure(AiError.InvalidResponse(it.message ?: "invalid fixture")) }
        return AiResult.Success(validated.value, meta(listOf("Demo scenario: $key")))
    }

    override suspend fun identifyIngredients(request: IngredientImageRequest): AiResult<IngredientAnalysis> {
        simulateLatency()
        val key = request.demoScenario ?: pickByHash(request.images.firstOrNull()?.sha256Hex, MockScenarios.ingredientKeys)
        val analysis = MockScenarios.ingredients[key] ?: MockScenarios.ingredients.getValue(MockScenarios.ingredientKeys.first())
        return AiResult.Success(analysis, meta(listOf("Demo scenario: $key")))
    }

    override suspend fun generateRecipes(request: RecipeGenerationRequest): AiResult<GeneratedRecipes> {
        simulateLatency()
        val recipes = recipeSource(request) ?: return AiResult.Failure(
            AiError.CapabilityUnavailable(AiCapability.RECIPE_GENERATION, "Demo mode suggests recipes from Nouri's built-in collection instead"),
        )
        return AiResult.Success(recipes, meta(listOf("Demo: recipes from the built-in collection")))
    }

    override suspend fun parseMealText(request: MealTextRequest): AiResult<ParsedMealText> {
        simulateLatency()
        val parsed = textParser(request) ?: return AiResult.Failure(AiError.InvalidResponse("Could not parse the text"))
        return AiResult.Success(parsed, meta())
    }

    override suspend fun readLabel(request: LabelImageRequest): AiResult<LabelReading> {
        simulateLatency()
        return AiResult.Success(LabelReading(productName = "Demo cereal", panelText = MockScenarios.usLabelText, language = "en"), meta())
    }

    private fun meta(warnings: List<String> = emptyList()) =
        AiCallMeta(ProviderKind.MOCK, "nouri-demo-fixtures", latencyMs, simulated = true, warnings = warnings)

    private suspend fun simulateLatency() {
        if (latencyMs > 0) delay(latencyMs)
    }

    private fun pickByHash(hash: String?, keys: List<String>): String {
        if (hash.isNullOrEmpty()) return keys.first()
        val n = hash.take(8).toLongOrNull(16) ?: hash.hashCode().toLong()
        return keys[((n % keys.size) + keys.size).toInt() % keys.size]
    }
}

/** Fixed, realistic fixtures. Also used by tests and the benchmark harness. */
object MockScenarios {
    val meals: Map<String, MealAnalysis> = linkedMapOf(
        "biryani_plate" to MealAnalysis(
            items = listOf(
                DetectedFoodItem(
                    label = "Chicken biryani",
                    candidates = listOf(
                        DishCandidate("chicken biryani", Likelihood.HIGH, "layered grains in white and orange, chicken on the bone, fried onions"),
                        DishCandidate("chicken pulao", Likelihood.LOW, "rice with chicken, but colour layering suggests biryani"),
                    ),
                    cuisine = "Pakistani",
                    components = listOf("basmati rice", "chicken on the bone", "fried onions", "potato", "whole spices"),
                    cookingMethod = "layered and steamed (dum)",
                    visibleFat = "medium",
                    estimatedGrams = GramRange(280.0, 360.0, 450.0),
                    householdEstimate = "about 1¾ cups",
                    portionBasis = "standard 26 cm dinner plate assumed; fork used for scale",
                ),
                DetectedFoodItem(
                    label = "Cucumber raita",
                    candidates = listOf(DishCandidate("raita", Likelihood.HIGH, "white yogurt with green flecks")),
                    cuisine = "Pakistani",
                    components = listOf("yogurt", "cucumber", "mint"),
                    estimatedGrams = GramRange(60.0, 90.0, 120.0),
                    householdEstimate = "a small bowl, about 1/3 cup",
                    portionBasis = "small bowl next to plate",
                    isSauceOrCondiment = true,
                ),
                DetectedFoodItem(
                    label = "Kachumber salad",
                    candidates = listOf(DishCandidate("kachumber salad", Likelihood.MEDIUM, "diced cucumber, tomato and onion"), DishCandidate("green salad", Likelihood.LOW)),
                    components = listOf("cucumber", "tomato", "onion"),
                    visibleFat = "none",
                    estimatedGrams = GramRange(50.0, 80.0, 110.0),
                    householdEstimate = "about 1/2 cup",
                    portionBasis = "side portion on plate",
                ),
            ),
            referenceObjects = listOf(ReferenceObject("fork", "standard dinner fork beside plate")),
            imageQuality = "good",
        ),
        "ambiguous_rice" to MealAnalysis(
            items = listOf(
                DetectedFoodItem(
                    label = "Chicken rice dish",
                    candidates = listOf(
                        DishCandidate("chicken pulao", Likelihood.MEDIUM, "uniform brown grains"),
                        DishCandidate("chicken biryani", Likelihood.MEDIUM, "some coloured grains visible near the edge"),
                    ),
                    components = listOf("rice", "chicken pieces"),
                    visibleFat = "medium",
                    estimatedGrams = GramRange(250.0, 330.0, 420.0),
                    householdEstimate = "about 1½ cups",
                    portionBasis = "plate size assumed",
                ),
            ),
            suggestedQuestion = ClarifyingQuestion("Is this biryani or pulao?", listOf("Biryani", "Pulao", "Not sure"), itemIndex = 0),
            imageQuality = "good",
        ),
        "karahi_naan" to MealAnalysis(
            items = listOf(
                DetectedFoodItem(
                    label = "Chicken karahi",
                    candidates = listOf(DishCandidate("chicken karahi", Likelihood.HIGH, "thick tomato masala in a karahi, ginger julienne"), DishCandidate("chicken curry", Likelihood.LOW)),
                    cuisine = "Pakistani",
                    components = listOf("bone-in chicken", "tomato masala", "green chillies", "ginger"),
                    cookingMethod = "stir-fried in a karahi",
                    visibleFat = "heavy",
                    estimatedGrams = GramRange(200.0, 280.0, 360.0),
                    householdEstimate = "about 1¼ cups",
                    portionBasis = "serving dish; portion assumed to be one person's share",
                ),
                DetectedFoodItem(
                    label = "Naan",
                    candidates = listOf(DishCandidate("naan", Likelihood.HIGH, "puffy leavened bread with char bubbles")),
                    components = listOf("naan"),
                    estimatedGrams = GramRange(80.0, 100.0, 130.0),
                    householdEstimate = "1 naan",
                    portionBasis = "one whole naan",
                ),
            ),
            suggestedQuestion = ClarifyingQuestion("Was this from a restaurant or homemade?", listOf("Restaurant", "Homemade"), itemIndex = 0),
            imageQuality = "good",
        ),
        "breakfast_paratha" to MealAnalysis(
            items = listOf(
                DetectedFoodItem("Masala omelette", listOf(DishCandidate("omelette", Likelihood.HIGH)), components = listOf("egg", "onion", "tomato", "green chilli"), visibleFat = "medium", estimatedGrams = GramRange(120.0, 160.0, 200.0), householdEstimate = "2-egg omelette", portionBasis = "typical 2-egg omelette size"),
                DetectedFoodItem("Paratha", listOf(DishCandidate("paratha", Likelihood.HIGH, "flaky layered shiny flatbread"), DishCandidate("chapati", Likelihood.LOW)), visibleFat = "medium", estimatedGrams = GramRange(70.0, 85.0, 110.0), householdEstimate = "1 paratha", portionBasis = "one whole paratha"),
                DetectedFoodItem("Tea with milk", listOf(DishCandidate("tea", Likelihood.HIGH)), components = listOf("black tea", "milk", "sugar"), estimatedGrams = GramRange(180.0, 220.0, 250.0), householdEstimate = "1 cup", portionBasis = "standard tea cup", isSauceOrCondiment = false),
            ),
            imageQuality = "good",
        ),
        "burger_fries" to MealAnalysis(
            items = listOf(
                DetectedFoodItem("Cheeseburger", listOf(DishCandidate("beef burger", Likelihood.HIGH, "beef patty with cheese in a bun")), components = listOf("bun", "beef patty", "cheese", "lettuce", "tomato"), visibleFat = "medium", estimatedGrams = GramRange(200.0, 250.0, 320.0), householdEstimate = "1 burger", portionBasis = "standard burger bun ~10 cm"),
                DetectedFoodItem("French fries", listOf(DishCandidate("french fries", Likelihood.HIGH)), visibleFat = "heavy", estimatedGrams = GramRange(90.0, 120.0, 160.0), householdEstimate = "medium portion", portionBasis = "paper carton"),
            ),
            imageQuality = "good",
        ),
        "ramen_bowl" to MealAnalysis(
            items = listOf(
                DetectedFoodItem("Ramen", listOf(DishCandidate("ramen", Likelihood.HIGH, "wheat noodles in broth with soft egg"), DishCandidate("pho bo", Likelihood.LOW)), cuisine = "Japanese", components = listOf("wheat noodles", "broth", "soft-boiled egg", "sliced meat", "spring onion"), visibleFat = "medium", estimatedGrams = GramRange(650.0, 850.0, 1050.0), householdEstimate = "1 large bowl", portionBasis = "deep ramen bowl ~20 cm"),
            ),
            suggestedQuestion = ClarifyingQuestion("What kind of broth is it?", listOf("Pork (tonkotsu)", "Chicken/soy (shoyu)", "Miso", "Not sure"), itemIndex = 0),
            imageQuality = "good",
        ),
    )
    val mealKeys: List<String> = meals.keys.toList()

    val ingredients: Map<String, IngredientAnalysis> = linkedMapOf(
        "fridge" to IngredientAnalysis(
            items = listOf(
                DetectedIngredient("tomatoes", 4.0, "piece", certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("onions", 2.0, "piece", certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("eggs", 6.0, "piece", packaged = true, certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("chicken thighs", 500.0, "g", packaged = true, certainty = IngredientCertainty.LIKELY, notes = "tray, label partly visible"),
                DetectedIngredient("plain yogurt", 500.0, "g", packaged = true, certainty = IngredientCertainty.LIKELY),
                DetectedIngredient("spinach", null, null, packaged = true, certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("cheddar cheese", null, null, packaged = true, certainty = IngredientCertainty.LIKELY),
                DetectedIngredient("green chillies", 5.0, "piece", certainty = IngredientCertainty.LIKELY),
                DetectedIngredient("chutney", null, null, packaged = true, certainty = IngredientCertainty.POSSIBLE, notes = "opaque jar; contents uncertain"),
            ),
            scene = "fridge",
        ),
        "counter" to IngredientAnalysis(
            items = listOf(
                DetectedIngredient("chicken breast", 2.0, "piece", certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("potatoes", 3.0, "piece", certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("onion", 2.0, "piece", certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("tomatoes", 3.0, "piece", certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("garlic", 1.0, "bulb", certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("yogurt", null, null, packaged = true, certainty = IngredientCertainty.LIKELY),
                DetectedIngredient("basmati rice", null, null, packaged = true, certainty = IngredientCertainty.LIKELY),
                DetectedIngredient("ginger", 1.0, "piece", certainty = IngredientCertainty.LIKELY),
            ),
            scene = "counter",
        ),
        "pantry" to IngredientAnalysis(
            items = listOf(
                DetectedIngredient("red lentils", null, null, packaged = true, certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("rice", null, null, packaged = true, certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("canned chickpeas", 2.0, "can", packaged = true, certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("pasta", null, null, packaged = true, certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("canned tomatoes", 1.0, "can", packaged = true, certainty = IngredientCertainty.CLEAR),
                DetectedIngredient("rolled oats", null, null, packaged = true, certainty = IngredientCertainty.LIKELY),
                DetectedIngredient("spices", null, null, packaged = true, certainty = IngredientCertainty.POSSIBLE, notes = "several small jars"),
            ),
            scene = "pantry",
        ),
    )
    val ingredientKeys: List<String> = ingredients.keys.toList()

    const val usLabelText = """Nutrition Facts
8 servings per container
Serving size 2/3 cup (55g)
Amount per serving
Calories 230
% Daily Value*
Total Fat 8g 10%
Saturated Fat 1g 5%
Trans Fat 0g
Cholesterol 0mg 0%
Sodium 160mg 7%
Total Carbohydrate 37g 13%
Dietary Fiber 4g 14%
Total Sugars 12g
Includes 10g Added Sugars 20%
Protein 3g"""
}
