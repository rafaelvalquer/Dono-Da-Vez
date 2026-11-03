package app.donodavez.core.net

import app.donodavez.core.AppConfig
import app.donodavez.core.auth.AuthRepository
import app.donodavez.core.time.ServerClock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Cliente da Edge Function `api`. O jogador é identificado só pelo JWT: nenhuma rota recebe user_id.
 * Respostas são JsonObject; os chamadores decodificam com [decode].
 */
class ApiClient(
    private val config: AppConfig,
    private val http: OkHttpClient,
    private val auth: AuthRepository,
    private val clock: ServerClock,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun get(path: String, query: Map<String, Any?> = emptyMap()): JsonObject =
        execute("GET", path, query, null, null)

    suspend fun post(path: String, body: JsonElement? = null, idempotencyKey: String? = null): JsonObject =
        execute("POST", path, emptyMap(), body ?: JsonObject(emptyMap()), idempotencyKey)

    suspend fun patch(path: String, body: JsonElement): JsonObject = execute("PATCH", path, emptyMap(), body, null)

    private suspend fun execute(
        method: String, path: String, query: Map<String, Any?>, body: JsonElement?, key: String?, allowRefresh: Boolean = true,
    ): JsonObject {
        val token = auth.validAccessToken()
        val url = (config.apiBaseUrl + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
        }.build()
        val correlation = UUID.randomUUID().toString()
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("apikey", config.publishableKey)
            .header("X-Correlation-Id", correlation)
            .header("X-App-Version", config.appVersionCode.toString())
            .apply { if (key != null) header("Idempotency-Key", key) }
            .method(method, body?.toString()?.toRequestBody(jsonType))
            .build()
        val (res, text) = try {
            http.newCall(req).awaitBody()
        } catch (e: IOException) {
            throw NetworkException(e)
        }
        val obj = runCatching { DvJson.parseToJsonElement(text).jsonObject }.getOrNull()
        (obj?.get("server_time") as? JsonPrimitive)?.contentOrNull?.let(clock::update)
        if (res.code == 401 && allowRefresh && ApiException.fromBody(401, obj, null).code != "REAUTH_REQUIRED") {
            auth.validAccessToken(forceRefresh = true)
            return execute(method, path, query, body, key, allowRefresh = false)
        }
        if (!res.isSuccessful) throw ApiException.fromBody(res.code, obj, correlation)
        return obj ?: JsonObject(emptyMap())
    }
}

inline fun <reified T> JsonObject.decode(): T = DvJson.decodeFromJsonElement(serializer<T>(), this)
