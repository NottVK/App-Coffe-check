package co.agrovision.ml.registry

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verificación de firma Ed25519 del artefacto de modelo (§15.4, RFC 8032).
 *
 * **Esta es la frontera de confianza del subsistema de modelos.** Sin ella,
 * cualquiera con control del CDN —o con capacidad de interceptar la conexión—
 * puede hacer que la flota entera ejecute los pesos que quiera. Y un modelo es
 * código: decide qué se le dice al productor que tiene en su cultivo.
 *
 * ## Por qué BouncyCastle y no `java.security.Signature`
 *
 * `Signature.getInstance("Ed25519")` solo existe desde API 33. Este proyecto
 * llega a API 22 (ADR-012), así que en buena parte de la flota no está. La
 * implementación autónoma de BouncyCastle es idéntica en todas las versiones de
 * Android, lo que además elimina la posibilidad de que la verificación se comporte
 * distinto según el dispositivo — que es exactamente el tipo de variación que no
 * se quiere en un control de seguridad.
 *
 * La clave pública se embebe en el binario y la protege R8. No impide que alguien
 * decidido la extraiga; impide que la sustituya, que es lo que importa: la firma
 * se verifica contra ESA clave, no contra una que venga en el manifiesto.
 */
@Singleton
class Ed25519SignatureVerifier @Inject constructor(
    private val publicKeyBase64: String,
) {

    /**
     * Verifica `signature` sobre `message`.
     *
     * `message` es el **hash SHA-256 del conjunto de los cuatro artefactos**
     * (modelo, labels, calibración, métricas), no el modelo suelto: los cinco
     * archivos viajan y se versionan como una unidad indivisible (§13.4). Firmar
     * solo el `.tflite` dejaría la puerta abierta a sustituir el `calibration.json`
     * —y con él, los umbrales de las tres compuertas— sin romper la firma.
     *
     * Devuelve `false` ante cualquier problema, nunca lanza. Una excepción aquí
     * acabaría en un `catch` genérico varias capas arriba, y un fallo de
     * verificación tiene que ser una decisión explícita, no un camino de error.
     */
    fun verify(message: ByteArray, signatureBase64: String): Boolean = runCatching {
        val publicKey = decodeKey() ?: return false
        val signature = Base64.decode(signatureBase64, Base64.DEFAULT)

        // Ed25519 produce firmas de exactamente 64 bytes. Comprobarlo antes evita
        // que BouncyCastle lance con una entrada malformada.
        if (signature.size != SIGNATURE_BYTES) return false

        Ed25519Signer().apply {
            init(false, publicKey)
            update(message, 0, message.size)
        }.verifySignature(signature)
    }.getOrDefault(false)

    /** `true` si hay una clave pública utilizable embebida. */
    fun isConfigured(): Boolean = decodeKey() != null

    private fun decodeKey(): Ed25519PublicKeyParameters? = runCatching {
        if (publicKeyBase64.isBlank() || publicKeyBase64.startsWith(PLACEHOLDER_PREFIX)) {
            return null
        }
        val raw = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        // Una clave Ed25519 son 32 bytes exactos. Si llegan 44, alguien pegó el
        // PEM con cabecera en vez del contenido crudo.
        if (raw.size != PUBLIC_KEY_BYTES) return null
        Ed25519PublicKeyParameters(raw, 0)
    }.getOrNull()

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val SIGNATURE_BYTES = 64
        const val PLACEHOLDER_PREFIX = "TODO"
    }
}
