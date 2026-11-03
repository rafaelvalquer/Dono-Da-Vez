package app.donodavez.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import app.donodavez.core.location.Geo
import app.donodavez.core.ui.Avatars
import app.donodavez.core.ui.PropertyState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.any
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.concat
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/** Camadas próprias do jogo sobre o estilo base (fontes GeoJSON atualizadas pelo app). */
object MapLayers {
    private const val SRC_PROPS = "dv-props"
    private const val SRC_CLUSTERS = "dv-clusters"
    private const val SRC_SELECTED = "dv-selected"
    private const val SRC_ME = "dv-me"
    private const val SRC_RADIUS = "dv-radius"
    private const val SRC_ACCURACY = "dv-accuracy"

    private const val L_ACCURACY = "dv-accuracy-fill"
    private const val L_RADIUS_FILL = "dv-radius-fill"
    private const val L_RADIUS_LINE = "dv-radius-line"
    const val L_FLAT = "dv-flat"
    const val L_EXTRUSION = "dv-extrusion"
    private const val L_OUTLINES = "dv-property-edges"
    const val L_POINTS = "dv-points"
    const val L_ICONS = "dv-icons"
    private const val L_SELECTED_LINE = "dv-selected-line"
    private const val L_SELECTED_POINT = "dv-selected-point"
    private const val L_CLUSTER = "dv-cluster"
    private const val L_CLUSTER_AVAILABLE = "dv-cluster-available"
    private const val L_ME = "dv-me"

    /** Camadas consultadas no toque (prédio 3D, pegada plana, ponto ou ícone). */
    val TAPPABLE = arrayOf(L_EXTRUSION, L_FLAT, L_POINTS, L_ICONS)

    private const val AVATAR_IMAGE = "dv-avatar"
    private const val DEFAULT_HEIGHT_M = 6.0
    private val EMPTY = buildJsonObject { put("type", "FeatureCollection"); put("features", JsonArray(emptyList())) }.toString()

    private fun stateColor(): Expression = match(
        get("state"), color(PropertyState.UNAVAILABLE.argb()),
        *PropertyState.entries.map { stop(it.key, color(it.argb())) }.toTypedArray(),
    )

    private fun footprint(has: Boolean) = eq(get("has_footprint"), literal(has))

