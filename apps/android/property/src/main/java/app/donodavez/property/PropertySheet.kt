package app.donodavez.property

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donodavez.core.AppContainer
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.location.Geo
import app.donodavez.core.model.LatLonDto
import app.donodavez.core.model.Money
import app.donodavez.core.model.PropertyDetailDto
import app.donodavez.core.model.WalletDto
import app.donodavez.core.ui.Banner
import app.donodavez.core.ui.BusyButton
import app.donodavez.core.ui.Countdown
import app.donodavez.core.ui.ErrorBox
import app.donodavez.core.ui.InfoLine
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.LoadingBox
import app.donodavez.core.ui.MoneyLine
import app.donodavez.core.ui.PropertyState
import app.donodavez.core.ui.StateBadge
import app.donodavez.core.ui.addressLabel
import app.donodavez.core.ui.categoryLabel
import app.donodavez.core.ui.eligibilityLabel
import app.donodavez.core.ui.formatDateTime
import app.donodavez.core.ui.proposalStatusLabel
import app.donodavez.core.ui.tradeTypeLabel
import kotlin.math.roundToInt

/**
 * Ficha do imóvel (conteúdo do bottom sheet): dados, dono, renda, ações e histórico.
 * [here] é a última posição do jogador (estimativa de distância); o servidor decide a presença na prova.
 */
