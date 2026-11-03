package app.donodavez.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.location.LocationPermission
import app.donodavez.core.ui.BusyButton

/**
 * Permissões de localização: explica o uso (somente com o app aberto), pede precisa + aproximada e
 * trata os três resultados — negada, aproximada (só visualiza) e precisa (pode negociar).
 */
@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    var permission by remember { mutableStateOf(c.location.permission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        permission = c.location.permission()
        if (permission == LocationPermission.PRECISE) onContinue()
    }
    LifecycleResumeEffect(Unit) {
        permission = c.location.permission()
        onPauseOrDispose { }
    }
    // Negada permanentemente: o sistema não mostra mais o diálogo; só pelas configurações.
    val blocked = asked && permission == LocationPermission.DENIED &&
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == false

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        val icon = when (permission) {
            LocationPermission.PRECISE -> Icons.Filled.LocationOn
            LocationPermission.APPROXIMATE -> Icons.Filled.LocationSearching
            LocationPermission.DENIED -> Icons.Filled.LocationOff
        }
        Icon(icon, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        when (permission) {
            LocationPermission.DENIED -> {
                Text("Sua localização move o jogo", style = MaterialTheme.typography.headlineSmall)
                Text("Para comprar ou propor, você precisa estar a até 80 m do imóvel. Usamos o GPS somente com o app aberto, " +
                    "para mostrar seu avatar e confirmar presença no momento da negociação. Não guardamos seu trajeto.")
                Text("Outros jogadores nunca veem sua posição.", style = MaterialTheme.typography.bodySmall)
                if (blocked) {
                    Text("A permissão foi negada. Ative \"Localização → Precisa\" nas configurações do app.",
                        color = MaterialTheme.colorScheme.error)
                    BusyButton("Abrir configurações", busy = false) { openAppSettings(context) }
                } else {
                    BusyButton("Permitir localização", busy = false) {
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                TextButton(onClick = onContinue) { Text("Agora não (apenas explorar o mapa)") }
            }
            LocationPermission.APPROXIMATE -> {
                Text("Localização aproximada ativa", style = MaterialTheme.typography.headlineSmall)
                Text("Com a localização aproximada você vê o mapa, mas não consegue negociar: a presença no imóvel " +
                    "exige precisão de até 30 m. Ative a localização precisa para jogar.")
                BusyButton("Ativar localização precisa", busy = false) {
                    if (activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == false && asked) {
                        openAppSettings(context)
                    } else {
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continuar com aproximada") }
            }
            LocationPermission.PRECISE -> {
                Text("Tudo pronto", style = MaterialTheme.typography.headlineSmall)
                Text("Localização precisa ativa. Ande até um imóvel e toque nele para ver a ficha.")
                BusyButton("Ir para o mapa", busy = false, onClick = onContinue)
            }
        }
    }
}

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
