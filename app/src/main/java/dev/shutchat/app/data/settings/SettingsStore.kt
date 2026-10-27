package dev.shutchat.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.shutchat.app.core.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shutchat_settings")

/** App-wide preferences and the local identity. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val nodeId = stringPreferencesKey("node_id")
        val displayName = stringPreferencesKey("display_name")
        val status = stringPreferencesKey("status")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val readReceipts = booleanPreferencesKey("read_receipts")
        val typingIndicator = booleanPreferencesKey("typing_indicator")
        val autoDownload = booleanPreferencesKey("auto_download")
        val retentionDays = intPreferencesKey("retention_days")
        val notificationsEnabled = booleanPreferencesKey("notifications")
        val messageSounds = booleanPreferencesKey("message_sounds")
        val vibration = booleanPreferencesKey("vibration")
        val backgroundMode = booleanPreferencesKey("background_mode")
        val lanDiscovery = booleanPreferencesKey("lan_discovery")
        val nearbyDiscovery = booleanPreferencesKey("nearby_discovery")
        val relayEnabled = booleanPreferencesKey("relay_enabled")
        /**
         * Long-distance relay over a public broker. Separate from [relayEnabled],
         * which only means "forward for other devices". This one leaves the local
         * network, so it defaults to off and is never turned on without the user
         * asking: the rest of the app promises no server.
         */
        val publicRelay = booleanPreferencesKey("public_relay")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val lastSeenBroadcast = longPreferencesKey("last_seen_broadcast")
    }

    val displayName: Flow<String> = context.dataStore.data.map { it[Keys.displayName] ?: "" }
    val status: Flow<String> = context.dataStore.data.map { it[Keys.status] ?: "Disponibile" }
    val theme: Flow<String> = context.dataStore.data.map { it[Keys.theme] ?: "system" }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[Keys.dynamicColor] ?: true }
    val readReceipts: Flow<Boolean> = context.dataStore.data.map { it[Keys.readReceipts] ?: true }
    val typingIndicator: Flow<Boolean> = context.dataStore.data.map { it[Keys.typingIndicator] ?: true }
    val autoDownload: Flow<Boolean> = context.dataStore.data.map { it[Keys.autoDownload] ?: false }
    val retentionDays: Flow<Int> = context.dataStore.data.map { it[Keys.retentionDays] ?: 30 }
    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.notificationsEnabled] ?: true }
    val messageSounds: Flow<Boolean> = context.dataStore.data.map { it[Keys.messageSounds] ?: true }
    val vibration: Flow<Boolean> = context.dataStore.data.map { it[Keys.vibration] ?: true }
    val backgroundMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.backgroundMode] ?: true }
    val lanDiscovery: Flow<Boolean> = context.dataStore.data.map { it[Keys.lanDiscovery] ?: true }
    val nearbyDiscovery: Flow<Boolean> = context.dataStore.data.map { it[Keys.nearbyDiscovery] ?: true }
    val relayEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.relayEnabled] ?: true }
    /** Defaults to false: reaching the internet is opt-in, never automatic. */
    val publicRelay: Flow<Boolean> = context.dataStore.data.map { it[Keys.publicRelay] ?: false }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.onboardingDone] ?: false }

    /**
     * Stable per-install identity. Generated on first read so the app has an
     * identity before the user has typed anything.
     */
    suspend fun nodeId(): String {
        val existing = context.dataStore.data.first()[Keys.nodeId]
        if (existing != null) return existing
        val generated = Ids.newId()
        context.dataStore.edit { it[Keys.nodeId] = generated }
        return generated
    }

    suspend fun currentDisplayName(): String = displayName.first()

    suspend fun setDisplayName(value: String) = edit { it[Keys.displayName] = value }
    suspend fun setStatus(value: String) = edit { it[Keys.status] = value }
    suspend fun setTheme(value: String) = edit { it[Keys.theme] = value }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.dynamicColor] = value }
    suspend fun setReadReceipts(value: Boolean) = edit { it[Keys.readReceipts] = value }
    suspend fun setTypingIndicator(value: Boolean) = edit { it[Keys.typingIndicator] = value }
    suspend fun setAutoDownload(value: Boolean) = edit { it[Keys.autoDownload] = value }
    suspend fun setRetentionDays(value: Int) = edit { it[Keys.retentionDays] = value }
    suspend fun setNotificationsEnabled(value: Boolean) = edit { it[Keys.notificationsEnabled] = value }
    suspend fun setMessageSounds(value: Boolean) = edit { it[Keys.messageSounds] = value }
    suspend fun setVibration(value: Boolean) = edit { it[Keys.vibration] = value }
    suspend fun setBackgroundMode(value: Boolean) = edit { it[Keys.backgroundMode] = value }
    suspend fun setLanDiscovery(value: Boolean) = edit { it[Keys.lanDiscovery] = value }
    suspend fun setNearbyDiscovery(value: Boolean) = edit { it[Keys.nearbyDiscovery] = value }
    suspend fun setRelayEnabled(value: Boolean) = edit { it[Keys.relayEnabled] = value }

    suspend fun setPublicRelay(value: Boolean) = edit { it[Keys.publicRelay] = value }
    suspend fun setOnboardingDone(value: Boolean) = edit { it[Keys.onboardingDone] = value }
    suspend fun setLastExpirySweep(value: Long) = edit { it[Keys.lastSeenBroadcast] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
