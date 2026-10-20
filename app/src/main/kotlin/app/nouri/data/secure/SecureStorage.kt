package app.nouri.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.nouri.core.auth.SiwcRegistration
import app.nouri.core.auth.SiwcSession
import app.nouri.core.auth.SiwcStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Symmetric encryption for small secrets. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore. Blob layout:
 * [ivLength][iv][ciphertext+tag].
 */
class KeystoreCipher(private val alias: String = "nouri_secrets_v1") : SecretCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val ivLength = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
    }

    /** Deletes the key; every blob encrypted with it becomes unreadable. */
    fun destroyKey() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** One encrypted file per secret in app-private storage (excluded from backups). Writes are atomic. */
class EncryptedBlobStore(private val directory: File, private val cipher: SecretCipher) {
    private val mutex = Mutex()

    suspend fun get(name: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(directory, "$name.bin")
            if (!file.exists()) return@withLock null
            runCatching { cipher.decrypt(file.readBytes()) }.getOrNull()
        }
    }

    suspend fun put(name: String, value: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.mkdirs()
            val tmp = File(directory, "$name.tmp")
            tmp.writeBytes(cipher.encrypt(value))
            if (!tmp.renameTo(File(directory, "$name.bin"))) {
                File(directory, "$name.bin").writeBytes(tmp.readBytes())
                tmp.delete()
            }
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) { mutex.withLock { File(directory, "$name.bin").delete() } }

    suspend fun wipe() = withContext(Dispatchers.IO) { mutex.withLock { directory.listFiles()?.forEach { it.delete() } } }
}

/** Sign in with ChatGPT tokens, kept on this device only (SIWC terms require local, user-controlled storage). */
class AndroidSiwcStore(private val blobs: EncryptedBlobStore) : SiwcStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val registrations = ListSerializer(SiwcRegistration.serializer())

    override suspend fun hostId(): String? = blobs.get("siwc_host")?.decodeToString()
    override suspend fun saveHostId(hostId: String) = blobs.put("siwc_host", hostId.encodeToByteArray())

    override suspend fun session(): SiwcSession? =
        blobs.get("siwc_session")?.let { runCatching { json.decodeFromString(SiwcSession.serializer(), it.decodeToString()) }.getOrNull() }

    override suspend fun saveSession(session: SiwcSession) =
        blobs.put("siwc_session", json.encodeToString(SiwcSession.serializer(), session).encodeToByteArray())

    override suspend fun clearSession() = blobs.delete("siwc_session").let { }

    override suspend fun registrations(): List<SiwcRegistration> =
        blobs.get("siwc_registrations")?.let { runCatching { json.decodeFromString(registrations, it.decodeToString()) }.getOrNull() }.orEmpty()

    override suspend fun saveRegistration(registration: SiwcRegistration) {
        val updated = registrations().filter { it.accountSubject != registration.accountSubject } + registration
        blobs.put("siwc_registrations", json.encodeToString(registrations, updated).encodeToByteArray())
    }
}

/** The optional OpenAI API key, encrypted like the OAuth tokens. */
class ApiKeyStore(private val blobs: EncryptedBlobStore) {
    suspend fun get(): String? = blobs.get("openai_api_key")?.decodeToString()?.takeIf { it.isNotBlank() }
    suspend fun set(key: String) = blobs.put("openai_api_key", key.trim().encodeToByteArray())
    suspend fun clear() = blobs.delete("openai_api_key").let { }
}
