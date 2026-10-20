package app.nouri.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.nouri.AppContainer
import app.nouri.data.db.NouriDatabase
import app.nouri.data.prefs.SettingsRepository
import app.nouri.data.secure.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.UUID

/** Reversible stand-in for the Keystore cipher, which Robolectric doesn't provide. Tests only. */
class XorTestCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun decrypt(blob: ByteArray) = encrypt(blob)
}

/** A fully wired container with an in-memory database, a throwaway DataStore and no simulated latency. */
fun testContainer(context: Context = ApplicationProvider.getApplicationContext()): AppContainer {
    val dir = File(context.cacheDir, "test-${UUID.randomUUID()}").apply { mkdirs() }
    val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(dir, "settings.preferences_pb") }
    return AppContainer(
        context = context,
        database = NouriDatabase.inMemory(context),
        settings = SettingsRepository(dataStore),
        secretCipher = { XorTestCipher() },
        mockLatencyMs = 0,
    )
}
