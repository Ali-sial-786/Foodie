package app.nouri.core.benchmark

import app.nouri.core.ai.DetectedFoodItem
import app.nouri.core.ai.DishCandidate
import app.nouri.core.ai.GramRange
import app.nouri.core.ai.Likelihood
import app.nouri.core.ai.MealAnalysis
import app.nouri.core.ai.mock.MockScenarios
import app.nouri.core.benchmark.BenchmarkCategory.AFRICAN
import app.nouri.core.benchmark.BenchmarkCategory.DIFFICULT_PORTION
import app.nouri.core.benchmark.BenchmarkCategory.EAST_ASIAN
import app.nouri.core.benchmark.BenchmarkCategory.LATIN_AMERICAN
import app.nouri.core.benchmark.BenchmarkCategory.LOOKALIKE
import app.nouri.core.benchmark.BenchmarkCategory.MIDDLE_EASTERN
import app.nouri.core.benchmark.BenchmarkCategory.MIXED_PLATE
import app.nouri.core.benchmark.BenchmarkCategory.PACKAGED
import app.nouri.core.benchmark.BenchmarkCategory.PARTIALLY_OBSCURED
import app.nouri.core.benchmark.BenchmarkCategory.RESTAURANT
import app.nouri.core.benchmark.BenchmarkCategory.SINGLE_INGREDIENT
import app.nouri.core.benchmark.BenchmarkCategory.SOUTH_ASIAN
import app.nouri.core.benchmark.BenchmarkCategory.WESTERN

/**
 * Synthetic benchmark set. Each case pairs a provider output (with realistic
 * mistakes: lookalike confusion, missed drinks, under-estimated large portions)
 * with a ground truth expressed through Nouri's reference recipes and USDA records.
 *
 * Because the truth uses the same reference data, these cases measure the
 * pipeline's handling of identification and portion errors — not real-world
 * nutrition accuracy. A photo dataset with weighed portions is needed for that
 * (see TESTING.md, "Benchmark").
 */
object BenchmarkFixtures {
    private fun item(label: String, low: Double, typical: Double, high: Double, vararg candidates: Pair<String, Likelihood>, hidden: Boolean = false, fat: String? = null) =
        DetectedFoodItem(
            label = label,
            candidates = candidates.map { DishCandidate(it.first, it.second) }.ifEmpty { listOf(DishCandidate(label, Likelihood.HIGH)) },
            estimatedGrams = GramRange(low, typical, high),
            partiallyHidden = hidden,
            visibleFat = fat,
        )

    private fun meal(vararg items: DetectedFoodItem) = MealAnalysis(items.toList())

    private fun case(id: String, categories: Set<BenchmarkCategory>, description: String, fixture: MealAnalysis, truthSource: TruthSource = TruthSource.KNOWN_RECIPE, vararg truth: TruthItem) =
        BenchmarkCase(id, categories, description, truth.toList(), truthSource, providerFixture = fixture)

    private fun t(id: String, grams: Double, vararg alt: String) = TruthItem(id, grams, alt.toList())

