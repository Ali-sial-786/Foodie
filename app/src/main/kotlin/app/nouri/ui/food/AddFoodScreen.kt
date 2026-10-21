package app.nouri.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.barcode.BrandedProduct
import app.nouri.core.barcode.OpenFoodFactsMapper
import app.nouri.core.diary.MealType
import app.nouri.core.parse.ResolvedTextItem
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ConfidenceBadge
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.scan.ScanMode
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodScreen(nav: AppNavigator, route: Route.AddFood) {
    val container = LocalAppContainer.current
    val vm = viewModel {
        AddFoodViewModel(
            container, LocalDate.parse(route.date),
            runCatching { MealType.valueOf(route.meal) }.getOrDefault(MealType.SNACK),
            runCatching { AddMode.valueOf(route.mode) }.getOrDefault(AddMode.SEARCH),
        )
    }
    val mode by vm.mode.collectAsStateWithLifecycle()
    val meal by vm.meal.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val duplicate by vm.duplicate.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) nav.back() }

    Scaffold(topBar = { NouriTopBar("Add to ${meal.label}", onBack = nav::back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            MealChips(meal, vm::setMeal)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                AddMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = mode == m, onClick = { vm.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, AddMode.entries.size)) { Text(m.label) }
                }
            }
            when (mode) {
                AddMode.SEARCH -> SearchPane(vm, nav, route)
                AddMode.DESCRIBE -> DescribePane(vm) { q -> vm.setQuery(q); vm.setMode(AddMode.SEARCH) }
                AddMode.QUICK -> QuickPane(vm)
            }
        }
    }

    duplicate?.let { d ->
        AlertDialog(
            onDismissRequest = vm::dismissDuplicate,
            title = { Text("Log this again?") },
            text = { Text("You logged ${d.existing.name} to ${d.existing.mealType.label.lowercase()} a moment ago. Add it a second time?") },
            confirmButton = { TextButton(onClick = vm::confirmDuplicate) { Text("Log again") } },
            dismissButton = { TextButton(onClick = vm::dismissDuplicate) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MealChips(selected: MealType, onSelect: (MealType) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MealType.entries.forEach { m -> FilterChip(selected = m == selected, onClick = { onSelect(m) }, label = { Text(m.label) }) }
    }
}

@Composable
private fun SearchPane(vm: AddFoodViewModel, nav: AppNavigator, route: Route.AddFood) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val meal by vm.meal.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    OutlinedTextField(
        value = query,
        onValueChange = vm::setQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text("Search foods, dishes, your recipes") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear search") } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { nav.to(Route.Camera(ScanMode.LABEL.name)) }, label = { Text("Barcode or label") }, leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(18.dp)) })
        AssistChip(onClick = { nav.to(Route.Camera(ScanMode.MEAL.name)) }, label = { Text("Meal photo") }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)) })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (query.trim().length < 2) {
            if (recent.isNotEmpty()) item { Text("Recent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            items(recent, key = { "r" + it.id }) { e ->
                ListItem(
                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(e.quantity?.toString(), Fmt.kcalUnit(e.nutrients.energyKcal, e.snapshot.precision)).joinToString(" · ")) },
                    leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                    trailingContent = { TextButton(onClick = { vm.relog(e) }) { Text("Add") } },
                    modifier = Modifier.clickable {
                        val id = e.foodRefId
                        if (id != null) nav.to(Route.FoodDetail(id, route.date, meal.name, e.name)) else vm.relog(e)
                    },
                )
            }
            if (recent.isEmpty()) item {
                Text(
                    "Search the built-in database of dishes and ingredients, or scan a barcode. Foods you log appear here for one-tap logging.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            if (results.isEmpty()) item {
                Column(Modifier.padding(16.dp)) {
                    Text("No matches for “${query.trim()}”.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.updateQuick { it.copy(name = query.trim()) }; vm.setMode(AddMode.QUICK) }) { Text("Quick add calories instead") }
                }
            }
            items(results, key = { it.food.id }) { r ->
                ListItem(
                    headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${r.subtitle} · ${Fmt.kcal(r.food.per100g.energyKcal)} kcal per 100 g") },
                    modifier = Modifier.clickable { nav.to(Route.FoodDetail(r.food.id, route.date, meal.name, r.title)) },
                )
                HorizontalDivider()
            }
            item(key = "online") {
                OnlineProducts(
                    online = online.takeIf { it.forDescribedIndex == null && it.terms.equals(query.trim(), ignoreCase = true) },
                    query = query.trim(),
                    onSearch = { vm.searchOnline(query) },
                    onPick = { product -> scope.launch { vm.keepProduct(product)?.let { id -> nav.to(Route.FoodDetail(id, route.date, meal.name, product.food?.name)) } } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DescribePane(vm: AddFoodViewModel, onSearchInstead: (String) -> Unit) {
    val d by vm.describe.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    online.forDescribedIndex?.let { index ->
        ModalBottomSheet(onDismissRequest = vm::clearOnline) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Products for “${online.terms}”", style = MaterialTheme.typography.titleMedium)
                Text("From Open Food Facts. Only these search words were sent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OnlineProducts(online = online, query = online.terms, onSearch = { vm.searchOnline(online.terms, index) }, onPick = { vm.useProductForDescribed(index, it) })
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = d.text,
                onValueChange = vm::setDescribeText,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("e.g. 2 rotis, a bowl of chicken karahi and a glass of lassi") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.interpretOffline() }),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::interpretOffline, enabled = d.text.isNotBlank() && !d.working) { Text("Interpret") }
                if (d.aiAvailable) {
                    OutlinedButton(onClick = vm::interpretWithAi, enabled = d.text.isNotBlank() && !d.working) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Interpret with AI")
                    }
                }
            }
            Text(
                if (d.aiAvailable) "“Interpret” works on your phone. “Interpret with AI” sends only this text to ${d.aiProviderLabel}; calories are always calculated on your phone."
                else "Interpreted on your phone — nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (d.working) item { LoadingBox(label = "Working out what you ate…") }
        d.error?.let { item { ErrorBanner(it) } }
        d.items.forEachIndexed { i, item ->
            item(key = "d$i") {
                DescribedRow(
                    item, included = i !in d.excluded, onToggle = { vm.toggleDescribed(i) }, onSearch = { onSearchInstead(item.mention.food) },
                    onFindProduct = item.mention.brand?.takeIf { item.match?.food?.id?.startsWith("off:") != true }?.let { brand ->
                        { vm.searchOnline("$brand ${item.mention.food}", forDescribedIndex = i) }
                    },
                )
            }
        }
        if (d.included.isNotEmpty()) {
            item {
                Button(onClick = { vm.logDescribed() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Log ${d.included.size} ${if (d.included.size == 1) "item" else "items"} · ${Fmt.kcalUnit(d.total.energyKcal)}")
                }
            }
        }
    }
}

@Composable
private fun DescribedRow(item: ResolvedTextItem, included: Boolean, onToggle: () -> Unit, onSearch: () -> Unit, onFindProduct: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = included && item.isResolved, onCheckedChange = { onToggle() }, enabled = item.isResolved)
        Column(Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyLarge)
            if (item.isResolved) {
                Text(
                    listOfNotNull(item.quantity?.toString(), item.grams?.let { Fmt.gramsShort(it) }, item.portionSource.label).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ConfidenceBadge(item.confidence, Modifier.padding(top = 2.dp))
                if (onFindProduct != null) {
                    TextButton(onClick = onFindProduct) { Text("Find ${item.mention.brand?.replaceFirstChar { it.uppercase() }} ${item.mention.food} product") }
                }
            } else {
                Text("Not found in the database", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onSearch) { Text("Search for “${item.mention.food}”") }
            }
        }
        if (item.isResolved) Text("${Fmt.kcal(item.nutrients.energyKcal)} kcal", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun QuickPane(vm: AddFoodViewModel) {
    val q by vm.quick.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = q.name, onValueChange = { v -> vm.updateQuick { it.copy(name = v) } }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        NumberField(q.kcal, { v -> vm.updateQuick { it.copy(kcal = v) } }, "Calories", Modifier.fillMaxWidth(), suffix = "kcal")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(q.protein, { v -> vm.updateQuick { it.copy(protein = v) } }, "Protein", Modifier.weight(1f), suffix = "g")
            NumberField(q.carbs, { v -> vm.updateQuick { it.copy(carbs = v) } }, "Carbs", Modifier.weight(1f), suffix = "g")
            NumberField(q.fat, { v -> vm.updateQuick { it.copy(fat = v) } }, "Fat", Modifier.weight(1f), suffix = "g")
        }
        Text("Use this when you know the numbers, for example from a menu or a label.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = vm::logQuick, enabled = q.kcal.replace(',', '.').toDoubleOrNull() != null, modifier = Modifier.fillMaxWidth()) { Text("Log") }
    }
}

/** Open Food Facts name-search results; only the search words are sent, and only when the user asks. */
@Composable
private fun OnlineProducts(
    online: OnlineSearch?,
    query: String,
    onSearch: () -> Unit,
    onPick: (BrandedProduct) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        when {
            online == null || (!online.searched && !online.loading) -> if (query.length >= 2) {
                OutlinedButton(onClick = onSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Search packaged products for “$query”")
                }
                Text("Searches Open Food Facts; only these words are sent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            online.loading -> LoadingBox(label = "Searching Open Food Facts…")
            online.error != null -> ErrorBanner(online.error, onRetry = onSearch)
            online.products.isEmpty() -> Text("No products found for “${online.terms}”. Try the brand and product name, or scan the barcode.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text("Packaged products", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                online.products.forEach { p ->
                    val food = p.food
                    ListItem(
                        headlineContent = { Text(listOfNotNull(p.brand, p.name).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (food != null) listOfNotNull("${Fmt.kcal(food.per100g.energyKcal)} kcal per 100 g", p.servingSizeText?.let { "serving $it" }).joinToString(" · ")
                                else "Nutrition incomplete — scan the label instead",
                            )
                        },
                        modifier = if (food != null) Modifier.clickable { onPick(p) } else Modifier,
                    )
                    HorizontalDivider()
                }
                Text(OpenFoodFactsMapper.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
