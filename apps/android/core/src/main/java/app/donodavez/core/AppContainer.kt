package app.donodavez.core

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.room.Room
import app.donodavez.core.auth.AuthRepository
import app.donodavez.core.auth.SessionStore
import app.donodavez.core.data.DvDatabase
import app.donodavez.core.data.SettingsStore
import app.donodavez.core.data.Snapshots
import app.donodavez.core.game.GameRepository
import app.donodavez.core.game.IncomeCollector
import app.donodavez.core.location.LocationClient
import app.donodavez.core.net.ApiClient
import app.donodavez.core.net.OperationRunner
import app.donodavez.core.net.buildHttpClient
import app.donodavez.core.presence.PresenceRunner
import app.donodavez.core.time.ServerClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Injeção manual: uma instância por processo, criada no Application. */
class AppContainer(context: Context, val config: AppConfig) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = ServerClock()
    val http = buildHttpClient()
    val auth = AuthRepository(config, http, SessionStore(context), appScope)
    val api = ApiClient(config, http, auth, clock)
    val operations = OperationRunner(api)
    val game = GameRepository(api, config)
    val income = IncomeCollector(api, operations, game)
    val location = LocationClient(context)
    val presence = PresenceRunner(api, location, game)
    val settings = SettingsStore(context)
    val db: DvDatabase = Room.databaseBuilder(context, DvDatabase::class.java, "dv.db")
        .fallbackToDestructiveMigration(true)
        .build()
    val snapshots = Snapshots(db.snapshots())

    /** Token FCM atual (null quando push não está configurado neste build). */
    val pushToken = MutableStateFlow<String?>(null)

    /** Logout: limpa caches locais com dados do jogador. */
    suspend fun clearUserData() {
        game.clear()
        db.snapshots().clear()
        db.features().clear()
    }
}

interface AppContainerOwner {
    val container: AppContainer
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer não fornecido") }
