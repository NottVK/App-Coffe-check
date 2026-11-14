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
    suspend fun save(type: String, rule: ZoneRule, etag: String?)

    /** El servidor ya no tiene regla activa para el tipo (204): se deja de ofrecer la zona. */
    suspend fun clear(type: String)

    fun observePlantsPerHa(): Flow<Int>
    suspend fun setPlantsPerHa(value: Int)
}
