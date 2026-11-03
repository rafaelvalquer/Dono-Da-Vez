package app.donodavez.core.ui

import app.donodavez.core.time.parseInstant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"))

fun formatDateTime(iso: String?): String =
    parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(dateTime) ?: "—"

/** "23 h 12 min", "8 min", "encerrado". */
fun formatRemaining(d: Duration?): String {
    if (d == null) return "—"
    if (d.isNegative || d.isZero) return "encerrado"
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return when {
        h >= 48 -> "${h / 24} dias"
        h > 0 -> "$h h ${m} min"
        m > 0 -> "$m min"
        else -> "menos de 1 min"
    }
}

fun categoryLabel(code: String?): String = when (code) {
    "house_small" -> "Casa pequena"
    "house_large" -> "Casa grande"
    "commercial" -> "Comércio"
    "residential_building" -> "Prédio residencial"
    null -> "Classificação pendente"
    else -> code
}

fun addressLabel(address: String?): String = address ?: "Sem endereço informado"

fun proposalStatusLabel(status: String): String = when (status) {
    "pending" -> "Aguardando resposta"
    "accepted" -> "Aceita"
    "rejected" -> "Recusada"
    "cancelled" -> "Cancelada"
    "expired" -> "Prazo encerrado"
    "executed_without_acceptance" -> "Comprada sem aceite"
    "superseded" -> "Encerrada (imóvel mudou de dono)"
    else -> status
}

fun eligibilityLabel(eligibility: String?, reason: String?): String = when (eligibility) {
    "eligible" -> "Negociável"
    "pending_classification" -> "Classificação pendente"
    "under_review" -> "Em revisão" + (reason?.let { " ($it)" } ?: "")
    "suspended" -> "Suspenso para negociações"
    "removed_from_source" -> "Indisponível"
    else -> "Indisponível"
}

fun transactionKindLabel(kind: String): String = when (kind) {
    "initial_grant" -> "Saldo inicial"
    "income" -> "Renda coletada"
    "system_purchase" -> "Compra inicial"
    "voluntary_sale" -> "Venda por proposta"
    "forced_sale_silence" -> "Compra sem aceite (sem resposta)"
    "forced_sale_inactivity" -> "Compra sem aceite (dono inativo)"
    "adjustment" -> "Ajuste do suporte"
    "compensation" -> "Compensação"
    "account_closure" -> "Encerramento de conta"
    else -> kind
}

fun holdStatusLabel(status: String, releaseReason: String?): String = when (status) {
    "active" -> "Reservado"
    "consumed" -> "Usado na compra"
    "released" -> "Devolvido" + (releaseReason?.let { " (${releaseReasonLabel(it)})" } ?: "")
    else -> status
}

private fun releaseReasonLabel(reason: String): String = when (reason) {
    "rejected" -> "recusada"
    "cancelled" -> "cancelada"
    "expired" -> "prazo encerrado"
    "superseded" -> "imóvel mudou de dono"
    "account_deletion" -> "conta excluída"
    "admin" -> "suporte"
    else -> reason
}

fun historyKindLabel(kind: String): String = when (kind) {
    "purchase" -> "Compra"
    "sale" -> "Venda"
    "proposal" -> "Proposta"
    "income" -> "Renda"
    else -> kind
}

fun propertyLabel(p: app.donodavez.core.model.PropertySummaryDto?): String =
    if (p == null) "—" else "${categoryLabel(p.category)} · ${addressLabel(p.addressLabel)}"

fun tradeTypeLabel(type: String?): String = when (type) {
    "system_purchase" -> "Compra inicial"
    "voluntary" -> "Proposta aceita"
    "forced_silence" -> "Sem aceite (sem resposta)"
    "forced_inactivity" -> "Sem aceite (dono inativo)"
    "returned_to_system" -> "Devolvido ao sistema"
    null -> ""
    else -> type
}
