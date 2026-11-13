package co.agrovision.core.datastore

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JWT de acceso del productor.
 *
 * `SharedPreferences` y no DataStore porque lo lee el interceptor de OkHttp, que
 * no es una función suspendida: así no hace falta `runBlocking` en cada petición.
 *
 * El nombre del archivo es el que ya excluyen `backup_rules.xml` y
 * `data_extraction_rules.xml`: el token nunca sale del dispositivo (§15.3).
 *
 * Cifrado en reposo con una clave AES-GCM del Android Keystore (API 23+), sin
 * `security-crypto`: la versión alfa que admite minSdk 22 falla en el Keystore de
 * algunos fabricantes, y un fallo ahí deja al productor sin poder iniciar sesión.
 * Por eso cualquier fallo del Keystore **degrada**, nunca bloquea:
 *  - API 22 (sin Keystore simétrico) o Keystore roto al guardar → se guarda como
 *    antes, en claro dentro del almacenamiento privado.
 *  - No se puede descifrar → se borra y el productor vuelve a entrar con OTP, lo
 *    mismo que ya ocurre cuando el token vence (dura 30 minutos).
 * Un token en claro de una versión anterior se sigue leyendo y se recifra.
 */
@Singleton
class SessionTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** El interceptor lo lee en cada petición: se descifra una vez y se guarda en memoria. */
    @Volatile private var cached: String? = null

    fun accessToken(): String? {
        cached?.let { return it }
        val encrypted = preferences.getString(KEY_ENCRYPTED_TOKEN, null)
        val token = if (encrypted != null) {
            decryptOrNull(encrypted) ?: run {
                clear()
                return null
            }
        } else {
            val legacy = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return null
            save(legacy)
            legacy
        }
        cached = token
        return token
    }

    fun save(accessToken: String) {
        cached = accessToken
        val encrypted = encryptOrNull(accessToken)
        preferences.edit().apply {
            if (encrypted != null) {
                putString(KEY_ENCRYPTED_TOKEN, encrypted)
                remove(KEY_ACCESS_TOKEN)
            } else {
                putString(KEY_ACCESS_TOKEN, accessToken)
                remove(KEY_ENCRYPTED_TOKEN)
            }
        }.apply()
    }

    fun clear() {
        cached = null
        preferences.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_ENCRYPTED_TOKEN).apply()
    }

    private fun encryptOrNull(plain: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun decryptOrNull(stored: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey(),
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
            )
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val FILE = "agrovision_tokens"
        /** Formato heredado, en claro. Solo se lee para migrarlo, o se escribe si no hay Keystore. */
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ENCRYPTED_TOKEN = "access_token_v2"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "agrovision_session_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
