package dev.shutchat.app.ui.screens.join

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shutchat.app.di.AppContainer
import dev.shutchat.app.domain.codec.InviteCodec
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.app.domain.model.InvalidInviteCodeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Joining from a code, typed or scanned.
 *
 * The code is validated as the user types so a typo is caught immediately rather
 * than after a round trip that does not exist — there is no server to say "not
 * found", so the only feedback available is local and it should be instant.
 */
class JoinViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val input: String = "",
        val validation: Validation = Validation.Empty,
        val joining: Boolean = false,
        val error: String? = null,
        val joinedChatId: String? = null,
        val joinedKind: InviteKind? = null,
    )

    sealed interface Validation {
        data object Empty : Validation
        data class Valid(val kind: InviteKind, val deadlineLabel: String) : Validation
        data class Invalid(val message: String) : Validation
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val clock get() = container.clock

    fun onInputChanged(value: String) {
        _state.value = _state.value.copy(input = value, validation = validate(value), error = null)
    }

    /** Fills the field from a scanned QR payload and joins straight away. */
    fun onScanned(payload: String) {
        onInputChanged(payload)
        join()
    }

    private fun validate(value: String): Validation {
        if (value.isBlank()) return Validation.Empty
        return try {
            val code = InviteCodec.parse(value)
            Validation.Valid(
                kind = code.kind,
                deadlineLabel = dev.shutchat.app.domain.expiry.Expiry
                    .describeDeadline(code.payload.expiryDay, container.clock),
            )
        } catch (e: InvalidInviteCodeException) {
            Validation.Invalid(e.message ?: "Codice non valido")
        }
    }

    fun join() {
        val current = _state.value
        if (current.joining || current.input.isBlank()) return
        _state.value = current.copy(joining = true, error = null)
        viewModelScope.launch {
            try {
                val outcome = container.chatFactory.join(current.input)
                container.refreshRooms()
                val kind = InviteCodec.parse(outcome.inviteCode).kind
                _state.value = _state.value.copy(
                    joining = false,
                    joinedChatId = outcome.chatId,
                    joinedKind = kind,
                )
            } catch (e: InvalidInviteCodeException) {
                _state.value = _state.value.copy(
                    joining = false,
                    error = e.message ?: "Codice non valido",
                    validation = Validation.Invalid(e.message ?: "Codice non valido"),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    joining = false,
                    error = e.message ?: "Non è stato possibile entrare",
                )
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Clears the join result so the caller can navigate once. */
    fun consumeJoined() {
        _state.value = _state.value.copy(joinedChatId = null, joinedKind = null)
    }
}
