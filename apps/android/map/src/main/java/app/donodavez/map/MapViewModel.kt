package app.donodavez.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donodavez.core.AppContainer
import app.donodavez.core.data.CameraMemory
import app.donodavez.core.location.Geo
import app.donodavez.core.location.LocationPermission
import app.donodavez.core.location.LocationSample
import app.donodavez.core.model.PropertySummaryDto
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.PropertyState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos
import kotlin.math.sqrt

/** Imóvel candidato quando o toque atinge mais de um. */
data class Candidate(val id: String, val state: PropertyState, val category: String?, val subtype: String?, val lat: Double, val lon: Double)

data class MapUi(
    val permission: LocationPermission = LocationPermission.DENIED,
    val me: LocationSample? = null,
    /** Sem leitura de GPS há muito tempo (ou nunca) com permissão concedida. */
    val noSignal: Boolean = false,
    val data: MapData? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val areaTooLarge: Boolean = false,
    val filters: Set<PropertyState> = PropertyState.entries.toSet(),
    val topView: Boolean = false,
    val follow: Boolean = true,
    val selectedId: String? = null,
    val candidates: List<Candidate> = emptyList(),
    val nearby: Loadable<List<PropertySummaryDto>>? = null,
)

/** Pedido de movimento de câmera emitido pelo ViewModel e consumido pela tela. */
data class CameraTarget(val lat: Double, val lon: Double, val zoom: Double?, val animate: Boolean)

