package app.donodavez.proposals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donodavez.core.AppContainer
import app.donodavez.core.model.OperationResultDto
import app.donodavez.core.model.ProposalDto
import app.donodavez.core.model.ProposalPageDto
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.decode
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.Loadable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class Direction(val key: String, val label: String) { RECEIVED("received", "Recebidas"), SENT("sent", "Enviadas") }
enum class StatusFilter(val key: String, val label: String) { OPEN("open", "Em aberto"), CLOSED("closed", "Encerradas") }
enum class ProposalCommand(val path: String) { ACCEPT("accept"), REJECT("reject"), CANCEL("cancel") }

data class ProposalsUi(
    val direction: Direction = Direction.RECEIVED,
    val status: StatusFilter = StatusFilter.OPEN,
    val page: Loadable<ProposalPageDto> = Loadable.Loading,
    val loadingMore: Boolean = false,
    val busyId: String? = null,
    val message: String? = null,
    val error: String? = null,
)

class ProposalsViewModel(private val c: AppContainer, initial: Direction) : ViewModel() {
    private val _ui = MutableStateFlow(ProposalsUi(direction = initial))
    val ui: StateFlow<ProposalsUi> = _ui.asStateFlow()
    private val keys = mutableMapOf<String, String>()
    private var job: Job? = null

    init {
        load()
        viewModelScope.launch { c.game.changes.collect { load() } }
    }

    fun setDirection(d: Direction) {
        if (d == _ui.value.direction) return
        _ui.update { it.copy(direction = d, page = Loadable.Loading, message = null, error = null) }
        load()
    }

    fun setStatus(s: StatusFilter) {
        if (s == _ui.value.status) return
        _ui.update { it.copy(status = s, page = Loadable.Loading, message = null, error = null) }
        load()
    }

    fun load() {
        val d = _ui.value.direction
        val s = _ui.value.status
        job?.cancel()
        job = viewModelScope.launch {
            val key = "proposals-${d.key}-${s.key}"
            val next: Loadable<ProposalPageDto> = try {
                val p: ProposalPageDto = c.api.get("/v1/proposals", mapOf("direction" to d.key, "status" to s.key, "limit" to 50)).decode()
                c.snapshots.save(key, ProposalPageDto.serializer(), p)
                Loadable.Ready(p)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                c.snapshots.load(key, ProposalPageDto.serializer())?.let { (p, at) -> Loadable.Ready(p, at) } ?: Loadable.Error(e.userMessage())
            }
            _ui.update { it.copy(page = next) }
        }
    }

    fun loadMore() {
        val current = (_ui.value.page as? Loadable.Ready)?.value ?: return
        val cursor = current.nextCursor ?: return
        if (_ui.value.loadingMore) return
        val d = _ui.value.direction
        val s = _ui.value.status
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val more = runCatching {
                c.api.get("/v1/proposals", mapOf("direction" to d.key, "status" to s.key, "limit" to 50, "cursor" to cursor)).decode<ProposalPageDto>()
            }.getOrNull()
            _ui.update {
                if (more == null || it.direction != d || it.status != s) it.copy(loadingMore = false)
                else it.copy(page = Loadable.Ready(current.copy(items = current.items + more.items, nextCursor = more.nextCursor)), loadingMore = false)
            }
        }
    }

    /** Aceitar/recusar (dono) e cancelar (comprador) não exigem presença; o servidor valida versão e prazo. */
    fun command(p: ProposalDto, cmd: ProposalCommand) {
        if (_ui.value.busyId != null) return
        val intent = "${p.id}:${cmd.path}"
        val key = keys.getOrPut(intent) { c.operations.newKey() }
        _ui.update { it.copy(busyId = p.id, message = null, error = null) }
        viewModelScope.launch {
            try {
                val r: OperationResultDto = c.operations.run("/v1/proposals/${p.id}/${cmd.path}",
                    buildJsonObject { put("expected_version", p.version) }, key).decode()
                keys.remove(intent)
                c.game.updateWallet(r.wallet)
                p.property?.id?.let(c.game::notifyChanged)
                _ui.update {
                    it.copy(busyId = null, message = when (cmd) {
                        ProposalCommand.ACCEPT -> "Proposta aceita: o imóvel foi vendido e o valor creditado na sua carteira."
                        ProposalCommand.REJECT -> "Proposta recusada. O valor reservado voltou para o comprador."
                        ProposalCommand.CANCEL -> "Proposta cancelada. O valor reservado voltou para sua carteira."
                    })
                }
                load()
            } catch (e: ApiException) {
                keys.remove(intent)
                _ui.update { it.copy(busyId = null, error = e.message) }
                load()
            } catch (e: Exception) {
                _ui.update { it.copy(busyId = null, error = e.userMessage()) }
            }
        }
    }
}
