package app.donodavez.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.model.NotificationPrefs
import app.donodavez.core.ui.AvatarImage
import app.donodavez.core.ui.Avatars
import app.donodavez.core.ui.BusyButton
import app.donodavez.core.ui.InfoLine
import app.donodavez.core.ui.LoadingBox
import app.donodavez.core.ui.formatDateTime

/** Perfil: apelido, avatar, notificações, privacidade, sair e excluir conta. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    offline: Boolean,
    onOpenHelp: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDeletion: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val c = LocalAppContainer.current
    val vm: ProfileViewModel = viewModel { ProfileViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val boot by vm.profile.collectAsStateWithLifecycle()
    val profile = boot?.profile ?: run { LoadingBox(); return }
    var nickname by rememberSaveable(profile.nickname) { mutableStateOf(profile.nickname.orEmpty()) }
    var privacy by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(profile.avatarId, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(profile.nickname ?: "Sem apelido", style = MaterialTheme.typography.headlineSmall)
                Text(vm.email().orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text("Jogando desde ${formatDateTime(profile.createdAt)}", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (profile.deletionRequestedAt != null) {
            Text("Exclusão solicitada em ${formatDateTime(profile.deletionRequestedAt)}.", color = MaterialTheme.colorScheme.error)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Apelido público", fontWeight = FontWeight.SemiBold)
                Text("É o único dado seu que outros jogadores veem (nas fichas e propostas).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it.take(24) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = nickname.isNotBlank() && ProfileViewModel.nicknameError(nickname.trim()) != null,
                    supportingText = { Text("3 a 24 caracteres: letras, números, . - _") },
                )
                BusyButton("Salvar apelido", busy = ui.saving, enabled = !offline && nickname.trim() != profile.nickname) {
                    vm.saveNickname(nickname)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Avatar", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatars.selectable.forEachIndexed { i, id ->
                        val selected = id == profile.avatarId
                        Surface(
                            shape = CircleShape,
                            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .semantics { contentDescription = "Avatar ${i + 1}"; this.selected = selected }
                                .clickable(enabled = !offline && !ui.saving && !selected) { vm.saveAvatar(id) },
                        ) { AvatarImage(id, size = 56.dp, modifier = Modifier.padding(3.dp)) }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Notificações push", fontWeight = FontWeight.SemiBold)
                Text("Avisos nunca alteram o jogo: prazos e saldos valem sempre como mostrados no app.", style = MaterialTheme.typography.bodySmall)
                val prefs = profile.notificationPrefs
                PrefSwitch("Propostas recebidas e respostas", prefs.proposals, !offline && !ui.saving) { vm.savePrefs(prefs.copy(proposals = it)) }
                PrefSwitch("Vendas e transferências", prefs.transfers, !offline && !ui.saving) { vm.savePrefs(prefs.copy(transfers = it)) }
                PrefSwitch("Lembretes de prazo", prefs.reminders, !offline && !ui.saving) { vm.savePrefs(prefs.copy(reminders = it)) }
            }
        }
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Card(Modifier.fillMaxWidth()) {
            Column {
                ListItem(headlineContent = { Text("Central de notificações") }, leadingContent = { Icon(Icons.Filled.Notifications, null) },
                    modifier = Modifier.clickable(onClick = onOpenNotifications))
                HorizontalDivider()
                ListItem(headlineContent = { Text("Privacidade") }, leadingContent = { Icon(Icons.Filled.PrivacyTip, null) },
                    modifier = Modifier.clickable { privacy = !privacy })
                if (privacy) PrivacyText()
                HorizontalDivider()
                ListItem(headlineContent = { Text("Ajuda e suporte") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.Help, null) },
                    modifier = Modifier.clickable(onClick = onOpenHelp))
                HorizontalDivider()
                ListItem(headlineContent = { Text("Sair") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                    modifier = Modifier.clickable { vm.signOut(onSignedOut) })
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Excluir conta", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable(enabled = !offline, onClick = onOpenDeletion),
                )
            }
        }
        InfoLine("Versão do app", c.config.appVersionName)
        InfoLine("Ambiente", c.config.environment)
    }
}

@Composable
private fun PrefSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PrivacyText() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "Sua localização é usada somente com o app aberto. Na negociação, enviamos uma leitura pontual ao servidor para validar a distância; não guardamos trajeto.",
            "As provas de presença guardam apenas distância e precisão por pouco tempo, para auditoria antifraude.",
            "Outros jogadores veem apenas seu apelido e avatar. E-mail e localização nunca são exibidos.",
            "Ao excluir a conta, seus dados pessoais são removidos ou pseudonimizados; o histórico econômico é mantido sem identificação.",
            "Dados do mapa: © colaboradores do OpenStreetMap (ODbL).",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/** Exclusão de conta com confirmação de senha (login recente) e digitação de EXCLUIR. */
@Composable
fun DeleteAccountScreen(onDeleted: () -> Unit, onCancel: () -> Unit) {
    val c = LocalAppContainer.current
    val vm: ProfileViewModel = viewModel { ProfileViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Excluir conta", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        listOf(
            "Suas propostas abertas serão canceladas e as reservas liberadas.",
            "Seus imóveis voltam a ficar disponíveis no mapa, sem pagamento.",
            "Seu saldo e renda acumulada são perdidos (moedas fictícias, sem valor em dinheiro).",
            "Seu apelido e avatar são removidos; o histórico de negociações fica anônimo.",
            "Não é possível desfazer.",
        ).forEach { Text("• $it") }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Sua senha") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirmation, onValueChange = { confirmation = it }, label = { Text("Digite EXCLUIR") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        BusyButton("Excluir minha conta", busy = ui.deleting, enabled = password.isNotEmpty() && confirmation.trim() == "EXCLUIR") {
            vm.deleteAccount(password, confirmation, onDeleted)
        }
        ui.deletionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onCancel, enabled = !ui.deleting) { Text("Voltar") }
    }
}
