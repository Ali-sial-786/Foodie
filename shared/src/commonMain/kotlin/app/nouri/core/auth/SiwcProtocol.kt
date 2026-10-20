package app.nouri.core.auth

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Sign in with ChatGPT — open-source app ("token sharing") flow.
 *
 * Endpoint and parameter names follow OpenAI's developer documentation
 * (developers.openai.com/siwc). They are centralised here because the feature is
 * in preview; see AI_INTEGRATION.md for the sources and what still needs live
 * verification. Discovery (`/.well-known/openid-configuration`) is preferred at
 * runtime and these values are fallbacks.
 */
object SiwcConfig {
    const val ISSUER = "https://auth.openai.com"
    const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"
    const val AUTHORIZE_URL = "$ISSUER/api/accounts/authorize"
    const val TOKEN_URL = "$ISSUER/api/accounts/oauth/token"

    /** Audience for plan-usage tokens. */
    const val RESOURCE = "https://api.openai.com/v1"

    /** First-registration entry point. Never stored and never used for token exchange. */
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"

    /** Name shown to the user during registration; must stay identical across installs. */
    const val AGENT_NAME = "Nouri"

    val IDENTITY_SCOPES = listOf("openid", "profile", "email")
    val PLAN_USAGE_SCOPES = listOf("offline_access", "resource.invoke", "chatgpt.tokens.use.direct")
    const val PLAN_USAGE_SCOPE = "chatgpt.tokens.use.direct"

    /** Loopback redirect: the port may change per attempt; scheme, host and path stay fixed. */
    const val REDIRECT_HOST = "127.0.0.1"
    const val REDIRECT_PATH = "/auth/callback"

    fun redirectUri(port: Int): String = "http://$REDIRECT_HOST:$port$REDIRECT_PATH"
}

/** Per-attempt secrets. Fresh state, nonce and PKCE values are generated for every attempt. */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        fun generate(random: SecureRandomSource): Pkce {
            val verifier = Base64Url.encode(random.nextBytes(48)) // 64 chars, within RFC 7636's 43–128
            return Pkce(verifier, challengeFor(verifier))
        }

        fun challengeFor(verifier: String): String = Base64Url.encode(Sha256.digest(verifier.encodeToByteArray()))
    }
}

/** A sign-in attempt waiting for its browser callback. Kept in memory only. */
data class PendingAuthorization(
    val authorizationUrl: String,
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val pkce: Pkce,
    /** Issued client id when re-authorising a known account; null on first registration. */
    val expectedClientId: String?,
    val requestedPlanUsage: Boolean,
)

/** A ChatGPT account previously registered with this app; kept after sign-out so re-sign-in reuses it. */
@Serializable
data class SiwcRegistration(
    val accountSubject: String,
    val email: String? = null,
    val clientId: String,
    /** Retained to identify the account on re-authorisation; sent only to the authorize endpoint. */
    val lastIdToken: String? = null,
)

object AuthorizationRequestBuilder {
    fun build(
        hostId: String,
        redirectUri: String,
        random: SecureRandomSource,
        registration: SiwcRegistration? = null,
        requestPlanUsage: Boolean = true,
        authorizeUrl: String = SiwcConfig.AUTHORIZE_URL,
    ): PendingAuthorization {
        require(hostId.isNotBlank()) { "ext_agent_host_id must be set before sign-in" }
        val state = Base64Url.encode(random.nextBytes(32))
        val nonce = Base64Url.encode(random.nextBytes(32))
        val pkce = Pkce.generate(random)
        val scopes = SiwcConfig.IDENTITY_SCOPES + if (requestPlanUsage) SiwcConfig.PLAN_USAGE_SCOPES else emptyList()

        val url = URLBuilder(authorizeUrl).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", registration?.clientId ?: SiwcConfig.DYNAMIC_CLIENT_ID)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("nonce", nonce)
            parameters.append("code_challenge", pkce.challenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("ext_agent_host_id", hostId)
            if (requestPlanUsage) parameters.append("resource", SiwcConfig.RESOURCE)
            if (registration == null) {
                parameters.append("agent_name_hint", SiwcConfig.AGENT_NAME)
            } else {
                registration.lastIdToken?.let { parameters.append("id_token_hint", it) }
                registration.email?.let { parameters.append("login_hint", it) }
            }
        }.buildString()

        return PendingAuthorization(url, redirectUri, state, nonce, pkce, registration?.clientId, requestPlanUsage)
    }

