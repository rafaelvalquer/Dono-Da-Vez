package app.donodavez.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.model.ReportResultDto
import app.donodavez.core.net.decode
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.BusyButton
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ReportCategory(val key: String, val label: String) {
    MAP_ERROR("map_error", "Erro no mapa"),
    WRONG_CATEGORY("wrong_category", "Categoria errada"),
    MISSING_PROPERTY("missing_property", "Imóvel faltando"),
    ABUSE("abuse", "Abuso ou trapaça"),
    BUG("bug", "Erro no app"),
    ACCOUNT("account", "Conta"),
    OTHER("other", "Outro"),
}

/**
 * Ajuda: regras, problemas de mapa/GPS e suporte com protocolo.
 * [propertyId] preenche o chamado quando aberto a partir da ficha de um imóvel.
 */
@Composable
fun HelpScreen(propertyId: String?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ajuda", style = MaterialTheme.typography.headlineSmall)
        if (propertyId == null) {
            Section("Como funciona", initiallyOpen = true, items = RULES)
            Section("Propostas e compra sem aceite", items = PROPOSALS)
            Section("Problemas com mapa e GPS", items = MAP_PROBLEMS)
        }
        ReportForm(propertyId)
    }
}

@Composable
private fun Section(title: String, items: List<String>, initiallyOpen: Boolean = false) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Recolher" else "Expandir")
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportForm(propertyId: String?) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf(if (propertyId != null) ReportCategory.MAP_ERROR else ReportCategory.BUG) }
    var message by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var protocol by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (propertyId != null) "Reportar problema neste imóvel" else "Falar com o suporte", fontWeight = FontWeight.SemiBold)
            if (protocol != null) {
                Text("Recebemos seu chamado. Protocolo: $protocol", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Guarde o protocolo. Avisaremos pela central de notificações quando houver atualização.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReportCategory.entries.forEach { rc ->
                    FilterChip(selected = category == rc, onClick = { category = rc }, label = { Text(rc.label) })
                }
            }
            OutlinedTextField(
                value = message, onValueChange = { message = it.take(2000) }, modifier = Modifier.fillMaxWidth(), minLines = 4,
                label = { Text("Descreva o problema") },
                supportingText = { Text("${message.length}/2000 · não informe senha nem dados pessoais") },
            )
            BusyButton("Enviar", busy = busy, enabled = message.trim().length >= 5) {
                busy = true; error = null
                scope.launch {
                    try {
                        val r: ReportResultDto = c.api.post("/v1/reports", buildJsonObject {
                            put("category", category.key)
                            if (propertyId != null) put("property_id", propertyId)
                            put("message", message.trim())
                        }).decode()
                        protocol = r.protocol ?: "registrado"
                    } catch (e: Exception) {
                        error = e.userMessage()
                    } finally {
                        busy = false
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private val RULES = listOf(
    "Você começa com 5.000 moedas fictícias, creditadas uma única vez após confirmar o e-mail.",
    "Para comprar ou fazer proposta é preciso estar a até 80 m do imóvel, com GPS preciso (até 30 m) e parado ou caminhando.",
    "Imóveis sem dono custam o preço da categoria: casa pequena 1.000, casa grande 2.500, comércio 3.000 e prédio residencial 10.000.",
    "Cada imóvel rende 1% ao dia sobre o valor que você pagou. A renda acumula e você coleta quando quiser.",
    "As moedas não têm valor em dinheiro e não podem ser compradas, vendidas ou sacadas.",
)

private val PROPOSALS = listOf(
    "Imóveis com dono só mudam de mãos por proposta. O valor oferecido fica reservado na sua carteira.",
    "O dono tem 24 h para aceitar ou recusar. Ao aceitar, recebe o valor e a renda acumulada até ali.",
    "Se o dono não responder em 24 h, você pode comprar sem aceite pelo valor da proposta + 20%, no local, durante as 24 h seguintes.",
    "Se o dono ficar 14 dias sem abrir o jogo, qualquer jogador no local pode comprar sem aceite pelo último valor pago + 20%.",
    "Após qualquer transferência, o imóvel fica protegido por 24 h contra compra sem aceite.",
)

private val MAP_PROBLEMS = listOf(
    "Avatar parado ou longe: vá para um local aberto, longe de prédios altos, e aguarde alguns segundos.",
    "\"GPS impreciso\": a precisão precisa ser de até 30 m. Ative a localização precisa nas configurações do Android.",
    "Imóvel no lugar errado, com categoria errada ou faltando: use \"Reportar problema\" na ficha ou o formulário abaixo.",
    "As alturas dos prédios no 3D são ilustrativas.",
    "Localização simulada (apps de GPS falso) é recusada nas negociações.",
)
