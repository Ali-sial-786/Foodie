package app.nouri.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.Difficulty
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.NouriCard

/** One version of a dish, with what the version picker shows about it. */
data class RecipeVersion(val recipe: Recipe, val kcalPerServing: Double, val rating: Int?, val cookedCount: Int)

/** Short label telling versions of the same dish apart. */
fun versionLabel(recipe: Recipe): String = when (recipe.origin) {
    RecipeOrigin.EDITORIAL_TRADITIONAL -> recipe.region?.let { "Traditional · $it" } ?: "Traditional"
    RecipeOrigin.EDITORIAL_VARIATION -> "Variation"
    RecipeOrigin.AI_GENERATED -> "AI-generated"
    RecipeOrigin.USER -> "Your version"
    RecipeOrigin.EXTERNAL_LICENSED -> "External"
    RecipeOrigin.IMPORTED -> "Saved by you"
}

/** Difficulty as one to three bars, always with its name (never colour or bars alone). */
@Composable
fun DifficultyIndicator(difficulty: Difficulty, modifier: Modifier = Modifier) {
    val level = difficulty.ordinal + 1
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Difficulty: ${difficulty.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            listOf(6, 9, 12).forEachIndexed { i, h ->
                Box(
                    Modifier.width(4.dp).height(h.dp).background(
                        if (i < level) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(1.dp),
                    ),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(difficulty.label, style = MaterialTheme.typography.labelMedium)
    }
}

/** The user's own rating, e.g. "★ 4/5". */
@Composable
fun RatingIndicator(rating: Int, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { contentDescription = "Your rating: $rating out of 5" }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(2.dp))
        Text("$rating/5", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun CookedIndicator(count: Int, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { contentDescription = "Cooked $count ${if (count == 1) "time" else "times"}" }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Restaurant, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(2.dp))
        Text("Cooked $count×", style = MaterialTheme.typography.labelMedium)
    }
}

/** Difficulty, rating and cooking history in one row. */
@Composable
fun RecipeIndicators(difficulty: Difficulty, rating: Int?, cookedCount: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        DifficultyIndicator(difficulty)
        rating?.let { RatingIndicator(it) }
        if (cookedCount > 0) CookedIndicator(cookedCount)
    }
}

/** Five stars; tapping the current rating again clears it. */
@Composable
fun RatingBar(rating: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { n ->
            val on = rating != null && n <= rating
            IconButton(onClick = { onRate(if (rating == n) null else n) }) {
                Icon(
                    if (on) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = when {
                        rating == n -> "Rated $n of 5. Tap to clear"
                        else -> "Rate $n of 5"
                    },
                    tint = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun VersionCard(v: RecipeVersion, current: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val r = v.recipe
    NouriCard(onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(versionLabel(r), CuisineCatalog[r.cuisineId]?.name).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (current) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = "Showing this version", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            "${RecipeText.minutes(r.totalMinutes)} · ${Fmt.kcal(v.kcalPerServing)} kcal per serving",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
        )
        RecipeIndicators(r.difficulty, v.rating, v.cookedCount, Modifier.padding(top = 4.dp))
    }
}

/** Every version of a dish; picking one opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsSheet(title: String, versions: List<RecipeVersion>, currentId: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("$title · ${versions.size} versions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            items(versions, key = { it.recipe.id }) { v ->
                VersionCard(v, current = v.recipe.id == currentId) { onPick(v.recipe.id) }
            }
        }
    }
}
