package app.nouri.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.core.ai.ProviderKind
import app.nouri.core.ai.ProviderStatus
import app.nouri.core.auth.SignInResult
import app.nouri.core.auth.SiwcSession
import app.nouri.data.prefs.AppSettings
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.InfoBanner
import app.nouri.ui.components.LabeledValue
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.theme.NouriTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AiSettingsState(
    val loadingStatus: Boolean = true,
    val statuses: List<ProviderStatus> = emptyList(),
    val session: SiwcSession? = null,
    val hasApiKey: Boolean = false,
    val signingIn: Boolean = false,
    /** Outcome of the last sign-in or sign-out, shown in the ChatGPT card. */
    val notice: String? = null,
    val error: String? = null,
)

class AiSettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(AiSettingsState())
    val state: StateFlow<AiSettingsState> = _state.asStateFlow()

    private var signInJob: Job? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loadingStatus = true) }
        // Status calls can touch the network (model discovery), so keep them off the main thread.
        val (statuses, session, key) = withContext(Dispatchers.IO) {
            Triple(runCatching { c.ai.statuses() }.getOrDefault(emptyList()), runCatching { c.siwc.session() }.getOrNull(), runCatching { c.apiKeys.get() }.getOrNull())
        }
        _state.update { it.copy(loadingStatus = false, statuses = statuses, session = session, hasApiKey = key != null) }
    }

    fun select(kind: ProviderKind) = update { it.copy(ai = it.ai.copy(selected = kind)) }

    fun signIn(openBrowser: (String) -> Unit) {
        if (signInJob?.isActive == true) return
        _state.update { it.copy(signingIn = true, notice = null, error = null) }
        signInJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    c.chatGptSignIn.signIn(openBrowser = { url -> viewModelScope.launch(Dispatchers.Main) { openBrowser(url) } })
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(signingIn = false, notice = "Sign-in cancelled") }
                throw e
            } catch (e: Exception) {
                SignInResult.Failed("Sign-in couldn't start: ${e.message ?: e::class.simpleName}", retryable = true)
            }
            c.forgetDiscoveredModels()
            when (result) {
                is SignInResult.Success -> _state.update {
                    it.copy(signingIn = false, notice = "Connected${result.session.email?.let { e -> " as $e" } ?: ""}. You allowed Nouri to use your ChatGPT plan for the requests you make in this app.")
                }
                is SignInResult.SignedInWithoutPlanUsage -> _state.update {
                    it.copy(signingIn = false, notice = "Signed in, but plan usage wasn't granted, so Nouri can't send AI requests on your plan. Reconnect and allow access to use it.")
                }
                is SignInResult.Cancelled -> _state.update { it.copy(signingIn = false, notice = result.reason) }
                is SignInResult.Failed -> _state.update { it.copy(signingIn = false, error = result.reason) }
            }
            refresh()
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        _state.update { it.copy(signingIn = false) }
    }

    fun disconnect() = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { c.siwc.signOut() }.getOrNull() }
        c.forgetDiscoveredModels()
        c.settings.update { s -> if (s.ai.selected == ProviderKind.CHATGPT_PLAN) s.copy(ai = s.ai.copy(selected = ProviderKind.MOCK)) else s }
        _state.update { it.copy(notice = result?.message ?: "Signed out on this device", error = null) }
        refresh()
    }

    fun saveApiKey(key: String) = viewModelScope.launch {
        if (key.isBlank()) return@launch
        c.apiKeys.set(key)
        c.forgetDiscoveredModels()
        c.messages.show("API key saved on this device")
        refresh()
    }

    fun removeApiKey() = viewModelScope.launch {
        c.apiKeys.clear()
        c.forgetDiscoveredModels()
        c.settings.update { s ->
            val ai = s.ai.copy(allowApiKeyFallback = false, selected = if (s.ai.selected == ProviderKind.OPENAI_API_KEY) ProviderKind.MOCK else s.ai.selected)
            s.copy(ai = ai)
        }
        c.messages.show("API key removed")
        refresh()
    }

    fun setModels(plan: String, api: String) {
        update { it.copy(ai = it.ai.copy(planModel = plan.trim().ifBlank { null }, apiModel = api.trim().ifBlank { null })) }
        c.forgetDiscoveredModels()
        c.messages.show("Model choices saved")
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            c.settings.update(transform)
            refresh()
        }
    }
}

