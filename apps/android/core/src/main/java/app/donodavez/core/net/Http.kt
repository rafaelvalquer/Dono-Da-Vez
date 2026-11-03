package app.donodavez.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val DvJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false) // reenvio é decidido pela camada de operações (idempotência)
    .build()

/** Executa a chamada de forma cancelável. Lê o corpo inteiro antes de retornar. */
suspend fun Call.awaitBody(): Pair<Response, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val text = try {
                response.use { it.body.string() }
            } catch (e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
                return
            }
            if (cont.isActive) cont.resume(response to text)
        }
    })
}
