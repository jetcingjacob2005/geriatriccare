package com.geriatricare.app.data.model

/**
 * A nearby doctor shown in the "Book Appointment" doctor picker.
 * This is a curated local directory (Trivandrum) bundled with the app —
 * there is no live doctor-directory backend in this project.
 * `avatarUrl` points to a generated illustrated avatar (DiceBear), not a
 * real photo of a real person.
 */
data class Doctor(
    val id: String,
    val name: String,
    val specialty: String,
    val hospital: String,
    val locality: String,          // area within Thiruvananthapuram
    val phone: String,
    val experienceYears: Int,
    val rating: Float,             // out of 5
    val availability: String,      // e.g. "Mon–Sat, 9:00 AM–1:00 PM"
    val slots: List<String> = emptyList() // today's bookable time slots
) {
    /** Generated illustrated avatar (not a real photo of a real person) — stable per doctor id. */
    val avatarUrl: String
        get() = "https://api.dicebear.com/7.x/avataaars/png?seed=$id&backgroundType=gradientLinear&size=200"
}
