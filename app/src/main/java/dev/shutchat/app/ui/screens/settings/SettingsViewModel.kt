package dev.shutchat.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shutchat.app.BuildConfig
import dev.shutchat.app.core.Clock
import dev.shutchat.app.data.settings.SettingsStore
import dev.shutchat.app.di.AppContainer
import dev.shutchat.app.p2p.MeshDiagnostics
import dev.shutchat.app.update.UpdateResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val displayName: String = "",
    val status: String = "",
    val theme: String = "system",
    val dynamicColor: Boolean = true,
    val readReceipts: Boolean = true,
    val typingIndicator: Boolean = true,
    val autoDownload: Boolean = false,
    val retentionDays: Int = 30,
    val notificationsEnabled: Boolean = true,
    val messageSounds: Boolean = true,
    val vibration: Boolean = true,
    val backgroundMode: Boolean = true,
    val lanDiscovery: Boolean = true,
    val nearbyDiscovery: Boolean = true,
    val relayEnabled: Boolean = true,
    val publicRelay: Boolean = false,
    val diagnostics: MeshDiagnostics = MeshDiagnostics(),
    val chatCount: Int = 0,
    val messageCount: Int = 0,
    val appVersion: String = "",
    val updateState: UpdateCheckState = UpdateCheckState.Idle,
)

/** What the update row is currently showing. */
sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Available(val version: String, val url: String?, val notes: String) : UpdateCheckState
    data class Failed(val reason: String) : UpdateCheckState
}

/** Grouped inputs, one holder per settings section. */
private data class IdentityPrefs(
    val displayName: String,
    val status: String,
    val theme: String,
    val dynamicColor: Boolean,
)

private data class BehaviourPrefs(
    val readReceipts: Boolean,
    val typingIndicator: Boolean,
    val autoDownload: Boolean,
    val retentionDays: Int,
)

private data class AlertPrefs(
    val notifications: Boolean,
    val sounds: Boolean,
    val vibration: Boolean,
    val background: Boolean,
)

