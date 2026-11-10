package app.donodavez.property

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import app.donodavez.core.model.OpeningBonusDto
import app.donodavez.core.model.PropertyDetailDto
import app.donodavez.core.model.WalletDto
import app.donodavez.core.ui.Avatars
import app.donodavez.core.ui.Banner
import app.donodavez.core.ui.BusyButton
import app.donodavez.core.ui.CoinAmount
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

/** Faixa do topo da ficha: gradiente na cor do estado, ícone da categoria, selo e valor principal. */
@Composable
private fun Header(
    title: String,
    subtitle: String?,
    category: String?,
    state: PropertyState,
    valueLabel: String,
    value: String?,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(state.color, state.shade)))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Color.White, shadowElevation = 2.dp, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(Avatars.category(category)), contentDescription = null,
                        tint = state.color, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f)) }
            }
            Spacer(Modifier.width(8.dp))
            StateBadge(state, onColor = true)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.18f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(valueLabel, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                CoinAmount(value, style = MaterialTheme.typography.headlineSmall, iconSize = 26.dp, color = Color.White)
            }
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

    val own = d.ownership
    // Valor principal do cabeçalho (sai da lista abaixo para não repetir).
    val headline: Pair<String, String?> = when {
        own == null -> "Preço da categoria" to (d.actions.purchase.price ?: d.referencePrice)
        own.isMine -> "Valor pago" to own.purchasePrice
        else -> "Último valor pago" to own.purchasePrice
    }
    Header(
        title = p.categoryName ?: categoryLabel(p.category),
        subtitle = p.subtype,
        category = p.category,
        state = state,
        valueLabel = headline.first,
        value = headline.second,
    )
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
    when (p.classificationStatus) {
        "resolved" -> Unit
        // Regras v2 do importador: o mapa não informa o uso; altura e tamanho estimam a categoria.
        "inferred" -> Text("Categoria estimada pela altura e pelo tamanho do prédio (o mapa não informa o uso).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Text("Categoria em revisão: o preço pode ser ajustado antes da liberação.", style = MaterialTheme.typography.bodySmall)
    }
    if (p.heightSource != null && p.heightSource != "source") {
        Text("Altura do modelo 3D ilustrativa (estimada a partir de andares ou padrão da categoria).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    HorizontalDivider()

    // Dono e renda
    when {
        own == null -> {
            Text("Sem dono", fontWeight = FontWeight.SemiBold)
            MoneyLine("Renda por dia", d.actions.purchase.dailyIncome ?: Money.dailyIncome(d.referencePrice, d.dailyIncomeRate))
            d.actions.purchase.openingBonus?.let { OpeningBonusOffer(it) }
        }
        own.isMine -> {
            Text("Este imóvel é seu desde ${formatDateTime(own.acquiredAt)}", fontWeight = FontWeight.SemiBold)
            MoneyLine("Renda por dia", own.ownerDailyIncome)
            d.mine?.let { mine ->
                mine.openingBonus?.let { b ->
                    MoneyLine("Bônus de inauguração por dia", b.daily)
                    Countdown("Inauguração termina em", b.until, c.clock)
                }
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

/** Bônus de inauguração que uma compra do sistema receberia agora (o servidor grava o valor na compra). */
@Composable
private fun OpeningBonusOffer(b: OpeningBonusDto) {
    MoneyLine("Bônus de inauguração por dia", b.daily)
    val period = PropertyRules.openingBonusPeriod(b.seconds)
    Text(
        if (period == null) "Renda extra por tempo limitado após a compra; depois fica só a renda por dia."
        else "Renda extra $period após a compra; depois fica só a renda por dia.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    d.actions.purchase.openingBonus?.let { OpeningBonusOffer(it) }
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

