package app.nouri.core.ai

import app.nouri.core.dishes.DishCatalog
import app.nouri.core.dishes.DishDefinition

/**
 * Instructions sent with each request. They are passed in the Responses API
 * `instructions` field (plan-usage requests reject system-role messages) and
 * deliberately keep personal context minimal.
 */
object Prompts {

    private const val JSON_ONLY = "Respond with a single JSON object that matches the provided schema. No prose, no markdown."

    /** Visual cues for dishes that are frequently confused, so the model can tell them apart. */
    fun disambiguationGuide(dishes: List<DishDefinition> = DishCatalog.all, maxChars: Int = 2600): String {
        val lines = dishes.filter { it.lookalikes.isNotEmpty() && it.visualCues.isNotBlank() }
            .map { "- ${it.name}: ${it.visualCues}" }
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.length + line.length + 1 > maxChars) break
            sb.appendLine(line)
        }
        return sb.toString().trimEnd()
    }

    fun mealAnalysis(request: MealImageRequest): String = buildString {
        appendLine("You identify foods in meal photos for a nutrition diary. Another system calculates nutrition from your output, so accuracy of identification and portion size matters; do NOT estimate calories or nutrients.")
        appendLine()
        appendLine("For each separate food on the plate or table (including breads, rice, sauces, condiments, drinks):")
        appendLine("1. Give the most specific dish name you can justify (e.g. \"chicken biryani\", not \"rice\"; \"chicken karahi\", not \"curry\"). Use visual evidence: grain colour and layering, gravy texture and colour, garnish, cooking vessel, char marks.")
        appendLine("2. List 1–4 candidate names with likelihood high/medium/low. When two dishes remain plausible, say so rather than inventing certainty.")
        appendLine("3. List visible components, cooking method, and visible fat (none/light/medium/heavy: glossy oil, butter, cream, fried surfaces).")
        appendLine("4. Estimate the EDIBLE weight as a gram range (low, typical, high) and a household estimate (\"about 1.5 cups\", \"2 rotis\"). Exclude bones and inedible parts from grams when visible.")
        appendLine("5. In portionBasis, state what the estimate relies on. Do not infer exact plate size from a single photo: use a reference object or the user's stated plate size if available, otherwise say the plate size is assumed.")
        appendLine("6. Mark sauces/condiments (raita, chutney, dressing, ketchup) with isSauceOrCondiment=true and items that are partly hidden with partiallyHidden=true.")
        appendLine("7. Put anything you cannot identify in `unidentified` instead of guessing.")
        appendLine("8. Ask at most ONE short multiple-choice question, and only if the answer would change the estimate materially (dish identity with very different richness, homemade vs restaurant for oily dishes, a hidden component). Otherwise set suggestedQuestion to null.")
        appendLine("9. Set imageQuality to blurry/dark/partial/not_food when appropriate.")
        if (request.images.size > 1) {
            appendLine()
            appendLine("There are ${request.images.size} photos of the SAME meal (roles: ${request.images.joinToString { it.role.name.lowercase() }}). Combine them: use the top-down view for area and the angled view for height/depth. Do not double-count items.")
        }
        val h = request.hints
        val hints = buildList {
            h.plateDiameterCm?.let { add("plate diameter ${it.toInt()} cm") }
            h.bowlVolumeMl?.let { add("bowl volume ${it.toInt()} ml") }
            h.totalWeightGrams?.let { add("the food on the plate weighs ${it.toInt()} g in total") }
            h.referenceObject?.let { add("reference object in photo: $it") }
            h.homemade?.let { add(if (it) "homemade" else "restaurant or takeaway") }
            h.userNote?.takeIf { it.isNotBlank() }?.let { add("user note: \"${it.take(200)}\"") }
        }
        if (hints.isNotEmpty()) {
            appendLine()
            appendLine("User-provided context (treat as reliable): ${hints.joinToString("; ")}.")
        }
        if (request.preferredCuisines.isNotEmpty()) {
            appendLine("The user often eats: ${request.preferredCuisines.take(6).joinToString()}. Use this only to break ties between visually similar dishes.")
        }
        if (request.knownDishes.isNotEmpty()) {
            appendLine("Dishes this user has logged before: ${request.knownDishes.take(15).joinToString()}.")
        }
        appendLine()
        appendLine("Distinguishing cues for commonly confused dishes:")
        appendLine(disambiguationGuide())
        appendLine()
        append(JSON_ONLY)
    }

    fun ingredientAnalysis(request: IngredientImageRequest): String = buildString {
        appendLine("You list food items visible in photos of a fridge, pantry shelf, counter or grocery haul so a cooking assistant can suggest recipes.")
        appendLine("- Name each distinct item generically and in English (e.g. \"tomato\", \"plain yogurt\", \"chicken thighs\"), adding the visible variety when useful.")
        appendLine("- Give a count or approximate quantity only when you can see it (\"3\" tomatoes, \"about 500 g\" chicken); otherwise leave quantity null.")
        appendLine("- Mark packaged=true for items in packaging; read the product type from the label if legible.")
        appendLine("- certainty: clear (plainly visible), likely, or possible (partly hidden, ambiguous, or an opaque container). Never list items you cannot see; do not assume what is inside closed containers.")
        appendLine("- Ignore non-food items.")
        if (request.images.size > 1) appendLine("There are ${request.images.size} photos; merge duplicates across photos.")
        append(JSON_ONLY)
    }

    fun recipeGeneration(request: RecipeGenerationRequest): String = buildString {
        appendLine("You are a knowledgeable home-cooking assistant who respects culinary traditions.")
        appendLine("Create ${request.count} varied recipe options that fit the request below.")
        appendLine("Rules:")
        appendLine("- Use authentic dish names, ingredient combinations, spices and techniques. Do not flatten a country into one generic cuisine; name the region when meaningful.")
        appendLine("- If a recipe deviates from the traditional version (lighter, faster, substitutions), set isTraditional=false, set variantOf to the traditional dish name and say what changed in the description. Never present a variation as the original.")
        appendLine("- Give every ingredient an amount and unit (metric grams/ml preferred; household units allowed) and grams when you can. Quantities must be consistent with the stated servings and with the steps.")
        appendLine("- Steps must be complete and in order, with timers where useful and temperatures for ovens and doneness. Include food-safety notes: poultry and minced meat to 74 °C (165 °F), whole cuts of beef/lamb to at least 63 °C (145 °F) with rest, fish to 63 °C (145 °F), reheat leftovers to 74 °C.")
        appendLine("- List realistic substitutions for less common ingredients.")
        appendLine("- Do NOT include calories or nutrient numbers; they are calculated separately.")
        appendLine("- Do not cite or invent published sources, chefs or websites.")
        appendLine()
        appendLine("Request:")
        if (request.availableIngredients.isNotEmpty()) {
            appendLine("- Available ingredients: ${request.availableIngredients.take(40).joinToString()}")
            appendLine(
                if (request.onlyAvailableIngredients) "- Use ONLY these ingredients plus water, salt, pepper and cooking oil."
                else "- Prefer the available ingredients; keep extra ingredients few and common.",
            )
        }
        request.userPrompt?.takeIf { it.isNotBlank() }?.let { appendLine("- The user asked: \"${it.take(500)}\"") }
        if (request.cuisines.isNotEmpty()) appendLine("- Cuisines: ${request.cuisines.joinToString()}")
        if (request.dietaryRequirements.isNotEmpty()) appendLine("- Dietary requirements (mandatory): ${request.dietaryRequirements.joinToString()}")
        if (request.allergiesToAvoid.isNotEmpty()) appendLine("- Allergies — never include: ${request.allergiesToAvoid.joinToString()}")
        if (request.dislikes.isNotEmpty()) appendLine("- Avoid: ${request.dislikes.joinToString()}")
        request.maxMinutes?.let { appendLine("- Total time at most $it minutes") }
        request.targetKcalPerServing?.let { appendLine("- Aim for roughly $it kcal per serving by choosing portion sizes and lighter techniques (it will be checked)") }
        if (request.equipment.isNotEmpty()) appendLine("- Available equipment: ${request.equipment.joinToString()}")
        request.skill?.let { appendLine("- Cooking skill: $it") }
        appendLine("- Servings: ${request.servings}")
        append(JSON_ONLY)
    }

    fun mealText(request: MealTextRequest): String = buildString {
        appendLine("Split the user's description of what they ate into individual foods for a nutrition diary.")
        appendLine("- Keep the food name as specific as the user said it (\"chicken curry\", \"roti\", \"coffee with a splash of milk\" → coffee + milk).")
        appendLine("- amount/unit exactly as stated (\"2\" + \"piece\", \"0.5\" + \"cup\"); grams only if the user gave a weight.")
        appendLine("- For phrases like \"half of the biryani I made yesterday\", set recipeFraction (0.5) and recipeReference (\"biryani\").")
        appendLine("- If the user names a brand, shop or restaurant (\"2 Deep brand naans\", \"McDonald's fries\"), put it in brand and keep food generic (\"naan\", \"fries\"). Never guess a brand that wasn't mentioned.")
        appendLine("- Use a measurement unit only if one was stated (g, cup, tbsp, slice, bowl…). For plain counts (\"2 naans\"), set amount and leave unit null.")
        appendLine("- Do not add foods the user did not mention. Do not estimate calories.")
        if (request.recentRecipeNames.isNotEmpty()) appendLine("The user's saved recipes: ${request.recentRecipeNames.take(20).joinToString()}.")
        append(JSON_ONLY)
    }

    fun recipeImport(request: RecipeImportRequest): String = buildString {
        appendLine("The user is saving a recipe they found (pasted text and/or screenshots, possibly several pages of one recipe). Transcribe it into the JSON structure.")
        appendLine("Rules:")
        appendLine("- Keep the author's recipe: the same ingredients, amounts and steps. Do not add, remove, substitute or 'improve' anything, and do not invent quantities.")
        appendLine("- Clean up only: fix obvious typos and OCR errors, split combined ingredient lines, put each ingredient's amount and unit in their own fields (keep the original unit; amount null when none is given, e.g. \"salt to taste\"), move preparation words (\"chopped\", \"to taste\") to preparation, keep ingredient groups (\"For the marinade\"), and number steps in order. Merge text that continues across screenshots.")
        appendLine("- Ignore everything that isn't the recipe: ads, comments, life stories, navigation, nutrition panels.")
        appendLine("- servings, prepMinutes and cookMinutes: as stated. If not stated, give your best estimate and add a note saying it was estimated.")
        appendLine("- cuisine and region: best guess from the dish (\"pakistani\", \"italian\"…). isTraditional=false, variantOf=null.")
        appendLine("- sourceName: the author, website, book or channel only if it is written in the input. Never guess one.")
        appendLine("- notes: anything the user should check (missing amounts, estimated servings, parts you couldn't read).")
        appendLine("- If there is no recipe in the input, set isRecipe=false and recipe=null.")
        appendLine("- Do not include calories or nutrient numbers; they are calculated separately.")
        if (request.images.size > 1) appendLine("There are ${request.images.size} screenshots; treat them as consecutive pages of one recipe.")
        append(JSON_ONLY)
    }

    fun labelReading(): String =
        "Transcribe the nutrition facts panel in the photo exactly as printed, one line per row, preserving numbers and units " +
            "(include serving size and servings per container). Also give the product name if visible. Do not compute or correct values. " + JSON_ONLY
}
