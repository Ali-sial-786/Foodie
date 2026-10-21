package app.nouri.ai

import app.nouri.core.auth.LoopbackRedirectReceiver
import app.nouri.core.auth.SignInResult
import app.nouri.core.auth.SiwcAuthManager
import app.nouri.core.auth.SiwcConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Receives the OAuth redirect on http://127.0.0.1:<port>/auth/callback. The port
 * is assigned by the OS for each attempt and the socket only accepts loopback
 * connections. The response page links back to the app because Android 15+ does
 * not bring the app to the foreground automatically.
 */
class AndroidLoopbackReceiver(private val returnLink: String = "nouri://auth-complete") : LoopbackRedirectReceiver {
    @Volatile private var server: ServerSocket? = null

    override suspend fun start(): Int {
        val socket = ServerSocket(0, 4, InetAddress.getByName(SiwcConfig.REDIRECT_HOST))
        server = socket
        return socket.localPort
    }

    override suspend fun awaitCallback(timeoutMs: Long): String? = suspendCancellableCoroutine { cont ->
        val socket = server
        if (socket == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { stop() }
        thread(name = "nouri-oauth-callback", isDaemon = true) {
            val result = runCatching { acceptLoop(socket, timeoutMs) }.getOrNull()
            stop()
            if (cont.isActive) cont.resume(result)
        }
    }

    private fun acceptLoop(socket: ServerSocket, timeoutMs: Long): String? {
        socket.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketTimeoutException) {
                return null
            }
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val requestLine = reader.readLine() ?: return@use
                val target = requestLine.split(' ').getOrNull(1) ?: return@use
                val out = c.getOutputStream()
                if (!target.startsWith(SiwcConfig.REDIRECT_PATH)) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@use
                }
                // The app validates state and exchanges the code afterwards; this page only says what happened in the browser.
                val page = resultPage(denied = Regex("[?&]error=").containsMatchIn(target))
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                        "Content-Length: ${page.toByteArray().size}\r\nConnection: close\r\n\r\n$page").toByteArray(),
                )
                out.flush()
                return "http://${SiwcConfig.REDIRECT_HOST}:${socket.localPort}$target"
            }
        }
        return null
    }

    private fun resultPage(denied: Boolean): String {
        val heading = if (denied) "Sign-in wasn't completed" else "Almost done"
        val body = if (denied) "No access was granted. You can return to Nouri and try again." else "Return to Nouri to finish connecting."
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Nouri</title>
<style>body{font-family:sans-serif;background:#FBF8F4;color:#1F1B16;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
.c{text-align:center;padding:24px}a{display:inline-block;margin-top:16px;background:#2F6B4F;color:#fff;padding:14px 24px;border-radius:24px;text-decoration:none}</style></head>
<body><div class="c"><h2>$heading</h2><p>$body</p><a href="$returnLink">Return to Nouri</a></div></body></html>"""
    }

    override fun stop() {
        runCatching { server?.close() }
        server = null
    }
}

/**
 * Runs the browser-based Sign in with ChatGPT flow end to end.
 *
 * The browser is in front when the callback arrives, and Android 15+ blocks network
 * requests from apps in the background (they fail with UnknownHostException). So the
 * callback is received in the background (loopback only), and the code exchange waits
 * until [awaitForeground] reports that the user is back in the app.
 */
class ChatGptSignIn(
    private val manager: SiwcAuthManager,
    private val receiverFactory: () -> LoopbackRedirectReceiver = { AndroidLoopbackReceiver() },
    private val awaitForeground: suspend () -> Unit = {},
) {
    suspend fun signIn(openBrowser: (String) -> Unit, timeoutMs: Long = 5 * 60_000L): SignInResult {
        val receiver = receiverFactory()
        return try {
            val port = receiver.start()
            val pending = manager.begin(SiwcConfig.redirectUri(port))
            openBrowser(pending.authorizationUrl)
            val callback = receiver.awaitCallback(timeoutMs) ?: return SignInResult.Cancelled("Sign-in was closed or timed out")
            receiver.stop()
            withTimeoutOrNull(RETURN_TIMEOUT_MS) { awaitForeground() }
                ?: return SignInResult.Failed("Sign-in wasn't finished because Nouri wasn't reopened. Please try again.", retryable = true)
            val first = manager.complete(pending, callback)
            if (first is SignInResult.Failed && first.unreachable) {
                // The code was never sent, so it is still valid: give the network a moment and retry once.
                delay(RETRY_DELAY_MS)
                awaitForeground()
                manager.complete(pending, callback)
            } else {
                first
            }
        } catch (e: java.io.IOException) {
            SignInResult.Failed("Couldn't start sign-in: ${e.message}", retryable = true)
        } finally {
            receiver.stop()
        }
    }

    private companion object {
        const val RETURN_TIMEOUT_MS = 10 * 60_000L
        const val RETRY_DELAY_MS = 1_500L
    }
}
