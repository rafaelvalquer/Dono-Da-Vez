package app.donodavez.core.game

import app.donodavez.core.model.Money
import app.donodavez.core.model.OperationResultDto
import app.donodavez.core.model.PortfolioDto
import app.donodavez.core.net.ApiClient
import app.donodavez.core.net.OperationRunner
import app.donodavez.core.net.decode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.math.BigDecimal

/** Coleta de renda em lotes de até 50 imóveis (limite do endpoint). Valores são calculados pelo servidor. */
class IncomeCollector(
    private val api: ApiClient,
    private val operations: OperationRunner,
    private val game: GameRepository,
) {
    /** Titularidades com renda coletável (≥ 0,01) agora. */
    suspend fun collectableOwnerships(): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page: PortfolioDto = api.get("/v1/portfolio", mapOf("cursor" to cursor, "limit" to 100)).decode()
            ids += page.items.filter { Money.parse(it.collectable).signum() > 0 }.map { it.ownershipId }
            cursor = page.nextCursor
        } while (cursor != null)
        return ids
    }

    /**
     * [keyFor] devolve a chave de idempotência de cada lote: o chamador a guarda até o resultado
     * definitivo, para que um reenvio após falha de rede não colete duas vezes.
     */
    suspend fun collect(ownershipIds: List<String>, keyFor: (List<String>) -> String): BigDecimal {
        var total = BigDecimal.ZERO
        for (chunk in ownershipIds.distinct().sorted().chunked(BATCH)) {
            val body = buildJsonObject { put("ownership_ids", JsonArray(chunk.map(::JsonPrimitive))) }
            val result: OperationResultDto = operations.run("/v1/income/collect", body, keyFor(chunk)).decode()
            game.updateWallet(result.wallet)
            total += Money.parse(result.collected)
        }
        return total
    }

    companion object {
        const val BATCH = 50
    }
}
