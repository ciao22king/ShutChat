package dev.shutchat.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shutchat.app.core.Clock
import dev.shutchat.app.di.AppContainer
import dev.shutchat.app.domain.model.Chat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the conversation list needs to render, in one object. */
data class HomeUiState(
    val chats: List<Chat> = emptyList(),
    val memberCounts: Map<String, Int> = emptyMap(),
    val connectedPeers: Int = 0,
    val lanActive: Boolean = false,
    val nearbyActive: Boolean = false,
    val displayName: String = "",
    val onboardingDone: Boolean = true,
)

private data class Identity(
    val displayName: String,
    val onboardingDone: Boolean,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val chats = container.chatRepository.chats
    private val memberCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Identity and preferences, read once per emission of the list. */
    private val identity: kotlinx.coroutines.flow.Flow<Identity> = combine(
        container.settings.displayName,
        container.settings.onboardingDone,
    ) { name, onboarded -> Identity(name, onboarded) }

    val uiState: StateFlow<HomeUiState> = combine(
        chats,
        memberCounts,
        identity,
    ) { chatList, counts, identity ->
        HomeUiState(
            chats = chatList,
            memberCounts = counts,
            connectedPeers = container.mesh.diagnostics.connectedPeers,
            lanActive = container.mesh.diagnostics.lanActive,
            nearbyActive = container.mesh.diagnostics.nearbyActive,
            displayName = identity.displayName,
            onboardingDone = identity.onboardingDone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val clock: Clock get() = container.clock

    init {
        // Recount members whenever the roster changes, and tick the clock so the
        // "expires in" lines stay truthful while the list is on screen.
        viewModelScope.launch {
            chats.collect { list ->
                memberCounts.value = list.associate { chat ->
                    chat.id to container.chatRepository.members(chat.id).size
                }
            }
        }
        viewModelScope.launch {
            container.chatRepository.purgeOldMessages()
        }
    }

    fun onChatOpened(chatId: String) {
        viewModelScope.launch { container.chatRepository.markRead(chatId) }
        container.notifier.clear(chatId)
    }

    fun setPinned(chatId: String, pinned: Boolean) {
        viewModelScope.launch { container.chatRepository.setPinned(chatId, pinned) }
    }

    fun setMuted(chatId: String, muted: Boolean) {
        viewModelScope.launch { container.chatRepository.setMuted(chatId, muted) }
    }

    fun setArchived(chatId: String, archived: Boolean) {
        viewModelScope.launch { container.chatRepository.setArchived(chatId, archived) }
    }

    fun delete(chatId: String) {
        viewModelScope.launch {
            container.chatFactory.deleteChat(chatId)
            container.refreshRooms()
        }
    }

    fun markOnboardingDone() {
        viewModelScope.launch { container.settings.setOnboardingDone(true) }
    }
}
