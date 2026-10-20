package app.nouri.ui.pantry

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.pantry.GroceryItem
import app.nouri.core.pantry.PantryItem
import app.nouri.core.pantry.PantryLogic
import app.nouri.core.pantry.PantrySource
import app.nouri.core.ontology.StorageLocation
import app.nouri.core.util.SystemTime
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.recipes.RecipeLineParser
import app.nouri.ui.recipes.RecipeText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroceryViewModel(private val c: AppContainer) : ViewModel() {
    val items: StateFlow<List<GroceryItem>?> = c.pantry.grocery.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "2 kg onions" → quantity + name; anything unreadable stays in the name. */
    fun add(text: String) = viewModelScope.launch {
        val parsed = RecipeLineParser.parse(text) ?: return@launch
        val ingredient = c.core().normalizer.resolve(parsed.name)?.takeIf { it.isConfident }?.ingredient
        val item = GroceryItem(
            id = c.newId(),
            name = parsed.name.replaceFirstChar { it.uppercase() },
            ingredientId = ingredient?.id,
            quantity = parsed.quantity.takeIf { it.amount > 0 },
            category = ingredient?.category?.label ?: "Other",
            addedAtEpochMs = SystemTime.nowEpochMs(),
        )
        c.pantry.saveGrocery(listOf(item))
    }

    fun toggle(item: GroceryItem) = viewModelScope.launch { c.pantry.saveGrocery(listOf(item.copy(checked = !item.checked))) }

    fun delete(item: GroceryItem) = viewModelScope.launch {
        c.pantry.deleteGrocery(listOf(item.id))
        c.messages.show("Removed ${item.name}", "Undo") { c.pantry.saveGrocery(listOf(item)) }
    }

    fun clearChecked() = viewModelScope.launch {
        val checked = c.pantry.groceryNow().filter { it.checked }
        if (checked.isEmpty()) return@launch
        c.pantry.deleteGrocery(checked.map { it.id })
        c.messages.show("Cleared ${checked.size} items", "Undo") { c.pantry.saveGrocery(checked) }
    }

    /** Explicit action: bought items go into the pantry with a typical (approximate) use-by date. */
    fun moveCheckedToPantry() = viewModelScope.launch {
        val checked = c.pantry.groceryNow().filter { it.checked }
        if (checked.isEmpty()) return@launch
        val today = SystemTime.today()
        val added = checked.map { g ->
            val location = g.ingredientId?.let { IngredientCatalog[it]?.storage } ?: StorageLocation.PANTRY
            PantryItem(
                id = c.newId(), name = g.name, ingredientId = g.ingredientId, quantity = g.quantity, location = location,
                addedOn = today, expiresOn = PantryLogic.defaultExpiry(g.ingredientId, location, today), source = PantrySource.GROCERY_LIST,
            )
        }
        c.pantry.savePantry(added)
        c.pantry.deleteGrocery(checked.map { it.id })
        c.messages.show("Moved ${added.size} items to your pantry", "Undo") {
            c.pantry.deletePantry(added.map { it.id })
            c.pantry.saveGrocery(checked)
        }
    }

    companion object {
        /** Plain-text list for sharing, grouped by aisle category; checked items are left out. */
        fun shareText(items: List<GroceryItem>): String = buildString {
            append("Grocery list\n")
            items.filter { !it.checked }.groupBy { it.category }.toSortedMap().forEach { (category, list) ->
                append('\n').append(category).append('\n')
                list.forEach { g ->
                    val q = g.quantity?.let { RecipeText.quantity(it) }?.takeIf { it.isNotEmpty() }
                    append("• ").append(g.name)
                    if (q != null) append(" — ").append(q)
                    append('\n')
                }
            }
        }.trimEnd()
    }
}

@Composable
fun GroceryScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { GroceryViewModel(container) }
    val items by vm.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = {
        NouriTopBar("Grocery list", onBack = nav::back) {
            IconButton(onClick = { nav.to(Route.Pantry) }) { Icon(Icons.Outlined.Kitchen, contentDescription = "Pantry") }
            val list = items.orEmpty()
            if (list.any { !it.checked }) {
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, GroceryViewModel.shareText(list))
                    }
                    context.startActivity(Intent.createChooser(send, "Share grocery list"))
                }) { Icon(Icons.Outlined.Share, contentDescription = "Share list") }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Add an item, e.g. 1 kg onions") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { vm.add(text); text = "" } }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.add(text); text = "" }, enabled = text.isNotBlank()) { Icon(Icons.Outlined.Add, contentDescription = "Add item") }
            }
            val list = items
            when {
                list == null -> LoadingBox()
                list.isEmpty() -> EmptyState(
                    Icons.Outlined.ShoppingBasket, "Nothing on your list",
                    "Add items here, or from a recipe's missing ingredients.",
                )
                else -> {
                    val checkedCount = list.count { it.checked }
                    if (checkedCount > 0) {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::moveCheckedToPantry) { Text("Move $checkedCount to pantry") }
                            TextButton(onClick = vm::clearChecked) { Text("Clear checked") }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        val groups = list.sortedBy { it.checked }.groupBy { if (it.checked) "In the basket" else it.category }
                        groups.forEach { (category, groupItems) ->
                            item(key = "h_$category") {
                                Text(category, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp).semantics { heading() })
                            }
                            items(groupItems, key = { it.id }) { g -> GroceryRow(g, onToggle = { vm.toggle(g) }, onDelete = { vm.delete(g) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroceryRow(item: GroceryItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    val q = item.quantity?.let { RecipeText.quantity(it) }?.takeIf { it.isNotEmpty() }
    val detail = listOfNotNull(q, item.forRecipeIds.takeIf { it.isNotEmpty() }?.let { "for ${it.size} recipe${if (it.size == 1) "" else "s"}" }).joinToString(" · ")
    ListItem(
        headlineContent = { Text(item.name, textDecoration = if (item.checked) TextDecoration.LineThrough else null) },
        supportingContent = detail.takeIf { it.isNotEmpty() }?.let { d -> { Text(d) } },
        leadingContent = { Checkbox(checked = item.checked, onCheckedChange = { onToggle() }) },
        trailingContent = { IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Remove ${item.name}") } },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    )
}
