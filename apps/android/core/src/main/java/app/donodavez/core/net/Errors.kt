package app.donodavez.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** Erro de domínio/HTTP com código estável (packages/contracts/schemas/error-codes.json). */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val detail: JsonObject = JsonObject(emptyMap()),
    val correlationId: String? = null,
) : Exception(message) {
    fun detailString(key: String): String? = (detail[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        fun fromBody(status: Int, body: JsonObject?, correlationId: String?): ApiException {
            val error = body?.get("error") as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull ?: if (status == 401) "UNAUTHORIZED" else "INTERNAL"
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: defaultMessage(status)
            val detail = error?.get("detail") as? JsonObject ?: JsonObject(emptyMap())
            val corr = correlationId ?: body?.get("correlation_id")?.jsonPrimitive?.contentOrNull
            return ApiException(status, code, message, detail, corr)
        }

        private fun defaultMessage(status: Int) = when (status) {
            401 -> "Sessão expirada; entre novamente"
            429 -> "Muitas tentativas; aguarde um instante"
            in 500..599 -> "Serviço indisponível no momento; tente novamente"
            else -> "Não foi possível concluir a operação"
        }
    }
}

/** Falha de rede antes de obter resposta (o servidor pode ou não ter processado). */
class NetworkException(cause: IOException) : Exception("Sem conexão. Verifique a internet e tente de novo.", cause)

/** O servidor confirmou que a operação não começou: é seguro tentar de novo com a mesma chave. */
class OperationNotStartedException(cause: Throwable) :
    Exception("Sem conexão no envio; nada foi cobrado. Tente novamente.", cause)

/** Não foi possível confirmar o resultado: o usuário deve conferir histórico/carteira antes de repetir. */
class OperationUnknownException(cause: Throwable) :
    Exception("Não conseguimos confirmar o resultado. Confira o histórico antes de tentar de novo.", cause)

fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message
    is NetworkException, is OperationNotStartedException, is OperationUnknownException -> message ?: "Sem conexão"
    is IOException -> "Sem conexão. Verifique a internet e tente de novo."
    else -> "Algo deu errado. Tente novamente."
}
