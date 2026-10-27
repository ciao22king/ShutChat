package dev.shutchat.app.ui.screens.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shutchat.app.di.AppContainer
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.InviteKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "+" flow.
 *
 * Two steps, in this order: pick how long the code should live, then receive the
 * code. The expiry choice comes first on purpose — a code is the only thing that
 * grants access, so deciding its lifetime before it exists is the honest order,
 * and the reveal screen then has nothing left to ask.
 */
class CreateChatViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface Step {
        data object ChooseKind : Step
        data object ChooseExpiry : Step
        data class Reveal(val chatId: String, val code: String, val expiryDay: Int?) : Step
    }

    data class UiState(
        val step: Step = Step.ChooseKind,
        val kind: InviteKind = InviteKind.DIRECT,
        val preset: Expiry.Preset = Expiry.Preset.ONE_WEEK,
        val groupName: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val clock get() = container.clock

    fun chooseKind(kind: InviteKind) {
        _state.value = _state.value.copy(kind = kind, step = Step.ChooseExpiry)
    }

    fun setPreset(preset: Expiry.Preset) {
        _state.value = _state.value.copy(preset = preset)
    }

    fun setGroupName(name: String) {
        _state.value = _state.value.copy(groupName = name)
    }

    fun back() {
        if (_state.value.step is Step.ChooseExpiry) {
            _state.value = _state.value.copy(step = Step.ChooseKind)
        }
    }

    /** Creates the room and moves to the reveal step. */
    fun confirm() {
        val current = _state.value
        if (current.busy) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val expiryDay = Expiry.presetExpiryDay(current.preset, container.clock)
                val outcome = container.chatFactory.create(
                    kind = current.kind,
                    expiryDay = expiryDay,
                    title = current.groupName.takeIf { current.kind == InviteKind.GROUP },
                )
                container.refreshRooms()
                _state.value = _state.value.copy(
                    busy = false,
                    step = Step.Reveal(outcome.chatId, outcome.inviteCode, expiryDay),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = e.message ?: "Non è stato possibile creare la chat",
                )
            }
        }
    }

    /** Regenerates the code for the room being shown, keeping the transcript. */
    fun rotate() {
        val reveal = _state.value.step as? Step.Reveal ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val code = container.chatFactory.rotateCode(reveal.chatId, reveal.expiryDay)
                container.refreshRooms()
                _state.value = _state.value.copy(
                    busy = false,
                    step = Step.Reveal(reveal.chatId, code.text, reveal.expiryDay),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = e.message)
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    suspend fun memberCount(chatId: String): Int = container.chatRepository.members(chatId).size

    suspend fun title(chatId: String): String =
        container.chatRepository.chat(chatId)?.title ?: "Nuova chat"
}
