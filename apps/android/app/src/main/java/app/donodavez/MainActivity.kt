package app.donodavez

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import app.donodavez.core.AppContainerOwner
import app.donodavez.core.LocalAppContainer
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.DvTheme
import app.donodavez.push.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as AppContainerOwner).container

    /** Rota pedida por uma notificação tocada (consumida pela navegação). */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            DvTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppRoot(pendingRoute)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(Notifications.EXTRA_ROUTE)?.let { pendingRoute.value = it }
        val uri = intent?.data ?: return
        if (uri.scheme == "donodavez" && uri.host == "auth-callback") {
            lifecycleScope.launch {
                runCatching { container.auth.handleCallback(uri) }
                    .onSuccess { ok -> if (!ok) toast("Link inválido ou expirado. Peça um novo e-mail.") }
                    .onFailure { toast(it.userMessage()) }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
