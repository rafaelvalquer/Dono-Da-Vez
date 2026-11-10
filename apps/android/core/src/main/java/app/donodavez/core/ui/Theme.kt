package app.donodavez.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

private val Light = lightColorScheme(
    primary = Color(0xFF1B5E52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBE3),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFFB8860B),
    secondaryContainer = Color(0xFFFCE8B2),
    onSecondaryContainer = Color(0xFF3A2A00),
    tertiary = Color(0xFF5E35B1),
    background = Color(0xFFF7F6F2),
    surface = Color.White,
    // Contêineres em tons creme (combinam com o mapa "cidade de brinquedo"), sem o lilás padrão.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF8F2),
    surfaceContainer = Color(0xFFF6F1E8),
    surfaceContainerHigh = Color(0xFFF1EBE1),
    surfaceContainerHighest = Color(0xFFEBE4D9),
    surfaceVariant = Color(0xFFEDE6DA),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD1BF),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFB4F0E2),
    secondary = Color(0xFFE6C15A),
    secondaryContainer = Color(0xFF5A4300),
    onSecondaryContainer = Color(0xFFFFE08A),
    tertiary = Color(0xFFB39DDB),
    background = Color(0xFF121413),
    surface = Color(0xFF1C1F1E),
    surfaceContainerLowest = Color(0xFF0F1211),
    surfaceContainerLow = Color(0xFF171A19),
    surfaceContainer = Color(0xFF1B1E1D),
    surfaceContainerHigh = Color(0xFF252928),
    surfaceContainerHighest = Color(0xFF303433),
    surfaceVariant = Color(0xFF3A3F3D),
)

@Composable
fun DvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/**
 * Estados do imóvel para o jogador: cor + ícone + rótulo (nunca só cor).
 * [color] é a cor de interface (contraste em texto); [body], [roof] e [shade] pintam a peça 3D no mapa.
 */
enum class PropertyState(
    val key: String,
    val label: String,
    val color: Color,
    val icon: ImageVector,
    val body: Color,
    val roof: Color,
    val shade: Color,
) {
    AVAILABLE("available", "Disponível", Color(0xFF2E7D32), Icons.Filled.AddCircle,
        Color(0xFF4CAF50), Color(0xFFA5E89A), Color(0xFF1B5E20)),
    MINE("mine", "Seu", Color(0xFF1565C0), Icons.Filled.Star,
        Color(0xFF2F80ED), Color(0xFF9CC8FF), Color(0xFF0D3C8C)),
    OTHER("other", "De outro dono", Color(0xFFEF6C00), Icons.Filled.Person,
        Color(0xFFFF8A2B), Color(0xFFFFC58F), Color(0xFFA84A00)),
    PROPOSAL("proposal", "Sua proposta", Color(0xFF6A1B9A), Icons.Filled.Mail,
        Color(0xFF8E44D9), Color(0xFFD2A8FF), Color(0xFF4A148C)),
    // Peça indisponível em tons claros e quentes: recua na cena para as peças negociáveis se destacarem.
    UNAVAILABLE("unavailable", "Indisponível", Color(0xFF757575), Icons.Filled.Block,
        Color(0xFFD3CBC0), Color(0xFFF1ECE5), Color(0xFFA89E92));

    companion object {
        fun of(key: String?): PropertyState = entries.firstOrNull { it.key == key } ?: UNAVAILABLE
    }
}
