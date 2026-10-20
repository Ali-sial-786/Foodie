package app.nouri.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nouri.core.ai.AiError
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.NouriCard

/** Honest progress: each phase is shown only while that work is actually happening. */
enum class AnalysisPhase(val label: String) {
    PREPARING("Preparing photos…"),
    ANALYSING("Identifying the food…"),
    CALCULATING("Calculating nutrition from reference data…"),
    DONE("Done"),
    FAILED("Couldn't finish"),
}

enum class ErrorAction { RETRY, OPEN_AI_SETTINGS, DESCRIBE_INSTEAD, RETAKE }

data class ErrorUi(val title: String, val body: String, val actions: List<ErrorAction>)

/** Maps provider failures to clear next steps. Nothing here switches provider on the user's behalf. */
fun AiError.toUi(): ErrorUi = when (this) {
    is AiError.NotConfigured -> ErrorUi("AI isn't set up", message, listOf(ErrorAction.OPEN_AI_SETTINGS, ErrorAction.DESCRIBE_INSTEAD))
    is AiError.Unauthorized -> ErrorUi("Reconnect needed", "$message. Sign in again in Settings › AI provider.", listOf(ErrorAction.OPEN_AI_SETTINGS, ErrorAction.DESCRIBE_INSTEAD))
    is AiError.UsageLimitReached -> ErrorUi(
        "Usage limit reached",
        "$message. Nouri won't switch to paid API usage automatically. You can wait for your limit to reset, describe the meal instead, or choose another provider in Settings.",
        listOf(ErrorAction.DESCRIBE_INSTEAD, ErrorAction.OPEN_AI_SETTINGS),
    )
    is AiError.RateLimited -> ErrorUi("Busy right now", message, listOf(ErrorAction.RETRY, ErrorAction.DESCRIBE_INSTEAD))
    is AiError.CapabilityUnavailable -> ErrorUi("Not available", message, listOf(ErrorAction.DESCRIBE_INSTEAD, ErrorAction.OPEN_AI_SETTINGS))
    is AiError.InvalidResponse -> ErrorUi("Unclear result", "The analysis came back incomplete. Try again, or retake the photo in better light.", listOf(ErrorAction.RETRY, ErrorAction.RETAKE))
    is AiError.Network -> ErrorUi("You're offline", "$message. Your photos are kept so you can try again, or log the meal by describing it — that works offline.", listOf(ErrorAction.RETRY, ErrorAction.DESCRIBE_INSTEAD))
    is AiError.Timeout -> ErrorUi("Taking too long", message, listOf(ErrorAction.RETRY, ErrorAction.DESCRIBE_INSTEAD))
    is AiError.Server -> ErrorUi("Service problem", message, listOf(ErrorAction.RETRY, ErrorAction.DESCRIBE_INSTEAD))
    is AiError.Refused -> ErrorUi("Request declined", message, listOf(ErrorAction.RETAKE, ErrorAction.DESCRIBE_INSTEAD))
}

@Composable
fun AnalysisProgress(phase: AnalysisPhase, provider: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    NouriCard(modifier) {
        Text(phase.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (phase == AnalysisPhase.ANALYSING) Text("Using $provider", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
fun AnalysisError(error: ErrorUi, onAction: (ErrorAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ErrorBanner("${error.title}: ${error.body}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            error.actions.forEachIndexed { i, a ->
                val label = when (a) {
                    ErrorAction.RETRY -> "Try again"
                    ErrorAction.OPEN_AI_SETTINGS -> "AI settings"
                    ErrorAction.DESCRIBE_INSTEAD -> "Describe instead"
                    ErrorAction.RETAKE -> "Retake"
                }
                if (i == 0) Button(onClick = { onAction(a) }) { Text(label) } else OutlinedButton(onClick = { onAction(a) }) { Text(label) }
            }
        }
    }
}
