package app.donodavez.map

import app.donodavez.core.AppContainer
import app.donodavez.core.data.MapFeatureEntity
import app.donodavez.core.model.MapResponseDto
import app.donodavez.core.model.PropertySummaryDto
import app.donodavez.core.model.RegionDto
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.DvJson
import app.donodavez.core.net.decode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** Retângulo visível: [minLon, minLat, maxLon, maxLat] e zoom da câmera. */
data class Viewport(val bbox: DoubleArray, val zoom: Double) {
    override fun equals(other: Any?) = other is Viewport && bbox.contentEquals(other.bbox) && zoom == other.zoom
    override fun hashCode() = 31 * bbox.contentHashCode() + zoom.hashCode()
}

data class MapData(
    val mode: String,
    val regionStatus: String,
    val regions: List<RegionDto>,
    val features: List<JsonObject>,
    /** Não nulo quando os dados vieram do cache local (sem rede). */
    val offlineSince: Long?,
    /** Há mais imóveis na área do que os carregados (aproximar o mapa). */
    val truncated: Boolean,
    val fetchedAt: Long,
)

@Serializable
private data class NearbyDto(val items: List<PropertySummaryDto> = emptyList())

/**
 * Busca somente o retângulo visível (com margem). Em zoom distante o servidor devolve agrupamentos;
 * em zoom próximo, os imóveis (pegada ou ponto), paginados por cursor.
 */
class MapRepository(private val c: AppContainer) {

    suspend fun load(v: Viewport): MapData {
        val now = System.currentTimeMillis()
        try {
            val features = mutableListOf<JsonObject>()
            var first: MapResponseDto? = null
            var cursor: String? = null
            var pages = 0
            do {
                val r: MapResponseDto = c.api.get("/v1/map", mapOf(
                    "bbox" to v.bbox.joinToString(",") { String.format(Locale.US, "%.6f", it) },
                    "zoom" to String.format(Locale.US, "%.2f", v.zoom),
                    "cursor" to cursor,
                    "limit" to PAGE_SIZE,
                )).decode()
                if (first == null) first = r
                features += r.features.map { it.jsonObject }
                cursor = r.nextCursor
                pages++
            } while (cursor != null && pages < MAX_PAGES)
            val head: MapResponseDto = first
            if (head.mode == "features") cache(features, now)
            return MapData(head.mode, head.regionStatus, head.regions, features, null, cursor != null, now)
        } catch (e: ApiException) {
            throw e // resposta do servidor (área grande, conta bloqueada…): não mascarar com cache
        } catch (e: Exception) {
            val cached = c.db.features().inBox(v.bbox[1], v.bbox[3], v.bbox[0], v.bbox[2], PAGE_SIZE * MAX_PAGES)
            if (cached.isEmpty() || v.zoom < CLUSTER_ZOOM) throw e
            return MapData(
                mode = "features", regionStatus = "available", regions = emptyList(),
                features = cached.mapNotNull { runCatching { DvJson.parseToJsonElement(it.featureJson).jsonObject }.getOrNull() },
                offlineSince = cached.minOf { it.fetchedAt }, truncated = false, fetchedAt = now,
            )
        }
    }

    /** Imóveis no raio de interação (lista acessível, sem depender do toque no mapa). */
    suspend fun nearby(lat: Double, lon: Double, radiusM: Int): List<PropertySummaryDto> =
        c.api.get("/v1/nearby", mapOf("lat" to lat, "lon" to lon, "radius" to radiusM)).decode<NearbyDto>().items

    private suspend fun cache(features: List<JsonObject>, now: Long) {
        val rows = features.mapNotNull { f ->
            val p = f["properties"]?.jsonObject ?: return@mapNotNull null
            val id = p["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val lat = p["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = p["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            MapFeatureEntity(id, lat, lon, f.toString(), now)
        }
        c.db.features().upsert(rows)
        c.db.features().purgeOlderThan(now - CACHE_TTL_MS)
    }

    companion object {
        const val PAGE_SIZE = 500
        const val MAX_PAGES = 3
        const val CLUSTER_ZOOM = 15.0
        private const val CACHE_TTL_MS = 7L * 24 * 3600 * 1000
    }
}
