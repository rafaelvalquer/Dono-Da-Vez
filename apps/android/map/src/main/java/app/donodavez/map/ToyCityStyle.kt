package app.donodavez.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloBlur
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.light.Position

/**
 * Paleta "cidade de brinquedo" aplicada ao mapa-base em tempo de execução: chão creme, parques verde vivo,
 * água turquesa, ruas brancas largas e prédios do provedor discretos, para que os imóveis do jogo (dv-*)
 * se destaquem como peças. Só muda cores e visibilidade de camadas visuais; a atribuição continua visível.
 *
 * A classificação usa id e source-layer do esquema OpenMapTiles (OpenFreeMap Bright e similares).
 * Camadas desconhecidas e as do jogo nunca são tocadas.
 */
internal object ToyCityStyle {
    enum class Role {
        BACKGROUND, RESIDENTIAL, COMMERCIAL, INDUSTRIAL, SPECIAL, CEMETERY, PARK, GRASS, WOOD, SAND,
        WATER, WATERWAY, BUILDING, ROAD_AREA, ROAD_CASING_MINOR, ROAD_CASING_MAJOR, ROAD_MINOR, ROAD_MEDIUM,
        ROAD_MAJOR, PATH, RAIL, ROAD_LABEL, PLACE_LABEL, WATER_LABEL, HIDE,
    }

    private val MAJOR = Regex("motorway|trunk|primary")
    private val MEDIUM = Regex("secondary|tertiary|link")
    private val MINOR = Regex("minor|service|track|pier")

    /** Papel da camada no estilo, ou null para não mexer. [type] é o tipo do estilo ("fill", "line"...). */
    fun roleOf(id: String, sourceLayer: String, type: String): Role? {
        if (id.startsWith("dv-")) return null
        val i = id.lowercase()
        return when (sourceLayer.lowercase()) {
            "" -> if (type == "background") Role.BACKGROUND else null
            "landuse" -> when {
                "commercial" in i || "retail" in i -> Role.COMMERCIAL
                "industrial" in i || "railway" in i -> Role.INDUSTRIAL
                "cemetery" in i -> Role.CEMETERY
                "hospital" in i || "school" in i -> Role.SPECIAL
                else -> Role.RESIDENTIAL
            }
            "landcover" -> when {
                "wood" in i -> Role.WOOD
                "grass" in i -> Role.GRASS
                "sand" in i -> Role.SAND
                else -> null
            }
            "park" -> Role.PARK
            "water" -> if (type == "fill") Role.WATER else null
            "waterway" -> if (type == "line") Role.WATERWAY else Role.WATER_LABEL
            "water_name" -> Role.WATER_LABEL
            // building-top é o falso 3D do provedor (deslocamento): briga com a extrusão real dos imóveis.
            "building" -> if (i == "building-top") Role.HIDE else Role.BUILDING
            "transportation" -> when (type) {
                "fill" -> Role.ROAD_AREA
                "symbol" -> Role.HIDE // setas de mão única
                "line" -> when {
                    "rail" in i -> Role.RAIL
                    "path" in i -> if ("casing" in i) null else Role.PATH
                    "casing" in i -> if (MAJOR.containsMatchIn(i) || MEDIUM.containsMatchIn(i)) Role.ROAD_CASING_MAJOR else Role.ROAD_CASING_MINOR
                    MAJOR.containsMatchIn(i) -> Role.ROAD_MAJOR
                    MEDIUM.containsMatchIn(i) -> Role.ROAD_MEDIUM
                    MINOR.containsMatchIn(i) -> Role.ROAD_MINOR
                    else -> null
                }
                else -> null
            }
            "transportation_name" -> if ("shield" in i) Role.HIDE else Role.ROAD_LABEL
            "boundary" -> Role.HIDE
            "place" -> Role.PLACE_LABEL
            else -> null
        }
    }

    private const val ROAD_WIDTH_SCALE = 1.3f

    fun apply(style: Style) {
        style.layers.forEach { layer ->
            val role = roleOf(layer.id, sourceLayerOf(layer), typeOf(layer)) ?: return@forEach
            paint(layer, role)
        }
        // Luz lateral quente: faces dos prédios com sombreamento visível na inclinação de 45°.
        style.light?.let { light ->
            light.anchor = Property.ANCHOR_VIEWPORT
            light.position = Position(1.2f, 215f, 35f)
            light.setColor(Color.parseColor("#FFF1DC"))
            light.intensity = 0.5f
        }
    }

