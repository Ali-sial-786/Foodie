package app.nouri.ui.recipes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nouri.core.ontology.HalalStatus
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.ui.theme.NouriTheme

/** Where a recipe came from, always with an icon and words. AI-generated recipes are never shown as traditional. */
@Composable
fun OriginBadge(origin: RecipeOrigin, modifier: Modifier = Modifier) {
    val (icon, text) = when (origin) {
        RecipeOrigin.EDITORIAL_TRADITIONAL -> Icons.AutoMirrored.Outlined.MenuBook to "Traditional · Nouri reference recipe"
        RecipeOrigin.EDITORIAL_VARIATION -> Icons.Outlined.Tune to "Variation — not the traditional version"
        RecipeOrigin.AI_GENERATED -> Icons.Outlined.AutoAwesome to "AI-generated — check before cooking"
        RecipeOrigin.USER -> Icons.Outlined.Edit to "Your recipe"
        RecipeOrigin.EXTERNAL_LICENSED -> Icons.Outlined.Public to "External recipe"
    }
    val (container, content) = when (origin) {
        RecipeOrigin.AI_GENERATED -> NouriTheme.extra.simulatedBanner to NouriTheme.extra.onSimulatedBanner
        RecipeOrigin.EDITORIAL_VARIATION -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Plain-language halal status. Cuisines are never assumed halal; meat always needs a halal source. */
fun halalText(status: HalalStatus): String = when (status) {
    HalalStatus.PERMISSIBLE -> "No haram ingredients identified"
    HalalStatus.REQUIRES_HALAL_SOURCE -> "Halal only with halal-certified meat or poultry"
    HalalStatus.NOT_PERMISSIBLE -> "Not halal as written"
    HalalStatus.VERIFY -> "Check ingredients — halal status depends on the product or source"
}

@Composable
fun halalColor(status: HalalStatus): Color = when (status) {
    HalalStatus.PERMISSIBLE -> NouriTheme.extra.higherConfidence
    HalalStatus.REQUIRES_HALAL_SOURCE, HalalStatus.VERIFY -> NouriTheme.extra.moderateConfidence
    HalalStatus.NOT_PERMISSIBLE -> NouriTheme.extra.lowConfidence
}
