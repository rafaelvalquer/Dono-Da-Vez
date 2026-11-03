package app.donodavez.portfolio

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.model.Money
import app.donodavez.core.model.PortfolioDto
import app.donodavez.core.model.PortfolioItemDto
import app.donodavez.core.ui.Avatars
import app.donodavez.core.ui.BusyButton
import app.donodavez.core.ui.EmptyState
import app.donodavez.core.ui.ErrorBox
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.LoadingBox
import app.donodavez.core.ui.MoneyLine
import app.donodavez.core.ui.OfflineBanner
import app.donodavez.core.ui.PropertyState
import app.donodavez.core.ui.addressLabel
import app.donodavez.core.ui.categoryLabel
import app.donodavez.core.ui.formatDateTime
import app.donodavez.property.PropertySheet

/** Meus imóveis: totais, busca, ordenação e coleta de renda; toque abre a ficha. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(offline: Boolean, onReportProblem: (String) -> Unit, onOpenProposals: () -> Unit) {
    val c = LocalAppContainer.current
    val vm: PortfolioViewModel = viewModel { PortfolioViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var opened by rememberSaveable { mutableStateOf<String?>(null) }

    when (val page = ui.page) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Error -> ErrorBox(page.message, onRetry = { vm.load() })
        is Loadable.Ready -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { vm.load() }, modifier = Modifier.fillMaxSize()) {
            Content(page.value, page.savedAt, ui, offline, vm) { opened = it }
        }
    }
    opened?.let { id ->
        ModalBottomSheet(onDismissRequest = { opened = null; vm.load() }) {
            PropertySheet(id, here = null, onReportProblem = onReportProblem, onOpenProposals = onOpenProposals)
        }
    }
}

@Composable
private fun Content(p: PortfolioDto, savedAt: Long?, ui: PortfolioUi, offline: Boolean, vm: PortfolioViewModel, onOpen: (String) -> Unit) {
    val visible = remember(p.items, ui.query, ui.sort, ui.onlyWithOffers) {
        PortfolioViewModel.visible(p.items, ui.query, ui.sort, ui.onlyWithOffers)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Meus imóveis", style = MaterialTheme.typography.headlineSmall) }
        if (savedAt != null) item { OfflineBanner(savedAt) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (p.totals.count == 1) "1 imóvel" else "${p.totals.count} imóveis", fontWeight = FontWeight.Bold)
                    MoneyLine("Total investido", p.totals.invested)
                    MoneyLine("Renda por dia", p.totals.dailyIncome, emphasize = true)
                    MoneyLine("Renda para coletar", p.totals.collectable)
                    BusyButton("Coletar tudo", busy = ui.collecting,
                        enabled = Money.parse(p.totals.collectable).signum() > 0 && !offline && savedAt == null) { vm.collect() }
                    ui.message?.let { Text(it, color = PropertyState.AVAILABLE.color, modifier = Modifier.padding(top = 6.dp)) }
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
        if (p.items.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = ui.query, onValueChange = vm::setQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Buscar por tipo ou endereço") },
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PortfolioSort.entries.forEach { s -> FilterChip(selected = ui.sort == s, onClick = { vm.setSort(s) }, label = { Text(s.label) }) }
                    FilterChip(selected = ui.onlyWithOffers, onClick = vm::toggleOffers, label = { Text("Com propostas") },
                        leadingIcon = { Icon(Icons.Filled.Mail, contentDescription = null, modifier = Modifier.size(18.dp)) })
                }
            }
        }
        when {
            p.items.isEmpty() -> item {
                EmptyState(Icons.Filled.Home, "Você ainda não tem imóveis. Caminhe até um imóvel disponível (verde) no mapa e compre estando a até 80 m.")
            }
            visible.isEmpty() -> item { EmptyState(Icons.Filled.Search, "Nenhum imóvel encontrado com esse filtro.") }
        }
        items(visible, key = { it.ownershipId }) { item ->
            PortfolioRow(item, collecting = ui.collecting, offline = offline || savedAt != null, onCollect = { vm.collect(item.ownershipId) }) {
                onOpen(item.property.id)
            }
        }
        if (p.nextCursor != null) item {
            OutlinedButton(onClick = vm::loadMore, enabled = !ui.loadingMore, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.loadingMore) "Carregando…" else "Carregar mais")
            }
        }
    }
}

@Composable
private fun PortfolioRow(item: PortfolioItemDto, collecting: Boolean, offline: Boolean, onCollect: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Avatars.category(item.property.category)), contentDescription = null,
                    tint = PropertyState.MINE.color, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(categoryLabel(item.property.category), fontWeight = FontWeight.SemiBold)
                    Text(addressLabel(item.property.addressLabel), style = MaterialTheme.typography.bodySmall)
                }
                if (item.pendingOffers > 0) Badge { Text("${item.pendingOffers} proposta${if (item.pendingOffers > 1) "s" else ""}") }
            }
            MoneyLine("Valor pago", item.purchasePrice)
            MoneyLine("Renda por dia", item.dailyIncome)
            MoneyLine("Para coletar", item.collectable, emphasize = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Desde ${formatDateTime(item.acquiredAt)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCollect, enabled = !collecting && !offline && Money.parse(item.collectable).signum() > 0) { Text("Coletar") }
            }
        }
    }
}
