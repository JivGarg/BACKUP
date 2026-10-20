package app.backupcircle.guardian.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.backupcircle.core.ui.BackupTheme
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.service.ConnectionService
import app.backupcircle.guardian.ui.demo.DemoPanelScreen
import app.backupcircle.guardian.ui.events.EventsScreen
import app.backupcircle.guardian.ui.home.HomeScreen
import app.backupcircle.guardian.ui.onboarding.AlertPermissionsScreen
import app.backupcircle.guardian.ui.onboarding.NameScreen
import app.backupcircle.guardian.ui.onboarding.WelcomeScreen
import app.backupcircle.guardian.ui.pair.EnterCodeScreen
import app.backupcircle.guardian.ui.pair.NameThemScreen

object Routes {
    const val WELCOME = "welcome"
    const val NAME = "name"
    const val PERMISSIONS = "permissions"
    const val HOME = "home"
    const val PAIR = "pair"
    const val NAME_THEM = "name_them/{memberId}"
    const val EVENTS = "events"
    const val DEMO = "demo"
    fun nameThem(memberId: String) = "name_them/$memberId"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = guardianGraph.store.current
        val start = when {
            prefs.name == null -> Routes.WELCOME
            !prefs.onboardingDone -> Routes.PERMISSIONS
            else -> Routes.HOME
        }
        if (prefs.name != null) ConnectionService.start(this)
        setContent {
            BackupTheme {
                GuardianNav(start, onRestart = ::restart)
            }
        }
    }

    /** After "Reset this phone": start again from the welcome screen. */
    private fun restart() {
        finish()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
}

@Composable
private fun GuardianNav(start: String, onRestart: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onStart = { nav.navigate(Routes.NAME) },
                onDemoPanel = { nav.navigate(Routes.DEMO) },
            )
        }
        composable(Routes.NAME) {
            NameScreen(
                onBack = { nav.popBackStack() },
                onRegistered = {
                    nav.navigate(Routes.PERMISSIONS) { popUpTo(Routes.WELCOME) { inclusive = true } }
                },
                onDemoPanel = { nav.navigate(Routes.DEMO) },
            )
        }
        composable(Routes.PERMISSIONS) {
            AlertPermissionsScreen(
                onDone = {
                    if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
                        nav.navigate(Routes.HOME) { popUpTo(Routes.PERMISSIONS) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onPair = { nav.navigate(Routes.PAIR) },
                onEvents = { nav.navigate(Routes.EVENTS) },
                onFixPermissions = { nav.navigate(Routes.PERMISSIONS) },
                onDemoPanel = { nav.navigate(Routes.DEMO) },
            )
        }
        composable(Routes.PAIR) {
            EnterCodeScreen(
                onBack = { nav.popBackStack() },
                onPaired = { memberId ->
                    nav.navigate(Routes.nameThem(memberId)) { popUpTo(Routes.PAIR) { inclusive = true } }
                },
            )
        }
        composable(Routes.NAME_THEM) { entry ->
            val memberId = entry.arguments?.getString("memberId").orEmpty()
            NameThemScreen(
                memberId = memberId,
                onDone = {
                    if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
                        nav.navigate(Routes.HOME) { popUpTo(0) }
                    }
                },
            )
        }
        composable(Routes.EVENTS) {
            EventsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.DEMO) {
            DemoPanelScreen(onBack = { nav.popBackStack() }, onReset = onRestart)
        }
    }
}
