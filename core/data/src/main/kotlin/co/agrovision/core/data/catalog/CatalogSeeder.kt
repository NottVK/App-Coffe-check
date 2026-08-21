package co.agrovision.core.data.catalog

import android.content.Context
import android.util.Log
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.ManagementKind
import co.agrovision.core.model.pest.ManagementRecommendation
import co.agrovision.core.model.pest.PestKind
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.PestCatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Siembra el catálogo desde un activo empaquetado en el APK.
 *
 * ## Por qué el catálogo viaja dentro de la app
 *
 * El productor consulta la ficha **en el lote, donde no hay señal**. Si el
 * catálogo se descargara, la primera vez que alguien abre la app en la ladera se
 * encontraría una lista vacía — justo cuando más la necesita (§3.1 RF-08).
 *
 * El activo es la semilla, no la fuente de verdad. Una vez sembrado, el catálogo
 * se actualiza de forma incremental contra el servidor con ETag, y esas
 * actualizaciones sobreescriben lo sembrado.
 *
 * ## Sobre el aval profesional
 *
 * Las fichas heredadas del proyecto Ionic llegan **sin aval de agrónomo**, y eso
 * es deliberado: `approval = null` hace que `PestTaxon.showsRecommendations`
 * devuelva `false` y la app oculte la sección de manejo.
 *
 * Esas fichas contienen dosis e ingredientes activos concretos —óxido de cobre,
 * entre otros—. Distribuirlos sin respaldo de un ingeniero agrónomo con tarjeta
 * profesional vigente es exposición civil e incumplimiento ante el ICA
 * (§13.6, error prohibido nº 15). El texto está aquí, listo; se muestra el día
 * que alguien lo firme.
 */
@Singleton
class CatalogSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: PestCatalogRepository,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun seedIfEmpty(): Int = withContext(io) {
        // Se comprueba el contenido, no una bandera: si alguien limpió los datos
        // de la app, el catálogo tiene que volver. Una bandera en DataStore diría
        // «ya sembré» sobre una base vacía.
        val existing = catalog.search("")
        if (existing.isNotEmpty()) return@withContext 0

        val raw = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.e(TAG, "No se pudo leer $ASSET_PATH", it)
            return@withContext 0
        }

        val parsed = runCatching {
            json.decodeFromString(ListSerializer(SeedPestJson.serializer()), raw)
        }.getOrElse {
            Log.e(TAG, "El catálogo semilla es ilegible", it)
            return@withContext 0
        }

        catalog.upsertAll(parsed.map { it.toDomain() })
        Log.i(TAG, "Catálogo sembrado con ${parsed.size} fichas")
        parsed.size
    }

    private companion object {
        const val TAG = "AgroVision.Catalog"
        const val ASSET_PATH = "catalog/pests.json"
    }
}

@Serializable
private data class SeedPestJson(
    val id: String,
    val scientificName: String,
    val commonName: String,
    val localSynonyms: List<String> = emptyList(),
    val crop: String = "CAFE",
    val affectedOrgans: List<String> = emptyList(),
    val description: String = "",
    val recommendations: List<SeedRecommendationJson> = emptyList(),
    val images: List<String> = emptyList(),
    val version: Int = 1,
    val kind: String? = null,
    val reference: String? = null,
) {
    fun toDomain() = PestTaxon(
        id = id,
        scientificName = scientificName,
        commonName = commonName,
        localSynonyms = localSynonyms,
        crop = CropType.entries.firstOrNull { it.name == crop } ?: CropType.CAFE,
        affectedOrgans = affectedOrgans.mapNotNull { name ->
            PlantOrgan.entries.firstOrNull { it.name == name }
        },
        description = description,
        recommendations = recommendations.map {
            ManagementRecommendation(
                kind = ManagementKind.entries.firstOrNull { k -> k.name == it.kind }
                    ?: ManagementKind.CULTURAL,
                summary = it.summary,
                detail = it.detail,
            )
        },
        // Las rutas se resuelven contra `assets/catalog/`. Se guarda el nombre
        // desnudo para que una actualización del servidor pueda sustituirlo por
        // una URL sin migrar la columna.
        imageRefs = images,
        version = version,
        kind = PestKind.parse(kind),
        reference = reference?.takeIf { it.isNotBlank() },
        approval = null,
    )
}

@Serializable
private data class SeedRecommendationJson(
    val kind: String,
    val summary: String,
    @SerialName("detail") val detail: String = "",
)
