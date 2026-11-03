package app.donodavez.map

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.location.Geo
import app.donodavez.core.location.LocationPermission
import app.donodavez.core.model.LatLonDto
import app.donodavez.core.model.Money
import app.donodavez.core.net.DvJson
import app.donodavez.core.ui.AvatarImage
import app.donodavez.core.ui.Avatars
import app.donodavez.core.ui.Banner
import app.donodavez.core.ui.EmptyState
import app.donodavez.core.ui.ErrorBox
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.LoadingBox
import app.donodavez.core.ui.OfflineBanner
import app.donodavez.core.ui.PropertyState
import app.donodavez.core.ui.StateBadge
import app.donodavez.core.ui.addressLabel
import app.donodavez.core.ui.categoryLabel
import app.donodavez.property.PropertySheet
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

/** Tela principal: mapa 3D inclinado (45°), avatar, raio de 80 m, filtros e ficha do imóvel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    offline: Boolean,
    onOpenWallet: () -> Unit,
    onOpenProposals: () -> Unit,
    onOpenNotifications: () -> Unit,
    onReportProblem: (propertyId: String) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val vm: MapViewModel = viewModel { MapViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()
    val wallet by c.game.wallet.collectAsStateWithLifecycle()
    val boot by c.game.bootstrap.collectAsStateWithLifecycle()
    val cfg = c.game.gameConfig()
    val radius = cfg.interactionRadiusM

    LifecycleStartEffect(Unit) {
        vm.startLocation()
        onStopOrDispose { vm.stopLocation() }
    }

    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var legend by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setMinZoomPreference(CityMapBounds.MIN_ZOOM)
            m.setMaxZoomPreference(CityMapBounds.MAX_ZOOM)
            // Limita a navegação ao entorno do município; o servidor delimita os imóveis publicados.
            m.setLatLngBoundsForCameraTarget(LatLngBounds.Builder()
                .include(LatLng(CityMapBounds.NORTH, CityMapBounds.WEST))
                .include(LatLng(CityMapBounds.SOUTH, CityMapBounds.EAST))
                .build())
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isTiltGesturesEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) vm.onUserGesture()
            }
            m.addOnCameraIdleListener {
                val target = m.cameraPosition.target ?: return@addOnCameraIdleListener
                val w = mapView.width.toFloat()
                val h = mapView.height.toFloat()
                if (w <= 0f || h <= 0f) return@addOnCameraIdleListener
                val corners = listOf(PointF(0f, 0f), PointF(w, 0f), PointF(0f, h), PointF(w, h)).map { m.projection.fromScreenLocation(it) }
                val bbox = doubleArrayOf(
                    corners.minOf { it.longitude }, corners.minOf { it.latitude },
                    corners.maxOf { it.longitude }, corners.maxOf { it.latitude },
                )
                vm.onCameraIdle(bbox, m.cameraPosition.zoom, target.latitude, target.longitude)
            }
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val r = context.dp(22f)
                val hits = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), *MapLayers.TAPPABLE)
                vm.onTapFeatures(
                    hits.mapNotNull { runCatching { DvJson.parseToJsonElement(it.toJson()).jsonObject }.getOrNull() },
                    latLng.latitude, latLng.longitude,
                )
                true
            }
            m.setStyle(baseStyle(c.config)) { s ->
                MapLayers.install(context, s, c.game.profile()?.avatarId)
                style = s
            }
            map = m
        }
    }

    // Câmera pedida pelo ViewModel (seguir jogador, recentrar, ir para a região).
    LaunchedEffect(map, camera) {
        val m = map ?: return@LaunchedEffect
        val t = camera ?: return@LaunchedEffect
        val pos = CameraPosition.Builder(m.cameraPosition)
            .target(LatLng(t.lat.coerceIn(CityMapBounds.SOUTH, CityMapBounds.NORTH),
                t.lon.coerceIn(CityMapBounds.WEST, CityMapBounds.EAST)))
            .apply { t.zoom?.let { zoom(it) } }
            .tilt(if (ui.topView) 0.0 else TILT)
            .build()
        if (t.animate) m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 700)
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        vm.cameraConsumed(t)
    }
    LaunchedEffect(map, ui.topView) {
        val m = map ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(m.cameraPosition).tilt(if (ui.topView) 0.0 else TILT).build()), 500)
    }
    LaunchedEffect(style, ui.data) { style?.let { MapLayers.setData(it, ui.data) } }
    LaunchedEffect(style, ui.filters) { style?.let { MapLayers.setFilter(it, ui.filters) } }
    LaunchedEffect(style, ui.topView) { style?.let { MapLayers.setTopView(it, ui.topView) } }
    LaunchedEffect(style, ui.selectedId, ui.data) { style?.let { MapLayers.setSelected(it, vm.selectedFeature()) } }
    LaunchedEffect(style, boot?.profile?.avatarId) { style?.let { MapLayers.setAvatar(context, it, boot?.profile?.avatarId) } }

    // Avatar suavizado entre leituras (a posição oficial para ações é sempre uma leitura nova).
    var shown by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(style, ui.me) {
        val s = style ?: return@LaunchedEffect
        val me = ui.me
        if (me == null) {
            MapLayers.setMe(s, null, null, null, radius); shown = null; return@LaunchedEffect
        }
        val from = shown?.takeIf { Geo.distanceM(it.first, it.second, me.lat, me.lon) < 300 } ?: (me.lat to me.lon)
        val steps = 12
        for (i in 1..steps) {
            val k = i / steps.toDouble()
            val lat = from.first + (me.lat - from.first) * k
            val lon = from.second + (me.lon - from.second) * k
            MapLayers.setMe(s, lat, lon, me.accuracyM.toDouble(), radius)
            shown = lat to lon
            if (i < steps) delay(50)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Topo: saldo, notificações, filtros e avisos
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp, shadowElevation = 3.dp) {
                Row(Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(boot?.profile?.avatarId, size = 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).clickable(onClick = onOpenWallet)) {
                        Text("${Money.format(wallet?.available)} moedas", fontWeight = FontWeight.Bold)
                        val reserved = Money.parse(wallet?.reserved)
                        Text(
                            if (reserved.signum() > 0) "disponível · ${Money.format(reserved)} reservado" else "saldo disponível",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(onClick = onOpenNotifications) { Icon(Icons.Filled.Notifications, contentDescription = "Notificações") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PropertyState.entries.forEach { s ->
                    FilterChip(
                        selected = s in ui.filters,
                        onClick = { vm.toggleFilter(s) },
                        label = { Text(s.label) },
                        leadingIcon = { Icon(s.icon, contentDescription = null, tint = s.color, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp))
                    Text("SÃO PAULO  ·  EXPLORE E COMPRE",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            MapBanners(ui, offline, cfg.maxGpsAccuracyM, c.config.allowMockLocation, onRequestPermission, vm)
        }

        // Ações do mapa
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(onClick = { legend = true }) { Icon(Icons.Filled.Info, contentDescription = "Legenda do mapa") }
            SmallFloatingActionButton(onClick = vm::toggleTopView) {
                if (ui.topView) Icon(Icons.Filled.ViewInAr, contentDescription = "Ver em 3D")
                else Icon(Icons.Filled.Map, contentDescription = "Ver de cima")
            }
            SmallFloatingActionButton(onClick = vm::openNearby) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Imóveis ao alcance")
            }
            FloatingActionButton(onClick = vm::recenter) {
                Icon(if (ui.me == null) Icons.Filled.GpsOff else Icons.Filled.MyLocation, contentDescription = "Centralizar em mim")
            }
        }
    }

    val here = ui.me?.let { LatLonDto(it.lat, it.lon) }
    ui.selectedId?.let { id ->
        ModalBottomSheet(onDismissRequest = { vm.select(null) }) {
            PropertySheet(id, here, onReportProblem = onReportProblem, onOpenProposals = onOpenProposals)
        }
    }
    if (ui.candidates.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = vm::dismissCandidates) {
            Text("Qual imóvel?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(ui.candidates, key = { it.id }) { cand ->
                    CandidateRow(cand.category, cand.subtype, null, cand.state,
                        here?.let { Geo.distanceM(it.lat, it.lon, cand.lat, cand.lon) }) { vm.select(cand.id) }
                }
            }
        }
    }
    ui.nearby?.let { nearby ->
        ModalBottomSheet(onDismissRequest = vm::closeNearby) {
            Text("Ao seu alcance ($radius m)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            when (nearby) {
                Loadable.Loading -> Box(Modifier.fillMaxWidth().height(160.dp)) { LoadingBox() }
                is Loadable.Error -> ErrorBox(nearby.message, onRetry = vm::openNearby, modifier = Modifier.height(240.dp))
                is Loadable.Ready -> if (nearby.value.isEmpty()) {
                    EmptyState(Icons.Filled.LocationOff, "Nenhum imóvel a até $radius m. Caminhe até um prédio, casa ou comércio do mapa.")
                } else LazyColumn(Modifier.padding(bottom = 24.dp)) {
                    items(nearby.value, key = { it.id }) { p ->
                        CandidateRow(p.category, p.subtype, p.addressLabel, PropertyState.of(p.state), p.distanceM) { vm.select(p.id) }
                    }
                }
            }
        }
    }
    if (legend) LegendDialog(ui, radius) { legend = false }
}

@Composable
private fun MapBanners(
    ui: MapUi,
    offline: Boolean,
    maxAccuracyM: Int,
    allowMock: Boolean,
    onRequestPermission: () -> Unit,
    vm: MapViewModel,
) {
    val banners = buildList<@Composable () -> Unit> {
        val data = ui.data
        when {
            data?.offlineSince != null -> add { OfflineBanner(data.offlineSince) }
            offline -> add { Banner("Sem conexão. O mapa pode estar desatualizado; ações exigem internet.", icon = Icons.Filled.CloudOff) }
        }
        when (ui.permission) {
            LocationPermission.DENIED -> add {
                Banner("Localização desligada: você pode explorar, mas não negociar.", icon = Icons.Filled.LocationOff,
                    action = "Ativar", onAction = onRequestPermission)
            }
            LocationPermission.APPROXIMATE -> add {
                Banner("Localização aproximada: ative a precisa para negociar.", icon = Icons.Filled.LocationOff,
                    action = "Ativar", onAction = onRequestPermission)
            }
            LocationPermission.PRECISE -> {
                val me = ui.me
                when {
                    ui.noSignal -> add { Banner("Sem sinal de GPS. Vá para um local aberto e verifique se a localização do aparelho está ligada.", icon = Icons.Filled.GpsOff) }
                    me != null && me.isMock && !allowMock -> add { Banner("Localização simulada detectada: negociações serão recusadas.", icon = Icons.Filled.GpsOff) }
                    me != null && me.accuracyM > maxAccuracyM -> add {
                        Banner("GPS impreciso (±${me.accuracyM.roundToInt()} m). Para negociar é preciso até $maxAccuracyM m.", icon = Icons.Filled.GpsOff)
                    }
                }
            }
        }
        when {
            data != null && data.regionStatus == "unavailable" -> add {
                Banner("Fora da região do jogo. Os imóveis aparecem apenas nas regiões liberadas.", action = "Ir para a região", onAction = vm::goToRegion)
            }
            data?.mode == "clusters" -> add { Banner("Aproxime o mapa para ver e negociar os imóveis.", icon = Icons.Filled.ZoomIn) }
            ui.error != null -> add {
                if (ui.areaTooLarge) Banner(ui.error, icon = Icons.Filled.ZoomIn)
                else Banner(ui.error, action = "Tentar de novo", onAction = vm::retry)
            }
            data?.truncated == true -> add { Banner("Mostrando parte dos imóveis. Aproxime o mapa para ver mais.", icon = Icons.Filled.ZoomIn) }
        }
    }
    banners.take(2).forEach { it() }
}

@Composable
private fun CandidateRow(category: String?, subtype: String?, address: String?, state: PropertyState, distanceM: Double?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(Avatars.category(category)), contentDescription = null, tint = state.color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(categoryLabel(category), fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(subtype, address?.let(::addressLabel)).joinToString(" · ").ifEmpty { "Sem endereço informado" },
                style = MaterialTheme.typography.bodySmall)
            distanceM?.let { Text("${it.roundToInt()} m de você", style = MaterialTheme.typography.labelSmall) }
        }
        StateBadge(state)
    }
}

@Composable
private fun LegendDialog(ui: MapUi, radius: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Entendi") } },
        title = { Text("Como ler o mapa") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyState.entries.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.icon, contentDescription = null, tint = s.color)
                        Spacer(Modifier.width(8.dp))
                        Text(s.label)
                    }
                }
                Text("Círculo verde tracejado: alcance de $radius m para negociar.")
                Text("Círculo azul: precisão atual do GPS (quanto menor, melhor).")
                Text("As alturas dos prédios em 3D são ilustrativas; quando o dado não existe, usamos andares ou um padrão da categoria.")
                Text("Toque em um imóvel para ver a ficha. Se houver vários próximos, escolha na lista.")
                ui.data?.regions?.mapNotNull { it.attribution }?.distinct()?.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
    )
}

private const val TILT = 45.0
