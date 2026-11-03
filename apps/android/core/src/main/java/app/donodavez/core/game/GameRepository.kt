package app.donodavez.core.game

import app.donodavez.core.AppConfig
import app.donodavez.core.model.BootstrapDto
import app.donodavez.core.model.GameConfigDto
import app.donodavez.core.model.ProfileDto
import app.donodavez.core.model.WalletDto
import app.donodavez.core.net.ApiClient
import app.donodavez.core.net.decode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Estado compartilhado da sessão de jogo: configuração pública, perfil e carteira confirmados pelo servidor. */
class GameRepository(private val api: ApiClient, private val config: AppConfig) {
    private val _bootstrap = MutableStateFlow<BootstrapDto?>(null)
    val bootstrap: StateFlow<BootstrapDto?> = _bootstrap.asStateFlow()

    private val _wallet = MutableStateFlow<WalletDto?>(null)
    val wallet: StateFlow<WalletDto?> = _wallet.asStateFlow()

    /** Inicializa a sessão; o servidor concede o saldo inicial uma única vez por conta e e-mail. */
    suspend fun refresh(): BootstrapDto {
        val boot: BootstrapDto = api.get("/v1/bootstrap", mapOf("app_version" to config.appVersionCode)).decode()
        _bootstrap.value = boot
        _wallet.value = boot.wallet
        return boot
    }

    /** Sem rede ao abrir: usa a última inicialização salva (somente leitura; ações reconsultam o servidor). */
    fun restore(boot: BootstrapDto) {
        _bootstrap.value = boot
        _wallet.value = boot.wallet
    }

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** IDs de imóveis alterados por ações deste aparelho (mapa e listas recarregam). */
    val changes: SharedFlow<String> = _changes

    fun notifyChanged(propertyId: String) {
        _changes.tryEmit(propertyId)
    }

    fun gameConfig(): GameConfigDto = _bootstrap.value?.config ?: GameConfigDto()

    fun profile(): ProfileDto? = _bootstrap.value?.profile

    fun updateProfile(profile: ProfileDto) = _bootstrap.update { it?.copy(profile = profile) }

    fun updateWallet(wallet: WalletDto?) {
        if (wallet != null) _wallet.value = wallet
    }

    fun categoryName(code: String?): String = when (code) {
        null -> "Classificação pendente"
        else -> gameConfig().categories.firstOrNull { it.code == code }?.name ?: code
    }

    /** Atividade qualificada (app em primeiro plano) — base da regra de inatividade de 14 dias. */
    suspend fun heartbeat(source: String) {
        api.post("/v1/activity", buildJsonObject { put("source", source) })
    }

    fun clear() {
        _bootstrap.value = null
        _wallet.value = null
    }
}
