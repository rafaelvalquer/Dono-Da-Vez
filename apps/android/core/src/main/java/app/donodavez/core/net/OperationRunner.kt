package app.donodavez.core.net

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * Mutações econômicas idempotentes. A chave é criada uma vez por intenção do usuário (ao confirmar) e
 * reutilizada em qualquer reenvio. Se a rede cair, consulta /v1/operations/{key} antes de qualquer nova tentativa;
 * nunca enfileira compra para executar depois.
 */
class OperationRunner(private val api: ApiClient) {

    fun newKey(): String = "op-" + UUID.randomUUID().toString()

    suspend fun run(path: String, body: JsonObject, key: String): JsonObject {
        return try {
            api.post(path, body, key)
        } catch (e: NetworkException) {
            recover(key, e)
        } catch (e: ApiException) {
            when {
                // Mesma chave já registrada (tentativa anterior chegou): o resultado gravado vale.
                e.code == "IDEMPOTENCY_CONFLICT" -> {
                    val op = lookup(key)
                    if (op == null || !op.found()) throw e
                    resolve(op, e) ?: recover(key, e)
                }
                e.code == "TRANSIENT" || e.status >= 500 -> recover(key, e)
                else -> throw e
            }
        }
    }

    private suspend fun lookup(key: String): JsonObject? =
        runCatching { api.get("/v1/operations/$key") }.getOrNull()

    private fun JsonObject.found(): Boolean = (this["found"] as? JsonPrimitive)?.booleanOrNull == true

    /** Resultado gravado da operação; null enquanto ainda está em andamento. */
    private fun resolve(op: JsonObject, cause: Throwable): JsonObject? {
        val response = op["response"] as? JsonObject
        return when ((op["status"] as? JsonPrimitive)?.contentOrNull) {
            "succeeded" -> response ?: throw OperationUnknownException(cause)
            "failed" -> throw ApiException.fromBody(409, response, null)
            "in_progress" -> null
            else -> throw OperationUnknownException(cause)
        }
    }

    private suspend fun recover(key: String, cause: Throwable): JsonObject {
        repeat(5) { attempt ->
            delay(700L * (attempt + 1))
            val op = lookup(key) ?: return@repeat
            if (!op.found()) throw OperationNotStartedException(cause)
            resolve(op, cause)?.let { return it }
        }
        throw OperationUnknownException(cause)
    }
}
