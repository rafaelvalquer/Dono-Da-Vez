package app.donodavez

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.donodavez.auth.AuthFlow
import app.donodavez.auth.NewPasswordScreen
import app.donodavez.core.AppContainer
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.auth.AuthState
import app.donodavez.core.model.BootstrapDto
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.BusyButton
import app.donodavez.core.ui.ErrorBox
import app.donodavez.core.ui.LoadingBox
import app.donodavez.map.MapScreen
import app.donodavez.map.PermissionsScreen
import app.donodavez.portfolio.PortfolioScreen
import app.donodavez.profile.DeleteAccountScreen
import app.donodavez.profile.HelpScreen
import app.donodavez.profile.NotificationsScreen
import app.donodavez.profile.ProfileScreen
import app.donodavez.proposals.ProposalsScreen
import app.donodavez.push.PushRegistrar
import app.donodavez.wallet.HistoryScreen
import app.donodavez.wallet.WalletScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Portão de autenticação: login/cadastro, nova senha (link de recuperação) ou jogo. */
@Composable
fun AppRoot(pendingRoute: MutableStateFlow<String?>) {
    val c = LocalAppContainer.current
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val online by rememberOnline()
    when (val s = auth) {
        AuthState.Loading -> LoadingBox()
        AuthState.SignedOut -> Box(Modifier.safeDrawingPadding()) { AuthFlow(offline = !online) }
        is AuthState.SignedIn ->
            if (s.recovery) Box(Modifier.safeDrawingPadding()) { NewPasswordScreen(onDone = {}) }
            else SignedIn(c, s.session.userId, !online, pendingRoute)
    }
}

private sealed interface Boot {
    data object Loading : Boot
    data class Ready(val boot: BootstrapDto, val offline: Boolean) : Boot
    data class Failed(val error: Throwable) : Boot
}

@Composable
private fun SignedIn(c: AppContainer, userId: String, offline: Boolean, pendingRoute: MutableStateFlow<String?>) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var boot by remember(userId) { mutableStateOf<Boot>(Boot.Loading) }
    LaunchedEffect(userId, attempt) {
        boot = Boot.Loading
        boot = try {
            val b = c.game.refresh()
            c.snapshots.save("bootstrap-$userId", BootstrapDto.serializer(), b)
            launch { PushRegistrar.register(context, c) }
            Boot.Ready(b, offline = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val saved = if (e is ApiException) null else c.snapshots.load("bootstrap-$userId", BootstrapDto.serializer())
            if (saved != null) {
                c.game.restore(saved.first); Boot.Ready(saved.first, offline = true)
            } else Boot.Failed(e)
        }
    }
    // Voltou a conexão depois de abrir offline: inicializa de verdade.
    LaunchedEffect(offline) {
        if (!offline && (boot as? Boot.Ready)?.offline == true) attempt++
    }

    when (val b = boot) {
        Boot.Loading -> LoadingBox()
        is Boot.Failed -> BootError(c, b.error) { attempt++ }
        is Boot.Ready -> when {
            b.boot.updateRequired -> Message(
                "Atualize o app",
                "Esta versão não é mais compatível com as regras do jogo. Atualize pela loja para continuar.",
            )
            else -> Onboarded(c, offline, pendingRoute)
        }
    }
}

@Composable
private fun BootError(c: AppContainer, error: Throwable, onRetry: () -> Unit) {
    val scope = rememberCoroutineScope()
    val code = (error as? ApiException)?.code
    when (code) {
        "EMAIL_NOT_CONFIRMED", "ACCOUNT_SUSPENDED", "ACCOUNT_CLOSED" -> Message(
            title = when (code) {
                "EMAIL_NOT_CONFIRMED" -> "Confirme seu e-mail"
                "ACCOUNT_SUSPENDED" -> "Conta suspensa"
                else -> "Conta encerrada"
            },
            text = error.userMessage() + if (code == "ACCOUNT_SUSPENDED") " Fale com o suporte pelo e-mail informado na loja." else "",
            primary = "Tentar novamente" to onRetry,
            secondary = "Sair" to { scope.launch { c.auth.signOut(); c.clearUserData() } },
        )
        else -> Box(Modifier.safeDrawingPadding()) { ErrorBox(error.userMessage(), onRetry = onRetry) }
    }
}

