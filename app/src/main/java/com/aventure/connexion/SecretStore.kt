package com.aventure.connexion

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val email: String, val password: String)

/** Une adresse enregistrée. L'identifiant sert à la retrouver quand on la modifie ou la supprime. */
data class Account(
    val id: String,
    val email: String,
    val password: String,
    /** Heure (en millisecondes) de la dernière connexion, 0 si aucune connexion de moins de 24 h. */
    val lastLogin: Long = 0L,
)

/**
 * Garde les adresses e-mail (en clair) et leurs mots de passe Proton (chiffrés en AES-GCM avec une clé
 * du Keystore Android : la clé elle-même ne sort jamais du téléphone).
 */
object SecretStore {
    private const val PREFS = "connexion"
    /** Au-delà de cette durée (24 h), l'heure de dernière connexion n'est plus gardée ni affichée. */
    const val RECENT_LOGIN_MS = 24L * 60 * 60 * 1000

    private const val KEY_ACCOUNTS = "accounts"

    // Ancien format (une seule adresse), converti automatiquement au premier lancement.
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "connexion_proton"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SEPARATOR = ":"

    fun loadAccounts(context: Context): List<Account> {
        val prefs = prefs(context)
        prefs.getString(KEY_ACCOUNTS, null)?.let { return parse(it) }

        // Première ouverture après la mise à jour : on reprend l'adresse enregistrée avant.
        val email = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val password = prefs.getString(KEY_PASSWORD, null)?.let { decrypt(it) }.orEmpty()
        val migrated = listOf(Account(UUID.randomUUID().toString(), email, password))
        saveAccounts(context, migrated)
        prefs.edit().remove(KEY_EMAIL).remove(KEY_PASSWORD).apply()
        return migrated
    }

    fun saveAccounts(context: Context, accounts: List<Account>) {
        val array = JSONArray()
        for (account in accounts) {
            array.put(
                JSONObject()
                    .put("id", account.id)
                    .put("email", account.email.trim())
                    .put("lastLogin", account.lastLogin)
                    .put("password", if (account.password.isEmpty()) "" else encrypt(account.password)),
            )
        }
        prefs(context).edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    private fun parse(json: String): List<Account> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Account(
                id = item.getString("id"),
                email = item.getString("email"),
                password = item.optString("password").takeIf { it.isNotEmpty() }?.let { decrypt(it) }.orEmpty(),
                // Une connexion de plus de 24 h est oubliée dès la lecture (et donc à la prochaine sauvegarde).
                lastLogin = item.optLong("lastLogin", 0L)
                    .takeIf { System.currentTimeMillis() - it < RECENT_LOGIN_MS } ?: 0L,
            )
        }
    } catch (e: JSONException) {
        emptyList()
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