@Composable
fun PropertySheet(
    propertyId: String,
    here: LatLonDto?,
    onReportProblem: (propertyId: String) -> Unit,
    onOpenProposals: () -> Unit,
) {
    val c = LocalAppContainer.current
    val vm: PropertyViewModel = viewModel(key = "property-$propertyId") { PropertyViewModel(c, propertyId) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val wallet by c.game.wallet.collectAsStateWithLifecycle()

    when (val d = detail) {
        Loadable.Loading -> Box(Modifier.fillMaxWidth().height(220.dp)) { LoadingBox() }
        is Loadable.Error -> Box(Modifier.fillMaxWidth().height(260.dp)) { ErrorBox(d.message, onRetry = { vm.load() }) }
        is Loadable.Ready -> Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Content(d.value, action, wallet, here, c, vm, onOpenProposals)
            TextButton(onClick = { onReportProblem(propertyId) }) { Text("Reportar problema neste imóvel") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ColumnScope.Content(
    d: PropertyDetailDto,
    action: ActionUi,
    wallet: WalletDto?,
    here: LatLonDto?,
    c: AppContainer,
    vm: PropertyViewModel,
    onOpenProposals: () -> Unit,
) {
    val p = d.property
    val state = PropertyRules.viewerState(d)
    val radius = c.game.gameConfig().interactionRadiusM
    val target = p.interactionPoint ?: LatLonDto(p.lat, p.lon)
    val distanceM = here?.let { Geo.distanceM(it.lat, it.lon, target.lat, target.lon) }

    // Cabeçalho
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.categoryName ?: categoryLabel(p.category), style = MaterialTheme.typography.titleLarge)
            p.subtype?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        StateBadge(state)
    }
    Text(addressLabel(p.addressLabel))
    if (distanceM != null) {
        val near = distanceM <= radius
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = if (near) PropertyState.AVAILABLE.color else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(4.dp))
            Text(
                if (near) "Você está a cerca de ${distanceM.roundToInt()} m (dentro do raio de $radius m)"
                else "Você está a cerca de ${distanceM.roundToInt()} m. Aproxime-se até $radius m para negociar.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (p.eligibility != "eligible") Banner(eligibilityLabel(p.eligibility, p.eligibilityReason))
    if (p.classificationStatus != "resolved") {
        Text("Categoria em revisão: o preço pode ser ajustado antes da liberação.", style = MaterialTheme.typography.bodySmall)
    }
    if (p.heightSource != null && p.heightSource != "source") {
        Text("Altura do modelo 3D ilustrativa (estimada a partir de andares ou padrão da categoria).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    HorizontalDivider()

    // Dono e renda
    val own = d.ownership
    when {
        own == null -> {
            Text("Sem dono", fontWeight = FontWeight.SemiBold)
            MoneyLine("Preço da categoria", d.actions.purchase.price ?: d.referencePrice, emphasize = true)
            MoneyLine("Renda por dia", d.actions.purchase.dailyIncome ?: Money.dailyIncome(d.referencePrice, d.dailyIncomeRate))
        }
        own.isMine -> {
            Text("Este imóvel é seu desde ${formatDateTime(own.acquiredAt)}", fontWeight = FontWeight.SemiBold)
            MoneyLine("Valor pago", own.purchasePrice)
            MoneyLine("Renda por dia", own.ownerDailyIncome)
            d.mine?.let { mine ->
                MoneyLine("Acumulado", mine.accrued, decimals = 4)
                MoneyLine("Disponível para coletar", mine.collectable, emphasize = true)
                val canCollect = Money.parse(mine.collectable).signum() > 0
                BusyButton("Coletar renda", busy = action.busy && action.open == PropertyAction.COLLECT, enabled = canCollect && !action.busy) { vm.collect() }
                if (mine.pendingOffers > 0) {
                    OutlinedButton(onClick = onOpenProposals, modifier = Modifier.fillMaxWidth()) {
                        Text(if (mine.pendingOffers == 1) "Ver 1 proposta recebida" else "Ver ${mine.pendingOffers} propostas recebidas")
                    }
                }
            }
            own.protectedUntil?.let { Countdown("Proteção contra compra sem aceite", it, c.clock) }
        }
        else -> {
            Text("Dono: ${own.ownerNickname ?: "jogador"}", fontWeight = FontWeight.SemiBold)
            MoneyLine("Último valor pago", own.purchasePrice, emphasize = true)
            MoneyLine("Renda por dia do dono", own.ownerDailyIncome)
            own.protectedUntil?.let { Countdown("Protegido por mais", it, c.clock) }
        }
    }

    // Minha proposta
    d.myProposal?.let { mp ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Sua proposta: ${proposalStatusLabel(mp.status)}", fontWeight = FontWeight.SemiBold)
                MoneyLine("Valor (reservado)", mp.amount)
                if (mp.status == "pending") {
                    Countdown("Resposta do dono em até", mp.expiresAt, c.clock)
                    OutlinedButton(onClick = vm::cancelMyProposal, enabled = !action.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancelar proposta")
                    }
                }
            }
        }
    }

    HorizontalDivider()

    // Ações
    val a = d.actions
    when (action.open) {
        PropertyAction.PURCHASE -> PurchaseConfirm(d, wallet, action, vm)
        PropertyAction.PROPOSE -> ProposalForm(d, wallet, action, vm)
        PropertyAction.FORCED_INACTIVITY -> ForcedConfirm(
            title = "Compra sem aceite: dono inativo",
            explanation = "O dono não usa o jogo há mais de 14 dias. Você paga o último valor + 20% e o valor vai para o dono atual.",
            price = a.forcedInactivity.price, until = null, wallet, action, c, onConfirm = vm::forcedInactivity, onCancel = { vm.open(null) },
        )
        PropertyAction.FORCED_SILENCE -> ForcedConfirm(
            title = "Compra sem aceite: proposta sem resposta",
            explanation = "O dono não respondeu sua proposta em 24 h. Você pode comprar pelo valor da proposta + 20% até o fim da janela.",
            price = a.forcedSilence.price, until = a.forcedSilence.eligibleUntil, wallet, action, c,
            onConfirm = vm::forcedSilence, onCancel = { vm.open(null) },
        )
        else -> {
            if (a.purchase.allowed) {
                BusyButton("Comprar por ${Money.withUnit(a.purchase.price)}", busy = false, enabled = !action.busy) { vm.open(PropertyAction.PURCHASE) }
            }
            if (a.propose.allowed) {
                BusyButton("Fazer proposta ao dono", busy = false, enabled = !action.busy) { vm.open(PropertyAction.PROPOSE) }
            }
            if (a.forcedSilence.allowed) {
                OutlinedButton(onClick = { vm.open(PropertyAction.FORCED_SILENCE) }, enabled = !action.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Comprar sem aceite por ${Money.withUnit(a.forcedSilence.price)}")
                }
            }
            if (a.forcedInactivity.allowed) {
                OutlinedButton(onClick = { vm.open(PropertyAction.FORCED_INACTIVITY) }, enabled = !action.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Comprar sem aceite (dono inativo) por ${Money.withUnit(a.forcedInactivity.price)}")
                }
            }
            if (!a.purchase.allowed && !a.propose.allowed && !a.forcedSilence.allowed && !a.forcedInactivity.allowed) {
                val reason = listOf(a.purchase.reason, a.propose.reason, a.forcedInactivity.reason)
                    .firstNotNullOfOrNull { PropertyRules.reasonLabel(it) }
                if (reason != null && own?.isMine != true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(6.dp))
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (a.forcedInactivity.reason == "owner_active" && a.propose.allowed) {
                Text("Compra sem aceite só fica disponível se o dono ficar 14 dias inativo ou não responder uma proposta em 24 h.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    action.step?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    action.success?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = PropertyState.AVAILABLE.color)
            Spacer(Modifier.width(6.dp))
            Text(it)
        }
    }

    // Histórico
    if (d.history.isNotEmpty()) {
        HorizontalDivider()
        Text("Histórico de negociações", style = MaterialTheme.typography.titleSmall)
        d.history.forEach { t ->
            InfoLine(
                "${formatDateTime(t.occurredAt)} · ${tradeTypeLabel(t.type)}",
                "${Money.format(t.price)} moedas" + (t.buyerNickname?.let { " → $it" } ?: ""),
            )
        }
    }
    p.region?.attribution?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
}

@Composable
private fun PurchaseConfirm(d: PropertyDetailDto, wallet: WalletDto?, action: ActionUi, vm: PropertyViewModel) {
    var consent by rememberSaveable { mutableStateOf(false) }
    val price = d.actions.purchase.price
    val enough = Money.parse(wallet?.available) >= Money.parse(price)
    Text("Confirmar compra", style = MaterialTheme.typography.titleMedium)
    MoneyLine("Preço", price, emphasize = true)
    MoneyLine("Seu saldo disponível", wallet?.available)
    MoneyLine("Renda por dia após a compra", d.actions.purchase.dailyIncome)
    if (!enough) Text("Saldo disponível insuficiente.", color = MaterialTheme.colorScheme.error)
    ConsentRow(consent, "Confirmo a compra. Vou validar minha localização agora (preciso estar a até 80 m).") { consent = it }
    BusyButton("Confirmar compra", busy = action.busy, enabled = consent && enough, onClick = vm::purchase)
    TextButton(onClick = { vm.open(null) }, enabled = !action.busy) { Text("Voltar") }
}

@Composable
private fun ProposalForm(d: PropertyDetailDto, wallet: WalletDto?, action: ActionUi, vm: PropertyViewModel) {
    var amount by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    val normalized = Money.normalizeOffer(amount)
    val localError = if (amount.isBlank()) null else PropertyRules.offerError(amount, wallet?.available)
    Text("Fazer proposta", style = MaterialTheme.typography.titleMedium)
    MoneyLine("Último valor pago", d.ownership?.purchasePrice)
    OutlinedTextField(
        value = amount, onValueChange = { amount = it.take(18) }, label = { Text("Sua oferta (moedas)") },
        singleLine = true, isError = localError != null, supportingText = localError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
    MoneyLine("Saldo disponível", wallet?.available)
    MoneyLine("Valor que ficará reservado", normalized ?: "0")
    MoneyLine("Renda por dia se aceita", Money.dailyIncome(normalized, d.dailyIncomeRate))
    Text(
        "O valor fica reservado (não pode ser usado) até a resposta. O dono tem 24 h para aceitar ou recusar; " +
            "sem resposta, você poderá comprar sem aceite pelo valor + 20% por mais 24 h. Cancelar devolve a reserva.",
        style = MaterialTheme.typography.bodySmall,
    )
    ConsentRow(consent, "Entendo a reserva e o prazo de 24 h.") { consent = it }
    BusyButton("Enviar proposta", busy = action.busy, enabled = consent && normalized != null && localError == null) { vm.propose(amount) }
    TextButton(onClick = { vm.open(null) }, enabled = !action.busy) { Text("Voltar") }
}

@Composable
private fun ForcedConfirm(
    title: String,
    explanation: String,
    price: String?,
    until: String?,
    wallet: WalletDto?,
    action: ActionUi,
    c: AppContainer,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var consent by rememberSaveable { mutableStateOf(false) }
    val enough = Money.parse(wallet?.available) >= Money.parse(price)
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(explanation, style = MaterialTheme.typography.bodySmall)
    MoneyLine("Preço (+20%)", price, emphasize = true)
    MoneyLine("Seu saldo disponível", wallet?.available)
    if (until != null) Countdown("Janela termina em", until, c.clock)
    if (!enough) Text("Saldo disponível insuficiente.", color = MaterialTheme.colorScheme.error)
    ConsentRow(consent, "Confirmo a compra sem aceite. O valor será pago ao dono atual.") { consent = it }
    BusyButton("Confirmar compra sem aceite", busy = action.busy, enabled = consent && enough, onClick = onConfirm)
    TextButton(onClick = onCancel, enabled = !action.busy) { Text("Voltar") }
}

@Composable
private fun ConsentRow(checked: Boolean, text: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

