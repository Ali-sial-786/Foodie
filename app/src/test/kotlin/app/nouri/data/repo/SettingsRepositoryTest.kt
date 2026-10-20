package app.nouri.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nouri.core.ai.ProviderKind
import app.nouri.data.prefs.ImageRetention
import app.nouri.testing.testContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private val c = testContainer()

    @Test
    fun defaultsArePrivateAndUseTheDemoProvider() = runBlocking {
        val s = c.settings.current()
        assertFalse(s.onboardingComplete)
        assertEquals(ProviderKind.MOCK, s.ai.selected)
        assertFalse("photos are never sent without consent", s.ai.photoConsent)
        assertFalse("paid fallback is opt-in", s.ai.allowApiKeyFallback)
        assertEquals(ImageRetention.DELETE_AFTER_ANALYSIS, s.privacy.imageRetention)
    }

    @Test
    fun updatesPersistAndResetClearsEverything() = runBlocking {
        c.settings.update { it.copy(onboardingComplete = true, ai = it.ai.copy(photoConsent = true)) }
        assertTrue(c.settings.current().onboardingComplete)
        assertTrue(c.settings.current().ai.photoConsent)
        c.settings.reset()
        assertFalse(c.settings.current().onboardingComplete)
    }

    @Test
    fun apiKeysAreStoredEncryptedAndCanBeRemoved() = runBlocking {
        c.apiKeys.set("sk-test-not-a-real-key-1234")
        assertEquals("sk-test-not-a-real-key-1234", c.apiKeys.get())
        val raw = java.io.File(c.context.noBackupFilesDir, "secrets").walk().filter { it.isFile }.map { it.readBytes().decodeToString() }.joinToString()
        assertFalse("key must not be stored in plain text", raw.contains("sk-test-not-a-real-key"))
        c.apiKeys.clear()
        assertEquals(null, c.apiKeys.get())
    }
}
