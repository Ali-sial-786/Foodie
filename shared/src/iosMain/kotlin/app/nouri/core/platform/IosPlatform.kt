package app.nouri.core.platform

import app.nouri.core.app.NouriAppModel
import app.nouri.core.app.SecretStore
import app.nouri.core.app.TextStorage
import app.nouri.core.auth.SecureRandomSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

/** iOS entry points for the Swift app: the network client, secure randomness and the app model. */
@OptIn(ExperimentalForeignApi::class)
object IosPlatform {
    fun httpClient(): HttpClient = HttpClient(Darwin) { expectSuccess = false }

    val secureRandom = SecureRandomSource { size ->
        val bytes = ByteArray(size)
        if (size > 0) {
            val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
            check(status == 0) { "Secure random bytes unavailable ($status)" }
        }
        bytes
    }

    fun makeModel(storage: TextStorage, secrets: SecretStore, usdaTsv: String?, libraryJson: String?, appVersion: String): NouriAppModel =
        NouriAppModel(storage, secrets, httpClient(), secureRandom, usdaTsv, libraryJson, userAgent = "Nouri/$appVersion (iOS)")

    /** For tests and previews: no network, demo AI only. */
    fun makeOfflineModel(storage: TextStorage, secrets: SecretStore, usdaTsv: String?, libraryJson: String?): NouriAppModel =
        NouriAppModel(storage, secrets, null, secureRandom, usdaTsv, libraryJson, mockLatencyMs = 0)

    /** Photo bytes from Swift (`Data` bridges to NSData). */
    fun bytes(data: NSData): ByteArray {
        val size = data.length.toInt()
        val out = ByteArray(size)
        if (size > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }
}