    /** Random opaque host identifier, generated once per installation. Not a secret. */
    fun newHostId(random: SecureRandomSource): String = "nouri-" + random.nextBytes(16).toHex()
}

sealed interface CallbackResult {
    data class Success(val code: String, val clientId: String) : CallbackResult
    data class Denied(val error: String, val description: String?) : CallbackResult
    data class Invalid(val reason: String) : CallbackResult
}

object CallbackParser {
    fun parse(callbackUrl: String, pending: PendingAuthorization): CallbackResult {
        val url = runCatching { Url(callbackUrl) }.getOrElse { return CallbackResult.Invalid("Malformed callback") }
        if (url.encodedPath != SiwcConfig.REDIRECT_PATH) return CallbackResult.Invalid("Unexpected callback path")
        val params = url.parameters
        val state = params["state"]
        if (state == null || !constantTimeEquals(state, pending.state)) return CallbackResult.Invalid("State mismatch")
        params["error"]?.let { return CallbackResult.Denied(it, params["error_description"]) }
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return CallbackResult.Invalid("Missing authorization code")
        val returnedClientId = params["client_id"]?.takeIf { it.isNotBlank() }
        val clientId = when {
            pending.expectedClientId != null && returnedClientId != null && returnedClientId != pending.expectedClientId ->
                return CallbackResult.Invalid("Callback returned a different client id")
            pending.expectedClientId != null -> pending.expectedClientId
            returnedClientId == null -> return CallbackResult.Invalid("Registration did not return a client id")
            returnedClientId == SiwcConfig.DYNAMIC_CLIENT_ID -> return CallbackResult.Invalid("Placeholder client id returned")
            else -> returnedClientId
        }
        return CallbackResult.Success(code, clientId)
    }

    internal fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    val scope: String? = null,
    /** Earliest time a refresh is permitted (seconds since epoch), when the server provides it. */
    @SerialName("earliest_refresh_at") val earliestRefreshAt: Long? = null,
) {
    val scopes: Set<String> get() = scope?.split(' ')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** A valid ID token alone does not authorise plan usage; the granted scope must include it. */
    val grantsPlanUsage: Boolean get() = SiwcConfig.PLAN_USAGE_SCOPE in scopes
}

@Serializable
data class IdTokenClaims(
    val iss: String? = null,
    val sub: String,
    val aud: List<String> = emptyList(),
    val exp: Long? = null,
    val iat: Long? = null,
    val nonce: String? = null,
    val email: String? = null,
    val name: String? = null,
)

/** Platform hook for verifying the ID token signature against the issuer's JWKS. */
fun interface IdTokenSignatureVerifier {
    suspend fun verify(idToken: String): Boolean
}

object IdTokens {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decodes the JWT payload without verifying the signature. */
    fun decode(idToken: String): IdTokenClaims? {
        val parts = idToken.split('.')
        if (parts.size != 3) return null
        val payload = runCatching { Base64Url.decode(parts[1]).decodeToString() }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val aud = when (val a = obj["aud"]) {
            is JsonPrimitive -> listOfNotNull(a.contentOrNull)
            is JsonArray -> a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        fun long(key: String) = (obj[key] as? JsonPrimitive)?.longOrNull
        return IdTokenClaims(
            iss = str("iss"), sub = str("sub") ?: return null, aud = aud, exp = long("exp"), iat = long("iat"),
            nonce = str("nonce"), email = str("email"), name = str("name"),
        )
    }

    /** Claim checks from OpenID Connect Core §3.1.3.7 (issuer, audience, expiry, nonce). */
    fun validate(claims: IdTokenClaims, clientId: String, nonce: String, nowEpochSeconds: Long, issuer: String = SiwcConfig.ISSUER, skewSeconds: Long = 300): String? = when {
        claims.iss?.trimEnd('/') != issuer.trimEnd('/') -> "Unexpected issuer"
        clientId !in claims.aud -> "Token was not issued for this app"
        claims.exp == null || claims.exp + skewSeconds < nowEpochSeconds -> "Token expired"
        claims.nonce != nonce -> "Nonce mismatch"
        else -> null
    }
}
