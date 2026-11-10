package app.donodavez.map

import android.content.Context
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.donodavez.core.location.Geo
import app.donodavez.core.ui.Avatars
import app.donodavez.core.ui.PropertyState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.max
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.subtract
import org.maplibre.android.style.expressions.Expression.switchCase
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
import org.maplibre.android.style.layers.PropertyFactory.circlePitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionVerticalGradient
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.fillTranslate
import org.maplibre.android.style.layers.PropertyFactory.fillTranslateAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Camadas próprias do jogo sobre o estilo base (fontes GeoJSON atualizadas pelo app).
 * Cada imóvel é uma "peça": sombra no chão, base escura, corpo na cor do estado e teto mais claro.
 */
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
    private const val L_SHADOW = "dv-shadow"
    const val L_FLAT = "dv-flat"
    private const val L_PLINTH = "dv-plinth"
    const val L_EXTRUSION = "dv-extrusion"
    private const val L_ROOF = "dv-roof"
    private const val L_OUTLINES = "dv-property-edges"
    const val L_POINTS = "dv-points"
    const val L_ICONS = "dv-icons"
    private const val L_SELECTED_GLOW = "dv-selected-glow"
    private const val L_SELECTED_LINE = "dv-selected-line"
    private const val L_SELECTED_POINT = "dv-selected-point"
    private const val L_CLUSTER = "dv-cluster"
    private const val L_CLUSTER_AVAILABLE = "dv-cluster-available"
    private const val L_ME_PULSE = "dv-me-pulse"
    private const val L_ME_HEADING = "dv-me-heading"
    private const val L_ME = "dv-me"

    /** Camadas consultadas no toque (prédio 3D, pegada plana, ponto ou ícone). */
    val TAPPABLE = arrayOf(L_ROOF, L_EXTRUSION, L_PLINTH, L_FLAT, L_POINTS, L_ICONS)

    /** Camadas de pegada (polígono) que obedecem ao filtro por estado. */
    private val FOOTPRINT_LAYERS = listOf(L_SHADOW, L_FLAT, L_PLINTH, L_EXTRUSION, L_ROOF, L_OUTLINES)

    private const val AVATAR_IMAGE = "dv-avatar"
    private const val HEADING_IMAGE = "dv-heading"
    private const val PLINTH_M = 1.0
    private const val ROOF_M = 0.8
    private const val AVAILABLE_ICON_ZOOM = 18f
    private const val UNAVAILABLE_ICON_ZOOM = 18.5f
    private val EMPTY = buildJsonObject { put("type", "FeatureCollection"); put("features", JsonArray(emptyList())) }.toString()

    private val TEAL = Color.parseColor("#1B5E52")
    private val INK = Color.parseColor("#3B2F25")
    private val GOLD = Color.parseColor("#FFC93C")
    private val GOLD_LIGHT = Color.parseColor("#FFF1B8")

    private fun byState(pick: (PropertyState) -> androidx.compose.ui.graphics.Color): Expression = match(
        get("state"), color(pick(PropertyState.UNAVAILABLE).toArgb()),
        *PropertyState.entries.map { stop(it.key, color(pick(it).toArgb())) }.toTypedArray(),
    )

    /** Cor por estado; o imóvel selecionado vira dourado em todas as faces visíveis. */
    private fun pieceColor(selectedId: String?, selected: Int, pick: (PropertyState) -> androidx.compose.ui.graphics.Color): Expression =
        if (selectedId == null) byState(pick)
        else switchCase(eq(get("id"), literal(selectedId)), color(selected), byState(pick))

    private fun footprint(has: Boolean) = eq(get("has_footprint"), literal(has))

    /** Altura da peça: a do dado ou um mínimo por categoria (alturas são ilustrativas). */
    private fun height(): Expression = max(
        toNumber(coalesce(get("height_m"), literal(0.0))),
        match(get("category"), literal(6.0),
            stop("house_small", literal(5.0)), stop("house_large", literal(7.0)),
            stop("commercial", literal(8.0)), stop("residential_building", literal(12.0))),
    )

    fun install(context: Context, style: Style, avatarId: String?) {
        val density = context.resources.displayMetrics.density
        PropertyState.entries.forEach { s ->
            style.addImage("dv-state-${s.key}", PlayerMarker.drawable(context, stateDrawable(s), (28 * density).toInt()))
        }
        style.addImage(AVATAR_IMAGE, PlayerMarker.pin(context, Avatars.drawable(avatarId)))
        style.addImage(HEADING_IMAGE, PlayerMarker.heading(context))

        listOf(SRC_PROPS, SRC_CLUSTERS, SRC_SELECTED, SRC_ME, SRC_RADIUS, SRC_ACCURACY).forEach { id ->
            if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, EMPTY))
        }
        val h = height()
        val layers: List<Layer> = listOf(
            // Chão: precisão do GPS (azul) separada do raio de interação de 80 m (verde tracejado).
            FillLayer(L_ACCURACY, SRC_ACCURACY).withProperties(fillColor(Color.parseColor("#2F80ED")), fillOpacity(0.12f)),
            FillLayer(L_RADIUS_FILL, SRC_RADIUS).withProperties(fillColor(TEAL), fillOpacity(0.07f)),
            LineLayer(L_RADIUS_LINE, SRC_RADIUS).withProperties(
                lineColor(TEAL), lineWidth(2.5f), lineOpacity(0.8f), lineDasharray(arrayOf(2f, 1.5f))),
            // Sombra projetada no chão (direção fixa na tela, como a luz da cena).
            FillLayer(L_SHADOW, SRC_PROPS).withFilter(footprint(true)).withProperties(
                fillColor(Color.BLACK), fillOpacity(0.18f),
                fillTranslate(arrayOf(6f, 7f)), fillTranslateAnchor(Property.FILL_TRANSLATE_ANCHOR_VIEWPORT)),
            // Vista de cima
            FillLayer(L_FLAT, SRC_PROPS).withFilter(footprint(true)).withProperties(
                fillColor(byState { it.body }), fillOpacity(0.95f), fillOutlineColor(Color.WHITE), visibility(Property.NONE)),
            // Peça 3D: base escura, corpo e teto claro
            FillExtrusionLayer(L_PLINTH, SRC_PROPS).withFilter(footprint(true)).withProperties(
                fillExtrusionColor(byState { it.shade }),
                fillExtrusionBase(0f), fillExtrusionHeight(PLINTH_M.toFloat()),
                fillExtrusionVerticalGradient(false)),
            FillExtrusionLayer(L_EXTRUSION, SRC_PROPS).withFilter(footprint(true)).withProperties(
                fillExtrusionColor(pieceColor(null, GOLD) { it.body }),
                fillExtrusionBase(PLINTH_M.toFloat()),
                fillExtrusionHeight(subtract(h, literal(ROOF_M))),
                fillExtrusionVerticalGradient(true)),
            FillExtrusionLayer(L_ROOF, SRC_PROPS).withFilter(footprint(true)).withProperties(
                fillExtrusionColor(pieceColor(null, GOLD_LIGHT) { it.roof }),
                fillExtrusionBase(subtract(h, literal(ROOF_M))),
                fillExtrusionHeight(h),
                fillExtrusionVerticalGradient(false)),
            LineLayer(L_OUTLINES, SRC_PROPS).withFilter(footprint(true)).withProperties(
                lineColor(INK), lineOpacity(0.35f), lineWidth(1.2f)),
            CircleLayer(L_POINTS, SRC_PROPS).withFilter(footprint(false)).withProperties(
                circleColor(byState { it.body }), circleRadius(10f), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)),
            // Seleção: brilho dourado no chão + contorno nítido
            LineLayer(L_SELECTED_GLOW, SRC_SELECTED).withProperties(
                lineColor(GOLD), lineWidth(10f), lineBlur(7f), lineOpacity(0.9f)),
            LineLayer(L_SELECTED_LINE, SRC_SELECTED).withProperties(lineColor(Color.WHITE), lineWidth(2.5f)),
            CircleLayer(L_SELECTED_POINT, SRC_SELECTED).withFilter(eq(geometryType(), literal("Point"))).withProperties(
                circleRadius(15f), circleColor(GOLD), circleOpacity(0.35f), circleStrokeColor(GOLD), circleStrokeWidth(3f)),
            SymbolLayer(L_ICONS, SRC_PROPS).withProperties(
                iconImage(concat(literal("dv-state-"), get("state"))),
                iconAllowOverlap(true), iconIgnorePlacement(true),
                iconSize(interpolate(linear(), zoom(), stop(15, 0.55f), stop(18, 1f))),
                // Disponível e indisponível são a maioria: ícone só de perto, a cor da peça já mostra o estado.
                // Seu, de outro dono e com proposta são poucos e importam: aparecem em qualquer distância.
                iconOpacity(step(zoom(),
                    match(get("state"), literal(1f),
                        stop(PropertyState.AVAILABLE.key, literal(0f)), stop(PropertyState.UNAVAILABLE.key, literal(0f))),
                    stop(AVAILABLE_ICON_ZOOM, match(get("state"), literal(1f), stop(PropertyState.UNAVAILABLE.key, literal(0f)))),
                    stop(UNAVAILABLE_ICON_ZOOM, literal(1f)))),
            ).also { it.minZoom = 15.5f },
            // Agrupamentos (zoom distante): total e, por dentro, os disponíveis.
            CircleLayer(L_CLUSTER, SRC_CLUSTERS).withProperties(
                circleColor(TEAL), circleOpacity(0.8f), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f),
                circleRadius(interpolate(linear(), get("count"), stop(1, 8f), stop(50, 16f), stop(500, 26f), stop(5000, 36f)))),
            CircleLayer(L_CLUSTER_AVAILABLE, SRC_CLUSTERS).withProperties(
                circleColor(PropertyState.AVAILABLE.body.toArgb()), circleOpacity(0.95f),
                circleRadius(interpolate(linear(), get("available"), stop(0, 0f), stop(1, 4f), stop(50, 10f), stop(500, 18f), stop(5000, 26f)))),
            // Jogador: anel pulsante e seta de direção no chão, pin por cima de tudo.
            CircleLayer(L_ME_PULSE, SRC_ME).withProperties(
                circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
                circleColor(TEAL), circleOpacity(0.3f), circleRadius(12f),
                circleStrokeColor(TEAL), circleStrokeWidth(2f), circleStrokeOpacity(0.6f)),
            SymbolLayer(L_ME_HEADING, SRC_ME).withFilter(has("bearing")).withProperties(
                iconImage(HEADING_IMAGE), iconRotate(get("bearing")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                iconAllowOverlap(true), iconIgnorePlacement(true)),
            SymbolLayer(L_ME, SRC_ME).withProperties(
                iconImage(AVATAR_IMAGE), iconAnchor(Property.ICON_ANCHOR_CENTER),
                iconAllowOverlap(true), iconIgnorePlacement(true),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT)),
        )
        layers.forEach { if (style.getLayer(it.id) == null) style.addLayer(it) }
    }

    fun setAvatar(context: Context, style: Style, avatarId: String?) {
        style.addImage(AVATAR_IMAGE, PlayerMarker.pin(context, Avatars.drawable(avatarId)))
    }

    fun setData(style: Style, data: MapData?) {
        val features = data?.features.orEmpty()
        val clusters = data?.mode == "clusters"
        source(style, SRC_PROPS)?.setGeoJson(if (clusters) EMPTY else collection(features))
        source(style, SRC_CLUSTERS)?.setGeoJson(if (clusters) collection(features) else EMPTY)
    }

    fun setSelected(style: Style, feature: JsonObject?) {
        source(style, SRC_SELECTED)?.setGeoJson(if (feature == null) EMPTY else collection(listOf(feature)))
        val id = feature?.get("properties")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        style.getLayer(L_EXTRUSION)?.setProperties(fillExtrusionColor(pieceColor(id, GOLD) { it.body }))
        style.getLayer(L_ROOF)?.setProperties(fillExtrusionColor(pieceColor(id, GOLD_LIGHT) { it.roof }))
        style.getLayer(L_FLAT)?.setProperties(fillColor(pieceColor(id, GOLD) { it.body }))
    }

    /** Avatar, círculo de 80 m e círculo de precisão (indicadores separados); [bearing] liga a seta. */
    fun setMe(style: Style, lat: Double?, lon: Double?, accuracyM: Double?, radiusM: Int, bearing: Float? = null) {
        if (lat == null || lon == null) {
            listOf(SRC_ME, SRC_RADIUS, SRC_ACCURACY).forEach { source(style, it)?.setGeoJson(EMPTY) }
            return
        }
        val point = buildJsonObject {
            put("type", "Feature")
            put("properties", buildJsonObject { bearing?.takeIf { it.isFinite() }?.let { put("bearing", it) } })
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

    /** Anel pulsante sob o jogador; [phase] em 0..1 (cresce e some). */
    fun setPulse(style: Style, phase: Float) {
        val p = phase.coerceIn(0f, 1f)
        style.getLayer(L_ME_PULSE)?.setProperties(
            circleRadius(10f + 34f * p), circleOpacity(0.32f * (1f - p)), circleStrokeOpacity(0.7f * (1f - p)))
    }

    fun setTopView(style: Style, top: Boolean) {
        val solid = if (top) Property.NONE else Property.VISIBLE
        listOf(L_PLINTH, L_EXTRUSION, L_ROOF).forEach { style.getLayer(it)?.setProperties(visibility(solid)) }
        style.getLayer(L_FLAT)?.setProperties(visibility(if (top) Property.VISIBLE else Property.NONE))
    }

    /** Filtro por estado: aplicado às camadas de imóveis (o servidor continua enviando todos). */
    fun setFilter(style: Style, visible: Set<PropertyState>) {
        val states = if (visible.isEmpty()) literal(false)
        else any(*visible.map { eq(get("state"), literal(it.key)) }.toTypedArray())
        FOOTPRINT_LAYERS.forEach { id ->
            when (val l = style.getLayer(id)) {
                is FillLayer -> l.setFilter(all(footprint(true), states))
                is FillExtrusionLayer -> l.setFilter(all(footprint(true), states))
                is LineLayer -> l.setFilter(all(footprint(true), states))
                else -> Unit
            }
        }
        (style.getLayer(L_POINTS) as? CircleLayer)?.setFilter(all(footprint(false), states))
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
}
