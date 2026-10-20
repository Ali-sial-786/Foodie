package app.nouri.core.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiwcTest {
    private val random = SecureRandomSource { Random(42).nextBytes(it) }

    @Test
    fun sha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        // Multi-block input (> 64 bytes) crossing the padding boundary.
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            Sha256.hex("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu".encodeToByteArray()),
        )
    }

    @Test
    fun pkceMatchesRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val pkce = Pkce.generate(random)
        assertTrue(pkce.verifier.length in 43..128)
        assertFalse(pkce.verifier.contains('='))
    }

    @Test
    fun firstRegistrationUsesDynamicClientAndAgentName() {
        val pending = AuthorizationRequestBuilder.build("nouri-host", SiwcConfig.redirectUri(54321), random)
        val p = Url(pending.authorizationUrl).parameters
        assertEquals("dynamic_agent_client", p["client_id"])
        assertEquals("Nouri", p["agent_name_hint"])
        assertEquals("nouri-host", p["ext_agent_host_id"])
        assertEquals("S256", p["code_challenge_method"])
        assertEquals("https://api.openai.com/v1", p["resource"])
        assertEquals("http://127.0.0.1:54321/auth/callback", p["redirect_uri"])
        val scopes = p["scope"]!!.split(' ').toSet()
        assertTrue(scopes.containsAll(listOf("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct")))
        assertNull(pending.expectedClientId)
    }

    @Test
    fun reauthorisationReusesIssuedClientAndOmitsAgentName() {
        val reg = SiwcRegistration("sub-1", "me@example.com", "issued-client-9", "header.payload.sig")
        val p = Url(AuthorizationRequestBuilder.build("h", SiwcConfig.redirectUri(1), random, reg).authorizationUrl).parameters
        assertEquals("issued-client-9", p["client_id"])
        assertNull(p["agent_name_hint"])
        assertEquals("header.payload.sig", p["id_token_hint"])
        assertEquals("me@example.com", p["login_hint"])
    }

    @Test
    fun freshSecretsEveryAttempt() {
        val rnd = SecureRandomSource { Random.Default.nextBytes(it) }
        val a = AuthorizationRequestBuilder.build("h", SiwcConfig.redirectUri(1), rnd)
        val b = AuthorizationRequestBuilder.build("h", SiwcConfig.redirectUri(1), rnd)
        assertTrue(a.state != b.state && a.nonce != b.nonce && a.pkce.verifier != b.pkce.verifier)
    }

    private fun pending(expectedClient: String? = null) = AuthorizationRequestBuilder.build(
        "h", SiwcConfig.redirectUri(5000), random,
        registration = expectedClient?.let { SiwcRegistration("s", null, it) },
    )

    @Test
    fun callbackValidation() {
        val p = pending()
        assertIs<CallbackResult.Invalid>(CallbackParser.parse("http://127.0.0.1:5000/auth/callback?code=abc&state=wrong&client_id=c1", p))
        assertIs<CallbackResult.Invalid>(CallbackParser.parse("http://127.0.0.1:5000/auth/callback?code=abc&state=${p.state}", p), "first registration needs issued client id")
        assertIs<CallbackResult.Invalid>(CallbackParser.parse("http://127.0.0.1:5000/other?code=abc&state=${p.state}&client_id=c1", p))
        val denied = CallbackParser.parse("http://127.0.0.1:5000/auth/callback?error=access_denied&state=${p.state}", p)
        assertIs<CallbackResult.Denied>(denied)
        val ok = CallbackParser.parse("http://127.0.0.1:5000/auth/callback?code=abc&state=${p.state}&client_id=c1", p)
        assertEquals(CallbackResult.Success("abc", "c1"), ok)

        val re = pending("known")
        assertIs<CallbackResult.Invalid>(CallbackParser.parse("http://127.0.0.1:5000/auth/callback?code=x&state=${re.state}&client_id=other", re))
        assertEquals(CallbackResult.Success("x", "known"), CallbackParser.parse("http://127.0.0.1:5000/auth/callback?code=x&state=${re.state}", re))
    }

    private fun jwt(payload: String): String =
        Base64Url.encode("""{"alg":"RS256","typ":"JWT"}""".encodeToByteArray()) + "." +
            Base64Url.encode(payload.encodeToByteArray()) + ".c2ln"

    private class FakeClock(var now: Long) : Clock {
        override fun nowEpochMs() = now
    }

    private class Server {
        var tokenCalls = mutableListOf<Map<String, String>>()
        var revokeCalls = 0
        var scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        var refreshStatus = HttpStatusCode.OK
        var nonce = ""
        var rotation = 0
    }

    private fun client(server: Server) = HttpClient(
        MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(
                    """{"issuer":"https://auth.openai.com","authorization_endpoint":"https://auth.openai.com/api/accounts/authorize","token_endpoint":"https://auth.openai.com/api/accounts/oauth/token","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke","jwks_uri":"https://auth.openai.com/.well-known/jwks.json"}""",
                    HttpStatusCode.OK, json,
                )
                "/api/accounts/oauth/token" -> {
                    val form = (request.body as FormDataContent).formData
                    server.tokenCalls += form.names().associateWith { form[it]!! }
                    if (form["grant_type"] == "refresh_token" && server.refreshStatus != HttpStatusCode.OK) {
                        respond("""{"error":"invalid_grant"}""", server.refreshStatus, json)
                    } else {
                        server.rotation++
                        val id = jwt("""{"iss":"https://auth.openai.com","sub":"user-123","aud":["issued-1"],"exp":4000000000,"nonce":"${server.nonce}","email":"me@example.com"}""")
                        respond(
                            """{"access_token":"at-${server.rotation}","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-${server.rotation}","id_token":"$id","scope":"${server.scope}"}""",
                            HttpStatusCode.OK, json,
                        )
                    }
                }
                "/api/accounts/oauth/revoke" -> { server.revokeCalls++; respond("", HttpStatusCode.OK) }
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )

    private suspend fun signIn(server: Server, store: SiwcStore, clock: FakeClock): Pair<SiwcAuthManager, SignInResult> {
        val manager = SiwcAuthManager(client(server), store, random, clock, sleep = {})
        val pending = manager.begin(SiwcConfig.redirectUri(5555))
        server.nonce = pending.nonce
        val result = manager.complete(pending, "http://127.0.0.1:5555/auth/callback?code=the-code&state=${pending.state}&client_id=issued-1")
        return manager to result
    }

    @Test
    fun fullSignInExchangesCodeWithIssuedClientAndPkce() = runTest {
        val server = Server()
        val store = InMemorySiwcStore()
        val (manager, result) = signIn(server, store, FakeClock(1_000_000))
        val success = assertIs<SignInResult.Success>(result)
        assertTrue(success.session.planUsageAuthorized)
        val exchange = server.tokenCalls.single()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("issued-1", exchange["client_id"])
        assertEquals("http://127.0.0.1:5555/auth/callback", exchange["redirect_uri"])
        assertNotNull(exchange["code_verifier"])
        assertEquals("at-1", manager.token(false))
        assertEquals("issued-1", store.registrations().single().clientId)
        assertEquals("me@example.com", manager.account().label)
    }

    @Test
    fun missingPlanScopeMeansNoPlanUsage() = runTest {
        val server = Server().apply { scope = "openid profile email" }
        val store = InMemorySiwcStore()
        val (manager, result) = signIn(server, store, FakeClock(1_000_000))
        assertIs<SignInResult.SignedInWithoutPlanUsage>(result)
        assertNull(manager.token(false), "No plan-usage token without the scope")
        assertFalse(manager.account().connected)
    }

    @Test
    fun nonceMismatchFailsSignIn() = runTest {
        val server = Server()
        val manager = SiwcAuthManager(client(server), InMemorySiwcStore(), random, FakeClock(1_000_000), sleep = {})
        val pending = manager.begin(SiwcConfig.redirectUri(5555))
        server.nonce = "someone-elses-nonce"
        val result = manager.complete(pending, "http://127.0.0.1:5555/auth/callback?code=c&state=${pending.state}&client_id=issued-1")
        assertIs<SignInResult.Failed>(result)
    }

    @Test
    fun expiredTokenRefreshesAndRotates() = runTest {
        val server = Server()
        val store = InMemorySiwcStore()
        val clock = FakeClock(1_000_000)
        val (manager, _) = signIn(server, store, clock)
        clock.now += 3_600_000
        assertEquals("at-2", manager.token(false))
        val refresh = server.tokenCalls.last()
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("rt-1", refresh["refresh_token"])
        assertEquals("rt-2", store.session()!!.refreshToken)
    }

    @Test
    fun rejectedRefreshClearsSession() = runTest {
        val server = Server()
        val store = InMemorySiwcStore()
        val clock = FakeClock(1_000_000)
        val (manager, _) = signIn(server, store, clock)
        server.refreshStatus = HttpStatusCode.BadRequest
        assertNull(manager.token(forceRefresh = true))
        assertNull(store.session())
        assertEquals(1, store.registrations().size, "registration kept for re-sign-in")
    }

    @Test
    fun signOutRevokesAndClears() = runTest {
        val server = Server()
        val store = InMemorySiwcStore()
        val (manager, _) = signIn(server, store, FakeClock(1_000_000))
        val result = manager.signOut()
        assertTrue(result.remoteRevocationConfirmed)
        assertEquals(1, server.revokeCalls)
        assertNull(store.session())
        assertNull(manager.token(false))
    }

    @Test
    fun hostIdIsStable() = runTest {
        val store = InMemorySiwcStore()
        val m = SiwcAuthManager(client(Server()), store, SecureRandomSource { Random.Default.nextBytes(it) }, FakeClock(0), sleep = {})
        assertEquals(m.hostId(), m.hostId())
    }
}
