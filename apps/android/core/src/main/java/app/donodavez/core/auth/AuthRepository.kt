package app.donodavez.core.auth

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.donodavez.core.AppConfig
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.DvJson
import app.donodavez.core.net.NetworkException
import app.donodavez.core.net.awaitBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String? = null,
)

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val session: AuthSession, val recovery: Boolean = false) : AuthState
}

sealed interface SignUpResult {
    data object ConfirmationPending : SignUpResult
    data object SignedIn : SignUpResult
}

private val Context.sessionDataStore by preferencesDataStore("dv_session")
private val SESSION_KEY = stringPreferencesKey("session")

/** Sessão salva no armazenamento privado do app (sem service role, sem credenciais administrativas). */
class SessionStore(private val context: Context) {
    suspend fun load(): AuthSession? = context.sessionDataStore.data
        .map { it[SESSION_KEY] }
        .first()
        ?.let { runCatching { DvJson.decodeFromString(AuthSession.serializer(), it) }.getOrNull() }

    suspend fun save(session: AuthSession) {
        context.sessionDataStore.edit { it[SESSION_KEY] = DvJson.encodeToString(AuthSession.serializer(), session) }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.remove(SESSION_KEY) }
    }
}

/** Supabase Auth via HTTP: cadastro com confirmação, login, recuperação, refresh e logout. */
class AuthRepository(
    private val config: AppConfig,
    private val http: OkHttpClient,
    private val store: SessionStore,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private val refreshLock = Mutex()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    init {
        scope.launch {
            val saved = store.load()
            _state.value = if (saved == null) AuthState.SignedOut else AuthState.SignedIn(saved)
        }
    }

    suspend fun signUp(email: String, password: String): SignUpResult {
        val url = "${config.authBaseUrl}/signup".toHttpUrl().newBuilder()
            .addQueryParameter("redirect_to", config.authRedirectUrl).build().toString()
        val body = call(url, buildJsonObject { put("email", email.trim()); put("password", password) })
        return if (body.containsKey("access_token")) {
            setSession(sessionFrom(body)); SignUpResult.SignedIn
        } else {
            SignUpResult.ConfirmationPending
        }
    }

    suspend fun signIn(email: String, password: String) {
        val body = call("${config.authBaseUrl}/token?grant_type=password",
            buildJsonObject { put("email", email.trim()); put("password", password) })
        setSession(sessionFrom(body))
    }

    suspend fun resendConfirmation(email: String) {
        call("${config.authBaseUrl}/resend", buildJsonObject { put("type", "signup"); put("email", email.trim()) })
    }

    suspend fun recover(email: String) {
        val url = "${config.authBaseUrl}/recover".toHttpUrl().newBuilder()
            .addQueryParameter("redirect_to", config.authRedirectUrl).build().toString()
        call(url, buildJsonObject { put("email", email.trim()) })
    }

    /** Após o link de recuperação: define a nova senha com a sessão de recuperação. */
    suspend fun updatePassword(newPassword: String) {
        val token = validAccessToken()
        call("${config.authBaseUrl}/user", buildJsonObject { put("password", newPassword) }, method = "PUT", bearer = token)
        (state.value as? AuthState.SignedIn)?.let { _state.value = it.copy(recovery = false) }
    }

    /** donodavez://auth-callback#access_token=...&refresh_token=...&type=signup|recovery */
    suspend fun handleCallback(uri: Uri): Boolean {
        val fragment = uri.fragment ?: return false
        val params = fragment.split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to Uri.decode(it.substring(i + 1))
        }.toMap()
        params["error_description"]?.let { throw ApiException(400, "AUTH_LINK", it.replace('+', ' ')) }
        val access = params["access_token"] ?: return false
        val refresh = params["refresh_token"] ?: return false
        val user = fetchUser(access)
        val expiresAt = params["expires_at"]?.toLongOrNull()
            ?: (System.currentTimeMillis() / 1000 + (params["expires_in"]?.toLongOrNull() ?: 3600))
        val session = AuthSession(access, refresh, expiresAt, user.first, user.second)
        store.save(session)
        _state.value = AuthState.SignedIn(session, recovery = params["type"] == "recovery")
        return true
    }

    suspend fun signOut() {
        val current = (state.value as? AuthState.SignedIn)?.session
        if (current != null) {
            runCatching { call("${config.authBaseUrl}/logout", JsonObject(emptyMap()), bearer = current.accessToken) }
        }
        store.clear()
        _state.value = AuthState.SignedOut
    }

    /** Token válido por pelo menos 60 s; renova uma única vez por vez (refresh tokens são rotativos). */
    suspend fun validAccessToken(forceRefresh: Boolean = false): String {
        val current = (state.value as? AuthState.SignedIn)?.session
            ?: store.load()
            ?: throw ApiException(401, "UNAUTHORIZED", "Entre na sua conta para continuar")
        if (!forceRefresh && current.expiresAt - 60 > System.currentTimeMillis() / 1000) return current.accessToken
        return refreshLock.withLock {
            val latest = (state.value as? AuthState.SignedIn)?.session ?: current
            if (latest.accessToken != current.accessToken && latest.expiresAt - 60 > System.currentTimeMillis() / 1000) {
                return@withLock latest.accessToken
            }
            try {
                val body = call("${config.authBaseUrl}/token?grant_type=refresh_token",
                    buildJsonObject { put("refresh_token", latest.refreshToken) })
                val session = sessionFrom(body)
                setSession(session)
                session.accessToken
            } catch (e: ApiException) {
                if (e.status in 400..499) {
                    store.clear(); _state.value = AuthState.SignedOut
                    throw ApiException(401, "UNAUTHORIZED", "Sessão expirada; entre novamente")
                }
                throw e
            }
        }
    }

    private suspend fun setSession(session: AuthSession) {
        store.save(session)
        _state.value = AuthState.SignedIn(session)
    }

    private fun sessionFrom(body: JsonObject): AuthSession {
        val user = body["user"]?.jsonObject
        val expiresAt = (body["expires_at"] as? JsonPrimitive)?.long
            ?: (System.currentTimeMillis() / 1000 + ((body["expires_in"] as? JsonPrimitive)?.long ?: 3600))
        return AuthSession(
            accessToken = body.string("access_token")!!,
            refreshToken = body.string("refresh_token")!!,
            expiresAt = expiresAt,
            userId = user?.string("id") ?: "",
            email = user?.string("email"),
        )
    }

    private suspend fun fetchUser(accessToken: String): Pair<String, String?> {
        val req = Request.Builder().url("${config.authBaseUrl}/user")
            .header("apikey", config.publishableKey).header("Authorization", "Bearer $accessToken").get().build()
        val body = execute(req)
        return (body.string("id") ?: "") to body.string("email")
    }

    private suspend fun call(url: String, payload: JsonObject, method: String = "POST", bearer: String? = null): JsonObject {
        val builder = Request.Builder().url(url).header("apikey", config.publishableKey)
            .method(method, payload.toString().toRequestBody(jsonType))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        return execute(builder.build())
    }

    private suspend fun execute(req: Request): JsonObject {
        val (res, text) = try {
            http.newCall(req).awaitBody()
        } catch (e: IOException) {
            throw NetworkException(e)
        }
        val body = runCatching { DvJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        if (!res.isSuccessful) throw authError(res.code, body)
        return body
    }

    private fun authError(status: Int, body: JsonObject): ApiException {
        val code = body.string("error_code") ?: body.string("error") ?: "AUTH_ERROR"
        val message = when (code) {
            "invalid_credentials", "invalid_grant" -> "E-mail ou senha incorretos"
            "email_not_confirmed" -> "Confirme seu e-mail antes de entrar (verifique a caixa de entrada)"
            "user_already_exists", "email_exists" -> "Já existe uma conta com este e-mail"
            "weak_password" -> "Senha fraca: use pelo menos 8 caracteres com letras e números"
            "email_address_invalid", "validation_failed" -> "E-mail inválido"
            "over_email_send_rate_limit", "over_request_rate_limit" -> "Muitas tentativas; aguarde alguns minutos"
            "refresh_token_not_found", "refresh_token_already_used", "session_not_found" -> "Sessão expirada; entre novamente"
            "same_password" -> "A nova senha deve ser diferente da anterior"
            else -> body.string("msg") ?: body.string("error_description") ?: "Não foi possível autenticar agora"
        }
        return ApiException(status, code.uppercase(), message)
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
