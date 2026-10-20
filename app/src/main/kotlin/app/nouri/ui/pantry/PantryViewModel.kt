package app.nouri.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.StorageLocation
import app.nouri.core.pantry.GroceryItem
import app.nouri.core.pantry.PantryItem
import app.nouri.core.pantry.PantryLogic
import app.nouri.core.pantry.PantrySource
import app.nouri.core.units.Quantity
import app.nouri.core.util.SystemTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class PantryState(val today: LocalDate, val byLocation: Map<StorageLocation, List<PantryItem>>, val total: Int)

/** What the add dialog suggests for a typed name. */
data class PantrySuggestion(val ingredientId: String?, val matchedName: String?, val location: StorageLocation, val expiry: LocalDate?)

class PantryViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<PantryState?> = c.pantry.pantry.map { items ->
        val today = SystemTime.today()
        val sorted = items.sortedWith(compareBy<PantryItem, LocalDate?>(nullsLast<LocalDate>()) { it.expiresOn }.thenBy { it.name.lowercase() })
        PantryState(today, StorageLocation.entries.associateWith { loc -> sorted.filter { it.location == loc } }.filterValues { it.isNotEmpty() }, items.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Suggests a canonical ingredient, its usual storage and a conservative expiry; the user can change all of them. */
    fun suggest(name: String, location: StorageLocation?): PantrySuggestion {
        val match = c.core().normalizer.resolve(name)?.takeIf { it.isConfident }?.ingredient
        val loc = location ?: match?.storage ?: StorageLocation.PANTRY
        return PantrySuggestion(match?.id, match?.name, loc, PantryLogic.defaultExpiry(match?.id, loc, SystemTime.today()))
    }

    fun save(existing: PantryItem?, name: String, quantity: Quantity?, location: StorageLocation, expiresOn: LocalDate?, opened: Boolean) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        // Keep a scanned item's link if the user only changed the amount or date.
        val keptId = existing?.takeIf { it.name.equals(trimmed, ignoreCase = true) }?.ingredientId
        val ingredientId = keptId ?: c.core().normalizer.resolve(trimmed)?.takeIf { it.isConfident }?.ingredient?.id
        val item = (existing ?: PantryItem(id = c.newId(), name = trimmed, addedOn = SystemTime.today(), source = PantrySource.MANUAL))
            .copy(name = trimmed, ingredientId = ingredientId, quantity = quantity, location = location, expiresOn = expiresOn, opened = opened)
        c.pantry.savePantry(listOf(item))
        c.messages.show(if (existing == null) "Added $trimmed" else "Saved $trimmed")
    }

    fun delete(item: PantryItem) = viewModelScope.launch {
        c.pantry.deletePantry(listOf(item.id))
        c.messages.show("Removed ${item.name}", "Undo") { c.pantry.savePantry(listOf(item)) }
    }

    /** Adds the item to the grocery list (e.g. it ran out), without removing it from the pantry. */
    fun addToGrocery(item: PantryItem) = viewModelScope.launch {
        val existing = c.pantry.groceryNow()
        if (existing.any { !it.checked && (it.name.equals(item.name, true) || (item.ingredientId != null && it.ingredientId == item.ingredientId)) }) {
            c.messages.show("${item.name} is already on your grocery list")
            return@launch
        }
        val grocery = GroceryItem(
            id = c.newId(), name = item.name, ingredientId = item.ingredientId,
            category = item.ingredientId?.let { IngredientCatalog[it]?.category?.label } ?: "Other",
            addedAtEpochMs = SystemTime.nowEpochMs(),
        )
        c.pantry.saveGrocery(listOf(grocery))
        c.messages.show("Added ${item.name} to your grocery list", "Undo") { c.pantry.deleteGrocery(listOf(grocery.id)) }
    }
}
