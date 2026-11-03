package app.donodavez.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Evita que os POIs de terceiros pareçam imóveis negociáveis no Dono da Vez.
 *
 * O estilo remoto (OpenFreeMap Bright ou outro provedor) contém nomes/ícones de
 * lojas, restaurantes, garagens e transporte. Eles não correspondem ao catálogo
 * do jogo, que é carregado exclusivamente nas camadas dv-* pelo backend.
 *
 * Aplicar apenas às camadas SYMBOL do mapa-base: nunca ocultar ruas, quadras,
 * polígonos dos edifícios, o avatar ou os marcadores oficiais do jogo.
 */
internal object MapBaseStyleCleaner {
    fun isUnrelatedPoiLayer(id: String, sourceLayer: String): Boolean {
        if (id.startsWith("dv-")) return false
        return sourceLayer.equals("poi", ignoreCase = true) ||
            id.equals("poi", ignoreCase = true) ||
            id.startsWith("poi-", ignoreCase = true) ||
            id.startsWith("poi_", ignoreCase = true)
    }

    fun hideUnrelatedPois(style: Style) {
        style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
            if (isUnrelatedPoiLayer(layer.id, layer.sourceLayer)) {
                layer.setProperties(visibility(Property.NONE))
            }
        }
    }
}
