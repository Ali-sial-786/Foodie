package app.nouri.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals

class RecipeFamiliesTest {
    private fun recipe(id: String, family: String? = null, variantOf: String? = null) = Recipe(
        id = id, name = id, cuisineId = "pakistani", servings = 4, ingredients = emptyList(), steps = emptyList(),
        prepMinutes = 10, cookMinutes = 20, origin = if (variantOf != null) RecipeOrigin.EDITORIAL_VARIATION else RecipeOrigin.EDITORIAL_TRADITIONAL,
        variantOf = variantOf, family = family,
    )

    @Test
    fun regionalVersionsVariationsAndCopiesShareTheMainRecipe() {
        val main = recipe("chicken_biryani")
        val sindhi = recipe("sindhi_biryani", family = "chicken_biryani")
        val lighter = recipe("sindhi_biryani_lighter", variantOf = "sindhi_biryani")
        val copy = recipe("user:1", variantOf = "sindhi_biryani_lighter")
        val other = recipe("chicken_karahi")
        val groups = RecipeFamilies.group(listOf(sindhi, lighter, other, copy, main))
        assertEquals(listOf("chicken_biryani", "sindhi_biryani", "sindhi_biryani_lighter", "user:1"), groups["chicken_biryani"]!!.map { it.id })
        assertEquals(listOf("chicken_karahi"), groups["chicken_karahi"]!!.map { it.id })
    }

    @Test
    fun aCopyWhoseOriginalIsGoneStillGroupsWithItsSiblings() {
        val a = recipe("user:a", variantOf = "removed")
        val b = recipe("user:b", variantOf = "removed")
        assertEquals(setOf("removed"), RecipeFamilies.group(listOf(a, b)).keys)
    }
}
