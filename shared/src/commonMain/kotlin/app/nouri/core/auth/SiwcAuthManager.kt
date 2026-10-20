package app.nouri.core.auth

import app.nouri.core.ai.openai.AccountInfo
import app.nouri.core.ai.openai.BearerTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Tokens for the signed-in account. Persisted only on this device (encrypted by the platform). */
@Serializable
data class SiwcSession(
    val accountSubject: String,
    val email: String? = null,
    val name: String? = null,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAtEpochMs: Long,
    val earliestRefreshAtEpochMs: Long? = null,
    val scopes: Set<String> = emptySet(),
    val idTokenSignatureVerified: Boolean = false,
) {
    val planUsageAuthorized: Boolean get() = SiwcConfig.PLAN_USAGE_SCOPE in scopes
}

/**
 * Device-local secure storage. Implementations must keep data on the device under the
 * user's control (SIWC terms) — no cloud backup of tokens.
 */
interface SiwcStore {
    suspend fun hostId(): String?
    suspend fun saveHostId(hostId: String)
    suspend fun session(): SiwcSession?
    suspend fun saveSession(session: SiwcSession)
    suspend fun clearSession()
    suspend fun registrations(): List<SiwcRegistration>
    suspend fun saveRegistration(registration: SiwcRegistration)
}

class InMemorySiwcStore : SiwcStore {
    private var host: String? = null
    private var current: SiwcSession? = null
    private val regs = LinkedHashMap<String, SiwcRegistration>()
    override suspend fun hostId() = host
    override suspend fun saveHostId(hostId: String) { host = hostId }
    override suspend fun session() = current
    override suspend fun saveSession(session: SiwcSession) { current = session }
    override suspend fun clearSession() { current = null }
    override suspend fun registrations() = regs.values.toList()
    override suspend fun saveRegistration(registration: SiwcRegistration) { regs[registration.accountSubject] = registration }
}

data class SiwcEndpoints(
    val authorize: String = SiwcConfig.AUTHORIZE_URL,
    val token: String = SiwcConfig.TOKEN_URL,
    val revocation: String? = null,
    val jwksUri: String? = null,
    val issuer: String = SiwcConfig.ISSUER,
)

sealed interface SignInResult {
    data class Success(val session: SiwcSession) : SignInResult

    /** Signed in, but the user did not grant plan usage; AI requests won't use their plan. */
    data class SignedInWithoutPlanUsage(val session: SiwcSession) : SignInResult

    data class Cancelled(val reason: String) : SignInResult
    data class Failed(val reason: String, val retryable: Boolean) : SignInResult
}

data class SignOutResult(val remoteRevocationConfirmed: Boolean, val message: String)

fun interface Clock {
    fun nowEpochMs(): Long
}

/**
 * Orchestrates Sign in with ChatGPT: registration/sign-in URL, callback validation,
 * code exchange, refresh and revocation. Also serves bearer tokens to the
 * Responses client. One account's tokens are only ever used for that user's own
 * requests on this device.
 */
