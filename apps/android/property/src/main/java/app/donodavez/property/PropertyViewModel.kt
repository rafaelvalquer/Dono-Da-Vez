package app.donodavez.property

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donodavez.core.AppContainer
import app.donodavez.core.model.Money
import app.donodavez.core.model.OperationResultDto
import app.donodavez.core.model.PropertyDetailDto
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.decode
import app.donodavez.core.net.userMessage
import app.donodavez.core.ui.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class PropertyAction { PURCHASE, PROPOSE, FORCED_INACTIVITY, FORCED_SILENCE, CANCEL_PROPOSAL, COLLECT }

data class ActionUi(
    val open: PropertyAction? = null,
    val busy: Boolean = false,
    val step: String? = null,
    val error: String? = null,
    val success: String? = null,
)

class PropertyViewModel(private val c: AppContainer, val propertyId: String) : ViewModel() {
    private val _detail = MutableStateFlow<Loadable<PropertyDetailDto>>(Loadable.Loading)
    val detail: StateFlow<Loadable<PropertyDetailDto>> = _detail.asStateFlow()
    private val _action = MutableStateFlow(ActionUi())
    val action: StateFlow<ActionUi> = _action.asStateFlow()

    // Uma chave por intenção: reutilizada em reenvios até um resultado definitivo.
    private val pendingKeys = mutableMapOf<PropertyAction, String>()

    init { load() }

    fun load() = viewModelScope.launch {
        if (_detail.value !is Loadable.Ready) _detail.value = Loadable.Loading
        _detail.value = try {
            Loadable.Ready(c.api.get("/v1/properties/$propertyId").decode())
        } catch (e: Exception) {
            Loadable.Error(e.userMessage())
        }
    }

    fun open(action: PropertyAction?) = _action.update { ActionUi(open = action) }

    fun purchase() = presential(PropertyAction.PURCHASE, "/v1/purchases") { d, proof ->
        buildJsonObject { put("property_id", propertyId); put("proof_id", proof); put("expected_version", d.property.version) }
    }

    fun propose(amountInput: String) {
        val d = ready() ?: return
        PropertyRules.offerError(amountInput, c.game.wallet.value?.available)?.let { err ->
            _action.update { it.copy(error = err) }; return
        }
        val amount = Money.normalizeOffer(amountInput)!!
        val ownership = d.ownership?.ownershipId ?: return
        presential(PropertyAction.PROPOSE, "/v1/proposals") { _, proof ->
            buildJsonObject {
                put("property_id", propertyId); put("ownership_id", ownership); put("amount", amount); put("proof_id", proof)
            }
        }
    }

    fun forcedInactivity() = presential(PropertyAction.FORCED_INACTIVITY, "/v1/forced-purchases") { d, proof ->
        buildJsonObject {
            put("property_id", propertyId); put("reason", "inactivity"); put("proof_id", proof); put("expected_version", d.property.version)
        }
    }

    fun forcedSilence() {
        val proposalId = ready()?.actions?.forcedSilence?.proposalId ?: return
        presential(PropertyAction.FORCED_SILENCE, "/v1/forced-purchases") { d, proof ->
            buildJsonObject {
                put("property_id", propertyId); put("reason", "silence"); put("proposal_id", proposalId)
                put("proof_id", proof); put("expected_version", d.property.version)
            }
        }
    }

    fun cancelMyProposal() {
        val p = ready()?.myProposal ?: return
        remote(PropertyAction.CANCEL_PROPOSAL, "/v1/proposals/${p.id}/cancel",
            buildJsonObject { put("expected_version", p.version) }, "Proposta cancelada; o valor reservado voltou ao saldo disponível")
    }

    fun collect() {
        val o = ready()?.ownership ?: return
        remote(PropertyAction.COLLECT, "/v1/income/collect",
            buildJsonObject { put("ownership_ids", buildJsonArray { add(JsonPrimitive(o.ownershipId)) }) },
            null)
    }

    private fun ready(): PropertyDetailDto? = (_detail.value as? Loadable.Ready)?.value

    /** Presença (GPS novo → prova de 30 s) seguida da operação idempotente. */
    private fun presential(action: PropertyAction, path: String, body: (PropertyDetailDto, String) -> JsonObject) {
        val d = ready() ?: return
        if (_action.value.busy) return
        val key = pendingKeys.getOrPut(action) { c.operations.newKey() }
        _action.update { it.copy(open = action, busy = true, error = null, success = null, step = "Validando sua presença no local…") }
        viewModelScope.launch {
            val proof = try {
                c.presence.prove(propertyId)
            } catch (e: Exception) {
                _action.update { it.copy(busy = false, step = null, error = e.userMessage()) }
                return@launch
            }
            _action.update { it.copy(step = "Confirmando com o servidor…") }
            execute(action, path, body(d, proof), key, successText(action))
        }
    }

    private fun remote(action: PropertyAction, path: String, body: JsonObject, success: String?) {
        if (_action.value.busy) return
        val key = pendingKeys.getOrPut(action) { c.operations.newKey() }
        _action.update { it.copy(open = action, busy = true, error = null, success = null, step = "Enviando…") }
        viewModelScope.launch { execute(action, path, body, key, success) }
    }

    private suspend fun execute(action: PropertyAction, path: String, body: JsonObject, key: String, success: String?) {
        try {
            val result: OperationResultDto = c.operations.run(path, body, key).decode()
            pendingKeys.remove(action)
            c.game.updateWallet(result.wallet)
            c.game.notifyChanged(propertyId)
            val text = success ?: result.collected?.let { "Coletado: ${Money.withUnit(it)}" } ?: "Concluído"
            _action.update { ActionUi(open = null, success = text) }
            load()
        } catch (e: ApiException) {
            pendingKeys.remove(action) // resultado definitivo registrado; nova tentativa usa nova chave
            _action.update { it.copy(busy = false, step = null, error = e.message) }
            if (e.code in RELOAD_CODES) load()
        } catch (e: Exception) {
            // Sem confirmação: mantém a chave para que o reenvio devolva o mesmo resultado.
            _action.update { it.copy(busy = false, step = null, error = e.userMessage()) }
        }
    }

    private fun successText(action: PropertyAction) = when (action) {
        PropertyAction.PURCHASE -> "Compra confirmada! O imóvel agora é seu e já rende 1% ao dia."
        PropertyAction.PROPOSE -> "Proposta enviada. O valor ficou reservado até a resposta do dono (até 24 h)."
        PropertyAction.FORCED_INACTIVITY, PropertyAction.FORCED_SILENCE -> "Compra sem aceite confirmada. O valor foi pago ao antigo dono."
        else -> "Concluído"
    }

    companion object {
        private val RELOAD_CODES = setOf("PROPERTY_CHANGED", "ALREADY_OWNER", "PROPOSAL_CHANGED", "PROPOSAL_EXISTS",
            "OWNER_ACTIVE", "PROTECTION_ACTIVE", "WINDOW_CLOSED", "PROPOSAL_NOT_PENDING", "PROPOSAL_EXPIRED")
    }
}
