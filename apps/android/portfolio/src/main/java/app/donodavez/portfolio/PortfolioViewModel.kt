package app.donodavez.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donodavez.core.AppContainer
import app.donodavez.core.model.Money
import app.donodavez.core.model.PortfolioDto
import app.donodavez.core.model.PortfolioItemDto
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.decode
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.Loadable
import app.donodavez.core.ui.categoryLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

enum class PortfolioSort(val label: String) { RECENT("Mais recentes"), INCOME("Maior renda"), COLLECTABLE("Mais renda a coletar") }

data class PortfolioUi(
    val page: Loadable<PortfolioDto> = Loadable.Loading,
    val query: String = "",
    val sort: PortfolioSort = PortfolioSort.RECENT,
    val onlyWithOffers: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val collecting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class PortfolioViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(PortfolioUi())
    val ui: StateFlow<PortfolioUi> = _ui.asStateFlow()
    private val collectKeys = mutableMapOf<String, String>()

    init {
        load()
        viewModelScope.launch { c.game.changes.collect { load() } }
    }

    fun load() = viewModelScope.launch {
        _ui.update { it.copy(refreshing = it.page is Loadable.Ready) }
        val next: Loadable<PortfolioDto> = try {
            val p: PortfolioDto = c.api.get("/v1/portfolio", mapOf("limit" to PAGE)).decode()
            c.snapshots.save(SNAPSHOT, PortfolioDto.serializer(), p)
            Loadable.Ready(p)
        } catch (e: Exception) {
            c.snapshots.load(SNAPSHOT, PortfolioDto.serializer())?.let { (p, at) -> Loadable.Ready(p, at) } ?: Loadable.Error(e.userMessage())
        }
        _ui.update { it.copy(page = next, refreshing = false) }
    }

    fun loadMore() {
        val current = (_ui.value.page as? Loadable.Ready)?.value ?: return
        val cursor = current.nextCursor ?: return
        if (_ui.value.loadingMore) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val more = runCatching { c.api.get("/v1/portfolio", mapOf("limit" to PAGE, "cursor" to cursor)).decode<PortfolioDto>() }.getOrNull()
            _ui.update {
                if (more == null) it.copy(loadingMore = false, error = "Não foi possível carregar mais imóveis")
                else it.copy(page = Loadable.Ready(current.copy(items = current.items + more.items, nextCursor = more.nextCursor)), loadingMore = false)
            }
        }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q.take(60)) }
    fun setSort(s: PortfolioSort) = _ui.update { it.copy(sort = s) }
    fun toggleOffers() = _ui.update { it.copy(onlyWithOffers = !it.onlyWithOffers) }

    /** Coleta de um imóvel ([ownershipId]) ou de todos (null). */
    fun collect(ownershipId: String? = null) {
        if (_ui.value.collecting) return
        _ui.update { it.copy(collecting = true, message = null, error = null) }
        viewModelScope.launch {
            try {
                val ids = if (ownershipId != null) listOf(ownershipId) else c.income.collectableOwnerships()
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
                _ui.update { it.copy(collecting = false, error = e.userMessage()) }
            }
        }
    }

    companion object {
        private const val PAGE = 100
        private const val SNAPSHOT = "portfolio"

        /** Busca local sem acento/caixa por categoria, tipo e endereço; ordenação e filtro de ofertas. */
        fun visible(items: List<PortfolioItemDto>, query: String, sort: PortfolioSort, onlyWithOffers: Boolean): List<PortfolioItemDto> {
            val q = fold(query.trim())
            return items
                .filter { !onlyWithOffers || it.pendingOffers > 0 }
                .filter { q.isEmpty() || fold(listOfNotNull(categoryLabel(it.property.category), it.property.subtype, it.property.addressLabel).joinToString(" ")).contains(q) }
                .let { list ->
                    when (sort) {
                        PortfolioSort.RECENT -> list.sortedByDescending { it.acquiredAt ?: "" }
                        PortfolioSort.INCOME -> list.sortedByDescending { Money.parse(it.dailyIncome) }
                        PortfolioSort.COLLECTABLE -> list.sortedByDescending { Money.parse(it.collectable) }
                    }
                }
        }

        private fun fold(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}
