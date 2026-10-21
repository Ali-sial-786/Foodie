package app.nouri.ai

import app.nouri.core.auth.Base64Url
import app.nouri.core.auth.Clock
import app.nouri.core.auth.InMemorySiwcStore
import app.nouri.core.auth.LoopbackRedirectReceiver
import app.nouri.core.auth.SecureRandomSource
import app.nouri.core.auth.SignInResult
import app.nouri.core.auth.SiwcAuthManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import kotlin.random.Random

/** The Sign in with ChatGPT flow on Android: the loopback listener and when the code is exchanged. */
class ChatGptSignInTest {
    private val random = SecureRandomSource { Random(7).nextBytes(it) }

    /** OpenAI's endpoints; the nonce comes from the authorize URL the "browser" opened. */
    private class Auth {
        var nonce = ""
        var tokenCalls = 0
        /** The OS blocks the request before it leaves the phone (a backgrounded app on Android 15+). */
        var blocked = false

        val http = HttpClient(
            MockEngine { request ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when (request.url.encodedPath) {
                    "/.well-known/openid-configuration" -> respond(
                        """{"issuer":"https://auth.openai.com","authorization_endpoint":"https://auth.openai.com/api/accounts/authorize","token_endpoint":"https://auth.openai.com/api/accounts/oauth/token","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke","jwks_uri":"https://auth.openai.com/.well-known/jwks.json"}""",
                        HttpStatusCode.OK, json,
                    )
                    "/api/accounts/oauth/token" -> {
                        if (blocked) throw IllegalStateException("Unable to resolve host \"auth.openai.com\"")
                        tokenCalls++
                        val id = jwt("""{"iss":"https://auth.openai.com","sub":"user-123","aud":["issued-1"],"exp":4000000000,"nonce":"$nonce","email":"me@example.com"}""")
                        respond(
                            """{"access_token":"at","token_type":"Bearer","expires_in":3600,"refresh_token":"rt","id_token":"$id","scope":"openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"}""",
                            HttpStatusCode.OK, json,
                        )
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )

        private fun jwt(payload: String): String =
            Base64Url.encode("""{"alg":"RS256","typ":"JWT"}""".encodeToByteArray()) + "." + Base64Url.encode(payload.encodeToByteArray()) + ".c2ln"
    }

    private class FakeReceiver(private val callback: suspend () -> String?) : LoopbackRedirectReceiver {
        var stopped = false
        override suspend fun start() = 5555
        override suspend fun awaitCallback(timeoutMs: Long) = callback()
        override fun stop() { stopped = true }
    }

    private class FakeKeepAlive(private val available: Boolean) : SignInKeepAlive {
        var stopped = false
        override fun start() = available
        override suspend fun stop() { stopped = true }
    }

    private fun manager(auth: Auth) = SiwcAuthManager(auth.http, InMemorySiwcStore(), random, Clock { 1_000_000L }, sleep = {})

    /** What the browser sends back once the user allows access. */
    private fun approve(authorizeUrl: String, auth: Auth): String {
        val params = Url(authorizeUrl).parameters
        auth.nonce = params["nonce"]!!
        return "http://127.0.0.1:5555/auth/callback?code=the-code&state=${params["state"]}&client_id=issued-1"
    }

    @Test
    fun withTheKeepAliveServiceTheCodeIsExchangedWithoutReopeningTheApp() = runTest {
        val auth = Auth()
        val keepAlive = FakeKeepAlive(available = true)
        var opened = ""
        val receiver = FakeReceiver { approve(opened, auth) }
        val signIn = ChatGptSignIn(manager(auth), { receiver }, keepAlive) { error("must not wait for the app to be reopened") }

        val result = signIn.signIn(openBrowser = { opened = it })

        assertTrue("$result", result is SignInResult.Success)
        assertEquals(1, auth.tokenCalls)
        assertTrue("the service is stopped once sign-in is over", keepAlive.stopped)
        assertTrue(receiver.stopped)
    }

    @Test
    fun withoutTheServiceTheExchangeWaitsUntilTheAppIsBack() = runTest {
        val auth = Auth()
        val reachedWait = CompletableDeferred<Unit>()
        val back = CompletableDeferred<Unit>()
        var opened = ""
        val signIn = ChatGptSignIn(manager(auth), { FakeReceiver { approve(opened, auth) } }, SignInKeepAlive.None) {
            reachedWait.complete(Unit)
            back.await()
        }

        val result = async { signIn.signIn(openBrowser = { opened = it }) }
        reachedWait.await()
        assertEquals("nothing is sent while the app is in the background", 0, auth.tokenCalls)
        assertFalse(result.isCompleted)

        back.complete(Unit)
        assertTrue(result.await() is SignInResult.Success)
        assertEquals(1, auth.tokenCalls)
    }

    @Test
    fun ifTheNetworkIsStillBlockedTheSameCodeIsUsedOnceTheAppIsBack() = runTest {
        val auth = Auth().apply { blocked = true }
        val keepAlive = FakeKeepAlive(available = true)
        var opened = ""
        val signIn = ChatGptSignIn(manager(auth), { FakeReceiver { approve(opened, auth) } }, keepAlive) { auth.blocked = false }

        val result = signIn.signIn(openBrowser = { opened = it })

        assertTrue("$result", result is SignInResult.Success)
        assertEquals(1, auth.tokenCalls)
        assertTrue(keepAlive.stopped)
    }

    @Test
    fun aTimedOutSignInStillStopsTheService() = runTest {
        val keepAlive = FakeKeepAlive(available = true)
        val signIn = ChatGptSignIn(manager(Auth()), { FakeReceiver { null } }, keepAlive)

        val result = signIn.signIn(openBrowser = {})

        assertTrue(result is SignInResult.Cancelled)
        assertTrue(keepAlive.stopped)
    }

    @Test
    fun theListenerAnswersTheBrowserEvenAfterAnIdleConnection() = runBlocking {
        val receiver = AndroidLoopbackReceiver(clientReadTimeoutMs = 300)
        val port = receiver.start()
        // The listener has to run while this thread blocks on the sockets below.
        val callback = async(Dispatchers.IO) { receiver.awaitCallback(10_000) }
        val loopback = InetAddress.getByName("127.0.0.1")

        // Browsers sometimes open a spare connection and never send anything on it.
        Socket(loopback, port).use { _ ->
            Socket(loopback, port).use { browser ->
                browser.soTimeout = 5_000
                browser.getOutputStream().write("GET /auth/callback?code=c&state=s HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                assertEquals("HTTP/1.1 200 OK", browser.getInputStream().bufferedReader().readLine())
            }
        }
        assertEquals("http://127.0.0.1:$port/auth/callback?code=c&state=s", callback.await())
    }
}
