package app.donodavez.property

import app.donodavez.core.model.Money
import app.donodavez.core.model.PropertyDetailDto
import app.donodavez.core.ui.PropertyState
import java.math.BigDecimal

/** Regras de exibição puras (o servidor continua sendo a autoridade de cada ação). */
object PropertyRules {
    fun viewerState(d: PropertyDetailDto): PropertyState = when {
        d.ownership?.isMine == true -> PropertyState.MINE
        d.myProposal?.status == "pending" -> PropertyState.PROPOSAL
        d.property.eligibility != "eligible" -> PropertyState.UNAVAILABLE
        d.ownership != null -> PropertyState.OTHER
        else -> PropertyState.AVAILABLE
    }

    /** Validação local da oferta: formato, mínimo e saldo disponível (o servidor revalida tudo). */
    fun offerError(input: String, available: String?): String? {
        val normalized = Money.normalizeOffer(input) ?: return "Informe um valor maior que zero com até 2 casas decimais"
        if (BigDecimal(normalized) > Money.parse(available)) return "Saldo disponível insuficiente para reservar esse valor"
        return null
    }

    fun reasonLabel(reason: String?): String? = when (reason) {
        null -> null
        "unavailable" -> "Imóvel indisponível para negociação no momento"
        "owned" -> "Este imóvel já tem dono: envie uma proposta"
        "no_owner" -> "Sem dono: compre pelo preço da categoria"
        "mine" -> "Este imóvel é seu"
        "proposal_exists" -> "Você já tem uma proposta pendente para este imóvel"
        "owner_active" -> "O dono esteve ativo nos últimos 14 dias"
        "protection_active" -> "Proteção de 24 h após a última transferência"
        else -> reason
    }
}
