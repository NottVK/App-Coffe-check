package co.agrovision.core.model.port

import co.agrovision.core.model.zone.ZoneRule
import kotlinx.coroutines.flow.Flow

/** Regla en caché junto con la etiqueta HTTP con la que llegó. */
data class CachedZoneRule(val rule: ZoneRule, val etag: String?)

/**
 * Caché local de las reglas de zona, una por tipo. La pantalla solo lee de aquí:
 * la descarga la hace el trabajador de sincronización (§9.1), así que sin señal
 * se usa la última regla recibida y, si nunca llegó ninguna, no se muestra nada.
 */
interface ZoneRuleRepository {
    suspend fun cached(type: String): CachedZoneRule?

    /**
     * La regla del tipo a lo largo del tiempo: `null` mientras no haya regla válida.
     * Emite de nuevo cuando la sincronización la reemplaza o la retira (204), para
     * que una pantalla ya abierta no siga calculando con una regla que ya no rige.
     */
    fun observe(type: String): Flow<CachedZoneRule?>
    suspend fun save(type: String, rule: ZoneRule, etag: String?)

    /** El servidor ya no tiene regla activa para el tipo (204): se deja de ofrecer la zona. */
    suspend fun clear(type: String)

    fun observePlantsPerHa(): Flow<Int>
    suspend fun setPlantsPerHa(value: Int)
}