class SiwcAuthManager(
    private val http: HttpClient,
    private val store: SiwcStore,
    private val random: SecureRandomSource,
    private val clock: Clock,
    private val signatureVerifier: IdTokenSignatureVerifier? = null,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : BearerTokenSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val refreshMutex = Mutex()
    private var endpoints: SiwcEndpoints? = null

    /** Fetches the OIDC discovery document; falls back to documented defaults. */
    suspend fun endpoints(): SiwcEndpoints {
        endpoints?.let { return it }
        val discovered = runCatching {
            val response = http.get(SiwcConfig.DISCOVERY_URL)
            if (!response.status.isSuccess()) return@runCatching null
            val doc = json.parseToJsonElement(response.bodyAsText()).jsonObject
            fun s(key: String) = doc[key]?.jsonPrimitive?.contentOrNull
            SiwcEndpoints(
                authorize = s("authorization_endpoint") ?: SiwcConfig.AUTHORIZE_URL,
                token = s("token_endpoint") ?: SiwcConfig.TOKEN_URL,
                revocation = s("revocation_endpoint"),
                jwksUri = s("jwks_uri"),
                issuer = s("issuer") ?: SiwcConfig.ISSUER,
            )
        }.getOrNull()
        return (discovered ?: SiwcEndpoints()).also { if (discovered != null) endpoints = it }
    }

    suspend fun hostId(): String = store.hostId() ?: AuthorizationRequestBuilder.newHostId(random).also { store.saveHostId(it) }

    /** Starts an attempt. Pass [account] to re-authorise a previously registered account. */
    suspend fun begin(redirectUri: String, account: SiwcRegistration? = null, requestPlanUsage: Boolean = true): PendingAuthorization =
        AuthorizationRequestBuilder.build(
            hostId = hostId(),
            redirectUri = redirectUri,
            random = random,
            registration = account ?: store.registrations().singleOrNull(),
            requestPlanUsage = requestPlanUsage,
            authorizeUrl = endpoints().authorize,
        )

    suspend fun complete(pending: PendingAuthorization, callbackUrl: String): SignInResult {
        val callback = when (val parsed = CallbackParser.parse(callbackUrl, pending)) {
            is CallbackResult.Denied -> return if (parsed.error == "access_denied") SignInResult.Cancelled("You cancelled sign-in")
            else SignInResult.Failed(parsed.description ?: parsed.error, retryable = true)
            is CallbackResult.Invalid -> return SignInResult.Failed(parsed.reason, retryable = true)
            is CallbackResult.Success -> parsed
        }
        val ep = endpoints()
        val token = postToken(
            ep.token,
            Parameters.build {
                append("grant_type", "authorization_code")
                append("code", callback.code)
                append("redirect_uri", pending.redirectUri)
                append("client_id", callback.clientId)
                append("code_verifier", pending.pkce.verifier)
                if (pending.requestedPlanUsage) append("resource", SiwcConfig.RESOURCE)
            },
        ).getOrElse { return SignInResult.Failed(it.message ?: "Token exchange failed", retryable = true) }

        val idToken = token.idToken ?: return SignInResult.Failed("No ID token returned", retryable = true)
        val claims = IdTokens.decode(idToken) ?: return SignInResult.Failed("Unreadable ID token", retryable = true)
        IdTokens.validate(claims, callback.clientId, pending.nonce, clock.nowEpochMs() / 1000, ep.issuer)?.let {
            return SignInResult.Failed(it, retryable = true)
        }
        val verified = signatureVerifier?.let { v ->
            if (!v.verify(idToken)) return SignInResult.Failed("ID token signature could not be verified", retryable = true)
            true
        } ?: false

        val session = sessionFrom(token, callback.clientId, claims, verified, previous = null)
        store.saveSession(session)
        store.saveRegistration(SiwcRegistration(claims.sub, claims.email, callback.clientId, idToken))
        return if (token.grantsPlanUsage) SignInResult.Success(session) else SignInResult.SignedInWithoutPlanUsage(session)
    }

    /** Bearer token for plan-usage requests; refreshes when expired or after a 401. */
    override suspend fun token(forceRefresh: Boolean): String? {
        val session = store.session() ?: return null
        if (!session.planUsageAuthorized) return null
        val now = clock.nowEpochMs()
        if (!forceRefresh && now < session.expiresAtEpochMs - EXPIRY_SKEW_MS) return session.accessToken
        return refresh(session, now)?.accessToken
    }

    suspend fun account(): AccountInfo {
        val s = store.session()
        return AccountInfo(label = s?.email ?: s?.name, connected = s?.planUsageAuthorized == true)
    }

    suspend fun session(): SiwcSession? = store.session()

    private suspend fun refresh(stale: SiwcSession, now: Long): SiwcSession? = refreshMutex.withLock {
        // Another caller may have refreshed while we waited.
        val current = store.session() ?: return null
        if (current.accessToken != stale.accessToken && now < current.expiresAtEpochMs - EXPIRY_SKEW_MS) return current
        val refreshToken = current.refreshToken ?: return null
        current.earliestRefreshAtEpochMs?.let { if (now < it) return null }
        val token = postToken(
            endpoints().token,
            Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", current.clientId)
            },
        ).getOrElse { error ->
            if (error is TokenEndpointException && error.status in 400..499) {
                // Refresh token rejected (revoked/expired): the user must reconnect.
                store.clearSession()
            }
            return null
        }
        val claims = token.idToken?.let(IdTokens::decode)
        val updated = sessionFrom(token, current.clientId, claims, current.idTokenSignatureVerified, previous = current)
        store.saveSession(updated)
        updated
    }

    /** Revokes the renewable session remotely (with retries) and clears local tokens. */
    suspend fun signOut(): SignOutResult {
        val session = store.session() ?: return SignOutResult(true, "Already signed out")
        val revocation = endpoints().revocation
        var confirmed = false
        if (revocation != null && session.refreshToken != null) {
            for (attempt in 0 until 3) {
                val result = runCatching {
                    http.submitForm(
                        revocation,
                        Parameters.build {
                            append("token", session.refreshToken)
                            append("token_type_hint", "refresh_token")
                            append("client_id", session.clientId)
                        },
                    ).status
                }
                val status = result.getOrNull()
                if (status != null && status.isSuccess()) { confirmed = true; break }
                if (status != null && status.value in 400..499) break
                sleep(500L * (1 shl attempt))
            }
        }
        store.clearSession()
        return if (confirmed) SignOutResult(true, "Signed out and access revoked")
        else SignOutResult(false, "Signed out on this device. Remote revocation couldn't be confirmed — you can also disconnect Nouri in ChatGPT settings.")
    }

    private fun sessionFrom(token: TokenResponse, clientId: String, claims: IdTokenClaims?, verified: Boolean, previous: SiwcSession?): SiwcSession {
        val now = clock.nowEpochMs()
        return SiwcSession(
            accountSubject = claims?.sub ?: previous?.accountSubject ?: "unknown",
            email = claims?.email ?: previous?.email,
            name = claims?.name ?: previous?.name,
            clientId = clientId,
            accessToken = token.accessToken,
            // Refresh tokens rotate: always keep the newest one.
            refreshToken = token.refreshToken ?: previous?.refreshToken,
            idToken = token.idToken ?: previous?.idToken,
            expiresAtEpochMs = now + (token.expiresIn ?: DEFAULT_EXPIRY_S) * 1000,
            earliestRefreshAtEpochMs = token.earliestRefreshAt?.let { it * 1000 },
            scopes = token.scopes.ifEmpty { previous?.scopes.orEmpty() },
            idTokenSignatureVerified = verified,
        )
    }

    private suspend fun postToken(url: String, form: Parameters): Result<TokenResponse> = try {
        val response = http.submitForm(url, form)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val err = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            val description = err?.get("error_description")?.jsonPrimitive?.contentOrNull ?: err?.get("error")?.jsonPrimitive?.contentOrNull
            Result.failure(TokenEndpointException(response.status.value, description ?: "Token request failed (${response.status.value})"))
        } else {
            Result.success(json.decodeFromString(TokenResponse.serializer(), body))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    class TokenEndpointException(val status: Int, message: String) : Exception(message)

    companion object {
        const val EXPIRY_SKEW_MS = 60_000L
        const val DEFAULT_EXPIRY_S = 3600L
    }
}

/** Platform loopback receiver: listens on 127.0.0.1 with an OS-assigned port for one callback. */
interface LoopbackRedirectReceiver {
    /** Starts listening and returns the bound port. */
    suspend fun start(): Int

    /** Suspends until the browser hits the redirect path; returns the full callback URL. */
    suspend fun awaitCallback(timeoutMs: Long): String?

    fun stop()
}
