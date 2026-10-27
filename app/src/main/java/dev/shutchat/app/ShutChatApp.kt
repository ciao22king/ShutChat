package dev.shutchat.app

import android.app.Application
import dev.shutchat.app.core.Log
import dev.shutchat.app.di.AppContainer
import dev.shutchat.app.service.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * ShutChat has no backend, so "startup" means: build the local identity, open the
 * database and bring the mesh up. There is nothing to authenticate against and
 * nothing to download.
 */
class ShutChatApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        container.notifier.ensureChannels()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { container.bootstrap() }
                .onFailure { Log.e(TAG, "bootstrap fallito", it) }
        }
    }

    /** Starts the foreground listener, so messages arrive with the app closed. */
    fun startSessionService() {
        runCatching { startForegroundService(android.content.Intent(this, SessionService::class.java)) }
            .onFailure { Log.w(TAG, "servizio di sessione non avviato", it) }
    }

    fun stopSessionService() {
        runCatching { stopService(android.content.Intent(this, SessionService::class.java)) }
    }

    companion object {
        private const val TAG = "ShutChatApp"

        @Volatile
        private var instance: ShutChatApp? = null

        fun get(): ShutChatApp? = instance
    }
}
