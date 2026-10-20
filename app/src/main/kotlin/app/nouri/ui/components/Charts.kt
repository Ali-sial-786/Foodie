package app.nouri.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One day's bar: value null = not logged (drawn as an empty slot, never as zero). */
data class DayBar(val date: LocalDate, val value: Double?)

/**
 * Daily energy columns with an optional target reference line. Single series, so
 * no legend; tapping a column shows its value above the chart.
 */
@Composable
fun DailyBarChart(bars: List<DayBar>, target: Double?, modifier: Modifier = Modifier, valueLabel: (Double) -> String) {
    if (bars.isEmpty()) return
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val barColor = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val targetColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxValue = max(bars.maxOf { it.value ?: 0.0 }, target ?: 0.0).takeIf { it > 0 } ?: 1.0
    val logged = bars.filter { it.value != null }
    val summary = if (logged.isEmpty()) "No days logged in this period" else
        "Daily energy for ${bars.size} days, ${logged.size} logged. Highest ${valueLabel(logged.maxOf { it.value!! })}, lowest ${valueLabel(logged.minOf { it.value!! })}" +
            (target?.let { ". Target ${valueLabel(it)}" } ?: "")

    Column(modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        val sel = selected?.let { bars.getOrNull(it) }
        Text(
            when {
                sel == null -> "Tap a day for details"
                sel.value == null -> "${Fmt.shortDate(sel.date)}: not logged"
                else -> "${Fmt.shortDate(sel.date)}: ${valueLabel(sel.value)}"
            },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box {
            Canvas(
                Modifier.fillMaxWidth().height(140.dp).pointerInput(bars) {
                    detectTapGestures { offset ->
                        val slot = size.width / bars.size.toFloat()
                        selected = (offset.x / slot).toInt().coerceIn(0, bars.lastIndex)
                    }
                },
            ) {
                val h = size.height
                val slot = size.width / bars.size
                val barWidth = min(24.dp.toPx(), slot - 2.dp.toPx()).coerceAtLeast(2.dp.toPx())
                val radius = min(4.dp.toPx(), barWidth / 2)
                drawLine(grid, Offset(0f, h), Offset(size.width, h), strokeWidth = 1.dp.toPx())
                bars.forEachIndexed { i, bar ->
                    val v = bar.value ?: return@forEachIndexed
                    val barHeight = (v / maxValue * (h - 4.dp.toPx())).toFloat().coerceAtLeast(2.dp.toPx())
                    val left = i * slot + (slot - barWidth) / 2
                    val top = h - barHeight
                    // Rounded data end, square at the baseline.
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left, top = top, right = left + barWidth, bottom = h,
                                topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                            ),
                        )
                    }
                    drawPath(path, if (selected == null || selected == i) barColor else dim)
                }
                target?.let { t ->
                    val y = (h - t / maxValue * (h - 4.dp.toPx())).toFloat()
                    drawLine(targetColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
            if (target != null) {
                Text(
                    "Target", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(Fmt.shortDate(bars.first().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(Fmt.shortDate(bars.last().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class WeightPoint(val date: LocalDate, val scale: Double, val trend: Double)

/**
 * Scale readings (dots) with the smoothed trend (line). Two series, so a small
 * legend names them; values are in the user's display unit already.
 */
@Composable
fun WeightChart(points: List<WeightPoint>, unitSymbol: String, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val line = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    val grid = MaterialTheme.colorScheme.outlineVariant
    val minV = points.minOf { min(it.scale, it.trend) }
    val maxV = points.maxOf { max(it.scale, it.trend) }
    val pad = max((maxV - minV) * 0.15, 0.5)
    val lo = minV - pad
    val hi = maxV + pad
    val firstDay = points.first().date.toEpochDays()
    val span = max(1L, points.last().date.toEpochDays() - firstDay).toFloat()
    val summary = "Weight trend from ${Fmt.decimal(points.first().trend)} to ${Fmt.decimal(points.last().trend)} $unitSymbol between ${Fmt.shortDate(points.first().date)} and ${Fmt.shortDate(points.last().date)}"

    Column(modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(4.dp))
            Text("Scale reading", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(14.dp).height(2.dp).background(line))
            Spacer(Modifier.width(4.dp))
            Text("Trend", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            val sel = selected?.let { points.getOrNull(it) }
            if (sel != null) {
                Text("${Fmt.shortDate(sel.date)}: ${Fmt.decimal(sel.scale)} $unitSymbol (trend ${Fmt.decimal(sel.trend)})", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(160.dp).pointerInput(points) {
                detectTapGestures { offset ->
                    val x = offset.x / size.width * span
                    selected = points.indices.minByOrNull { abs((points[it].date.toEpochDays() - firstDay) - x) }
                }
            },
        ) {
            fun px(p: WeightPoint) = Offset(
                (p.date.toEpochDays() - firstDay) / span * size.width,
                ((hi - p.trend) / (hi - lo) * size.height).toFloat(),
            )
            fun py(p: WeightPoint) = Offset(px(p).x, ((hi - p.scale) / (hi - lo) * size.height).toFloat())
            // Recessive hairline grid at three levels.
            for (k in 0..2) {
                val y = size.height * (0.15f + 0.35f * k)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val path = Path()
            points.forEachIndexed { i, p -> val o = px(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            points.forEachIndexed { i, p ->
                val o = py(p)
                val r = if (selected == i) 6.dp.toPx() else 4.dp.toPx()
                drawCircle(surface, radius = r + 2.dp.toPx(), center = o)
                drawCircle(dot.copy(alpha = if (selected == null || selected == i) 0.8f else 0.4f), radius = r, center = o)
            }
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(Fmt.shortDate(points.first().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(Fmt.shortDate(points.last().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
