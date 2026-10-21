package app.nouri.core.recipes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Versions of a dish must point at its main recipe, which itself stands alone. */
class RecipeLibraryFamiliesTest {
    @Test
    fun familiesNameAnExistingMainRecipe() {
        val dir = File(System.getProperty("nouri.repoRoot") ?: "..", "data/recipes")
        val library = dir.listFiles { f -> f.extension == "json" }.orEmpty().flatMap { RecipeLibrary.decode(it.readText()) }
        val byId = (RecipeCatalog.all + library).associateBy { it.id }
        val problems = library.mapNotNull { r ->
            val family = r.family ?: return@mapNotNull null
            val main = byId[family]
            when {
                family == r.id -> "${r.id}: names itself as its family"
                main == null -> "${r.id}: family $family is not a recipe"
                main.family != null || main.variantOf != null -> "${r.id}: family $family is itself a version of another dish"
                r.variantOf != null -> "${r.id}: variations join their base's family automatically"
                else -> null
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
