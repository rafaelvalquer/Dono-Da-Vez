package app.donodavez.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.ui.BusyButton
import kotlinx.coroutines.launch

/** Boas-vindas → login/cadastro → confirmação de e-mail → recuperação. */
@Composable
fun AuthFlow(offline: Boolean) {
    val container = LocalAppContainer.current
    val vm: AuthViewModel = viewModel { AuthViewModel(container.auth) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    BackHandler(enabled = ui.step != AuthStep.WELCOME) { vm.go(AuthStep.WELCOME) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (offline) {
            Text("Sem conexão. Conecte-se à internet para entrar ou criar conta.", color = MaterialTheme.colorScheme.error)
        }
        when (ui.step) {
            AuthStep.WELCOME -> Welcome(onSignIn = { vm.go(AuthStep.SIGN_IN) }, onSignUp = { vm.go(AuthStep.SIGN_UP) })
            AuthStep.SIGN_IN -> SignIn(ui, vm)
            AuthStep.SIGN_UP -> SignUp(ui, vm)
            AuthStep.RECOVER -> Recover(ui, vm)
            AuthStep.CONFIRM_PENDING -> Message(
                title = "Confirme seu e-mail",
                text = "Enviamos um link para ${ui.email}. Abra o link neste aparelho para ativar a conta. " +
                    "O saldo inicial de 5.000 moedas é creditado uma única vez após a confirmação.",
                primary = "Já confirmei, entrar" to { vm.go(AuthStep.SIGN_IN) },
                secondary = "Reenviar e-mail" to vm::resend,
                busy = ui.busy,
            )
            AuthStep.RECOVERY_SENT -> Message(
                title = "Verifique seu e-mail",
                text = "Se existir uma conta para ${ui.email}, você receberá um link para criar uma nova senha.",
                primary = "Voltar ao login" to { vm.go(AuthStep.SIGN_IN) },
                secondary = null,
                busy = false,
            )
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ui.info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun ColumnScope.Welcome(onSignIn: () -> Unit, onSignUp: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
        tint = MaterialTheme.colorScheme.primary)
    Text("Dono da Vez", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
    Text(
        "Caminhe pela cidade, compre imóveis reais do mapa estando no local e receba 1% ao dia sobre o valor pago. " +
            "Negocie com outros jogadores por propostas.",
        textAlign = TextAlign.Center,
    )
    Text(
        "As moedas do jogo são fictícias, não têm valor em dinheiro e não podem ser compradas ou sacadas.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    BusyButton("Criar conta", busy = false, onClick = onSignUp)
    OutlinedButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Já tenho conta") }
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text("E-mail") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SignIn(ui: AuthUiState, vm: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf(ui.email) }
    var password by rememberSaveable { mutableStateOf("") }
    Text("Entrar", style = MaterialTheme.typography.headlineSmall)
    EmailField(email) { email = it }
    PasswordField(password, "Senha") { password = it }
    BusyButton("Entrar", busy = ui.busy) { vm.signIn(email, password) }
    TextButton(onClick = { vm.go(AuthStep.RECOVER) }) { Text("Esqueci minha senha") }
    TextButton(onClick = { vm.go(AuthStep.SIGN_UP) }) { Text("Criar uma conta") }
}

@Composable
private fun SignUp(ui: AuthUiState, vm: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf(ui.email) }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    Text("Criar conta", style = MaterialTheme.typography.headlineSmall)
    EmailField(email) { email = it }
    PasswordField(password, "Senha (mín. 8, letras e números)") { password = it }
    PasswordField(confirm, "Repita a senha") { confirm = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = accepted, onCheckedChange = { accepted = it })
        Text("Entendo que as moedas são fictícias e que comprar exige estar no local do imóvel.",
            style = MaterialTheme.typography.bodySmall)
    }
    BusyButton("Criar conta", busy = ui.busy) { vm.signUp(email, password, confirm, accepted) }
    TextButton(onClick = { vm.go(AuthStep.SIGN_IN) }) { Text("Já tenho conta") }
}

@Composable
private fun Recover(ui: AuthUiState, vm: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf(ui.email) }
    Text("Recuperar senha", style = MaterialTheme.typography.headlineSmall)
    EmailField(email) { email = it }
    BusyButton("Enviar link", busy = ui.busy) { vm.recover(email) }
    TextButton(onClick = { vm.go(AuthStep.SIGN_IN) }) { Text("Voltar") }
}

@Composable
private fun Message(title: String, text: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>?, busy: Boolean) {
    Spacer(Modifier.height(24.dp))
    Icon(Icons.Filled.MarkEmailUnread, contentDescription = null, modifier = Modifier.size(48.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(text)
    BusyButton(primary.first, busy = false, onClick = primary.second)
    if (secondary != null) OutlinedButton(onClick = secondary.second, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(secondary.first)
    }
}

/** Tela exibida após abrir o link de recuperação de senha. */
@Composable
fun NewPasswordScreen(onDone: () -> Unit) {
    val container = LocalAppContainer.current
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nova senha", style = MaterialTheme.typography.headlineSmall)
        PasswordField(password, "Nova senha (mín. 8, letras e números)") { password = it }
        BusyButton("Salvar", busy = busy) {
            if (password.length < 8 || !password.any(Char::isDigit) || !password.any(Char::isLetter)) {
                error = "Use pelo menos 8 caracteres, com letras e números"; return@BusyButton
            }
            busy = true
            scope.launch {
                runCatching { container.auth.updatePassword(password) }
                    .onSuccess { onDone() }
                    .onFailure { error = it.message }
                busy = false
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
