package app.donodavez.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donodavez.core.AppContainer
import app.donodavez.core.model.Money
import app.donodavez.core.model.WalletPageDto
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.decode
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WalletUi(
    val page: Loadable<WalletPageDto> = Loadable.Loading,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val collecting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class WalletViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(WalletUi())
    val ui: StateFlow<WalletUi> = _ui.asStateFlow()
    private val collectKeys = mutableMapOf<String, String>()

    init { load() }

    fun load() = viewModelScope.launch {
        _ui.update { it.copy(refreshing = it.page is Loadable.Ready, error = null) }
        val next: Loadable<WalletPageDto> = try {
            val p: WalletPageDto = c.api.get("/v1/wallet", mapOf("limit" to PAGE)).decode()
            c.game.updateWallet(p.wallet)
            c.snapshots.save(SNAPSHOT, WalletPageDto.serializer(), p)
            Loadable.Ready(p)
        } catch (e: Exception) {
            c.snapshots.load(SNAPSHOT, WalletPageDto.serializer())?.let { (p, at) -> Loadable.Ready(p, savedAt = at) }
                ?: Loadable.Error(e.userMessage())
        }
        _ui.update { it.copy(page = next, refreshing = false) }
    }

    fun loadMore() {
        val current = (_ui.value.page as? Loadable.Ready)?.value ?: return
        val cursor = current.nextCursor ?: return
        if (_ui.value.loadingMore) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val p: WalletPageDto = c.api.get("/v1/wallet", mapOf("limit" to PAGE, "cursor" to cursor)).decode()
                _ui.update { it.copy(page = Loadable.Ready(current.copy(entries = current.entries + p.entries, nextCursor = p.nextCursor)), loadingMore = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(loadingMore = false, error = e.userMessage()) }
            }
        }
    }

    fun collectAll() {
        if (_ui.value.collecting) return
        _ui.update { it.copy(collecting = true, message = null, error = null) }
        viewModelScope.launch {
            try {
                val ids = c.income.collectableOwnerships()
                if (ids.isEmpty()) {
                    _ui.update { it.copy(collecting = false, message = "Ainda não há renda de pelo menos 0,01 moeda para coletar.") }
                    return@launch
                }
                val total = c.income.collect(ids) { chunk -> collectKeys.getOrPut(chunk.joinToString(",")) { c.operations.newKey() } }
                collectKeys.clear()
                _ui.update { it.copy(collecting = false, message = "Renda coletada: ${Money.format(total)} moedas") }
                load()
            } catch (e: ApiException) {
                collectKeys.clear()
                _ui.update { it.copy(collecting = false, error = e.message) }
            } catch (e: Exception) {
                // Resultado incerto: mantém as chaves para que tentar de novo não colete em dobro.
                _ui.update { it.copy(collecting = false, error = e.userMessage()) }
            }
        }
    }

    private companion object {
        const val PAGE = 50
        const val SNAPSHOT = "wallet"
    }
}
