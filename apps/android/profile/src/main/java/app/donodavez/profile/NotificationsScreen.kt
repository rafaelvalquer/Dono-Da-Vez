package app.donodavez.profile

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.donodavez.core.model.Money
import app.donodavez.core.model.NotificationDto
import app.donodavez.core.model.NotificationPageDto
import app.donodavez.core.net.decode
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.EmptyState
import app.donodavez.core.ui.ErrorBox
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.LoadingBox
import app.donodavez.core.ui.formatDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

class NotificationsViewModel(private val c: AppContainer) : ViewModel() {
    private val _page = MutableStateFlow<Loadable<NotificationPageDto>>(Loadable.Loading)
    val page: StateFlow<Loadable<NotificationPageDto>> = _page.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _page.value = try {
            Loadable.Ready(c.api.get("/v1/notifications", mapOf("limit" to 50)).decode())
        } catch (e: Exception) {
            Loadable.Error(e.userMessage())
        }
    }

    fun loadMore() {
        val current = (_page.value as? Loadable.Ready)?.value ?: return
        val cursor = current.nextCursor ?: return
        viewModelScope.launch {
            runCatching { c.api.get("/v1/notifications", mapOf("limit" to 50, "cursor" to cursor)).decode<NotificationPageDto>() }
                .onSuccess { more -> _page.value = Loadable.Ready(current.copy(items = current.items + more.items, nextCursor = more.nextCursor)) }
        }
    }

    /** Marca como lidas: [ids] específicos ou todas (null). */
    fun markRead(ids: List<String>?) = viewModelScope.launch {
        val body = buildJsonObject { if (ids != null) put("ids", JsonArray(ids.map(::JsonPrimitive))) }
        runCatching { c.api.post("/v1/notifications/read", body) }.onSuccess {
            _page.update { p ->
                val r = (p as? Loadable.Ready)?.value ?: return@update p
                val now = c.clock.now().toString()
                val items = r.items.map { n -> if (ids == null || n.id in ids) n.copy(readAt = n.readAt ?: now) else n }
                Loadable.Ready(r.copy(items = items, unread = items.count { it.readAt == null }))
            }
        }
    }
}

/** Central de notificações (cópia no app de tudo que pode chegar por push). */
@Composable
fun NotificationsScreen(onOpenProposals: () -> Unit, onOpenWallet: () -> Unit) {
    val c = LocalAppContainer.current
    val vm: NotificationsViewModel = viewModel { NotificationsViewModel(c) }
    val page by vm.page.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notificações", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            val unread = (page as? Loadable.Ready)?.value?.unread ?: 0
            if (unread > 0) TextButton(onClick = { vm.markRead(null) }) { Text("Marcar todas como lidas") }
        }
        when (val p = page) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Error -> ErrorBox(p.message, onRetry = { vm.load() })
            is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                if (p.value.items.isEmpty()) item { EmptyState(Icons.Filled.NotificationsNone, "Nenhuma notificação.") }
                items(p.value.items, key = { it.id }) { n ->
                    NotificationRow(n) {
                        if (n.readAt == null) vm.markRead(listOf(n.id))
                        when {
                            n.type.startsWith("proposal.") || n.type == "risk.review_decided" -> onOpenProposals()
                            n.type.startsWith("property.") || n.type.startsWith("wallet.") -> onOpenWallet()
                        }
                    }
                }
                if (p.value.nextCursor != null) item {
                    OutlinedButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Carregar mais") }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: NotificationDto, onClick: () -> Unit) {
    val (title, body) = notificationText(n.type, n.payload)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        if (n.readAt == null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp).size(8.dp)) {}
        } else Spacer(Modifier.size(8.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (n.readAt == null) FontWeight.Bold else FontWeight.Normal)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Text(formatDateTime(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

/** Mesmos textos dos pushes (supabase/functions/outbox-worker/templates.ts). */
fun notificationText(type: String, payload: JsonObject): Pair<String, String> {
    fun coins(k: String) = (payload[k] as? JsonPrimitive)?.contentOrNull?.let { "${Money.format(it)} moedas" } ?: "moedas"
    return when (type) {
        "proposal.received" -> "Nova proposta" to "Você recebeu uma oferta de ${coins("amount")}. Responda dentro do prazo."
        "proposal.expiring" -> "Proposta perto do prazo" to "A oferta de ${coins("amount")} vence em breve. Aceite ou recuse no app."
        "proposal.accepted" -> "Proposta aceita" to "O imóvel agora é seu. Confira no portfólio."
        "proposal.rejected" -> "Proposta recusada" to "O valor reservado voltou para o seu saldo disponível."
        "proposal.expired" -> "Prazo encerrado" to "O dono não respondeu. Você pode comprar por ${coins("forced_price")} no local, dentro da janela exibida no app."
        "proposal.unanswered" -> "Proposta sem resposta" to "O prazo terminou. O comprador pode concluir a compra com adicional de 20% por tempo limitado."
        "proposal.superseded" -> "Proposta encerrada" to "O imóvel mudou de dono e a sua reserva foi liberada."
        "proposal.cancelled_by_support" -> "Proposta cancelada" to "O suporte cancelou uma proposta e liberou a reserva."
        "property.sold" -> "Imóvel vendido" to "Você recebeu ${coins("price")} pela venda."
        "property.forced_sale" -> "Imóvel comprado sem aceite" to "Você recebeu ${coins("price")}, incluindo o adicional de 20%."
        "property.compensated" -> "Imóvel encerrado com compensação" to "Você recebeu ${coins("compensation")} de compensação."
        "wallet.adjustment" -> "Ajuste na carteira" to "Um ajuste registrado foi aplicado. Veja o extrato."
        "risk.review_decided" -> "Revisão concluída" to "A revisão de uma negociação foi concluída."
        "report.updated" -> "Atualização do chamado" to "O chamado ${(payload["protocol"] as? JsonPrimitive)?.contentOrNull.orEmpty()} foi atualizado."
        else -> "Aviso" to "Abra o app para ver os detalhes."
    }
}