    val cases: List<BenchmarkCase> = listOf(
        case("banana", setOf(SINGLE_INGREDIENT), "One medium banana", meal(item("Banana", 100.0, 120.0, 140.0)), TruthSource.WEIGHED, t("usda:09040", 118.0)),
        case("apple", setOf(SINGLE_INGREDIENT), "One apple", meal(item("Apple", 150.0, 170.0, 200.0)), TruthSource.WEIGHED, t("usda:09003", 182.0)),
        case("boiled_eggs", setOf(SINGLE_INGREDIENT), "Two hard-boiled eggs", meal(item("Boiled eggs", 80.0, 100.0, 120.0)), TruthSource.WEIGHED, t("usda:01129", 100.0, "usda:01123")),
        case("biryani_plate", setOf(MIXED_PLATE, SOUTH_ASIAN), "Chicken biryani with raita and salad", MockScenarios.meals.getValue("biryani_plate"), TruthSource.KNOWN_RECIPE,
            t("dish:chicken_biryani", 400.0), t("dish:raita", 100.0), t("dish:kachumber_salad", 80.0)),
        case("pulao_vs_biryani", setOf(LOOKALIKE, SOUTH_ASIAN), "Biryani that looks like pulao", meal(item("Chicken pulao", 260.0, 330.0, 420.0, "chicken pulao" to Likelihood.MEDIUM, "chicken biryani" to Likelihood.MEDIUM)), TruthSource.KNOWN_RECIPE,
            t("dish:chicken_biryani", 350.0)),
        case("karahi_restaurant", setOf(RESTAURANT, SOUTH_ASIAN), "Restaurant chicken karahi and naan", MockScenarios.meals.getValue("karahi_naan").copy(suggestedQuestion = null), TruthSource.KNOWN_RECIPE,
            t("dish:chicken_karahi", 300.0), t("usda:04582", 15.0), t("dish:naan", 110.0)),
        case("dal_rice_roti", setOf(MIXED_PLATE, SOUTH_ASIAN), "Masoor dal, rice and two rotis",
            meal(item("Daal", 180.0, 240.0, 300.0, "masoor dal" to Likelihood.HIGH), item("Rice", 150.0, 180.0, 220.0, "plain rice" to Likelihood.HIGH), item("Roti", 70.0, 80.0, 90.0, "chapati" to Likelihood.HIGH)),
            TruthSource.KNOWN_RECIPE, t("dish:masoor_dal", 250.0), t("dish:plain_rice", 200.0), t("dish:chapati", 80.0)),
        case("ramen", setOf(EAST_ASIAN), "Bowl of chicken shoyu ramen", MockScenarios.meals.getValue("ramen_bowl").copy(suggestedQuestion = null), TruthSource.KNOWN_RECIPE, t("dish:ramen", 900.0)),
        case("nigiri", setOf(EAST_ASIAN), "Six pieces of salmon nigiri", meal(item("Salmon nigiri", 180.0, 220.0, 260.0)), TruthSource.KNOWN_RECIPE, t("dish:salmon_nigiri", 222.0)),
        case("pad_thai", setOf(EAST_ASIAN, RESTAURANT), "Takeaway pad thai", meal(item("Pad thai", 350.0, 450.0, 550.0, fat = "medium")), TruthSource.KNOWN_RECIPE, t("dish:pad_thai", 480.0)),
        case("shawarma_fries", setOf(MIDDLE_EASTERN, MIXED_PLATE), "Chicken shawarma wrap with fries",
            meal(item("Shawarma wrap", 250.0, 310.0, 380.0, "shawarma wrap" to Likelihood.HIGH), item("Fries", 90.0, 110.0, 140.0, "french fries" to Likelihood.HIGH)),
            TruthSource.KNOWN_RECIPE, t("dish:shawarma_wrap", 330.0), t("dish:french_fries", 120.0)),
        case("hummus_falafel", setOf(MIDDLE_EASTERN), "Hummus, falafel and pita",
            meal(item("Hummus", 50.0, 70.0, 90.0), item("Falafel", 70.0, 90.0, 110.0), item("Pita bread", 50.0, 60.0, 70.0, "pita" to Likelihood.HIGH)),
            TruthSource.KNOWN_RECIPE, t("dish:hummus", 80.0), t("dish:falafel", 100.0), t("usda:18041", 60.0)),
        case("burger_missed_drink", setOf(WESTERN, MIXED_PLATE), "Burger, fries and a cola the photo cut off", MockScenarios.meals.getValue("burger_fries"), TruthSource.KNOWN_RECIPE,
            t("dish:beef_burger", 260.0), t("dish:french_fries", 115.0), t("usda:14400", 355.0)),
        case("bolognese", setOf(WESTERN), "Plate of spaghetti bolognese", meal(item("Spaghetti bolognese", 350.0, 420.0, 500.0)), TruthSource.KNOWN_RECIPE, t("dish:spaghetti_bolognese", 450.0)),
        case("greek_salad_hidden", setOf(PARTIALLY_OBSCURED, WESTERN), "Greek salad partly under feta and bread", meal(item("Greek salad", 200.0, 260.0, 320.0, hidden = true)), TruthSource.KNOWN_RECIPE, t("dish:greek_salad", 300.0)),
        case("jollof_plantain", setOf(AFRICAN), "Jollof rice with fried plantain",
            meal(item("Jollof rice", 250.0, 300.0, 350.0), item("Fried plantain", 70.0, 90.0, 110.0, "dodo" to Likelihood.HIGH)),
            TruthSource.KNOWN_RECIPE, t("dish:jollof_rice", 320.0), t("dish:fried_plantain", 100.0)),
        case("injera_misir", setOf(AFRICAN), "Injera with misir wat",
            meal(item("Injera", 100.0, 120.0, 140.0), item("Misir wat", 150.0, 200.0, 250.0, "misir wat" to Likelihood.HIGH, "masoor dal" to Likelihood.LOW)),
            TruthSource.KNOWN_RECIPE, t("dish:injera", 120.0), t("dish:misir_wat", 200.0, "dish:masoor_dal")),
        case("tacos", setOf(LATIN_AMERICAN), "Three carne asada tacos", meal(item("Tacos", 200.0, 250.0, 300.0, "tacos de carne asada" to Likelihood.HIGH)), TruthSource.KNOWN_RECIPE, t("dish:tacos_carne_asada", 250.0)),
        case("yogurt_cup", setOf(PACKAGED, SINGLE_INGREDIENT), "Single-serve Greek yogurt cup (170 g label)", meal(item("Greek yogurt", 150.0, 170.0, 190.0)), TruthSource.LABEL, t("usda:01256", 170.0)),
        case("big_pasta", setOf(DIFFICULT_PORTION, WESTERN), "Very large bowl of pasta in tomato sauce", meal(item("Penne arrabbiata", 300.0, 400.0, 500.0, "penne arrabbiata" to Likelihood.HIGH)), TruthSource.KNOWN_RECIPE, t("dish:penne_arrabbiata", 600.0)),
    )
}
