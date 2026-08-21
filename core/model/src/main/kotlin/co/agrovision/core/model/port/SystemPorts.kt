package co.agrovision.core.model.port

/**
 * El tiempo, inyectado.
 *
 * Sin esto, cualquier prueba sobre la purga a 90 días, sobre el backoff o sobre
 * el intervalo de comprobación de OTA depende del reloj real y solo se puede
 * escribir esperando. Con esto, se escribe adelantando el reloj.
 */
fun interface Clock {
    fun nowEpochMillis(): Long
}

/**
 * Generación de identificadores, inyectada por la misma razón que el reloj, y
 * además porque el identificador es la clave de idempotencia que viaja al
 * servidor: una prueba de deduplicación necesita poder fijarlo.
 */
fun interface IdGenerator {
    fun newId(): String
}

/** Versión de la aplicación instalada. La necesita la negociación de modelo
 *  para evaluar `minAppVersion` (§8.2). */
fun interface AppVersionProvider {
    fun versionName(): String
}
