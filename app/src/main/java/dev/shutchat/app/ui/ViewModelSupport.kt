package dev.shutchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.shutchat.app.ShutChatApp
import dev.shutchat.app.di.AppContainer

/**
 * Access to the container from a composable.
 *
 * A hand written factory rather than a DI framework: the graph is small, and an
 * explicit list of constructors makes it obvious what each screen depends on.
 */
fun CreationExtras.container(): AppContainer {
    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShutChatApp
    return app.container
}

/** The app container, for screens that need a service rather than a view model. */
@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return (context.applicationContext as ShutChatApp).container
}

/** Builds a `viewModelFactory` that resolves the container from the app. */
inline fun <reified VM : ViewModel> containerFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create(container()) }
}

@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    crossinline create: (AppContainer) -> VM,
): VM = viewModel(factory = containerFactory(create))
