package app.backupcircle.member.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.backupcircle.core.ui.BackupTheme
import app.backupcircle.member.memberGraph
import app.backupcircle.member.service.ProtectionService

object Routes {
    const val LANGUAGE = "language"
    const val WELCOME = "welcome"
    const val PERMISSIONS = "permissions?fix={fix}"
    const val CODE = "code?add={add}"
    const val CONNECTED = "connected/{name}"
    const val HOME = "home"
    const val ABOUT = "about"
    const val DEMO = "demo"

    fun permissions(fix: Boolean) = "permissions?fix=$fix"
    fun code(add: Boolean) = "code?add=$add"
    fun connected(name: String) = "connected/${Uri.encode(name)}"
}

/** AppCompatActivity so the per-app language switch works on every Android version. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = memberGraph.store.current
        val start = when {
            prefs.language == null -> Routes.LANGUAGE
            !prefs.started -> Routes.WELCOME
            !prefs.permissionsDone -> Routes.permissions(false)
            prefs.guardians.isEmpty() -> Routes.code(false)
            else -> Routes.HOME
        }
        if (prefs.permissionsDone) ProtectionService.start(this)
        setContent {
            BackupTheme(large = true) {
                MemberNav(start, onRestart = ::restart)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        memberGraph.repository.recheckHealth()
    }

    /** After "Reset this phone": start again from the language screen. */
    private fun restart() {
        finish()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
}

@Composable
private fun MemberNav(start: String, onRestart: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    val graph = androidx.compose.ui.platform.LocalContext.current.memberGraph
    val prefs by graph.store.state.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()

    // If every Backup removes this phone, go back to showing the code.
    LaunchedEffect(prefs.guardians.isEmpty(), current?.destination?.route) {
        val route = current?.destination?.route
        if (prefs.guardians.isEmpty() && prefs.permissionsDone && (route == Routes.HOME || route == Routes.ABOUT)) {
            nav.navigate(Routes.code(false)) { popUpTo(0) }
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.LANGUAGE) {
            LanguageScreen(onChosen = { code ->
                // Navigate first: the language change recreates the screen and keeps this state.
                nav.navigate(Routes.WELCOME) { popUpTo(Routes.LANGUAGE) { inclusive = true } }
                applyLanguage(code)
            })
        }
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onStart = { nav.navigate(Routes.permissions(false)) },
                onDemoPanel = { nav.navigate(Routes.DEMO) },
            )
        }
        composable(
            Routes.PERMISSIONS,
            arguments = listOf(navArgument("fix") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val fix = entry.arguments?.getBoolean("fix") ?: false
            PermissionsScreen(onContinue = {
                if (fix) {
                    nav.popBackStack()
                } else if (graph.store.current.guardians.isEmpty()) {
                    nav.navigate(Routes.code(false)) { popUpTo(0) }
                } else {
                    nav.navigate(Routes.HOME) { popUpTo(0) }
                }
            })
        }
        composable(
            Routes.CODE,
            arguments = listOf(navArgument("add") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val add = entry.arguments?.getBoolean("add") ?: false
            CodeScreen(
                showBack = add,
                onBack = { nav.popBackStack() },
                onConnected = { name -> nav.navigate(Routes.connected(name)) { popUpTo(Routes.CODE) { inclusive = true } } },
                onDemoPanel = { nav.navigate(Routes.DEMO) },
            )
        }
        composable(Routes.CONNECTED) { entry ->
            ConnectedScreen(
                name = Uri.decode(entry.arguments?.getString("name").orEmpty()),
                onDone = { nav.navigate(Routes.HOME) { popUpTo(0) } },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onFix = { nav.navigate(Routes.permissions(true)) },
                onAbout = { nav.navigate(Routes.ABOUT) },
                onAddBackup = { nav.navigate(Routes.code(true)) },
                onDemoPanel = { nav.navigate(Routes.DEMO) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.DEMO) {
            DemoPanelScreen(onBack = { nav.popBackStack() }, onReset = onRestart)
        }
    }
}
