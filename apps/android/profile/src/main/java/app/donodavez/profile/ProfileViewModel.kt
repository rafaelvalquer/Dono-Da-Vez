package app.donodavez.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donodavez.core.AppContainer
import app.donodavez.core.auth.AuthState
import app.donodavez.core.model.NotificationPrefs
import app.donodavez.core.model.ProfileDto
import app.donodavez.core.net.ApiException
import app.donodavez.core.net.decode
import app.donodavez.core.net.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class ProfileEnvelope(val profile: ProfileDto)

data class ProfileUi(
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val deleting: Boolean = false,
    val deletionError: String? = null,
)

class ProfileViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ProfileUi())
    val ui: StateFlow<ProfileUi> = _ui.asStateFlow()
    private var deletionKey: String? = null

    val profile get() = c.game.bootstrap

    fun email(): String? = (c.auth.state.value as? AuthState.SignedIn)?.session?.email

    fun saveNickname(nickname: String) {
        val n = nickname.trim()
        nicknameError(n)?.let { err -> _ui.update { it.copy(error = err, message = null) }; return }
        patch(buildJsonObject { put("nickname", n) }, "Apelido salvo")
    }

    fun saveAvatar(id: String) = patch(buildJsonObject { put("avatar_id", id) }, "Avatar atualizado")

    fun savePrefs(prefs: NotificationPrefs) = patch(buildJsonObject {
        put("notification_prefs", buildJsonObject {
            put("proposals", prefs.proposals); put("transfers", prefs.transfers); put("reminders", prefs.reminders)
        })
    }, "Preferências salvas")

    private fun patch(body: JsonObject, success: String) {
        if (_ui.value.saving) return
        _ui.update { it.copy(saving = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val r: ProfileEnvelope = c.api.patch("/v1/profile", body).decode()
                c.game.updateProfile(r.profile)
                _ui.update { it.copy(saving = false, message = success) }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = e.userMessage()) }
            }
        }
    }

    fun signOut(onDone: () -> Unit) = viewModelScope.launch {
        unregisterPush()
        c.auth.signOut()
        c.clearUserData()
        onDone()
    }

    /**
     * Exclusão: confirma a senha (nova sessão com login recente, exigido pelo servidor) e envia "EXCLUIR".
     * A chave de idempotência é mantida até um resultado definitivo.
     */
    fun deleteAccount(password: String, confirmation: String, onDeleted: () -> Unit) {
        if (_ui.value.deleting) return
        if (confirmation.trim() != "EXCLUIR") {
            _ui.update { it.copy(deletionError = "Digite EXCLUIR em letras maiúsculas para confirmar") }; return
        }
        val email = email() ?: run { _ui.update { it.copy(deletionError = "Sessão inválida; entre novamente") }; return }
        _ui.update { it.copy(deleting = true, deletionError = null) }
        viewModelScope.launch {
            try {
                c.auth.signIn(email, password)
                val key = deletionKey ?: c.operations.newKey().also { deletionKey = it }
                c.operations.run("/v1/account/deletion", buildJsonObject { put("confirm", "EXCLUIR") }, key)
                deletionKey = null
                unregisterPush()
                c.auth.signOut()
                c.clearUserData()
                _ui.update { it.copy(deleting = false) }
                onDeleted()
            } catch (e: ApiException) {
                if (e.code != "REAUTH_REQUIRED") deletionKey = null
                _ui.update { it.copy(deleting = false, deletionError = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(deleting = false, deletionError = e.userMessage()) }
            }
        }
    }

    private suspend fun unregisterPush() {
        val token = c.pushToken.value ?: return
        runCatching { c.api.post("/v1/devices/unregister", buildJsonObject { put("token", token) }) }
    }

    companion object {
        private val NICK = Regex("^[A-Za-z0-9_.-]{3,24}$")

        fun nicknameError(n: String): String? =
            if (NICK.matches(n)) null else "Use de 3 a 24 caracteres: letras sem acento, números, ponto, hífen ou sublinhado"
    }
}
