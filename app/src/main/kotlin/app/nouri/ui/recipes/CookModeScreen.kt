package app.nouri.ui.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeScaler
import app.nouri.core.util.SystemTime
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A countdown for one step. Remaining time is derived from the wall clock so it stays right if the UI pauses. */
data class StepTimer(val totalSeconds: Int, val remainingSeconds: Int, val running: Boolean, val endsAtEpochMs: Long? = null) {
    val finished: Boolean get() = remainingSeconds <= 0
}

data class CookState(
    val recipe: Recipe? = null,
    val step: Int = 0,
    val checked: Set<Int> = emptySet(),
    val timers: Map<Int, StepTimer> = emptyMap(),
    val keepScreenOn: Boolean = true,
    val notFound: Boolean = false,
)

class CookModeViewModel(private val c: AppContainer, private val id: String, servings: Double) : ViewModel() {
    private val _state = MutableStateFlow(CookState())
    val state: StateFlow<CookState> = _state.asStateFlow()
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            val recipe = c.recipes.get(id)
            if (recipe == null) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            val n = servings.roundToInt().coerceIn(1, 100)
            val scaled = RecipeScaler.scale(recipe, n).recipe
            _state.update {
                it.copy(
                    recipe = scaled,
                    keepScreenOn = c.settings.current().keepScreenOnInCookMode,
                    timers = scaled.steps.mapIndexedNotNull { i, s -> s.timerSeconds?.let { t -> i to StepTimer(t, t, running = false) } }.toMap(),
                )
            }
        }
    }

    fun goTo(step: Int) {
        val count = _state.value.recipe?.steps?.size ?: return
        _state.update { it.copy(step = step.coerceIn(0, count - 1)) }
    }

    fun toggleIngredient(index: Int) = _state.update { s -> s.copy(checked = if (index in s.checked) s.checked - index else s.checked + index) }

    fun startTimer(step: Int) {
        _state.update { s ->
            val t = s.timers[step] ?: return@update s
            val remaining = if (t.finished) t.totalSeconds else t.remainingSeconds
            s.copy(timers = s.timers + (step to t.copy(remainingSeconds = remaining, running = true, endsAtEpochMs = SystemTime.nowEpochMs() + remaining * 1000L)))
        }
        ensureTicker()
    }

    fun pauseTimer(step: Int) = _state.update { s ->
        val t = s.timers[step] ?: return@update s
        s.copy(timers = s.timers + (step to t.copy(running = false, endsAtEpochMs = null)))
    }

    fun resetTimer(step: Int) = _state.update { s ->
        val t = s.timers[step] ?: return@update s
        s.copy(timers = s.timers + (step to t.copy(remainingSeconds = t.totalSeconds, running = false, endsAtEpochMs = null)))
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive && _state.value.timers.values.any { it.running }) {
                val now = SystemTime.nowEpochMs()
                _state.update { s ->
                    s.copy(timers = s.timers.mapValues { (_, t) ->
                        if (!t.running || t.endsAtEpochMs == null) t
                        else {
                            val left = ((t.endsAtEpochMs - now + 999) / 1000).toInt().coerceAtLeast(0)
                            if (left == 0) t.copy(remainingSeconds = 0, running = false, endsAtEpochMs = null) else t.copy(remainingSeconds = left)
                        }
                    })
                }
                delay(250)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookModeScreen(nav: AppNavigator, route: Route.CookMode) {
    val container = LocalAppContainer.current
    val vm = viewModel { CookModeViewModel(container, route.recipeId, route.servings) }
    val s by vm.state.collectAsStateWithLifecycle()
    var showIngredients by rememberSaveable { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(s.keepScreenOn) {
        view.keepScreenOn = s.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(s.recipe?.name ?: "Cook", maxLines = 1) },
            navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.Outlined.Close, contentDescription = "Leave cook mode") } },
            actions = { IconButton(onClick = { showIngredients = true }) { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Ingredients checklist") } },
        )
    }) { padding ->
        val recipe = s.recipe
        if (recipe == null) {
            LoadingBox(Modifier.padding(padding), label = if (s.notFound) "Recipe not found" else null)
            return@Scaffold
        }
        if (recipe.steps.isEmpty()) {
            Text("This recipe has no steps.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val index = s.step
        val step = recipe.steps[index]
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().padding(20.dp)) {
            LinearProgressIndicator(progress = { (index + 1f) / recipe.steps.size }, modifier = Modifier.fillMaxWidth())
            Text(
                "Step ${index + 1} of ${recipe.steps.size}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp).semantics { heading() },
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                Text(step.text, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 36.sp))
                step.temperatureC?.let { Text("$it °C", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
                s.timers[index]?.let { t -> TimerPanel(t, onStart = { vm.startTimer(index) }, onPause = { vm.pauseTimer(index) }, onReset = { vm.resetTimer(index) }) }
                s.timers.filter { (i, t) -> i != index && t.running }.forEach { (i, t) ->
                    Text(
                        "Step ${i + 1} timer: ${RecipeText.countdown(t.remainingSeconds)}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp).clickable { vm.goTo(i) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.goTo(index - 1) }, enabled = index > 0, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Previous") }
                if (index < recipe.steps.lastIndex) {
                    Button(onClick = { vm.goTo(index + 1) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Next") }
                } else {
                    Button(onClick = nav::back, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Done") }
                }
            }
        }
    }

    if (showIngredients && s.recipe != null) {
        ModalBottomSheet(onDismissRequest = { showIngredients = false }) {
            LazyColumn(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                item { Text("Ingredients", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() }) }
                itemsIndexed(s.recipe!!.ingredients) { i, line ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.toggleIngredient(i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = i in s.checked, onCheckedChange = { vm.toggleIngredient(i) })
                        Spacer(Modifier.width(4.dp))
                        Text(RecipeText.ingredientLine(line), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerPanel(t: StepTimer, onStart: () -> Unit, onPause: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        val text = if (t.finished) "Time's up" else RecipeText.countdown(t.remainingSeconds)
        Text(
            text,
            style = MaterialTheme.typography.displaySmall,
            color = if (t.finished) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics {
                // Announce only when the timer finishes, not every second.
                if (t.finished) liveRegion = LiveRegionMode.Assertive
                contentDescription = if (t.finished) "Timer finished" else "Timer ${RecipeText.countdown(t.remainingSeconds)} remaining"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (t.running) {
                FilledTonalButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Pause, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pause")
                }
            } else {
                FilledTonalButton(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (t.finished) "Restart timer" else if (t.remainingSeconds < t.totalSeconds) "Resume" else "Start ${RecipeText.minutes((t.totalSeconds + 59) / 60)} timer")
                }
            }
            if (t.remainingSeconds != t.totalSeconds) {
                OutlinedButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Replay, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reset")
                }
            }
        }
    }
}
