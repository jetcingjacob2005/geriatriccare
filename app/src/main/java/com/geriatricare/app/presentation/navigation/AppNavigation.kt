package com.geriatricare.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.geriatricare.app.presentation.addpatient.AddPatientScreen
import com.geriatricare.app.presentation.editpatient.EditPatientScreen
import com.geriatricare.app.presentation.dashboard.DashboardScreen
import com.geriatricare.app.presentation.login.LoginScreen
import com.geriatricare.app.presentation.patientlist.PatientListScreen
import com.geriatricare.app.presentation.splash.SplashScreen

@Composable
fun AppNavigation() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Screen.Splash.route) {

        composable(Screen.Splash.route) {
            SplashScreen(
                onLogin           = { nav.navigate(Screen.Login.route)           { popUpTo(Screen.Splash.route) { inclusive = true } } },
                onPatientList     = { nav.navigate(Screen.PatientList.route)     { popUpTo(Screen.Splash.route) { inclusive = true } } },
                onFamilyHome      = { nav.navigate(Screen.FamilyHome.route)      { popUpTo(Screen.Splash.route) { inclusive = true } } },
                onDoctorDashboard = { nav.navigate(Screen.DoctorDashboard.route) { popUpTo(Screen.Splash.route) { inclusive = true } } }
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { destination ->
                    val target = when (destination) {
                        com.geriatricare.app.presentation.login.LoginDestination.DOCTOR -> Screen.DoctorDashboard.route
                        com.geriatricare.app.presentation.login.LoginDestination.FAMILY -> Screen.FamilyHome.route
                        else -> Screen.PatientList.route
                    }
                    nav.navigate(target) { popUpTo(Screen.Login.route) { inclusive = true } }
                }
            )
        }

        composable(Screen.FamilyHome.route) {
            com.geriatricare.app.presentation.familyhome.FamilyHomeScreen(
                onPatientClick = { id -> nav.navigate(Screen.FamilyDashboard.route(id)) },
                onLogout       = { nav.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } } }
            )
        }

        composable(
            route = Screen.FamilyDashboard.route,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) { back ->
            val patientId = back.arguments?.getString("patientId") ?: return@composable
            com.geriatricare.app.presentation.familydashboard.FamilyDashboardScreen(
                onBack     = { nav.popBackStack() },
                onCareTeam = { nav.navigate(Screen.CareTeam.route(patientId)) }
            )
        }

        composable(Screen.PatientList.route) {
            PatientListScreen(
                onPatientClick = { id -> nav.navigate(Screen.Dashboard.route(id)) },
                onAddPatient   = { nav.navigate(Screen.AddPatient.route) },
                onLogout       = { nav.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } } }
            )
        }

        composable(Screen.AddPatient.route) {
            AddPatientScreen(
                onBack        = { nav.popBackStack() },
                onPatientSaved = { nav.popBackStack() }
            )
        }

        composable(
            route = Screen.EditPatient.route,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) {
            EditPatientScreen(
                onBack  = { nav.popBackStack() },
                onSaved = { nav.popBackStack() }
            )
        }

        composable(
            route = Screen.Dashboard.route,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) { back ->
            val patientId = back.arguments?.getString("patientId") ?: return@composable
            DashboardScreen(
                patientId = patientId,
                onBack    = { nav.popBackStack() },
                onCareTeam = { nav.navigate(Screen.CareTeam.route(patientId)) },
                onEditPatient = { nav.navigate(Screen.EditPatient.route(patientId)) },
                onStationMode = { nav.navigate(Screen.StationMode.route(patientId)) }
            )
        }

        composable(
            route = Screen.CareTeam.route,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) {
            com.geriatricare.app.presentation.careteam.CareTeamScreen(
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = Screen.StationMode.route,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) {
            com.geriatricare.app.presentation.camera.StationModeScreen(
                onBack = { nav.popBackStack() }
            )
        }

        composable(Screen.DoctorDashboard.route) {
            com.geriatricare.app.presentation.doctordashboard.DoctorDashboardScreen(
                onBack = { nav.popBackStack() },
                onLogout = { nav.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } } },
                onPatientClick = { id -> nav.navigate(Screen.Dashboard.route(id)) }
            )
        }
    }
}
