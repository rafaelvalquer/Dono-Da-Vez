package app.donodavez.proposals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donodavez.core.AppContainer
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.model.Money
import app.donodavez.core.model.ProposalDto
import app.donodavez.core.time.parseInstant
import app.donodavez.core.ui.Countdown
import app.donodavez.core.ui.EmptyState
import app.donodavez.core.ui.ErrorBox
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.LoadingBox
import app.donodavez.core.ui.MoneyLine
import app.donodavez.core.ui.OfflineBanner
import app.donodavez.core.ui.PropertyState
import app.donodavez.core.ui.formatDateTime
import app.donodavez.core.ui.proposalStatusLabel
import app.donodavez.core.ui.propertyLabel
import app.donodavez.property.PropertySheet

/** Caixa de propostas: recebidas (aceitar/recusar) e enviadas (cancelar, compra sem aceite após 24 h). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalsScreen(offline: Boolean, initialReceived: Boolean = true, onReportProblem: (String) -> Unit) {
    val c = LocalAppContainer.current
    val vm: ProposalsViewModel = viewModel { ProposalsViewModel(c, if (initialReceived) Direction.RECEIVED else Direction.SENT) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Pair<ProposalDto, ProposalCommand>?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text("Propostas", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        PrimaryTabRow(selectedTabIndex = ui.direction.ordinal) {
            Direction.entries.forEach { d -> Tab(selected = ui.direction == d, onClick = { vm.setDirection(d) }, text = { Text(d.label) }) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusFilter.entries.forEach { s -> FilterChip(selected = ui.status == s, onClick = { vm.setStatus(s) }, label = { Text(s.label) }) }
        }
        ui.message?.let { Text(it, color = PropertyState.AVAILABLE.color, modifier = Modifier.padding(horizontal = 16.dp)) }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        when (val page = ui.page) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Error -> ErrorBox(page.message, onRetry = vm::load)
            is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                page.savedAt?.let { at -> item { OfflineBanner(at) } }
                if (page.value.items.isEmpty()) item {
                    EmptyState(Icons.Filled.Mail, if (ui.direction == Direction.RECEIVED) "Nenhuma proposta recebida." else "Você não enviou propostas.")
                }
                items(page.value.items, key = { it.id }) { p ->
                    ProposalCard(p, c, busy = ui.busyId == p.id, enabled = ui.busyId == null && !offline && page.savedAt == null,
                        onCommand = { cmd -> confirm = p to cmd }, onOpen = { p.property?.id?.let { opened = it } })
                }
                if (page.value.nextCursor != null) item {
                    OutlinedButton(onClick = vm::loadMore, enabled = !ui.loadingMore, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ui.loadingMore) "Carregando…" else "Carregar mais")
                    }
                }
            }
        }
    }

    confirm?.let { (p, cmd) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(when (cmd) { ProposalCommand.ACCEPT -> "Vender o imóvel?"; ProposalCommand.REJECT -> "Recusar proposta?"; ProposalCommand.CANCEL -> "Cancelar proposta?" }) },
            text = {
                Text(when (cmd) {
                    ProposalCommand.ACCEPT -> "Você recebe ${Money.withUnit(p.amount)} e o imóvel passa para ${p.counterpartNickname ?: "o comprador"}. A renda acumulada até agora continua sua. Não é possível desfazer."
                    ProposalCommand.REJECT -> "O comprador recebe de volta o valor reservado. Ele não poderá comprar sem aceite por esta proposta."
                    ProposalCommand.CANCEL -> "O valor reservado (${Money.withUnit(p.amount)}) volta para seu saldo disponível."
                })
            },
            confirmButton = { Button(onClick = { vm.command(p, cmd); confirm = null }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Voltar") } },
        )
    }
    opened?.let { id ->
        ModalBottomSheet(onDismissRequest = { opened = null; vm.load() }) {
            PropertySheet(id, here = null, onReportProblem = onReportProblem, onOpenProposals = { opened = null })
        }
    }
}

@Composable
private fun ProposalCard(p: ProposalDto, c: AppContainer, busy: Boolean, enabled: Boolean, onCommand: (ProposalCommand) -> Unit, onOpen: () -> Unit) {
    val received = p.direction == "received"
    val now = c.clock.now()
    val pending = p.status == "pending" && (parseInstant(p.expiresAt)?.isAfter(now) ?: true)
    val forcedOpen = !received && (p.status == "expired" || (p.status == "pending" && !pending)) &&
        parseInstant(p.eligibleUntil)?.isAfter(now) == true

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp)) {
            Text(propertyLabel(p.property), fontWeight = FontWeight.SemiBold)
            Text((if (received) "De " else "Para ") + (p.counterpartNickname ?: "jogador"), style = MaterialTheme.typography.bodySmall)
            MoneyLine("Oferta", p.amount, emphasize = true)
            Text(proposalStatusLabel(if (p.status == "pending" && !pending) "expired" else p.status), style = MaterialTheme.typography.labelLarge)
            Text("Enviada em ${formatDateTime(p.createdAt)}", style = MaterialTheme.typography.labelSmall)
            when {
                pending && received -> {
                    Countdown("Responder em até", p.expiresAt, c.clock)
                    Text("Sem resposta em 24 h, o comprador poderá comprar sem aceite pelo valor + 20%.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onCommand(ProposalCommand.ACCEPT) }, enabled = enabled && !busy, modifier = Modifier.weight(1f)) {
                            Text(if (busy) "Enviando…" else "Aceitar")
                        }
                        OutlinedButton(onClick = { onCommand(ProposalCommand.REJECT) }, enabled = enabled && !busy, modifier = Modifier.weight(1f)) {
                            Text("Recusar")
                        }
                    }
                }
                pending -> {
                    Countdown("Resposta do dono em até", p.expiresAt, c.clock)
                    OutlinedButton(onClick = { onCommand(ProposalCommand.CANCEL) }, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Enviando…" else "Cancelar proposta")
                    }
                }
                forcedOpen -> {
                    MoneyLine("Compra sem aceite (+20%)", p.forcedPrice)
                    Countdown("Disponível por mais", p.eligibleUntil, c.clock)
                    Button(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Abrir ficha para comprar no local") }
                }
            }
        }
    }
}
