package app.nouri.core.recipes

/**
 * Versions of the same dish shown as one item: the main recipe plus regional versions
 * that name it as their [Recipe.family], and variations or copies derived from any of them.
 */
object RecipeFamilies {
    /** The id of the main recipe [recipe] is a version of; its own id when it stands alone. */
    fun key(recipe: Recipe, byId: (String) -> Recipe?): String {
        var current = recipe
        repeat(MAX_DEPTH) {
            current.family?.let { return it }
            val baseId = current.variantOf ?: return current.id
            current = byId(baseId) ?: return baseId
        }
        return current.id
    }

    /** Family key → its versions, main recipe first, then in the given order. */
    fun group(recipes: List<Recipe>): Map<String, List<Recipe>> {
        val byId = recipes.associateBy { it.id }
        return recipes.groupBy { key(it, byId::get) }
            .mapValues { (k, versions) -> versions.sortedByDescending { it.id == k } }
    }

    private const val MAX_DEPTH = 8
}
