package dev.shutchat.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.shutchat.app.ui.screens.conversation.ConversationScreen
import dev.shutchat.app.ui.screens.create.CreateChatScreen
import dev.shutchat.app.ui.screens.group.GroupScreen
import dev.shutchat.app.ui.screens.home.HomeScreen
import dev.shutchat.app.ui.screens.join.JoinScreen
import dev.shutchat.app.ui.screens.scan.ScanScreen
import dev.shutchat.app.ui.screens.settings.SettingsScreen

/**
 * Routes.
 *
 * String routes rather than a typed graph: the app has seven destinations and no
 * nested graphs, so the extra indirection would not pay for itself. Arguments are
 * limited to ids, which keeps every route deep-linkable.
 */
object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val JOIN = "join"
    const val SCAN = "scan"
    const val SETTINGS = "settings"
    const val GROUP = "group/{chatId}"
    const val CONVERSATION = "chat/{chatId}"

    fun group(chatId: String) = "group/$chatId"
    fun conversation(chatId: String) = "chat/$chatId"
}

@Composable
fun ShutChatNavHost(
    navController: NavHostController = rememberNavController(),
    startConversation: String? = null,
) {
    NavHost(
        navController = navController,
        startDestination = startConversation?.let(Routes::conversation) ?: Routes.HOME,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(240)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { navController.navigate(Routes.conversation(it)) },
                onCreate = { navController.navigate(Routes.CREATE) },
                onJoin = { navController.navigate(Routes.JOIN) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.CREATE) {
            CreateChatScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { chatId ->
                    // Replace so the back button from the new chat returns to the
                    // list, not to the code reveal screen.
                    navController.navigate(Routes.conversation(chatId)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }

        composable(Routes.JOIN) {
            JoinScreen(
                onBack = { navController.popBackStack() },
                onScan = { navController.navigate(Routes.SCAN) },
                onOpenChat = { chatId ->
                    navController.navigate(Routes.conversation(chatId)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }

        composable(Routes.SCAN) {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onCode = { code ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("scanned_code", code)
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = Routes.CONVERSATION,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) { entry ->
            val chatId = entry.arguments?.getString("chatId").orEmpty()
            ConversationScreen(
                chatId = chatId,
                onBack = { navController.popBackStack() },
                onOpenGroup = { navController.navigate(Routes.group(chatId)) },
            )
        }

        composable(
            route = Routes.GROUP,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) { entry ->
            val chatId = entry.arguments?.getString("chatId").orEmpty()
            GroupScreen(
                chatId = chatId,
                onBack = { navController.popBackStack() },
                onOpenChat = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