private fun openCustomTab(context: Context, url: String): Boolean = try {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    true
} catch (e: ActivityNotFoundException) {
    false
}

@Composable
fun AiSettingsScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { AiSettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(topBar = { NouriTopBar("AI provider", onBack = nav::back) }) { padding ->
        val s = settings ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BodyText(
                "AI identifies foods and suggests recipes. Calories and nutrients are always calculated on your phone from reference data, " +
                    "whichever provider you choose.",
            )

            SettingsHeader("Use for AI features")
            val plan = state.statuses.firstOrNull { it.kind == ProviderKind.CHATGPT_PLAN }
            val api = state.statuses.firstOrNull { it.kind == ProviderKind.OPENAI_API_KEY }
            RadioRow("Demo (simulated)", "Fixed demo results; photos are never analysed or uploaded", selected = s.ai.selected == ProviderKind.MOCK) { vm.select(ProviderKind.MOCK) }
            RadioRow(
                "ChatGPT plan (Sign in with ChatGPT)",
                if (plan?.connected == true) "Connected · uses your ChatGPT plan limits" else "Connect below first",
                selected = s.ai.selected == ProviderKind.CHATGPT_PLAN, enabled = plan?.connected == true,
            ) { vm.select(ProviderKind.CHATGPT_PLAN) }
            RadioRow(
                "OpenAI API key (billed to you)",
                if (state.hasApiKey) "Key saved · requests are billed to your API account" else "Add a key below first",
                selected = s.ai.selected == ProviderKind.OPENAI_API_KEY, enabled = state.hasApiKey,
            ) { vm.select(ProviderKind.OPENAI_API_KEY) }
            if (s.ai.selected == ProviderKind.MOCK) {
                InfoBanner(
                    "Demo mode is on. Results are simulated and clearly labelled; nothing you photograph is analysed.",
                    Icons.Outlined.Science, NouriTheme.extra.simulatedBanner, NouriTheme.extra.onSimulatedBanner,
                )
            }

            SettingsHeader("ChatGPT account")
            ChatGptCard(
                state = state,
                status = plan,
                selected = s.ai.selected == ProviderKind.CHATGPT_PLAN,
                onConnect = {
                    vm.signIn { url ->
                        if (!openCustomTab(context, url)) container.messages.show("No browser is available to sign in")
                    }
                },
                onCancel = vm::cancelSignIn,
                onDisconnect = vm::disconnect,
                onUse = { vm.select(ProviderKind.CHATGPT_PLAN) },
            )

            SettingsHeader("OpenAI API key")
            ApiKeyCard(
                hasKey = state.hasApiKey,
                status = api,
                fallbackAllowed = s.ai.allowApiKeyFallback,
                onSave = vm::saveApiKey,
                onRemove = vm::removeApiKey,
                onFallback = { on -> vm.update { it.copy(ai = it.ai.copy(allowApiKeyFallback = on)) } },
            )

            SettingsHeader("Photo analysis permission")
            NouriCard {
                if (s.ai.photoConsent) {
                    Text("You've allowed photo analysis", style = MaterialTheme.typography.titleSmall)
                    BodyText("When you analyse a photo, a resized copy without location data is sent to the provider selected above. Nothing is sent in the background.")
                    TextButton(onClick = { vm.update { it.copy(ai = it.ai.copy(photoConsent = false)) } }) { Text("Revoke permission") }
                } else {
                    Text("Not allowed yet", style = MaterialTheme.typography.titleSmall)
                    BodyText("Nouri asks before the first photo is sent to a real AI provider. Demo mode never sends photos.")
                }
            }

            SettingsHeader("Models")
            ModelCard(s.ai.planModel.orEmpty(), s.ai.apiModel.orEmpty(), plan?.model, api?.model, vm::setModels)

            SettingsHeader("Provider status")
            if (state.loadingStatus) LoadingBox(label = "Checking providers…")
            state.statuses.forEach { st -> StatusCard(st) }
        }
    }
}

