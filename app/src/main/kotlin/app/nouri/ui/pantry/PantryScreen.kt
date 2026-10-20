package app.nouri.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.ontology.StorageLocation
import app.nouri.core.pantry.PantryItem
import app.nouri.core.pantry.PantryLogic
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.diary.DiaryDatePicker
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.recipes.RecipeLineParser
import app.nouri.ui.recipes.RecipeText
import app.nouri.ui.theme.NouriTheme
import kotlinx.datetime.LocalDate

internal fun StorageLocation.label(): String = when (this) {
    StorageLocation.PANTRY -> "Cupboard"
    StorageLocation.FRIDGE -> "Fridge"
    StorageLocation.FREEZER -> "Freezer"
}

@Composable
fun PantryScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { PantryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PantryItem?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            NouriTopBar("Pantry", onBack = nav::back) {
                IconButton(onClick = { nav.to(Route.Grocery) }) { Icon(Icons.Outlined.ShoppingBasket, contentDescription = "Grocery list") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, contentDescription = null) }, text = { Text("Add item") })
        },
    ) { padding ->
        val s = state
        when {
            s == null -> LoadingBox(Modifier.padding(padding))
            s.total == 0 -> EmptyState(
                Icons.Outlined.Kitchen, "Your pantry is empty",
                "Add what you have, or scan your ingredients from the camera. Nouri only changes your pantry when you say so.",
                Modifier.padding(padding),
            ) { OutlinedButton(onClick = { adding = true }) { Text("Add an item") } }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.total} items", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { nav.toTab(Route.Discover) }) { Text("What can I cook?") }
                    }
                }
                s.byLocation.forEach { (location, items) ->
                    item(key = location.name) {
                        Text(
                            location.label(), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                        )
                    }
                    items(items, key = { it.id }) { item ->
                        PantryRow(item, s.today, onEdit = { editing = item }, onDelete = { vm.delete(item) }, onGrocery = { vm.addToGrocery(item) })
                    }
                }
            }
        }
    }

    if (adding) PantryItemDialog(null, vm, onDismiss = { adding = false })
    editing?.let { item -> PantryItemDialog(item, vm, onDismiss = { editing = null }) }
}

@Composable
private fun PantryRow(item: PantryItem, today: LocalDate, onEdit: () -> Unit, onDelete: () -> Unit, onGrocery: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val days = PantryLogic.daysLeft(item, today)
    val detail = listOfNotNull(item.quantity?.let { RecipeText.quantity(it) }?.takeIf { it.isNotEmpty() }, if (item.opened) "opened" else null).joinToString(" · ")
    ListItem(
        headlineContent = { Text(item.name) },
        supportingContent = detail.takeIf { it.isNotEmpty() }?.let { d -> { Text(d) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (days != null) {
                    Text(
                        expiryText(days),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            days < 0 -> NouriTheme.extra.lowConfidence
                            days <= 2 -> NouriTheme.extra.moderateConfidence
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${item.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Add to grocery list") }, onClick = { menu = false; onGrocery() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Expiry dates are approximate, so the wording is too. */
internal fun expiryText(days: Int): String = when {
    days < -1 -> "Expired ${-days} days ago"
    days == -1 -> "Expired yesterday"
    days == 0 -> "Use today"
    days == 1 -> "Use by tomorrow"
    days <= 14 -> "$days days left"
    else -> "${days / 7} weeks left"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PantryItemDialog(existing: PantryItem?, vm: PantryViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var quantity by rememberSaveable { mutableStateOf(existing?.quantity?.let { RecipeText.quantity(it) }.orEmpty()) }
    var location by remember { mutableStateOf(existing?.location) }
    var expiry by remember { mutableStateOf(existing?.expiresOn) }
    var expiryTouched by remember { mutableStateOf(existing != null) }
    var opened by rememberSaveable { mutableStateOf(existing?.opened ?: false) }
    var picking by remember { mutableStateOf(false) }

    val suggestion = remember(name, location) { vm.suggest(name, location) }
    val effectiveLocation = location ?: suggestion.location
    val effectiveExpiry = if (expiryTouched) expiry else suggestion.expiry
    val parsedQuantity = RecipeLineParser.parseQuantity(quantity)
    val quantityError = quantity.isNotBlank() && parsedQuantity == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add to pantry" else "Edit ${existing.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Item") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = suggestion.matchedName?.let { m -> { Text("Recognised as $m") } },
                )
                OutlinedTextField(
                    quantity, { quantity = it }, label = { Text("Amount (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = quantityError,
                    supportingText = { Text(if (quantityError) "Try “500 g”, “2” or “1 cup”" else "e.g. 500 g, 6, 1 bag") },
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    StorageLocation.entries.forEachIndexed { i, loc ->
                        SegmentedButton(
                            selected = effectiveLocation == loc,
                            onClick = { location = loc },
                            shape = SegmentedButtonDefaults.itemShape(i, StorageLocation.entries.size),
                        ) { Text(loc.label()) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            effectiveExpiry?.let { Fmt.shortDate(it) + if (!expiryTouched) " (typical, approximate)" else "" } ?: "Not set",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TextButton(onClick = { picking = true }) { Text("Change") }
                    if (effectiveExpiry != null) TextButton(onClick = { expiry = null; expiryTouched = true }) { Text("Clear") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = opened, onCheckedChange = { opened = it })
                    Text("Opened")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.save(existing, name, parsedQuantity, effectiveLocation, effectiveExpiry, opened)
                    onDismiss()
                },
                enabled = name.isNotBlank() && !quantityError,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (picking) {
        DiaryDatePicker(effectiveExpiry ?: app.nouri.core.util.SystemTime.today(), onDismiss = { picking = false }) {
            expiry = it
            expiryTouched = true
            picking = false
        }
    }
}
