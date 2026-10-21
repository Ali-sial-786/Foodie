package app.nouri.ui.recipes

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.formatAmount
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.InfoBanner
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.scan.PhotoThumb
import kotlinx.coroutines.launch

/**
 * Save a recipe from pasted text, screenshots, or anything shared into Nouri from another
 * app. The AI only tidies the structure; amounts and steps stay the author's, and the
 * user reviews everything before it is saved.
 */
@Composable
fun RecipeImportScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { RecipeImportViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(RecipeImportViewModel.MAX_IMAGES)) { uris -> vm.addImages(uris) }

    Scaffold(topBar = { NouriTopBar("Import a recipe", onBack = nav::back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val preview = s.preview
            if (preview != null) {
                ImportPreviewCard(
                    preview,
                    onSave = { scope.launch { vm.save()?.let { nav.replace(Route.RecipeDetail(it)) } } },
                    onEdit = { vm.editFirst()?.let { nav.replace(Route.RecipeEditor(draftId = it)) } },
                    onBack = vm::discardPreview,
                )
                return@Column
            }

            Text(
                "Paste a recipe, add screenshots of one, or share a recipe to Nouri from another app. It's saved privately on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = s.text,
                onValueChange = vm::setText,
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                placeholder = { Text("Paste the recipe here — title, ingredients and method") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboardText(context)?.let { vm.setText(if (s.text.isBlank()) it else s.text + "\n\n" + it) } }) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste")
                }
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = s.images.size < RecipeImportViewModel.MAX_IMAGES,
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add screenshots")
                }
            }
            if (s.images.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.images.forEachIndexed { i, img ->
                        Box {
                            PhotoThumb(img.file, "Screenshot ${i + 1}", Modifier.size(width = 96.dp, height = 160.dp).clip(MaterialTheme.shapes.small))
                            FilledTonalIconButton(onClick = { vm.removeImage(i) }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Remove screenshot ${i + 1}", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Text("Several screenshots are read as pages of one recipe, in this order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            s.working?.let { LoadingBox(label = it) }
            s.error?.let { ErrorBanner(it) }

            if (s.aiAvailable) {
                Button(onClick = vm::importWithAi, enabled = s.hasInput && s.working == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clean up with AI")
                }
            }
            OutlinedButton(onClick = vm::importOnDevice, enabled = s.hasInput && s.working == null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (s.aiAvailable) "Import on my phone (no AI)" else "Import")
            }
            InfoBanner(
                text = if (s.aiAvailable) {
                    "“Clean up with AI” sends only this text and these screenshots to ${s.providerLabel} to tidy the format — it keeps the author's ingredients, amounts and steps. " +
                        "“Import on my phone” reads them on this device. Either way you review the recipe before saving, and nutrition is calculated on your phone."
                } else {
                    "Recipes are read on this phone (screenshots with on-device text recognition) and you review them before saving. Connect an AI provider in Settings for smarter clean-up of messy text."
                },
                icon = Icons.Outlined.Shield,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (s.consentNeeded) {
        AlertDialog(
            onDismissRequest = vm::declineConsent,
            title = { Text("Send these screenshots?") },
            text = { Text("Resized copies of the screenshots, without location data, will be sent to ${s.providerLabel} to read the recipe. You can withdraw this in Settings › Privacy.") },
            confirmButton = { TextButton(onClick = vm::grantConsent) { Text("Agree and continue") } },
            dismissButton = { TextButton(onClick = vm::declineConsent) { Text("Not now") } },
        )
    }
}

@Composable
private fun ImportPreviewCard(preview: ImportPreview, onSave: () -> Unit, onEdit: () -> Unit, onBack: () -> Unit) {
    val r = preview.recipe
    NouriCard {
        Text(r.name, style = MaterialTheme.typography.headlineSmall)
        OriginBadge(r.origin, Modifier.padding(vertical = 6.dp))
        r.attribution?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "${r.servings} servings · ${r.totalMinutes.takeIf { it > 0 }?.let { "$it min · " } ?: ""}${r.ingredients.size} ingredients · ${r.steps.size} steps",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(if (preview.usedAi) "Tidied with AI — check it matches the original." else "Read on your phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (preview.notes.isNotEmpty() || preview.unmatched.isNotEmpty()) {
        NouriCard {
            Text("Please check", style = MaterialTheme.typography.titleSmall)
            preview.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (preview.unmatched.isNotEmpty()) {
                Text(
                    "• Not matched to nutrition data, so not counted in calories: ${preview.unmatched.joinToString()}. Rename them in the editor (e.g. “red lentils”).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    NouriCard {
        Text("Ingredients", style = MaterialTheme.typography.titleSmall)
        var group: String? = null
        r.ingredients.forEach { ing ->
            if (ing.group != group) {
                group = ing.group
                group?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)) }
            }
            val amount = ing.quantity.takeIf { it.amount > 0 }?.let { q -> "${formatAmount(q.amount)} ${if (q.unit == QuantityUnit.PIECE) "" else q.unit.symbol} ".replace("  ", " ") }.orEmpty()
            Text("• $amount${ing.displayName}${ing.preparation?.let { ", $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Method", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        r.steps.forEachIndexed { i, step -> Text("${i + 1}. ${step.text}", style = MaterialTheme.typography.bodySmall) }
    }
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save recipe") }
    OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit before saving") }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}