@Composable
private fun ChatGptCard(
    state: AiSettingsState,
    status: ProviderStatus?,
    selected: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onUse: () -> Unit,
) {
    val session = state.session
    NouriCard {
        when {
            state.signingIn -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Waiting for you to finish signing in in the browser…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                BodyText("After approving, tap “Return to Nouri” on the page.", Modifier.padding(top = 4.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            session != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(session.email ?: session.name ?: "Signed in", style = MaterialTheme.typography.titleSmall)
                }
                LabeledValue("Plan usage for Nouri", if (session.planUsageAuthorized) "Allowed" else "Not allowed")
                status?.model?.let { LabeledValue("Model", it) }
                if (!session.planUsageAuthorized) {
                    BodyText("You're signed in, but didn't allow Nouri to use your plan. Signing in alone doesn't let Nouri make AI requests. Reconnect and allow access if you want to use it.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (session.planUsageAuthorized && !selected) Button(onClick = onUse) { Text("Use for AI features") }
                    OutlinedButton(onClick = onConnect) { Text("Reconnect") }
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
            else -> {
                Text("Sign in with ChatGPT", style = MaterialTheme.typography.titleSmall)
                BodyText("Opens ChatGPT's sign-in page in your browser. Nouri never sees your password.")
                Button(onClick = onConnect, modifier = Modifier.padding(top = 8.dp)) { Text("Connect ChatGPT") }
            }
        }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        state.error?.let { ErrorBanner(it, Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(8.dp))
        Text("What this means", style = MaterialTheme.typography.labelLarge)
        BodyText("• Signing in identifies you; using your plan is a separate permission you grant on the consent screen.")
        BodyText("• Requests count towards your ChatGPT plan's usage limits. When you reach a limit, Nouri tells you and stops — it never switches to paid API usage on its own.")
        BodyText("• Only the photos and text you choose to analyse are sent. Nouri can't see your ChatGPT conversations, memories or other account data.")
        BodyText("• Sign-in tokens are encrypted on this phone, never backed up, and only used for your own requests.")
    }
}

@Composable
private fun ApiKeyCard(
    hasKey: Boolean,
    status: ProviderStatus?,
    fallbackAllowed: Boolean,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onFallback: (Boolean) -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    NouriCard {
        if (hasKey) {
            LabeledValue("Saved key", status?.accountLabel ?: "Saved")
            TextButton(onClick = onRemove) { Text("Remove key") }
        } else {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                supportingText = { if (key.isNotEmpty() && !key.startsWith("sk-")) Text("OpenAI keys usually start with “sk-”") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSave(key); key = "" }, enabled = key.length >= 20) { Text("Save key") }
        }
        BodyText("Requests are billed to your OpenAI API account. Nouri shows which provider handled each result. The key is encrypted with Android Keystore and never leaves this phone except to call OpenAI.")
        SwitchRow(
            "Use the key when ChatGPT can't handle a request",
            "Only for errors other than usage limits. Those requests are billed to you.",
            checked = fallbackAllowed && hasKey,
            enabled = hasKey,
            onCheckedChange = onFallback,
        )
    }
}

@Composable
private fun ModelCard(planModel: String, apiModel: String, planDetected: String?, apiDetected: String?, onSave: (String, String) -> Unit) {
    var plan by rememberSaveable(planModel) { mutableStateOf(planModel) }
    var api by rememberSaveable(apiModel) { mutableStateOf(apiModel) }
    NouriCard {
        BodyText("Leave blank to choose automatically from the models your account can use.")
        OutlinedTextField(plan, { plan = it }, label = { Text("ChatGPT plan model") }, placeholder = { Text(planDetected ?: "Automatic") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(api, { api = it }, label = { Text("API key model") }, placeholder = { Text(apiDetected ?: "Automatic") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onSave(plan, api) }) { Text("Save model choices") }
    }
}

@Composable
private fun StatusCard(st: ProviderStatus) {
    NouriCard {
        Text(st.kind.label, style = MaterialTheme.typography.titleSmall)
        LabeledValue("Status", when {
            st.isSimulated -> "Simulated"
            st.connected -> "Connected"
            else -> "Not connected"
        })
        st.accountLabel?.let { LabeledValue("Account", it) }
        LabeledValue("Billing", st.billing.label)
        st.model?.let { LabeledValue("Model", it) }
        BodyText("Can do: " + st.capabilities.joinToString { it.label.lowercase() })
        st.restrictions.forEach { r ->
            Row(Modifier.padding(top = 4.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                BodyText(r)
            }
        }
    }
}
