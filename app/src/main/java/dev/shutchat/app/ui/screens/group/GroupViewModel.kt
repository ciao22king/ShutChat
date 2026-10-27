package dev.shutchat.app.ui.screens.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shutchat.app.core.Clock
import dev.shutchat.app.data.repo.ChatRepository
import dev.shutchat.app.data.settings.SettingsStore
import dev.shutchat.app.di.AppContainer
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GroupUiState(
    val chat: Chat? = null,
    val members: List<ChatMember> = emptyList(),
    val connected: Int = 0,
    val ownerNodeId: String? = null,
    val isOwner: Boolean = false,
)

class GroupViewModel(
    private val container: AppContainer,
    private val chatId: String,
) : ViewModel() {

    private val chats: ChatRepository = container.chatRepository
    private val selfNodeId = MutableStateFlow("")
    private val connected = MutableStateFlow(0)

    val uiState: StateFlow<GroupUiState> = combine(
        chats.observeChat(chatId),
        chats.observeMembers(chatId),
        selfNodeId,
        connected,
    ) { chat, members, self, peers ->
        GroupUiState(
            chat = chat,
            members = members,
            connected = peers,
            ownerNodeId = members.firstOrNull { it.isOwner }?.nodeId,
            isOwner = members.firstOrNull { it.isSelf }?.isOwner == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupUiState())

    val clock: Clock get() = container.clock

    init {
        viewModelScope.launch { selfNodeId.value = container.settings.nodeId() }
        viewModelScope.launch {
            container.mesh.peerConnected.collect {
                connected.value = container.mesh.diagnostics.connectedPeers
            }
        }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { chats.setTitle(chatId, name) }
    }

    fun remove(nodeId: String) {
        viewModelScope.launch { chats.removeMember(chatId, nodeId) }
    }
}
