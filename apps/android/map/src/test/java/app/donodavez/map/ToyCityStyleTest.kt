package app.donodavez.map

import app.donodavez.map.ToyCityStyle.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ids reais do OpenFreeMap Bright (esquema OpenMapTiles). */
class ToyCityStyleTest {
    private fun role(id: String, sourceLayer: String, type: String) = ToyCityStyle.roleOf(id, sourceLayer, type)

    @Test fun groundGreensAndWater() {
        assertEquals(Role.BACKGROUND, role("background", "", "background"))
        assertEquals(Role.RESIDENTIAL, role("landuse-residential", "landuse", "fill"))
        assertEquals(Role.COMMERCIAL, role("landuse-commercial", "landuse", "fill"))
        assertEquals(Role.INDUSTRIAL, role("landuse-industrial", "landuse", "fill"))
        assertEquals(Role.PARK, role("park", "park", "fill"))
        assertEquals(Role.PARK, role("landcover-grass-park", "park", "fill"))
        assertEquals(Role.GRASS, role("landcover-grass", "landcover", "fill"))
        assertEquals(Role.WOOD, role("landcover-wood", "landcover", "fill"))
        assertEquals(Role.WATER, role("water", "water", "fill"))
        assertEquals(Role.WATERWAY, role("waterway-river", "waterway", "line"))
        assertNull(role("landcover-glacier", "landcover", "fill"))
    }

    @Test fun roadsCasingsAndRail() {
        assertEquals(Role.ROAD_CASING_MINOR, role("tunnel-minor-casing", "transportation", "line"))
        assertEquals(Role.ROAD_CASING_MAJOR, role("highway-primary-casing", "transportation", "line"))
        assertEquals(Role.ROAD_CASING_MAJOR, role("bridge-secondary-tertiary-casing", "transportation", "line"))
        assertEquals(Role.ROAD_MINOR, role("highway-minor", "transportation", "line"))
        assertEquals(Role.ROAD_MINOR, role("tunnel-service-track", "transportation", "line"))
        assertEquals(Role.ROAD_MEDIUM, role("highway-secondary-tertiary", "transportation", "line"))
        assertEquals(Role.ROAD_MAJOR, role("highway-motorway", "transportation", "line"))
        assertEquals(Role.PATH, role("highway-path", "transportation", "line"))
        assertNull(role("bridge-path-casing", "transportation", "line"))
        assertEquals(Role.RAIL, role("railway-transit", "transportation", "line"))
        assertEquals(Role.ROAD_AREA, role("highway-area", "transportation", "fill"))
    }

    @Test fun hidesClutterButKeepsLabels() {
        assertEquals(Role.HIDE, role("building-top", "building", "fill"))
        assertEquals(Role.BUILDING, role("building", "building", "fill"))
        assertEquals(Role.HIDE, role("road_oneway", "transportation", "symbol"))
        assertEquals(Role.HIDE, role("highway-shield-non-us", "transportation_name", "symbol"))
        assertEquals(Role.HIDE, role("boundary_2", "boundary", "line"))
        assertEquals(Role.ROAD_LABEL, role("highway-name-major", "transportation_name", "symbol"))
        assertEquals(Role.PLACE_LABEL, role("label_city", "place", "symbol"))
        assertEquals(Role.WATER_LABEL, role("water_name_line_label", "water_name", "symbol"))
    }

    @Test fun scalesRoadWidthStopsKeepingZoomAtTheTop() {
        val s = ToyCityStyle::scaleZoomCurve
        // Curva do Bright: só as larguras mudam; zoom continua como entrada do interpolate no topo.
        assertEquals(
            """["interpolate",["exponential",1.2],["zoom"],13.5,0.0,14,3.0,20,12.0]""",
            s("""["interpolate",["exponential",1.2],["zoom"],13.5,0,14,2.5,20,10]""", 1.2),
        )
        assertEquals("""["step",["zoom"],1.0,15,2.0]""", s("""["step",["zoom"],0.5,15,1]""", 2.0))
        // Parada com expressão de dado: multiplicada por dentro.
        assertEquals(
            """["interpolate",["linear"],["zoom"],14,["*",2.0,["get","w"]]]""",
            s("""["interpolate",["linear"],["zoom"],14,["get","w"]]""", 2.0),
        )
        // Sem zoom: pode envolver; zoom aninhado em formato desconhecido: não mexe.
        assertEquals("""["*",2.0,["get","w"]]""", s("""["get","w"]""", 2.0))
        assertNull(s("""["case",true,["interpolate",["linear"],["zoom"],1,1,2,2],1]""", 2.0))
        assertNull(s("not json", 2.0))
    }

    @Test fun neverTouchesGameLayersPoisOrUnknownSources() {
        assertNull(role("dv-extrusion", "", "fill-extrusion"))
        assertNull(role("dv-me", "", "symbol"))
        assertNull(role("dv-radius-line", "transportation", "line"))
        assertNull(role("poi_r20", "poi", "symbol"))
        assertNull(role("aeroway-runway", "aeroway", "line"))
        assertNull(role("custom", "", "fill"))
    }
}
