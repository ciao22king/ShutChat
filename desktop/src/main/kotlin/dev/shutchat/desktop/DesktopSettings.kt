package dev.shutchat.desktop

import dev.shutchat.app.core.Ids
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Desktop preferences and the local identity.
 *
 * A properties file under the XDG config directory rather than a database: the
 * desktop client has a handful of settings and no migrations to run, and a plain
 * file is something the user can inspect and delete.
 */
class DesktopSettings(private val dir: Path = defaultDir()) {

    private val file: Path = dir.resolve("settings.properties")
    private val props = Properties()

    val nodeId: String

    var displayName: String
        private set

    var publicRelay: Boolean
        private set

    var lanDiscovery: Boolean
        private set

    /** "system", "light" or "dark". */
    var theme: String
        private set

    var readReceipts: Boolean
        private set

    var typingIndicator: Boolean
        private set

    var notifications: Boolean
        private set

    var forwarding: Boolean
        private set

    /** Days of transcript to keep; 0 keeps everything. */
    var retentionDays: Int
        private set

    init {
        Files.createDirectories(dir)
        if (Files.exists(file)) {
            Files.newInputStream(file).use { props.load(it) }
        }
        val existing = props.getProperty(KEY_NODE_ID)
        if (existing.isNullOrBlank()) {
            val generated = Ids.newId()
            props.setProperty(KEY_NODE_ID, generated)
            nodeId = generated
            save()
        } else {
            nodeId = existing
        }
        displayName = props.getProperty(KEY_NAME) ?: System.getProperty("user.name").orEmpty()
        publicRelay = props.getProperty(KEY_RELAY)?.toBooleanStrictOrNull() ?: true
        lanDiscovery = props.getProperty(KEY_LAN)?.toBooleanStrictOrNull() ?: true
        theme = props.getProperty(KEY_THEME) ?: "dark"
        readReceipts = props.getProperty(KEY_RECEIPTS)?.toBooleanStrictOrNull() ?: true
        typingIndicator = props.getProperty(KEY_TYPING)?.toBooleanStrictOrNull() ?: true
        notifications = props.getProperty(KEY_NOTIFY)?.toBooleanStrictOrNull() ?: true
        forwarding = props.getProperty(KEY_FORWARD)?.toBooleanStrictOrNull() ?: true
        retentionDays = props.getProperty(KEY_RETENTION)?.toIntOrNull() ?: 0
    }

    fun update(
        displayName: String? = null,
        publicRelay: Boolean? = null,
        lanDiscovery: Boolean? = null,
        theme: String? = null,
        readReceipts: Boolean? = null,
        typingIndicator: Boolean? = null,
        notifications: Boolean? = null,
        forwarding: Boolean? = null,
        retentionDays: Int? = null,
    ) {
        displayName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            this.displayName = it
            props.setProperty(KEY_NAME, it)
        }
        publicRelay?.let {
            this.publicRelay = it
            props.setProperty(KEY_RELAY, it.toString())
        }
        lanDiscovery?.let {
            this.lanDiscovery = it
            props.setProperty(KEY_LAN, it.toString())
        }
        theme?.let {
            this.theme = it
            props.setProperty(KEY_THEME, it)
        }
        readReceipts?.let {
            this.readReceipts = it
            props.setProperty(KEY_RECEIPTS, it.toString())
        }
        typingIndicator?.let {
            this.typingIndicator = it
            props.setProperty(KEY_TYPING, it.toString())
        }
        notifications?.let {
            this.notifications = it
            props.setProperty(KEY_NOTIFY, it.toString())
        }
        forwarding?.let {
            this.forwarding = it
            props.setProperty(KEY_FORWARD, it.toString())
        }
        retentionDays?.let {
            this.retentionDays = it
            props.setProperty(KEY_RETENTION, it.toString())
        }
        save()
    }

    private fun save() {
        runCatching { Files.newOutputStream(file).use { props.store(it, "ShutChat desktop") } }
    }

    companion object {
        private const val KEY_NODE_ID = "node.id"
        private const val KEY_NAME = "display.name"
        private const val KEY_RELAY = "relay.public"
        private const val KEY_LAN = "lan.discovery"
        private const val KEY_THEME = "ui.theme"
        private const val KEY_RECEIPTS = "chat.readReceipts"
        private const val KEY_TYPING = "chat.typing"
        private const val KEY_NOTIFY = "ui.notifications"
        private const val KEY_FORWARD = "net.forwarding"
        private const val KEY_RETENTION = "chat.retentionDays"

        fun defaultDir(): Path = Paths.get(
            System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"),
            "shutchat",
        )

        fun dataDir(): Path = Paths.get(
            System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"),
            "shutchat",
        )
    }
}
