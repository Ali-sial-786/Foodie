package app.nouri.core.pantry

import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.StorageLocation
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeAnalyzer
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.UnitKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

@Serializable
enum class PantrySource { MANUAL, PHOTO_SCAN, BARCODE, GROCERY_LIST, LEFTOVER }

@Serializable
data class PantryItem(
    val id: String,
    val name: String,
    val ingredientId: String? = null,
    val quantity: Quantity? = null,
    val location: StorageLocation = StorageLocation.PANTRY,
    val addedOn: LocalDate,
    /** Approximate; null when unknown (no reminder is scheduled). */
    val expiresOn: LocalDate? = null,
    val opened: Boolean = false,
    val source: PantrySource = PantrySource.MANUAL,
    val barcode: String? = null,
    val note: String? = null,
)

@Serializable
data class GroceryItem(
    val id: String,
    val name: String,
    val ingredientId: String? = null,
    val quantity: Quantity? = null,
    val category: String = "Other",
    val checked: Boolean = false,
    /** Recipes that need this item, for context in the list. */
    val forRecipeIds: List<String> = emptyList(),
    val addedAtEpochMs: Long = 0,
)

data class DeductionProposal(
    val pantryItemId: String,
    val name: String,
    val current: Quantity?,
    val used: Quantity,
    /** Null when units can't be compared (e.g. "1 bag" vs "200 g"); the user decides. */
    val remaining: Quantity?,
    val removeItem: Boolean,
)

object PantryLogic {
    /** A conservative default expiry from the ingredient's typical shelf life, if known. */
    fun defaultExpiry(ingredientId: String?, location: StorageLocation, addedOn: LocalDate): LocalDate? {
        val ing = ingredientId?.let(IngredientCatalog::get) ?: return null
        val days = ing.shelfLifeDays ?: return null
        val adjusted = when {
            location == StorageLocation.FREEZER && ing.storage != StorageLocation.FREEZER -> days * 6 + 30
            location == StorageLocation.PANTRY && ing.storage == StorageLocation.FRIDGE -> maxOf(1, days / 3)
            else -> days
        }
        return addedOn.plus(adjusted, DateTimeUnit.DAY)
    }

    fun daysLeft(item: PantryItem, today: LocalDate): Int? = item.expiresOn?.let { today.daysUntil(it) }

    fun expiringSoon(items: List<PantryItem>, today: LocalDate, withinDays: Int = 3): List<PantryItem> =
        items.filter { item -> daysLeft(item, today)?.let { it <= withinDays } == true }.sortedBy { it.expiresOn }

    fun availableIngredientIds(items: List<PantryItem>): Set<String> = items.mapNotNull { it.ingredientId }.toSet()

    /**
     * After cooking, proposes what to take out of the pantry. Nothing is changed
     * until the user accepts; uncertain unit matches are left for the user.
     */
    fun proposeDeduction(recipe: Recipe, pantry: List<PantryItem>): List<DeductionProposal> = recipe.ingredients
        .filter { !it.optional && it.quantity.amount > 0 }
        .mapNotNull { line ->
            val canonical = RecipeAnalyzer.canonical(line) ?: return@mapNotNull null
            if (canonical.pantryStaple) return@mapNotNull null
            val item = pantry.firstOrNull { it.ingredientId == canonical.id } ?: return@mapNotNull null
            val current = item.quantity
            val used = line.quantity
            val remaining = current?.let { subtract(it, used) }
            DeductionProposal(
                pantryItemId = item.id,
                name = item.name,
                current = current,
                used = used,
                remaining = remaining,
                removeItem = remaining != null && remaining.amount <= 0.0,
            )
        }

    fun applyDeductions(pantry: List<PantryItem>, accepted: List<DeductionProposal>): List<PantryItem> {
        val byId = accepted.associateBy { it.pantryItemId }
        return pantry.mapNotNull { item ->
            val p = byId[item.id] ?: return@mapNotNull item
            when {
                p.removeItem -> null
                p.remaining != null -> item.copy(quantity = p.remaining, opened = true)
                else -> item.copy(opened = true)
            }
        }
    }

    private fun subtract(have: Quantity, used: Quantity): Quantity? {
        if (have.unit == used.unit) return have.copy(amount = (have.amount - used.amount).coerceAtLeast(0.0))
        val a = have.unit
        val b = used.unit
        if (a.kind == UnitKind.MASS && b.kind == UnitKind.MASS) {
            val grams = have.amount * a.massGrams!! - used.amount * b.massGrams!!
            return Quantity((grams / a.massGrams).coerceAtLeast(0.0), a)
        }
        if (a.kind == UnitKind.VOLUME && b.kind == UnitKind.VOLUME) {
            val sys = app.nouri.core.units.MeasurementSystem.METRIC
            val ml = have.amount * a.volumeMl(sys)!! - used.amount * b.volumeMl(sys)!!
            return Quantity((ml / a.volumeMl(sys)!!).coerceAtLeast(0.0), a)
        }
        if (a == QuantityUnit.PIECE && b == QuantityUnit.PIECE) return Quantity((have.amount - used.amount).coerceAtLeast(0.0), a)
        return null
    }
}
