package app.donodavez

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.donodavez.core.AppConfig
import app.donodavez.core.AppContainer
import app.donodavez.core.AppContainerOwner
import app.donodavez.core.auth.AuthState
import app.donodavez.push.Notifications
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class DonoDaVezApp : Application(), AppContainerOwner {
    override val container: AppContainer by lazy {
        AppContainer(
            this,
            AppConfig(
                environment = BuildConfig.DV_ENVIRONMENT,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                publishableKey = BuildConfig.PUBLISHABLE_KEY,
                mapStyleUrl = BuildConfig.MAP_STYLE_URL,
                mapPublicKey = BuildConfig.MAP_PUBLIC_KEY,
                allowMockLocation = BuildConfig.ALLOW_MOCK_LOCATION,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        initFirebase()
        Notifications.createChannels(this)

        // Atividade qualificada: app em primeiro plano (base da regra de 14 dias de inatividade).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (container.auth.state.value !is AuthState.SignedIn || container.game.bootstrap.value == null) return
                container.appScope.launch { runCatching { container.game.heartbeat("foreground") } }
            }
        })
    }

    /** Sem google-services.json: as opções vêm do BuildConfig; sem elas, o push fica desativado. */
    private fun initFirebase() {
        if (BuildConfig.FIREBASE_APP_ID.isBlank() || FirebaseApp.getApps(this).isNotEmpty()) return
        FirebaseApp.initializeApp(
            this,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
    }
}
