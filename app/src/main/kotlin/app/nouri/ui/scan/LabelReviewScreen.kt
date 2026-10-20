package app.nouri.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.diary.MealType
import app.nouri.core.util.SystemTime
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ConfidenceBadge
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.InfoBanner
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.SimulatedBanner
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelReviewScreen(nav: AppNavigator, route: Route.LabelReview) {
    val container = LocalAppContainer.current
    val vm = viewModel { LabelReviewViewModel(container, route.captureId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val today = SystemTime.diaryToday().toString()
    val meal = MealType.forMinuteOfDay(SystemTime.minuteOfDay()).name

    LaunchedEffect(s.savedFoodId) { s.savedFoodId?.let { nav.replace(Route.FoodDetail(it, today, meal, s.form.name)) } }

    Scaffold(topBar = { NouriTopBar(if (s.barcode != null && s.photo == null) "Product" else "Nutrition label", onBack = nav::back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.barcode?.let { Text("Barcode $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            s.photo?.let { PhotoThumb(it, "Label photo", Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.medium), ContentScale.Fit, sampleSize = 1) }
            when (s.phase) {
                LabelPhase.LOOKING_UP -> LoadingBox(label = "Looking up the product in Open Food Facts…")
                LabelPhase.READING -> LoadingBox(label = if (s.readWithAi) "Reading the label with ${s.aiLabel}…" else "Reading the label on your phone…")
                LabelPhase.FAILED -> ErrorBanner(s.message ?: "Something went wrong")
                LabelPhase.PRODUCT -> s.readyFoodId?.let { id ->
                    NouriCard {
                        Text(s.product?.name ?: s.form.name, style = MaterialTheme.typography.titleLarge)
                        s.product?.brand?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        s.product?.packageQuantityText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            if (s.fromCache) "From your saved foods" else s.product?.attribution ?: "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!s.product?.missingNutrients.isNullOrEmpty()) {
                            Text("Some values are missing in the product database: ${s.product!!.missingNutrients.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { nav.replace(Route.FoodDetail(id, today, meal, s.product?.name)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Choose amount and log") }
                        TextButton(onClick = { nav.replace(Route.Camera(ScanMode.LABEL.name, barcode = s.barcode)) }) { Text("Values look wrong? Scan the label instead") }
                    }
                }
                LabelPhase.NEEDS_PHOTO -> {
                    s.message?.let { InfoBanner(it, Icons.Outlined.Shield, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) }
                    Button(onClick = { nav.replace(Route.Camera(ScanMode.LABEL.name, barcode = s.barcode)) }, modifier = Modifier.fillMaxWidth()) { Text("Photograph the nutrition table") }
                    OutlinedButton(onClick = vm::enterManually, modifier = Modifier.fillMaxWidth()) { Text("Enter values myself") }
                    if (s.retryable) TextButton(onClick = { s.barcode?.let { vm.lookUp(it) } }) { Text("Try the lookup again") }
                }
                LabelPhase.FORM -> LabelFormContent(s, vm)
            }
        }
    }

    if (s.consentNeeded) {
        AlertDialog(
            onDismissRequest = vm::declineConsent,
            title = { Text("Send this label photo?") },
            text = { Text("A resized copy of the label photo, without location data, will be sent to ${s.aiLabel} to transcribe the nutrition table. You'll check every value before saving.") },
            confirmButton = { TextButton(onClick = vm::grantConsent) { Text("Agree and read") } },
            dismissButton = { TextButton(onClick = vm::declineConsent) { Text("Not now") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelFormContent(s: LabelReviewState, vm: LabelReviewViewModel) {
    val f = s.form
    if (s.simulated) SimulatedBanner(text = "Demo mode — this label text is a sample, not read from your photo.")
    s.message?.let { ErrorBanner(it) }
    s.parse?.let { p ->
        NouriCard {
            Text("Read from the label", style = MaterialTheme.typography.titleSmall)
            ConfidenceBadge(p.confidence, Modifier.padding(vertical = 4.dp))
            if (p.missingFields.isNotEmpty()) Text("Couldn't read: ${p.missingFields.joinToString()}", style = MaterialTheme.typography.bodySmall)
            p.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text("Please check each value against the package.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (s.photo != null && s.aiAvailable && !s.readWithAi) {
        OutlinedButton(onClick = vm::readWithAi) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Text("  Read with ${s.aiLabel}")
        }
    }
    OutlinedTextField(f.name, { v -> vm.updateForm { it.copy(name = v) } }, label = { Text("Product name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = f.per100g, onClick = { vm.updateForm { it.copy(per100g = true) } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Per 100 g / ml") }
        SegmentedButton(selected = !f.per100g, onClick = { vm.updateForm { it.copy(per100g = false) } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Per serving") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(f.servingGrams, { v -> vm.updateForm { it.copy(servingGrams = v) } }, "Serving size", Modifier.weight(1f), suffix = "g", isError = !f.per100g && f.servingGrams.isBlank())
        OutlinedTextField(f.servingText, { v -> vm.updateForm { it.copy(servingText = v) } }, label = { Text("As printed") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    NumberField(f.energy, { v -> vm.updateForm { it.copy(energy = v) } }, "Energy", Modifier.fillMaxWidth(), suffix = "kcal", isError = f.energy.isBlank())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(f.protein, { v -> vm.updateForm { it.copy(protein = v) } }, "Protein", Modifier.weight(1f), suffix = "g")
        NumberField(f.carbs, { v -> vm.updateForm { it.copy(carbs = v) } }, "Carbs (total)", Modifier.weight(1f), suffix = "g")
        NumberField(f.fat, { v -> vm.updateForm { it.copy(fat = v) } }, "Fat", Modifier.weight(1f), suffix = "g")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(f.fibre, { v -> vm.updateForm { it.copy(fibre = v) } }, "Fibre", Modifier.weight(1f), suffix = "g")
        NumberField(f.sugar, { v -> vm.updateForm { it.copy(sugar = v) } }, "Sugars", Modifier.weight(1f), suffix = "g")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(f.satFat, { v -> vm.updateForm { it.copy(satFat = v) } }, "Saturated fat", Modifier.weight(1f), suffix = "g")
        NumberField(f.sodiumMg, { v -> vm.updateForm { it.copy(sodiumMg = v) } }, "Sodium", Modifier.weight(1f), suffix = "mg")
    }
    Text(
        "Carbohydrate is total carbohydrate. On EU and UK labels, add fibre back if it's listed separately — the label reader does this for you.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = vm::save, enabled = f.canSave, modifier = Modifier.fillMaxWidth()) { Text("Save food and choose amount") }
}
