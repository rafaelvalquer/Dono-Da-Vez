package app.donodavez.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.donodavez.core.R
import app.donodavez.core.model.Money
import app.donodavez.core.time.ServerClock
import kotlinx.coroutines.delay

/** Estado genérico de tela: carregando, erro (com retry) ou conteúdo. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Error(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T, val savedAt: Long? = null) : Loadable<T>
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Tentar novamente") }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.ErrorOutline, action: String? = null, onAction: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun OfflineBanner(savedAt: Long?) {
    if (savedAt == null) return
    Banner(
        "Sem conexão: exibindo dados salvos em ${formatDateTime(java.time.Instant.ofEpochMilli(savedAt).toString())}. Ações exigem internet.",
        icon = Icons.Filled.CloudOff,
    )
}

/** Selo do estado (ícone + rótulo). [onColor] = sobre fundo colorido: pílula branca com texto na cor do estado. */
@Composable
fun StateBadge(state: PropertyState, modifier: Modifier = Modifier, onColor: Boolean = false) {
    Surface(color = if (onColor) Color.White else state.color.copy(alpha = 0.14f), shape = CircleShape, modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(state.icon, contentDescription = null, tint = state.color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(state.label, color = state.color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Filtro por estado em formato de pílula: ativo = cheio na cor do estado, inativo = branco com ícone colorido.
 * Mantém ícone e rótulo (nunca só cor) e expõe o estado selecionado para leitores de tela.
 */
@Composable
fun StatePill(state: PropertyState, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) state.color else Color.White.copy(alpha = 0.94f), label = "pill-bg")
    val fg = if (selected) Color.White else state.color
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        shadowElevation = if (selected) 4.dp else 2.dp,
        border = if (selected) null else BorderStroke(1.dp, state.color.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(state.icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(state.label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Moeda do jogo (arte original gerada por assets/tools/generate-assets.mjs). */
@Composable
fun CoinIcon(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Image(painterResource(R.drawable.dv_coin), contentDescription = null, modifier = modifier.size(size))
}

/** Valor em moedas com o ícone da moeda; lido como "X moedas" por leitores de tela. */
@Composable
fun CoinAmount(
    value: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    iconSize: Dp = 20.dp,
    color: Color = Color.Unspecified,
    decimals: Int = 2,
) {
    val text = Money.format(value, decimals)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$text moedas" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoinIcon(size = iconSize)
        Spacer(Modifier.width(6.dp))
        Text(text, style = style, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun MoneyLine(label: String, value: String?, modifier: Modifier = Modifier, emphasize: Boolean = false, decimals: Int = 2) {
    val text = "${Money.format(value, decimals)} moedas"
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $text" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun InfoLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, textAlign = TextAlign.End)
    }
}

/** Prazo regressivo pelo relógio do servidor (atualiza a cada 10 s). */
@Composable
fun Countdown(label: String, untilIso: String?, clock: ServerClock, modifier: Modifier = Modifier) {
    val tick by produceState(0L, untilIso) {
        while (true) { delay(10_000); value++ }
    }
    val remaining = remember(tick, untilIso) { clock.remaining(untilIso) }
    InfoLine(label, formatRemaining(remaining), modifier)
}

@Composable
fun BusyButton(text: String, busy: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth().height(48.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}