    private fun paint(layer: Layer, role: Role) {
        when (role) {
            Role.HIDE -> layer.setProperties(visibility(Property.NONE))
            Role.BACKGROUND -> (layer as? BackgroundLayer)?.setProperties(backgroundColor(c(GROUND)))
            Role.RESIDENTIAL -> fill(layer, "#EEE2CF", 0.75f)
            Role.COMMERCIAL -> fill(layer, "#F6D9C9", 0.65f)
            Role.INDUSTRIAL -> fill(layer, "#E6DDD5", 0.65f)
            Role.SPECIAL -> fill(layer, "#F4E1E6", 0.7f)
            Role.CEMETERY -> fill(layer, "#CFE3B8", 0.9f)
            Role.PARK -> fill(layer, "#9ED97A", 0.95f)
            Role.GRASS -> fill(layer, "#A9DE86", 1f)
            Role.WOOD -> fill(layer, "#7CC66B", 0.85f)
            Role.SAND -> fill(layer, "#F6E7B0", 1f)
            Role.WATER -> fill(layer, "#6FD3E8", 1f)
            Role.BUILDING -> (layer as? FillLayer)?.setProperties(
                fillColor(c("#E7DED2")), fillOpacity(0.9f), fillOutlineColor(c("#D6CBBE")))
            Role.ROAD_AREA -> fill(layer, "#FFFFFF", 0.9f)
            Role.WATERWAY -> line(layer, "#6FD3E8", widen = false)
            Role.ROAD_CASING_MINOR -> line(layer, "#D9C9B0")
            Role.ROAD_CASING_MAJOR -> line(layer, "#E8B85C")
            Role.ROAD_MINOR -> line(layer, "#FFFFFF")
            Role.ROAD_MEDIUM -> line(layer, "#FFF3C4")
            Role.ROAD_MAJOR -> line(layer, "#FFE08A")
            Role.PATH -> line(layer, "#D4BFA3", widen = false)
            Role.RAIL -> line(layer, "#BFAE9C", widen = false)
            Role.ROAD_LABEL -> text(layer, "#6B5E4E", "#FFFFFF", 1.6f)
            Role.PLACE_LABEL -> text(layer, "#4A3F35", "#FFFFFF", 2f)
            Role.WATER_LABEL -> text(layer, "#2A7F99", "#FFFFFF", 1.5f)
        }
    }

    private fun fill(layer: Layer, color: String, opacity: Float) {
        (layer as? FillLayer)?.setProperties(fillColor(c(color)), fillOpacity(opacity))
    }

    /** Ruas mais largas preservam a curva de zoom do provedor (largura original × escala). */
    private fun line(layer: Layer, color: String, widen: Boolean = true) {
        val l = layer as? LineLayer ?: return
        l.setProperties(lineColor(c(color)))
        if (!widen) return
        // Em expressões o valor guardado é o JSON da expressão (o tipo genérico Float é apagado).
        val w: PropertyValue<*> = l.lineWidth
        when (val raw = w.value) {
            is Number -> l.setProperties(lineWidth(raw.toFloat() * ROAD_WIDTH_SCALE))
            null -> Unit
            else -> if (w.isExpression) {
                scaleZoomCurve(raw.toString(), ROAD_WIDTH_SCALE.toDouble())?.let { l.setProperties(lineWidth(Expression.raw(it))) }
            }
        }
    }

    /**
     * Multiplica as saídas de uma curva de largura (JSON de expressão). O MapLibre só aceita "zoom" como entrada
     * de um interpolate/step no topo, então a escala entra em cada parada em vez de envolver a expressão.
     * Expressões sem zoom são envolvidas em ["*", k, …]; formatos não reconhecidos com zoom retornam null (sem mudança).
     */
    fun scaleZoomCurve(json: String, k: Double): String? {
        val e = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return null
        val op = (e.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val firstOutput = when {
            op == "interpolate" && isZoom(e.getOrNull(2)) -> 4
            op == "step" && isZoom(e.getOrNull(1)) -> 2
            mentionsZoom(e) -> return null
            else -> return times(e, k).toString()
        }
        return JsonArray(e.mapIndexed { i, v -> if (i >= firstOutput && (i - firstOutput) % 2 == 0) times(v, k) else v }).toString()
    }

    private fun isZoom(v: JsonElement?): Boolean =
        v is JsonArray && v.size == 1 && (v[0] as? JsonPrimitive)?.content == "zoom"

    private fun mentionsZoom(v: JsonElement): Boolean = v is JsonArray && (isZoom(v) || v.any(::mentionsZoom))

    private fun times(v: JsonElement, k: Double): JsonElement {
        val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return if (n != null) JsonPrimitive(Math.round(n * k * 1000) / 1000.0)
        else JsonArray(listOf(JsonPrimitive("*"), JsonPrimitive(k), v))
    }

    private fun text(layer: Layer, color: String, halo: String, haloWidth: Float) {
        (layer as? SymbolLayer)?.setProperties(
            textColor(c(color)), textHaloColor(c(halo)), textHaloWidth(haloWidth), textHaloBlur(0.4f))
    }

    private fun sourceLayerOf(layer: Layer): String = when (layer) {
        is FillLayer -> layer.sourceLayer
        is LineLayer -> layer.sourceLayer
        is SymbolLayer -> layer.sourceLayer
        else -> ""
    }.orEmpty()

    private fun typeOf(layer: Layer): String = when (layer) {
        is BackgroundLayer -> "background"
        is FillLayer -> "fill"
        is LineLayer -> "line"
        is SymbolLayer -> "symbol"
        else -> "other"
    }

    private fun c(hex: String) = Color.parseColor(hex)

    const val GROUND = "#F3EBDD"
}
