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
    secondary = Color(0xFFB8860B),
    tertiary = Color(0xFF5E35B1),
    background = Color(0xFFF7F6F2),
    surface = Color.White,
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD1BF),
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFFE6C15A),
    tertiary = Color(0xFFB39DDB),
    background = Color(0xFF121413),
    surface = Color(0xFF1C1F1E),
)

@Composable
fun DvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Estados do imóvel para o jogador: cor + ícone + rótulo (nunca só cor). */
enum class PropertyState(val key: String, val label: String, val color: Color, val icon: ImageVector) {
    AVAILABLE("available", "Disponível", Color(0xFF2E7D32), Icons.Filled.AddCircle),
    MINE("mine", "Seu", Color(0xFF1565C0), Icons.Filled.Star),
    OTHER("other", "De outro dono", Color(0xFFEF6C00), Icons.Filled.Person),
    PROPOSAL("proposal", "Sua proposta", Color(0xFF6A1B9A), Icons.Filled.Mail),
    UNAVAILABLE("unavailable", "Indisponível", Color(0xFF757575), Icons.Filled.Block);

    companion object {
        fun of(key: String?): PropertyState = entries.firstOrNull { it.key == key } ?: UNAVAILABLE
    }
}
