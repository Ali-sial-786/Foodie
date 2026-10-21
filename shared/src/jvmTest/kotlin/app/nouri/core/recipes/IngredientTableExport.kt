package app.nouri.core.recipes

import app.nouri.core.nutrition.NutritionEngine
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test

/**
 * Writes what one unit of every ingredient weighs and contains, so
 * `scripts/build_recipe_library.py --table` can estimate calories without Gradle
 * (`NOURI_EXPORT_INGREDIENTS=/path/table.json ./gradlew :shared:jvmTest --tests "*IngredientTableExport*"`).
 */
class IngredientTableExport {
    @Test
    fun export() {
        val out = System.getenv("NOURI_EXPORT_INGREDIENTS") ?: return
        val engine = NutritionEngine()
        val table = buildJsonObject {
            for (ing in IngredientCatalog.all) {
                put(ing.id, buildJsonObject {
                    put("name", ing.name)
                    put("category", ing.category.name)
                    put("halal", ing.halal.name)
                    for (cooked in listOf(false, true)) {
                        put(if (cooked) "cooked" else "raw", JsonObject(QuantityUnit.entries.associate { unit ->
                            val r = engine.calculateLine(RecipeIngredient(ing.name, ing.id, Quantity(1.0, unit), measuredCooked = cooked))
                            unit.name to JsonArray(listOf(
                                JsonPrimitive(r.status.name), JsonPrimitive(r.gramsEaten ?: 0.0), JsonPrimitive(r.nutrients.energyKcal),
                                JsonPrimitive(r.nutrients.proteinG), JsonPrimitive(r.nutrients.carbsG), JsonPrimitive(r.nutrients.fatG),
                            ))
                        }))
                    }
                })
            }
        }
        File(out).apply { parentFile?.mkdirs() }.writeText(table.toString())
    }
}
