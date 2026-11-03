package app.donodavez.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donodavez.core.auth.AuthRepository
import app.donodavez.core.auth.SignUpResult
import app.donodavez.core.net.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep { WELCOME, SIGN_IN, SIGN_UP, RECOVER, CONFIRM_PENDING, RECOVERY_SENT }

data class AuthUiState(
    val step: AuthStep = AuthStep.WELCOME,
    val email: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    fun go(step: AuthStep) = _ui.update { it.copy(step = step, error = null, info = null) }

    fun signIn(email: String, password: String) = submit(email) {
        validate(email, password, checkStrength = false)
        auth.signIn(email, password)
    }

    fun signUp(email: String, password: String, confirm: String, acceptedTerms: Boolean) = submit(email) {
        validate(email, password, checkStrength = true)
        require(password == confirm) { "As senhas não conferem" }
        require(acceptedTerms) { "Confirme que entendeu que as moedas são fictícias e sem valor real" }
        when (auth.signUp(email, password)) {
            SignUpResult.ConfirmationPending -> _ui.update { it.copy(step = AuthStep.CONFIRM_PENDING) }
            SignUpResult.SignedIn -> Unit
        }
    }

    fun recover(email: String) = submit(email) {
        require(Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { "E-mail inválido" }
        auth.recover(email)
        _ui.update { it.copy(step = AuthStep.RECOVERY_SENT) }
    }

    fun resend() = submit(_ui.value.email) {
        auth.resendConfirmation(_ui.value.email)
        _ui.update { it.copy(info = "Enviamos um novo e-mail de confirmação") }
    }

    private fun validate(email: String, password: String, checkStrength: Boolean) {
        require(Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { "E-mail inválido" }
        if (checkStrength) {
            require(password.length >= 8 && password.any(Char::isDigit) && password.any(Char::isLetter)) {
                "Use pelo menos 8 caracteres, com letras e números"
            }
        } else {
            require(password.isNotEmpty()) { "Informe a senha" }
        }
    }

    private fun submit(email: String, block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, info = null, email = email.trim()) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: IllegalArgumentException) {
                _ui.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.userMessage()) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }
}
