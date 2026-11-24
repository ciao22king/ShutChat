package dev.shutchat.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.shutchat.desktop.ui.ShutChatDesktopApp

fun main() {
    val app = DesktopApp()
    app.start()
    application {
        Window(
            onCloseRequest = {
                app.stop()
                exitApplication()
            },
            title = "ShutChat",
            state = rememberWindowState(size = DpSize(1120.dp, 780.dp)),
        ) {
            ShutChatDesktopApp(app)
        }
    }
}