    fun install(context: Context, style: Style, avatarId: String?) {
        val density = context.resources.displayMetrics.density
        PropertyState.entries.forEach { s ->
            style.addImage("dv-state-${s.key}", bitmap(context, stateDrawable(s), (28 * density).toInt()))
        }
        style.addImage(AVATAR_IMAGE, bitmap(context, Avatars.drawable(avatarId), (44 * density).toInt()))

        listOf(SRC_PROPS, SRC_CLUSTERS, SRC_SELECTED, SRC_ME, SRC_RADIUS, SRC_ACCURACY).forEach { id ->
            if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, EMPTY))
        }
        val teal = Color.parseColor("#1B5E52")
        val layers: List<Layer> = listOf(
            // Chão: precisão do GPS (azul) separada do raio de interação de 80 m (verde tracejado).
            FillLayer(L_ACCURACY, SRC_ACCURACY).withProperties(fillColor(Color.parseColor("#1565C0")), fillOpacity(0.14f)),
            FillLayer(L_RADIUS_FILL, SRC_RADIUS).withProperties(fillColor(teal), fillOpacity(0.06f)),
            LineLayer(L_RADIUS_LINE, SRC_RADIUS).withProperties(lineColor(teal), lineWidth(2f), lineDasharray(arrayOf(2f, 1.5f))),
            // Imóveis
            FillLayer(L_FLAT, SRC_PROPS).withFilter(footprint(true)).withProperties(
                fillColor(stateColor()), fillOpacity(0.85f), fillOutlineColor(Color.parseColor("#24463B")), visibility(Property.NONE)),
            FillExtrusionLayer(L_EXTRUSION, SRC_PROPS).withFilter(footprint(true)).withProperties(
                fillExtrusionColor(stateColor()),
                fillExtrusionHeight(toNumber(coalesce(get("height_m"), literal(DEFAULT_HEIGHT_M)))),
                fillExtrusionBase(0f),
                fillExtrusionOpacity(0.94f)),
            // Contorno forte, como peças colecionáveis sobre o mapa real.
            LineLayer(L_OUTLINES, SRC_PROPS).withFilter(footprint(true)).withProperties(
                lineColor(Color.parseColor("#24463B")), lineWidth(1.5f)),
            CircleLayer(L_POINTS, SRC_PROPS).withFilter(footprint(false)).withProperties(
                circleColor(stateColor()), circleRadius(9f), circleStrokeColor(Color.parseColor("#24463B")), circleStrokeWidth(2.5f)),
            LineLayer(L_SELECTED_LINE, SRC_SELECTED).withProperties(lineColor(Color.parseColor("#212121")), lineWidth(3.5f)),
            CircleLayer(L_SELECTED_POINT, SRC_SELECTED).withFilter(eq(geometryType(), literal("Point"))).withProperties(
                circleRadius(14f), circleOpacity(0f), circleStrokeColor(Color.parseColor("#212121")), circleStrokeWidth(3f)),
            SymbolLayer(L_ICONS, SRC_PROPS).withProperties(
                iconImage(concat(literal("dv-state-"), get("state"))),
                iconAllowOverlap(true), iconIgnorePlacement(true),
                iconSize(interpolate(linear(), zoom(), stop(15, 0.55f), stop(18, 1f))),
            ).also { it.minZoom = 15.5f },
            // Agrupamentos (zoom distante): total e, por dentro, os disponíveis.
            CircleLayer(L_CLUSTER, SRC_CLUSTERS).withProperties(
                circleColor(teal), circleOpacity(0.7f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
                circleRadius(interpolate(linear(), get("count"), stop(1, 8f), stop(50, 16f), stop(500, 26f), stop(5000, 36f)))),
            CircleLayer(L_CLUSTER_AVAILABLE, SRC_CLUSTERS).withProperties(
                circleColor(PropertyState.AVAILABLE.argb()), circleOpacity(0.9f),
                circleRadius(interpolate(linear(), get("available"), stop(0, 0f), stop(1, 4f), stop(50, 10f), stop(500, 18f), stop(5000, 26f)))),
            // Jogador por cima de tudo
            SymbolLayer(L_ME, SRC_ME).withProperties(
                iconImage(AVATAR_IMAGE), iconAllowOverlap(true), iconIgnorePlacement(true),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT)),
        )
        layers.forEach { if (style.getLayer(it.id) == null) style.addLayer(it) }
    }

    fun setAvatar(context: Context, style: Style, avatarId: String?) {
        val density = context.resources.displayMetrics.density
        style.addImage(AVATAR_IMAGE, bitmap(context, Avatars.drawable(avatarId), (44 * density).toInt()))
    }

    fun setData(style: Style, data: MapData?) {
        val features = data?.features.orEmpty()
        val clusters = data?.mode == "clusters"
        source(style, SRC_PROPS)?.setGeoJson(if (clusters) EMPTY else collection(features))
        source(style, SRC_CLUSTERS)?.setGeoJson(if (clusters) collection(features) else EMPTY)
    }

    fun setSelected(style: Style, feature: JsonObject?) {
        source(style, SRC_SELECTED)?.setGeoJson(if (feature == null) EMPTY else collection(listOf(feature)))
    }

    /** Avatar, círculo de 80 m e círculo de precisão (indicadores separados). */
    fun setMe(style: Style, lat: Double?, lon: Double?, accuracyM: Double?, radiusM: Int) {
        if (lat == null || lon == null) {
            listOf(SRC_ME, SRC_RADIUS, SRC_ACCURACY).forEach { source(style, it)?.setGeoJson(EMPTY) }
            return
        }
        val point = buildJsonObject {
            put("type", "Feature")
            put("properties", JsonObject(emptyMap()))
            put("geometry", buildJsonObject {
                put("type", "Point")
                put("coordinates", buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) })
            })
        }
        source(style, SRC_ME)?.setGeoJson(collection(listOf(point)))
        source(style, SRC_RADIUS)?.setGeoJson(collection(listOf(circle(lat, lon, radiusM.toDouble()))))
        val acc = accuracyM?.takeIf { it.isFinite() && it in 1.0..2_000.0 }
        source(style, SRC_ACCURACY)?.setGeoJson(if (acc == null) EMPTY else collection(listOf(circle(lat, lon, acc))))
    }

    fun setTopView(style: Style, top: Boolean) {
        style.getLayer(L_EXTRUSION)?.setProperties(visibility(if (top) Property.NONE else Property.VISIBLE))
        style.getLayer(L_FLAT)?.setProperties(visibility(if (top) Property.VISIBLE else Property.NONE))
    }

    /** Filtro por estado: aplicado às camadas de imóveis (o servidor continua enviando todos). */
    fun setFilter(style: Style, visible: Set<PropertyState>) {
        val states = if (visible.isEmpty()) literal(false)
        else any(*visible.map { eq(get("state"), literal(it.key)) }.toTypedArray())
        (style.getLayer(L_FLAT) as? FillLayer)?.setFilter(all(footprint(true), states))
        (style.getLayer(L_EXTRUSION) as? FillExtrusionLayer)?.setFilter(all(footprint(true), states))
        (style.getLayer(L_POINTS) as? CircleLayer)?.setFilter(all(footprint(false), states))
        (style.getLayer(L_OUTLINES) as? LineLayer)?.setFilter(all(footprint(true), states))
        (style.getLayer(L_ICONS) as? SymbolLayer)?.setFilter(states)
    }

    private fun source(style: Style, id: String): GeoJsonSource? = style.getSourceAs(id)

    private fun collection(features: List<JsonObject>): String =
        buildJsonObject { put("type", "FeatureCollection"); put("features", JsonArray(features)) }.toString()

    private fun circle(lat: Double, lon: Double, radiusM: Double): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("properties", JsonObject(emptyMap()))
        put("geometry", buildJsonObject {
            put("type", "Polygon")
            put("coordinates", buildJsonArray {
                add(buildJsonArray {
                    Geo.circleRing(lat, lon, radiusM).forEach { (x, y) -> add(buildJsonArray { add(JsonPrimitive(x)); add(JsonPrimitive(y)) }) }
                })
            })
        })
    }

    private fun stateDrawable(s: PropertyState): Int = when (s) {
        PropertyState.AVAILABLE -> R.drawable.dv_state_available
        PropertyState.MINE -> R.drawable.dv_state_mine
        PropertyState.OTHER -> R.drawable.dv_state_other
        PropertyState.PROPOSAL -> R.drawable.dv_state_proposal
        PropertyState.UNAVAILABLE -> R.drawable.dv_state_unavailable
    }

    private fun bitmap(context: Context, res: Int, sizePx: Int): Bitmap =
        ContextCompat.getDrawable(context, res)!!.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

private fun PropertyState.argb(): Int = color.toArgb()
