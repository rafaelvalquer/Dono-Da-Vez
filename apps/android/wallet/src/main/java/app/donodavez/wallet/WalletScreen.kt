package app.donodavez.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.model.LedgerEntryDto
import app.donodavez.core.model.Money
import app.donodavez.core.model.WalletPageDto
import app.donodavez.core.ui.Banner
import app.donodavez.core.ui.BusyButton
import app.donodavez.core.ui.EmptyState
import app.donodavez.core.ui.ErrorBox
import app.donodavez.core.ui.InfoLine
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.LoadingBox
import app.donodavez.core.ui.MoneyLine
import app.donodavez.core.ui.OfflineBanner
import app.donodavez.core.ui.PropertyState
import app.donodavez.core.ui.formatDateTime
import app.donodavez.core.ui.holdStatusLabel
import app.donodavez.core.ui.propertyLabel
import app.donodavez.core.ui.transactionKindLabel

/** Carteira: saldo, reservado, disponível, renda a coletar, reservas e extrato do ledger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(offline: Boolean, onOpenHistory: () -> Unit) {
    val c = LocalAppContainer.current
    val vm: WalletViewModel = viewModel { WalletViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    when (val page = ui.page) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Error -> ErrorBox(page.message, onRetry = { vm.load() })
        is Loadable.Ready -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { vm.load() }, modifier = Modifier.fillMaxSize()) {
            WalletContent(page.value, page.savedAt, ui, offline, vm, onOpenHistory)
        }
    }
}

@Composable
private fun WalletContent(p: WalletPageDto, savedAt: Long?, ui: WalletUi, offline: Boolean, vm: WalletViewModel, onOpenHistory: () -> Unit) {
    val w = p.wallet
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Carteira", style = MaterialTheme.typography.headlineSmall) }
        if (savedAt != null) item { OfflineBanner(savedAt) }
        if (w?.frozen == true) item {
            Banner("Carteira em revisão pelo suporte: negociações estão pausadas. Consulte a Ajuda.", icon = Icons.Filled.Lock)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    MoneyLine("Saldo total", w?.balance, emphasize = true)
                    MoneyLine("Reservado em propostas", w?.reserved)
                    MoneyLine("Disponível para usar", w?.available, emphasize = true)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MoneyLine("Renda acumulada para coletar", w?.collectableIncome)
                    val canCollect = Money.parse(w?.collectableIncome).signum() > 0
                    BusyButton("Coletar renda de todos os imóveis", busy = ui.collecting, enabled = canCollect && !offline && savedAt == null) {
                        vm.collectAll()
                    }
                    ui.message?.let { Text(it, color = PropertyState.AVAILABLE.color, modifier = Modifier.padding(top = 6.dp)) }
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
        item {
            Text("As moedas são fictícias: não têm valor em dinheiro, não podem ser compradas, sacadas ou transferidas.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (p.holds.isNotEmpty()) {
            item { Text("Reservas de propostas", style = MaterialTheme.typography.titleMedium) }
            items(p.holds, key = { it.holdId }) { h ->
                InfoLine("${holdStatusLabel(h.status, h.releaseReason)} · ${formatDateTime(h.releasedAt ?: h.createdAt)}", "${Money.format(h.amount)} moedas")
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Extrato", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenHistory) { Text("Histórico completo") }
            }
        }
        if (p.entries.isEmpty()) item { EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, "Nenhuma movimentação ainda.") }
        items(p.entries, key = { it.entryId }) { e -> EntryRow(e) }
        if (p.nextCursor != null) item {
            OutlinedButton(onClick = vm::loadMore, enabled = !ui.loadingMore, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.loadingMore) "Carregando…" else "Carregar mais")
            }
        }
    }
}

@Composable
private fun EntryRow(e: LedgerEntryDto) {
    val amount = Money.parse(e.amount)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(transactionKindLabel(e.transactionKind), fontWeight = FontWeight.SemiBold)
            if (e.property != null) Text(propertyLabel(e.property), style = MaterialTheme.typography.bodySmall)
            Text(formatDateTime(e.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (amount.signum() > 0) "+" else "−") + Money.format(amount.abs()),
            color = if (amount.signum() > 0) PropertyState.AVAILABLE.color else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
    }
    HorizontalDivider()
}
