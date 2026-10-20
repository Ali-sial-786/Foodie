package app.nouri.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nouri.core.diary.DailyTargets
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.Nutrients
import app.nouri.ui.theme.NouriTheme
import app.nouri.ui.theme.NumberStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NouriTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun NouriCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val border = CardDefaults.outlinedCardBorder()
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, border = border) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, border = border) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/** Ring showing energy eaten against the target; the centre reads remaining (or eaten without a target). */
@Composable
fun CalorieRing(consumedKcal: Double, targetKcal: Double?, modifier: Modifier = Modifier, size: Dp = 168.dp, approximate: Boolean = false) {
    val fraction = if (targetKcal != null && targetKcal > 0) (consumedKcal / targetKcal).toFloat() else 0f
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val over = targetKcal != null && consumedKcal > targetKcal
    val progressColor = if (over) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val prefix = if (approximate) "about " else ""
    val description = if (targetKcal != null) {
        val remaining = targetKcal - consumedKcal
        if (remaining >= 0) "${prefix}${Fmt.kcal(remaining)} kcal remaining of ${Fmt.kcal(targetKcal)}"
        else "${prefix}${Fmt.kcal(-remaining)} kcal over your ${Fmt.kcal(targetKcal)} kcal target"
    } else {
        "${prefix}${Fmt.kcal(consumedKcal)} kcal eaten"
    }
    Box(modifier.size(size).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (animated > 0f) {
                drawArc(progressColor, startAngle = 135f, sweepAngle = 270f * animated, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (targetKcal != null) {
                val remaining = targetKcal - consumedKcal
                Text(Fmt.kcal(kotlin.math.abs(remaining)), style = NumberStyle, color = MaterialTheme.colorScheme.onSurface)
                Text(if (remaining >= 0) "kcal left" else "kcal over", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(Fmt.kcal(consumedKcal), style = NumberStyle)
                Text("kcal eaten", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (approximate) Text("approx.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MacroProgress(label: String, value: Double, target: Double?, color: Color, modifier: Modifier = Modifier) {
    val text = if (target != null && target > 0) "${Fmt.gramsShort(value)} / ${Fmt.gramsShort(target)}" else Fmt.gramsShort(value)
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = MaterialTheme.typography.titleSmall)
        if (target != null && target > 0) {
            LinearProgressIndicator(
                progress = { (value / target).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
fun MacroRow(consumed: Nutrients, targets: DailyTargets?, modifier: Modifier = Modifier) {
    val extra = NouriTheme.extra
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MacroProgress("Protein", consumed.proteinG, targets?.proteinG, extra.protein, Modifier.weight(1f))
        MacroProgress("Carbs", consumed.carbsG, targets?.carbsG, extra.carbs, Modifier.weight(1f))
        MacroProgress("Fat", consumed.fatG, targets?.fatG, extra.fat, Modifier.weight(1f))
        MacroProgress("Fibre", consumed.fiberG ?: 0.0, targets?.fiberG, extra.fibre, Modifier.weight(1f))
    }
}

/** Confidence is always shown with an icon and words, never colour alone. */
@Composable
fun ConfidenceBadge(level: ConfidenceLevel, modifier: Modifier = Modifier) {
    val color = NouriTheme.extra.confidence(level)
    val icon = when (level) {
        ConfidenceLevel.HIGHER -> Icons.Outlined.Verified
        ConfidenceLevel.MODERATE -> Icons.Outlined.Info
        ConfidenceLevel.LOW -> Icons.Outlined.WarningAmber
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(level.label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/** Shown wherever results come from the simulated provider, so demo numbers are never mistaken for analysis. */
@Composable
fun SimulatedBanner(modifier: Modifier = Modifier, text: String = "Demo mode — these results are simulated, not an analysis of your photo.") {
    InfoBanner(text, Icons.Outlined.Science, NouriTheme.extra.simulatedBanner, NouriTheme.extra.onSimulatedBanner, modifier)
}

@Composable
fun InfoBanner(text: String, icon: ImageVector, container: Color, content: Color, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    InfoBanner(
        message, Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, modifier,
        action = onRetry?.let { retry -> { TextButton(onClick = retry) { Text("Try again") } } },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimal: Boolean = true,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> if (new.length <= 9 && new.all { it.isDigit() || it == '.' || it == ',' }) onValueChange(new) },
        label = { Text(label) },
        suffix = suffix?.let { s -> { Text(s) } },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { s -> { Text(s) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/** Compact nutrition table for a portion. Unknown optional nutrients show a dash, never zero. */
@Composable
fun NutritionTable(n: Nutrients, modifier: Modifier = Modifier, precision: app.nouri.core.nutrition.Precision = app.nouri.core.nutrition.Precision.CALCULATED) {
    Column(modifier) {
        LabeledValue("Energy", Fmt.kcalUnit(n.energyKcal, precision))
        LabeledValue("Protein", Fmt.grams(n.proteinG, precision))
        LabeledValue("Carbohydrate", Fmt.grams(n.carbsG, precision))
        LabeledValue("  of which sugars", Fmt.grams(n.sugarG, precision))
        LabeledValue("  fibre", Fmt.grams(n.fiberG, precision))
        LabeledValue("Fat", Fmt.grams(n.fatG, precision))
        LabeledValue("  of which saturated", Fmt.grams(n.saturatedFatG, precision))
        LabeledValue("Sodium", n.sodiumMg?.let { "${app.nouri.core.nutrition.NutritionRounding.sodium(it).toInt()} mg" } ?: "–")
    }
}