@OptIn(FlowPreview::class)
class MapViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = MapRepository(c)
    private val _ui = MutableStateFlow(MapUi())
    val ui: StateFlow<MapUi> = _ui.asStateFlow()

    private val _camera = MutableStateFlow<CameraTarget?>(null)
    val camera: StateFlow<CameraTarget?> = _camera.asStateFlow()

    private val viewport = MutableStateFlow<Viewport?>(null)
    private var loadedBox: DoubleArray? = null
    private var loadedClusters: Boolean? = null
    /** A área com folga não coube no limite de imóveis: as próximas cargas pedem só a tela. */
    private var screenOnly = false
    private var loadJob: Job? = null
    private var locationJob: Job? = null
    private var lastFixAtMs = 0L

    init {
        viewModelScope.launch { c.settings.topView.collect { top -> _ui.update { it.copy(topView = top) } } }
        viewModelScope.launch { viewport.filterNotNull().debounce(350).collect { load(it, force = false) } }
        viewModelScope.launch { c.game.changes.collect { viewport.value?.let { v -> load(v, force = true) } } }
        viewModelScope.launch {
            // Estados mudam por ações de outros jogadores: atualização leve enquanto a tela está aberta.
            while (true) {
                delay(REFRESH_MS)
                if (locationJob != null) viewport.value?.let { load(it, force = true) }
            }
        }
        viewModelScope.launch { initialCamera() }
    }

    private suspend fun initialCamera() {
        val saved = c.settings.lastCamera()
        if (saved != null && CityMapBounds.contains(saved.lat, saved.lon)) {
            _camera.value = CameraTarget(saved.lat, saved.lon, saved.zoom.coerceIn(CityMapBounds.MIN_ZOOM, CityMapBounds.MAX_ZOOM), animate = false)
            return
        }
        val boot = c.game.bootstrap.filterNotNull().first()
        val center = boot.regions.firstOrNull { it.center?.let { center -> CityMapBounds.contains(center.lat, center.lon) } == true }?.center
        _camera.value = CameraTarget(center?.lat ?: CityMapBounds.CENTER_LAT,
            center?.lon ?: CityMapBounds.CENTER_LON, DEFAULT_ZOOM, animate = false)
    }

    // ---- Localização (somente com a tela visível) ----

    fun refreshPermission() {
        _ui.update { it.copy(permission = c.location.permission()) }
    }

    fun startLocation() {
        refreshPermission()
        if (locationJob != null || _ui.value.permission == LocationPermission.DENIED) return
        lastFixAtMs = System.currentTimeMillis()
        locationJob = viewModelScope.launch {
            launch {
                while (true) {
                    delay(5_000)
                    val silent = System.currentTimeMillis() - lastFixAtMs > NO_SIGNAL_MS
                    if (silent != _ui.value.noSignal) _ui.update { it.copy(noSignal = silent) }
                }
            }
            c.location.updates().collect { s -> onFix(s) }
        }
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    private fun onFix(s: LocationSample) {
        lastFixAtMs = System.currentTimeMillis()
        val first = _ui.value.me == null
        _ui.update { it.copy(me = s, noSignal = false) }
        if (_ui.value.follow && CityMapBounds.contains(s.lat, s.lon)) {
            _camera.value = CameraTarget(s.lat, s.lon, if (first) DEFAULT_ZOOM else null, animate = !first)
        }
    }

    fun recenter() {
        val me = _ui.value.me?.takeIf { CityMapBounds.contains(it.lat, it.lon) } ?: return
        _ui.update { it.copy(follow = true) }
        _camera.value = CameraTarget(me.lat, me.lon, DEFAULT_ZOOM, animate = true)
    }

    fun onUserGesture() {
        if (_ui.value.follow) _ui.update { it.copy(follow = false) }
    }

    fun goToRegion() {
        val center = c.game.bootstrap.value?.regions?.firstOrNull { region ->
            region.center?.let { CityMapBounds.contains(it.lat, it.lon) } == true
        }?.center ?: return
        _ui.update { it.copy(follow = false) }
        _camera.value = CameraTarget(center.lat, center.lon, DEFAULT_ZOOM, animate = true)
    }

    fun cameraConsumed(target: CameraTarget) {
        _camera.compareAndSet(target, null)
    }

    // ---- Dados do mapa ----

    fun onCameraIdle(bbox: DoubleArray, zoom: Double, centerLat: Double, centerLon: Double) {
        viewport.value = Viewport(bbox, zoom)
        viewModelScope.launch { c.settings.saveCamera(CameraMemory(centerLat, centerLon, zoom)) }
    }

    fun retry() {
        viewport.value?.let { load(it, force = true) }
    }

    private fun load(v: Viewport, force: Boolean) {
        val clusters = v.zoom < MapRepository.CLUSTER_ZOOM
        val centerLat = (v.bbox[1] + v.bbox[3]) / 2
        val centerLon = (v.bbox[0] + v.bbox[2]) / 2
        val maxKm2 = if (clusters) MAX_CLUSTER_KM2 else MAX_FEATURES_KM2
        val screen = clampArea(v.bbox, centerLat, centerLon, maxKm2)
        val tight = screenOnly && !clusters
        val request = if (tight) screen else clampArea(Geo.expand(v.bbox, MARGIN), centerLat, centerLon, maxKm2)

        val loaded = _ui.value.data
        val box = loadedBox
        val fresh = loaded?.let { System.currentTimeMillis() - it.fetchedAt < REFRESH_MS } ?: false
        if (!force && fresh && box != null && loadedClusters == clusters && _ui.value.error == null &&
            canReuse(box, v.bbox, request, loaded?.truncated == true, if (tight) SCREEN_ONLY_TOLERANCE else 0.0)
        ) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            try {
                var area = request
                var data = repo.load(Viewport(area, v.zoom))
                if (data.truncated && !tight) {
                    // A folga não coube no limite de imóveis: a área da tela vem primeiro.
                    screenOnly = true
                    area = screen
                    data = repo.load(Viewport(area, v.zoom))
                } else if (tight && fitsWithMargin(data.features.size)) {
                    screenOnly = false
                }
                loadedBox = if (data.offlineSince == null) area else null
                loadedClusters = clusters
                _ui.update { it.copy(data = data, loading = false, error = null, areaTooLarge = false) }
            } catch (e: ApiException) {
                loadedBox = null
                _ui.update { it.copy(loading = false, error = e.message, areaTooLarge = e.code == "AREA_TOO_LARGE") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loadedBox = null
                _ui.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    // ---- Seleção ----

    fun onTapFeatures(features: List<JsonObject>, tapLat: Double, tapLon: Double) {
        val candidates = features.mapNotNull(::candidate).distinctBy { it.id }
            .sortedBy { Geo.distanceM(tapLat, tapLon, it.lat, it.lon) }
            .take(MAX_CANDIDATES)
        when (candidates.size) {
            0 -> _ui.update { it.copy(selectedId = null, candidates = emptyList()) }
            1 -> select(candidates.first().id)
            else -> _ui.update { it.copy(selectedId = null, candidates = candidates) }
        }
    }

    fun select(id: String?) = _ui.update { it.copy(selectedId = id, candidates = emptyList(), nearby = null) }

    fun dismissCandidates() = _ui.update { it.copy(candidates = emptyList()) }

    fun selectedFeature(): JsonObject? {
        val id = _ui.value.selectedId ?: return null
        return _ui.value.data?.features?.firstOrNull { it.props()?.get("id")?.jsonPrimitive?.contentOrNull == id }
    }

    fun openNearby() {
        val me = _ui.value.me
        if (me == null) {
            _ui.update { it.copy(nearby = Loadable.Error("Aguardando sua localização para listar os imóveis ao alcance.")) }
            return
        }
        _ui.update { it.copy(nearby = Loadable.Loading, selectedId = null, candidates = emptyList()) }
        viewModelScope.launch {
            val radius = c.game.gameConfig().interactionRadiusM
            _ui.update {
                it.copy(nearby = try {
                    Loadable.Ready(repo.nearby(me.lat, me.lon, radius))
                } catch (e: Exception) {
                    Loadable.Error(e.userMessage())
                })
            }
        }
    }

    fun closeNearby() = _ui.update { it.copy(nearby = null) }

    // ---- Preferências de exibição ----

    fun toggleFilter(state: PropertyState) = _ui.update {
        it.copy(filters = if (state in it.filters) it.filters - state else it.filters + state)
    }

    fun toggleTopView() {
        val top = !_ui.value.topView
        viewModelScope.launch { c.settings.setTopView(top) }
    }

    private fun candidate(f: JsonObject): Candidate? {
        val p = f.props() ?: return null
        if (p["cluster"] != null) return null
        val id = p["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return Candidate(
            id = id,
            state = PropertyState.of(p["state"]?.jsonPrimitive?.contentOrNull),
            category = p["category"]?.jsonPrimitive?.contentOrNull,
            subtype = p["subtype"]?.jsonPrimitive?.contentOrNull,
            lat = p["lat"]?.jsonPrimitive?.doubleOrNull ?: return null,
            lon = p["lon"]?.jsonPrimitive?.doubleOrNull ?: return null,
        )
    }

    companion object {
        const val DEFAULT_ZOOM = 17.5
        private const val REFRESH_MS = 60_000L
        private const val NO_SIGNAL_MS = 30_000L
        private const val MARGIN = 0.25
        private const val MAX_CANDIDATES = 8
        // Abaixo dos limites do servidor (map_max_bbox_km2 = 6, map_cluster_max_bbox_km2 = 400).
        private const val MAX_FEATURES_KM2 = 5.0
        private const val MAX_CLUSTER_KM2 = 350.0
        private const val SHRINK_TO_RELOAD = 0.9
        // Sem folga, a oscilação do GPS tiraria a tela da área carregada a cada leitura.
        private const val SCREEN_ONLY_TOLERANCE = 0.05
        // Sobra exigida para voltar a pedir com folga, evitando alternar entre os dois modos.
        private const val FIT_HEADROOM = 0.8

        /**
         * A carga anterior ainda serve para a câmera atual? Precisa cobrir a área visível e, se veio cortada,
         * não pode ser de uma área bem maior que a nova: ao aproximar, cabem os imóveis que ficaram de fora.
         */
        fun canReuse(
            loaded: DoubleArray, visible: DoubleArray, request: DoubleArray, truncated: Boolean, tolerance: Double = 0.0,
        ): Boolean = Geo.contains(Geo.expand(loaded, tolerance), visible) &&
            !(truncated && boxArea(request) < boxArea(loaded) * SHRINK_TO_RELOAD)

        /** Pela contagem na área da tela, a área com folga caberia com sobra no limite de imóveis? */
        fun fitsWithMargin(screenCount: Int): Boolean {
            val growth = (1 + 2 * MARGIN) * (1 + 2 * MARGIN)
            return screenCount * growth <= MapRepository.PAGE_SIZE * MapRepository.MAX_PAGES * FIT_HEADROOM
        }

        private fun boxArea(b: DoubleArray) = (b[2] - b[0]) * (b[3] - b[1])

        /** Reduz a caixa em torno do centro até caber na área máxima aceita pelo servidor. */
        fun clampArea(bbox: DoubleArray, centerLat: Double, centerLon: Double, maxKm2: Double): DoubleArray {
            val kmPerDegLat = 110.57
            val kmPerDegLon = 111.32 * cos(Math.toRadians(centerLat))
            val w = (bbox[2] - bbox[0]) * kmPerDegLon
            val h = (bbox[3] - bbox[1]) * kmPerDegLat
            val area = w * h
            if (area <= maxKm2) return bbox
            val k = sqrt(maxKm2 / area)
            val halfLon = (bbox[2] - bbox[0]) * k / 2
            val halfLat = (bbox[3] - bbox[1]) * k / 2
            return doubleArrayOf(centerLon - halfLon, centerLat - halfLat, centerLon + halfLon, centerLat + halfLat)
        }
    }
}

internal fun JsonObject.props(): JsonObject? = runCatching { this["properties"]?.jsonObject }.getOrNull()
