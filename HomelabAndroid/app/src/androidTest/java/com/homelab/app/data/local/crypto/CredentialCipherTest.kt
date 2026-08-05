package com.homelab.app.data.local.crypto

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test — [CredentialCipher] uses the Android Keystore, so it needs a real device or
 * emulator and cannot run as a JVM unit test.
 *
 *   ./gradlew connectedDebugAndroidTest
 *
 * This is the only security-relevant code in the fork, so the cases below deliberately cover the
 * properties we actually depend on, not just the happy path: that ciphertext is authenticated,
 * that IVs are not reused, and that legacy plaintext still reads.
 */
@RunWith(AndroidJUnit4::class)
class CredentialCipherTest {

    private lateinit var cipher: CredentialCipher

    @Before
    fun setUp() {
        cipher = CredentialCipher()
    }

    @Test
    fun roundTrip_returnsOriginalValue() {
        val secret = "komodo_api_key_9f3b2c1d-4e5a-6789-abcd-ef0123456789"

        val encrypted = cipher.encrypt(secret)
        assertNotEquals("value must not be stored as-is", secret, encrypted)

        assertEquals(secret, cipher.decrypt(encrypted))
    }

    @Test
    fun encrypted_valueIsTaggedAndOpaque() {
        val secret = "plex-token-abcdef"

        val encrypted = cipher.encrypt(secret)!!

        assertTrue("ciphertext must be tagged so legacy values are distinguishable", encrypted.startsWith("enc:v1:"))
        assertTrue("plaintext must not survive in the stored value", !encrypted.contains(secret))
    }

    @Test
    fun sameInput_producesDifferentCiphertext() {
        // GCM must never reuse an IV for the same key. Two encryptions of one value therefore have
        // to differ — if they don't, the IV is fixed and the mode is broken.
        val secret = "ha_long_lived_token"

        val first = cipher.encrypt(secret)
        val second = cipher.encrypt(secret)

        assertNotEquals(first, second)
        assertEquals(secret, cipher.decrypt(first))
        assertEquals(secret, cipher.decrypt(second))
    }

    @Test
    fun tamperedCiphertext_failsClosed() {
        // The GCM auth tag is the reason we can trust a value read back off disk. Flipping a bit
        // must be detected and must surface as null (re-prompt), never as garbage plaintext.
        val encrypted = cipher.encrypt("sonarr-api-key")!!
        val raw = Base64.decode(encrypted.removePrefix("enc:v1:"), Base64.NO_WRAP)

        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val tampered = "enc:v1:" + Base64.encodeToString(raw, Base64.NO_WRAP)

        assertNull(cipher.decrypt(tampered))
    }

    @Test
    fun malformedCiphertext_failsClosed() {
        assertNull(cipher.decrypt("enc:v1:not-valid-base64!!!"))
        assertNull(cipher.decrypt("enc:v1:"))
    }

    @Test
    fun legacyPlaintext_passesThroughUnchanged() {
        // Rows written by upstream have no tag. They must keep working, which is what lets us skip
        // a Room migration entirely.
        val legacy = "plaintext-token-from-upstream"

        assertEquals(legacy, cipher.decrypt(legacy))
    }

    @Test
    fun blankAndNullValues_passThrough() {
        assertNull(cipher.encrypt(null))
        assertNull(cipher.decrypt(null))
        assertEquals("", cipher.encrypt(""))
        assertEquals("", cipher.decrypt(""))
        assertEquals("   ", cipher.encrypt("   "))
    }

    @Test
    fun survivesNewCipherInstance() {
        // The key lives in the Keystore, not in the object. A fresh instance — as happens on every
        // app launch — must still read what a previous one wrote.
        val secret = "uptime-kuma-slug-secret"
        val encrypted = CredentialCipher().encrypt(secret)

        assertEquals(secret, CredentialCipher().decrypt(encrypted))
    }
}
