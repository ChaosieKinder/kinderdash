package com.homelab.app.data.repository

import com.homelab.app.data.local.crypto.CredentialCipher
import io.mockk.every
import io.mockk.mockk

/**
 * An identity stand-in for [CredentialCipher] in JVM unit tests.
 *
 * The real cipher is backed by the Android Keystore and cannot run off-device — it is covered by
 * `CredentialCipherTest` in `androidTest` instead. These repository tests are about mapping and
 * migration logic, so a passthrough keeps their assertions about stored values readable rather than
 * forcing every expectation to be ciphertext.
 */
internal fun passthroughCredentialCipher(): CredentialCipher = mockk {
    every { encrypt(any()) } answers { firstArg() }
    every { decrypt(any()) } answers { firstArg() }
}
