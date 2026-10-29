package com.geriatricare.app.presentation.navigation

sealed class Screen(val route: String) {
    object Splash      : Screen("splash")
    object Login       : Screen("login")
    object PatientList : Screen("patient_list")
    object AddPatient  : Screen("add_patient")
    object EditPatient : Screen("edit_patient/{patientId}") {
        fun route(id: String) = "edit_patient/$id"
    }

    object Dashboard : Screen("dashboard/{patientId}") {
        fun route(id: String) = "dashboard/$id"
    }
    object History : Screen("history/{patientId}") {
        fun route(id: String) = "history/$id"
    }
    object CareTeam : Screen("care_team/{patientId}") {
        fun route(id: String) = "care_team/$id"
    }
    object FamilyHome : Screen("family_home")
    object FamilyDashboard : Screen("family_dashboard/{patientId}") {
        fun route(id: String) = "family_dashboard/$id"
    }
    object StationMode : Screen("station_mode/{patientId}") {
        fun route(id: String) = "station_mode/$id"
    }
    object DoctorDashboard : Screen("doctor_dashboard")
}
