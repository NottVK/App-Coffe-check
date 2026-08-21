package co.agrovision.ml.registry

import android.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom

/**
 * Pruebas de la frontera de confianza del subsistema de modelos.
 *
 * Un verificador de firma que devuelve `true` de más no falla ruidosamente:
 * simplemente deja de proteger, y nadie se entera hasta que alguien publica pesos
 * que SmartClic no firmó. Por eso los casos negativos —firma de otra clave, byte
 * alterado, firma truncada— pesan más aquí que el camino feliz.
 */
@RunWith(RobolectricTestRunner::class)
class Ed25519SignatureVerifierTest {

    private val keyPair = generateKeyPair()
    private val verifier = Ed25519SignatureVerifier(keyPair.publicBase64)

    // ── Camino feliz ─────────────────────────────────────────────────────────

    @Test
    fun `una firma legitima sobre el mensaje correcto se acepta`() {
        val message = "sha256-del-conjunto-de-artefactos".toByteArray()

        assertTrue(verifier.verify(message, keyPair.sign(message)))
    }

    // ── Los casos que de verdad importan ─────────────────────────────────────

    @Test
    fun `una firma valida de OTRA clave se rechaza`() {
        // El escenario real: alguien controla el CDN y sirve su propio artefacto
        // correctamente firmado con SU clave. La firma es criptográficamente
        // válida; simplemente no es la nuestra.
        val impostor = generateKeyPair()
        val message = "modelo-malicioso".toByteArray()

        assertFalse(verifier.verify(message, impostor.sign(message)))
    }

    @Test
    fun `un solo byte alterado en el mensaje invalida la firma`() {
        val original = "sha256-del-conjunto".toByteArray()
        val signature = keyPair.sign(original)

        val tampered = original.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertFalse(verifier.verify(tampered, signature))
    }

    @Test
    fun `una firma truncada se rechaza sin lanzar`() {
        val message = "mensaje".toByteArray()
        val truncated = Base64.decode(keyPair.sign(message), Base64.DEFAULT)
            .copyOf(32)
            .let { Base64.encodeToString(it, Base64.NO_WRAP) }

        // Sin la comprobación de longitud, BouncyCastle lanzaría, y la excepción
        // acabaría en un catch genérico varias capas arriba. Un fallo de
        // verificación tiene que ser una decisión, no un camino de error.
        assertFalse(verifier.verify(message, truncated))
    }

    @Test
    fun `basura en lugar de firma se rechaza sin lanzar`() {
        assertFalse(verifier.verify("mensaje".toByteArray(), "no-es-base64-!!!"))
        assertFalse(verifier.verify("mensaje".toByteArray(), ""))
    }

    // ── Configuración ausente o mal pegada ───────────────────────────────────

    @Test
    fun `sin clave publica configurada NADA se acepta`() {
        // Estado del repositorio recién clonado: gradle.properties trae el
        // marcador TODO. El modo de fallo seguro es rechazarlo todo, nunca
        // aceptarlo todo.
        val sinConfigurar = Ed25519SignatureVerifier("TODO_ED25519_PUBLIC_KEY_BASE64")

        assertFalse(sinConfigurar.isConfigured())
        assertFalse(sinConfigurar.verify("mensaje".toByteArray(), keyPair.sign("mensaje".toByteArray())))
    }

    @Test
    fun `una clave con cabecera PEM pegada por error se detecta`() {
        // Error humano habitual: copiar el .pem entero en vez del contenido
        // crudo. La clave resultante no mide 32 bytes.
        val conCabecera = Ed25519SignatureVerifier(
            "MCowBQYDK2VwAyEA" + keyPair.publicBase64
        )

        assertFalse(conCabecera.isConfigured())
    }

    @Test
    fun `una clave publica valida se reconoce como configurada`() {
        assertTrue(verifier.isConfigured())
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private class KeyPair(
        private val privateKey: Ed25519PrivateKeyParameters,
        publicKey: Ed25519PublicKeyParameters,
    ) {
        val publicBase64: String = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

        fun sign(message: ByteArray): String {
            val signer = Ed25519Signer().apply {
                init(true, privateKey)
                update(message, 0, message.size)
            }
            return Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP)
        }
    }

    private fun generateKeyPair(): KeyPair {
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val pair = generator.generateKeyPair()
        return KeyPair(
            privateKey = pair.private as Ed25519PrivateKeyParameters,
            publicKey = pair.public as Ed25519PublicKeyParameters,
        )
    }
}