private data class TransportPrefs(
    val lan: Boolean,
    val nearby: Boolean,
    val relay: Boolean,
    val publicRelay: Boolean,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settings: SettingsStore = container.settings

    /** Polled rather than observed: the mesh exposes a snapshot, not a flow. */
    private val diagnostics = MutableStateFlow(MeshDiagnostics())
    private val counters = MutableStateFlow(0 to 0)

    private val updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)

    private val identity = combine(
        settings.displayName,
        settings.status,
        settings.theme,
        settings.dynamicColor,
    ) { name, status, theme, dynamic -> IdentityPrefs(name, status, theme, dynamic) }

    private val behaviour = combine(
        settings.readReceipts,
        settings.typingIndicator,
        settings.autoDownload,
        settings.retentionDays,
    ) { receipts, typing, auto, retention -> BehaviourPrefs(receipts, typing, auto, retention) }

    private val alerts = combine(
        settings.notificationsEnabled,
        settings.messageSounds,
        settings.vibration,
        settings.backgroundMode,
    ) { notifications, sounds, vibration, background ->
        AlertPrefs(notifications, sounds, vibration, background)
    }

    private val transport = combine(
        settings.lanDiscovery,
        settings.nearbyDiscovery,
        settings.relayEnabled,
        settings.publicRelay,
    ) { lan, nearby, relay, public -> TransportPrefs(lan, nearby, relay, public) }

    val uiState: StateFlow<SettingsUiState> = combine(
        identity,
        behaviour,
        alerts,
        transport,
        combine(diagnostics, counters, updateState) { snapshot, count, update ->
            Triple(snapshot, count, update)
        },
    ) { identity, behaviour, alerts, transport, extra ->
        val (snapshot, count, update) = extra
        SettingsUiState(
            displayName = identity.displayName,
            status = identity.status,
            theme = identity.theme,
            dynamicColor = identity.dynamicColor,
            readReceipts = behaviour.readReceipts,
            typingIndicator = behaviour.typingIndicator,
            autoDownload = behaviour.autoDownload,
            retentionDays = behaviour.retentionDays,
            notificationsEnabled = alerts.notifications,
            messageSounds = alerts.sounds,
            vibration = alerts.vibration,
            backgroundMode = alerts.background,
            lanDiscovery = transport.lan,
            nearbyDiscovery = transport.nearby,
            relayEnabled = transport.relay,
            publicRelay = transport.publicRelay,
            diagnostics = snapshot,
            chatCount = count.first,
            messageCount = count.second,
            appVersion = BuildConfig.VERSION_NAME,
            updateState = update,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val clock: Clock get() = container.clock

    private val _nodeId = MutableStateFlow("")

    /** Stable device id, shown so two users can compare it out of band. */
    val nodeId: StateFlow<String> = _nodeId

    init {
        viewModelScope.launch { _nodeId.value = settings.nodeId() }
        viewModelScope.launch {
            while (true) {
                diagnostics.value = container.mesh.diagnostics
                counters.value = container.database.chatDao().count() to
                    container.database.messageDao().count()
                delay(2_000)
            }
        }
    }

    fun setDisplayName(value: String) = viewModelScope.launch { settings.setDisplayName(value) }
    fun setStatus(value: String) = viewModelScope.launch { settings.setStatus(value) }
    fun setTheme(value: String) = viewModelScope.launch { settings.setTheme(value) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settings.setDynamicColor(value) }
    fun setReadReceipts(value: Boolean) = viewModelScope.launch { settings.setReadReceipts(value) }
    fun setTypingIndicator(value: Boolean) = viewModelScope.launch { settings.setTypingIndicator(value) }
    fun setAutoDownload(value: Boolean) = viewModelScope.launch { settings.setAutoDownload(value) }
    fun setRetentionDays(value: Int) = viewModelScope.launch { settings.setRetentionDays(value) }
    fun setNotificationsEnabled(value: Boolean) = viewModelScope.launch { settings.setNotificationsEnabled(value) }
    fun setMessageSounds(value: Boolean) = viewModelScope.launch { settings.setMessageSounds(value) }
    fun setVibration(value: Boolean) = viewModelScope.launch { settings.setVibration(value) }

    fun setBackgroundMode(value: Boolean) {
        viewModelScope.launch {
            settings.setBackgroundMode(value)
            val app = dev.shutchat.app.ShutChatApp.get() ?: return@launch
            if (value) app.startSessionService() else app.stopSessionService()
        }
    }

    fun setLanDiscovery(value: Boolean) {
        viewModelScope.launch {
            settings.setLanDiscovery(value)
            restartMesh()
        }
    }

    fun setNearbyDiscovery(value: Boolean) {
        viewModelScope.launch {
            settings.setNearbyDiscovery(value)
            restartMesh()
        }
    }

    fun setRelayEnabled(value: Boolean) {
        viewModelScope.launch {
            settings.setRelayEnabled(value)
            container.mesh.relayEnabled = value
        }
    }

    /**
     * Turns the public relay on or off.
     *
     * This is the one switch that sends traffic outside the local network, so it
     * is applied immediately and the mesh rebuilds its broker session rather than
     * waiting for a restart.
     */
    fun setPublicRelay(value: Boolean) {
        viewModelScope.launch {
            settings.setPublicRelay(value)
            container.mesh.setPublicRelay(value)
            diagnostics.value = container.mesh.diagnostics
        }
    }

    /**
     * Asks the release feed whether a newer build exists.
     *
     * Failure is a first-class outcome rather than an exception: the device may be
     * offline, and the row has to say so instead of spinning forever.
     */
    fun checkForUpdates() {
        if (updateState.value is UpdateCheckState.Checking) return
        updateState.value = UpdateCheckState.Checking
        viewModelScope.launch {
            updateState.value = when (val result = container.updateChecker.check(BuildConfig.VERSION_NAME)) {
                is UpdateResult.Available -> UpdateCheckState.Available(
                    version = result.release.version,
                    url = result.release.apkUrl ?: result.release.pageUrl,
                    notes = result.release.notes,
                )

                is UpdateResult.UpToDate -> UpdateCheckState.UpToDate
                is UpdateResult.Failed -> UpdateCheckState.Failed(result.reason)
            }
        }
    }

    /** Deletes every transcript but keeps the rooms and their codes. */
    fun clearAllMessages() {
        viewModelScope.launch {
            container.database.chatDao().all().forEach { chat ->
                container.database.messageDao().deleteForChat(chat.id)
                container.database.chatDao().updateLastMessage(chat.id, "", "", clock.nowMillis())
            }
            counters.value = container.database.chatDao().count() to 0
        }
    }

    /**
     * A transport toggle changes which sockets exist, so the mesh is torn down and
     * rebuilt with the new flags rather than trying to patch live links.
     */
    private suspend fun restartMesh() {
        container.mesh.stop()
        container.mesh.start(settings.lanDiscovery.first(), settings.nearbyDiscovery.first())
        diagnostics.value = container.mesh.diagnostics
    }
}
