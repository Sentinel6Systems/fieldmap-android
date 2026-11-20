package io.github.sentinel6systems.fieldmap.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a short secret (the NetBird setup key, the mesh radio channel) so that only this app on
 * this device can read it: encrypted with a key that lives in the device's key store and cannot
 * be read out of it. Each secret has a key of its own (`alias`), so that forgetting one leaves
 * the others readable.
 */
object Secret {
    private const val KEY = "fieldmap-secret"
    const val MESH = "fieldmap-mesh"
    private const val STORE = "AndroidKeyStore"
    private const val CIPHER = "AES/GCM/NoPadding"

    private fun key(make: Boolean, alias: String): SecretKey? {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!make) return null
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    /** The secret in a form that can be written down on the device; null when the key store fails. */
    fun seal(text: String, alias: String = KEY): String? = try {
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key(true, alias)) }
        Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    } catch (e: Exception) { null }

    /** The secret back; null when it cannot be read (another device, a cleared key store). */
    fun open(sealed: String, alias: String = KEY): String? = try {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.DECRYPT_MODE, key(false, alias), GCMParameterSpec(128, all, 0, 12)) }
        String(cipher.doFinal(all, 12, all.size - 12))
    } catch (e: Exception) { null }

    /** Throws the key away: what was sealed with it can never be read again. */
    fun forget(alias: String = KEY) {
        try { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(alias) } catch (e: Exception) { /* nothing to forget */ }
    }
}