@Composable
private fun Message(title: String, text: String, primary: Pair<String, () -> Unit>? = null, secondary: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text)
        primary?.let { (label, action) -> BusyButton(label, busy = false, onClick = action) }
        secondary?.let { (label, action) -> OutlinedButton(onClick = action) { Text(label) } }
    }
}

/** Primeira abertura: explicação e pedido de localização; depois, o jogo. */
@Composable
private fun Onboarded(c: AppContainer, offline: Boolean, pendingRoute: MutableStateFlow<String?>) {
    val scope = rememberCoroutineScope()
    val done by c.settings.onboardingDone.collectAsStateWithLifecycle(initialValue = null)
    when (done) {
        null -> LoadingBox()
        false -> Box(Modifier.safeDrawingPadding()) { PermissionsScreen(onContinue = { scope.launch { c.settings.setOnboardingDone() } }) }
        true -> MainScaffold(offline, pendingRoute)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("map", "Mapa", Icons.Filled.Map),
    Tab("portfolio", "Imóveis", Icons.Filled.Home),
    Tab("proposals", "Propostas", Icons.Filled.Mail),
    Tab("wallet", "Carteira", Icons.Filled.AccountBalanceWallet),
    Tab("profile", "Perfil", Icons.Filled.Person),
)

@Composable
private fun MainScaffold(offline: Boolean, pendingRoute: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route

    // Rota vinda de notificação tocada.
    val requested by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        val r = requested ?: return@LaunchedEffect
        if (TABS.any { it.route == r }) nav.switchTab(r) else nav.navigate(r) { launchSingleTop = true }
        pendingRoute.value = null
    }

    // Android 13+: pede permissão de notificação uma vez, depois do onboarding (push é opcional).
    var askedPush by rememberSaveable { mutableStateOf(false) }
    val pushLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!askedPush && Build.VERSION.SDK_INT >= 33 && PushRegistrar.available(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedPush = true
            pushLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        bottomBar = {
            if (TABS.any { it.route == current }) NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { nav.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { inner ->
        val padded = Modifier.padding(inner)
        NavHost(nav, startDestination = "map") {
            composable("map") {
                Box(Modifier.padding(bottom = inner.calculateBottomPadding())) {
                    MapScreen(
                        offline = offline,
                        onOpenWallet = { nav.switchTab("wallet") },
                        onOpenProposals = { nav.switchTab("proposals") },
                        onOpenNotifications = { nav.navigate("notifications") },
                        onReportProblem = { id -> nav.navigate("help?propertyId=$id") },
                        onRequestPermission = { nav.navigate("permissions") },
                    )
                }
            }
            composable("portfolio") {
                Box(padded) { PortfolioScreen(offline, onReportProblem = { nav.navigate("help?propertyId=$it") }, onOpenProposals = { nav.switchTab("proposals") }) }
            }
            composable("proposals") {
                Box(padded) { ProposalsScreen(offline, onReportProblem = { nav.navigate("help?propertyId=$it") }) }
            }
            composable("wallet") {
                Box(padded) { WalletScreen(offline, onOpenHistory = { nav.navigate("history") }) }
            }
            composable("profile") {
                Box(padded) {
                    ProfileScreen(
                        offline = offline,
                        onOpenHelp = { nav.navigate("help") },
                        onOpenNotifications = { nav.navigate("notifications") },
                        onOpenDeletion = { nav.navigate("deletion") },
                        onSignedOut = {},
                    )
                }
            }
            composable("history") { Box(padded) { HistoryScreen() } }
            composable("notifications") {
                Box(padded) { NotificationsScreen(onOpenProposals = { nav.switchTab("proposals") }, onOpenWallet = { nav.switchTab("wallet") }) }
            }
            composable(
                "help?propertyId={propertyId}",
                arguments = listOf(navArgument("propertyId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { e -> Box(padded) { HelpScreen(e.arguments?.getString("propertyId")) } }
            composable("permissions") { Box(padded) { PermissionsScreen(onContinue = { nav.popBackStack() }) } }
            composable("deletion") { Box(padded) { DeleteAccountScreen(onDeleted = {}, onCancel = { nav.popBackStack() }) } }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
