package com.geriatricare.app.data.doctor

import com.geriatricare.app.data.model.Doctor

/**
 * Curated, bundled-with-the-app directory of doctors near Thiruvananthapuram,
 * shown in Care Team → Book Appointment so a caregiver can pick a doctor
 * without leaving the app.
 *
 * NOTE: This is local sample data (not a live/verified directory feed).
 * Hospital names reflect well-known Thiruvananthapuram hospitals for realism;
 * doctor names, phone numbers and ratings are placeholders and should be
 * replaced with a verified directory (or real assigned-doctor records)
 * before any production use.
 */
object DoctorDirectory {

    val specialties = listOf(
        "All", "Geriatrician", "Cardiologist", "General Physician",
        "Neurologist", "Endocrinologist", "Pulmonologist", "Orthopedician", "Nephrologist"
    )

    val doctors: List<Doctor> = listOf(
        Doctor(
            id = "doc_jetcin",
            name = "Dr. Jetcin",
            specialty = "Geriatrician",
            hospital = "Chill Cheyyu Hospital",
            locality = "Medical College, Trivandrum",
            phone = "+919400199999",
            experienceYears = 16,
            rating = 4.9f,
            availability = "Mon–Sat, 9:00 AM – 5:00 PM",
            slots = listOf("9:00 AM", "10:00 AM", "11:30 AM", "2:00 PM", "3:30 PM", "4:30 PM")
        ),
        Doctor(
            id = "doc_01",
            name = "Dr. Anil Kumar",
            specialty = "Geriatrician",
            hospital = "KIMS Health, Trivandrum",
            locality = "Anayara",
            phone = "+919400100001",
            experienceYears = 18,
            rating = 4.8f,
            availability = "Mon–Sat, 9:00 AM – 1:00 PM",
            slots = listOf("9:00 AM", "9:30 AM", "10:00 AM", "11:30 AM")
        ),
        Doctor(
            id = "doc_02",
            name = "Dr. Meera Pillai",
            specialty = "Cardiologist",
            hospital = "Ananthapuri Hospitals & Research Institute",
            locality = "Chackai",
            phone = "+919400100002",
            experienceYears = 15,
            rating = 4.7f,
            availability = "Mon–Fri, 10:00 AM – 4:00 PM",
            slots = listOf("10:00 AM", "11:00 AM", "2:00 PM", "3:30 PM")
        ),
        Doctor(
            id = "doc_03",
            name = "Dr. Suresh Nair",
            specialty = "General Physician",
            hospital = "SUT Hospital, Pattom",
            locality = "Pattom",
            phone = "+919400100003",
            experienceYears = 22,
            rating = 4.6f,
            availability = "Mon–Sat, 8:30 AM – 12:30 PM",
            slots = listOf("8:30 AM", "9:00 AM", "9:30 AM", "12:00 PM")
        ),
        Doctor(
            id = "doc_04",
            name = "Dr. Lakshmi Menon",
            specialty = "Neurologist",
            hospital = "Cosmopolitan Hospital",
            locality = "Murinjapalam",
            phone = "+919400100004",
            experienceYears = 12,
            rating = 4.5f,
            availability = "Tue, Thu, Sat — 11:00 AM – 2:00 PM",
            slots = listOf("11:00 AM", "12:30 PM", "1:30 PM")
        ),
        Doctor(
            id = "doc_05",
            name = "Dr. Rajesh Varma",
            specialty = "Endocrinologist",
            hospital = "Sree Gokulam Medical College",
            locality = "Venjaramoodu",
            phone = "+919400100005",
            experienceYears = 14,
            rating = 4.6f,
            availability = "Mon–Fri, 9:00 AM – 1:00 PM",
            slots = listOf("9:00 AM", "10:30 AM", "12:00 PM")
        ),
        Doctor(
            id = "doc_06",
            name = "Dr. Priya Raghavan",
            specialty = "Pulmonologist",
            hospital = "PRS Hospital",
            locality = "Killipalam",
            phone = "+919400100006",
            experienceYears = 10,
            rating = 4.4f,
            availability = "Mon, Wed, Fri — 4:00 PM – 7:00 PM",
            slots = listOf("4:00 PM", "5:00 PM", "6:00 PM", "6:30 PM")
        ),
        Doctor(
            id = "doc_07",
            name = "Dr. Thomas Abraham",
            specialty = "Orthopedician",
            hospital = "Believers Church Medical College",
            locality = "Thiruvalla Rd, Kumarapuram",
            phone = "+919400100007",
            experienceYears = 20,
            rating = 4.7f,
            availability = "Mon–Sat, 9:00 AM – 12:00 PM",
            slots = listOf("9:00 AM", "9:30 AM", "11:00 AM")
        ),
        Doctor(
            id = "doc_08",
            name = "Dr. Divya Krishnan",
            specialty = "Nephrologist",
            hospital = "KIMS Health, Trivandrum",
            locality = "Anayara",
            phone = "+919400100008",
            experienceYears = 11,
            rating = 4.5f,
            availability = "Tue–Sat, 10:00 AM – 1:00 PM",
            slots = listOf("10:00 AM", "11:00 AM", "12:30 PM")
        ),
        Doctor(
            id = "doc_09",
            name = "Dr. Vinod Chandran",
            specialty = "Geriatrician",
            hospital = "Regional Cancer Centre Annex — General Medicine Wing",
            locality = "Medical College",
            phone = "+919400100009",
            experienceYears = 25,
            rating = 4.9f,
            availability = "Mon–Fri, 2:00 PM – 5:00 PM",
            slots = listOf("2:00 PM", "2:30 PM", "3:00 PM", "4:00 PM")
        ),
        Doctor(
            id = "doc_10",
            name = "Dr. Anjali Warrier",
            specialty = "Cardiologist",
            hospital = "SUT Hospital, Pattom",
            locality = "Pattom",
            phone = "+919400100010",
            experienceYears = 9,
            rating = 4.4f,
            availability = "Mon–Sat, 5:00 PM – 8:00 PM",
            slots = listOf("5:00 PM", "5:30 PM", "7:00 PM")
        )
    )

    fun bySpecialty(specialty: String): List<Doctor> =
        if (specialty == "All") doctors else doctors.filter { it.specialty == specialty }
}
