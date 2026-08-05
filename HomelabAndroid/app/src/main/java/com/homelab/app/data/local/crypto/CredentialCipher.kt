package com.homelab.app.data.local.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.homelab.app.util.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64

/**
 * Encrypts the credential columns of [com.homelab.app.data.local.entity.ServiceInstanceEntity]
 * before they reach the Room database.
 *
 * Upstream stored API keys, bearer tokens and passwords as plaintext columns. App-private storage
 * is reasonable protection on a non-rooted device, but "reasonable" is the wrong bar for a Komodo
 * API key + secret, a Plex token and a Home Assistant long-lived token — any one of which is
 * effectively a credential for the whole homelab.
 *
 * Design notes, all deliberate:
 *
 * - **AES-256-GCM with the key in the Android Keystore.** The key material never enters app
 *   memory; it lives in the TEE (or StrongBox where available, which is the case on both target
 *   devices). This is why we do not use SQLCipher: a whole-database passphrase has to be stored
 *   somewhere, and that somewhere would be the Keystore anyway — so SQLCipher would add ~5 MB of
 *   native libraries per ABI to arrive at the same trust root.
 *
 * - **[KeyGenParameterSpec.Builder.setUserAuthenticationRequired] is deliberately false.** An
 *   auth-bound key cannot be used while the device is locked, which would break the home-screen
 *   widget's background refresh — the entire point of the app. Biometric gating belongs on the UI
 *   that *displays* credentials, not on the key that reads them.
 *
 * - **Values carry a [PREFIX].** Anything without it is treated as legacy plaintext and passed
 *   through unchanged, so a database written by upstream still reads correctly and upgrades itself
 *   on the next write. That removes the need for a schema migration entirely.
 *
 * - **Decryption failure returns null rather than throwing.** If the key is ever invalidated
 *   (device wipe, restore onto different hardware), the app should prompt for the credential again,
 *   not crash on launch.
 */
@Singleton
class CredentialCipher @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    /** Returns [value] encrypted and [PREFIX]-tagged. Blank and null values pass through. */
    fun encrypt(value: String?): String? {
        if (value.isNullOrBlank()) return value
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + ciphertext
            PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (error: Exception) {
            // Failing closed here would silently drop the user's credential on save, which is worse
            // than storing it as upstream already did. Log loudly and fall back to plaintext.
            Logger.e(TAG, "Credential encryption failed, storing as-is: ${error.message}")
            value
        }
    }

    /**
     * Reverses [encrypt]. Values without [PREFIX] are legacy plaintext and returned unchanged.
     * Returns null if the ciphertext cannot be read, so callers re-prompt instead of crashing.
     */
    fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return value
        if (!value.startsWith(PREFIX)) return value

        return try {
            val packed = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            require(packed.size > IV_LENGTH_BYTES) { "ciphertext too short" }

            val iv = packed.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = packed.copyOfRange(IV_LENGTH_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Exception) {
            Logger.e(TAG, "Credential decryption failed — credential must be re-entered: ${error.message}")
            null
        }
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)

        // StrongBox is a discrete security chip — better, but not on every device, and the only way
        // to find out is to ask and handle the refusal.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(keySpec(strongBox = true))
                return generator.generateKey()
            } catch (error: StrongBoxUnavailableException) {
                Logger.w(TAG, "StrongBox unavailable, falling back to TEE-backed key")
            }
        }

        generator.init(keySpec(strongBox = false))
        return generator.generateKey()
    }

    private fun keySpec(strongBox: Boolean): KeyGenParameterSpec {
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        return KeyGenParameterSpec.Builder(KEY_ALIAS, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // False on purpose — see the class doc. The widget must refresh while locked.
            .setUserAuthenticationRequired(false)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
    }

    private companion object {
        const val TAG = "CredentialCipher"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "kinderdash.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128

        /** Marks a value as ciphertext. Absent => legacy plaintext from upstream. */
        const val PREFIX = "enc:v1:"
    }
}
