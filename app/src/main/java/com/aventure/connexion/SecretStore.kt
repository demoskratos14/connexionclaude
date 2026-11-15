package com.aventure.connexion

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val email: String, val password: String)

/**
 * Garde l'adresse e-mail (en clair) et le mot de passe Proton (chiffré en AES-GCM avec une clé
 * du Keystore Android : la clé elle-même ne sort jamais du téléphone).
 */
object SecretStore {
    private const val PREFS = "connexion"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "connexion_proton"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SEPARATOR = ":"

    fun load(context: Context): Credentials? {
        val prefs = prefs(context)
        val email = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.let { decrypt(it) }.orEmpty()
        return Credentials(email, password)
    }

    fun save(context: Context, credentials: Credentials) {
        val encrypted = if (credentials.password.isEmpty()) null else encrypt(credentials.password)
        prefs(context).edit()
            .putString(KEY_EMAIL, credentials.email.trim())
            .putString(KEY_PASSWORD, encrypted)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + SEPARATOR + encode(data)
    }

    /** Renvoie null si le texte est illisible (clé perdue après une réinitialisation, par exemple). */
    private fun decrypt(stored: String): String? = try {
        val (iv, data) = stored.split(SEPARATOR).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
