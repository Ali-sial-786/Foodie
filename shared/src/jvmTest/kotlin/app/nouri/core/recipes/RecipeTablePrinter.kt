package app.nouri.core.recipes

import app.nouri.core.nutrition.NutritionEngine
import kotlin.test.Test

/** Prints calculated per-serving nutrition for every built-in recipe (`NOURI_PRINT_RECIPES=1 ./gradlew :shared:jvmTest --tests "*RecipeTablePrinter*" -i`). */
class RecipeTablePrinter {
    @Test
    fun printTable() {
        if (System.getProperty("nouri.printRecipes") == null && System.getenv("NOURI_PRINT_RECIPES") == null) return
        val engine = NutritionEngine()
        for (r in RecipeCatalog.all) {
            val report = engine.calculateRecipe(r)
            val s = report.perServing
            println("%-26s %-14s x%-2d %5.0f kcal  P%5.1f C%5.1f F%5.1f | %4.0f g/serving  complete %.2f %s".format(
                r.id, r.cuisineId, r.servings, s.energyKcal, s.proteinG, s.carbsG, s.fatG,
                report.cookedGramsPerServing, report.completeness, report.confidence,
            ))
            report.warnings.filterNot { it.startsWith("Cooked weight estimated") }.forEach { println("    ! $it") }
        }
    }
}
