package app.donodavez.wallet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donodavez.core.AppContainer
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.model.HistoryItemDto
import app.donodavez.core.model.HistoryPageDto
import app.donodavez.core.model.Money
import app.donodavez.core.net.decode
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.EmptyState
import app.donodavez.core.ui.ErrorBox
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.LoadingBox
import app.donodavez.core.ui.OfflineBanner
import app.donodavez.core.ui.formatDateTime
import app.donodavez.core.ui.historyKindLabel
import app.donodavez.core.ui.proposalStatusLabel
import app.donodavez.core.ui.propertyLabel
import app.donodavez.core.ui.tradeTypeLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class HistoryFilter(val key: String, val label: String) {
    ALL("all", "Tudo"), PURCHASES("purchases", "Compras"), SALES("sales", "Vendas"),
    PROPOSALS("proposals", "Propostas"), INCOME("income", "Renda"),
}

data class HistoryUi(
    val filter: HistoryFilter = HistoryFilter.ALL,
    val page: Loadable<HistoryPageDto> = Loadable.Loading,
    val loadingMore: Boolean = false,
)

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HistoryUi())
    val ui: StateFlow<HistoryUi> = _ui.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun setFilter(f: HistoryFilter) {
        if (f == _ui.value.filter) return
        _ui.update { HistoryUi(filter = f) }
        load()
    }

    fun load() {
        val f = _ui.value.filter
        job?.cancel()
        job = viewModelScope.launch {
            val key = "history-${f.key}"
            val next: Loadable<HistoryPageDto> = try {
                val p: HistoryPageDto = c.api.get("/v1/history", mapOf("kind" to f.key, "limit" to 50)).decode()
                c.snapshots.save(key, HistoryPageDto.serializer(), p)
                Loadable.Ready(p)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                c.snapshots.load(key, HistoryPageDto.serializer())?.let { (p, at) -> Loadable.Ready(p, at) } ?: Loadable.Error(e.userMessage())
            }
            _ui.update { it.copy(page = next) }
        }
    }

    fun loadMore() {
        val current = (_ui.value.page as? Loadable.Ready)?.value ?: return
        val cursor = current.nextCursor ?: return
        if (_ui.value.loadingMore) return
        val f = _ui.value.filter
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val more = runCatching { c.api.get("/v1/history", mapOf("kind" to f.key, "limit" to 50, "cursor" to cursor)).decode<HistoryPageDto>() }.getOrNull()
            _ui.update {
                if (more == null || it.filter != f) it.copy(loadingMore = false)
                else it.copy(page = Loadable.Ready(current.copy(items = current.items + more.items, nextCursor = more.nextCursor)), loadingMore = false)
            }
        }
    }
}

/** Histórico do jogador: compras, vendas, propostas e renda, com filtros. */
@Composable
fun HistoryScreen() {
    val c = LocalAppContainer.current
    val vm: HistoryViewModel = viewModel { HistoryViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Text("Histórico", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryFilter.entries.forEach { f -> FilterChip(selected = ui.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) }) }
        }
        when (val page = ui.page) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Error -> ErrorBox(page.message, onRetry = vm::load)
            is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                page.savedAt?.let { at -> item { OfflineBanner(at) } }
                if (page.value.items.isEmpty()) item { EmptyState(Icons.Filled.History, "Nada por aqui ainda.") }
                items(page.value.items, key = { "${it.kind}-${it.ref}" }) { HistoryRow(it) }
                if (page.value.nextCursor != null) item {
                    OutlinedButton(onClick = vm::loadMore, enabled = !ui.loadingMore, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(if (ui.loadingMore) "Carregando…" else "Carregar mais")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryItemDto) {
    fun detail(k: String) = h.detail[k]?.jsonPrimitive?.contentOrNull
    val icon = when (h.kind) {
        "purchase" -> Icons.Filled.ShoppingCart
        "sale" -> Icons.Filled.Sell
        "proposal" -> Icons.Filled.Mail
        else -> Icons.Filled.Payments
    }
    val subtitle = when (h.kind) {
        "purchase", "sale" -> listOfNotNull(tradeTypeLabel(detail("trade_type")).ifEmpty { null }, detail("counterpart")?.let { "com $it" }).joinToString(" ")
        "proposal" -> (if (detail("direction") == "sent") "Enviada · " else "Recebida · ") + proposalStatusLabel(detail("status") ?: "")
        else -> "Coleta de renda"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${historyKindLabel(h.kind)} · ${Money.format(h.amount)} moedas", fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
            if (h.property != null) Text(propertyLabel(h.property), style = MaterialTheme.typography.bodySmall)
            Text(formatDateTime(h.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}
