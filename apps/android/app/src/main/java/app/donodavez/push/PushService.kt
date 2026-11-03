package app.donodavez.push

import android.content.Context
import app.donodavez.core.AppContainer
import app.donodavez.core.AppContainerOwner
import app.donodavez.core.auth.AuthState
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Push é só aviso: nenhum efeito econômico depende de entrega (a central no app é a cópia fiel). */
class PushService : FirebaseMessagingService() {
    private val container get() = (application as AppContainerOwner).container

    // firebase-messaging 26 entrega o token por onRegistered; onNewToken segue chamado em versões anteriores do GMS.
    override fun onRegistered(token: String) = upload(token)

    @Deprecated("Substituído por onRegistered (firebase-messaging 26)")
    override fun onNewToken(token: String) = upload(token)

    private fun upload(token: String) {
        container.pushToken.value = token
        container.appScope.launch { PushRegistrar.send(container, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification
        val type = message.data["type"]
        Notifications.show(this, type, n?.title ?: "Dono da Vez", n?.body ?: "Abra o app para ver os detalhes.", n?.channelId)
    }
}

object PushRegistrar {
    fun available(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** Obtém o token FCM e registra no servidor (idempotente por token). */
    suspend fun register(context: Context, c: AppContainer) {
        if (!available(context)) return
        @Suppress("DEPRECATION") // leitura direta do token atual; o registro novo chega também por onRegistered
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        c.pushToken.value = token
        send(c, token)
    }

    suspend fun send(c: AppContainer, token: String) {
        if (c.auth.state.value !is AuthState.SignedIn) return
        runCatching { c.api.post("/v1/devices", buildJsonObject { put("token", token); put("platform", "android") }) }
    }
}
