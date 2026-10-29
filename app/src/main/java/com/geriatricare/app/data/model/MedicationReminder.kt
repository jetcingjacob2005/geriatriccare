package com.geriatricare.app.data.model

data class MedicationReminder(
    val id: String = "",
    val prescriptionId: String = "",
    val patientId: String = "",
    val medicationName: String = "",
    val dosage: String = "",
    val scheduledTime: String = "",
    val date: String = "",
    val status: ReminderStatus = ReminderStatus.PENDING,
    val mealTiming: String = "", // e.g. "Morning • After Breakfast (After Food)"
    val instructions: String = "" // e.g. "Take after meals with water"
)

enum class ReminderStatus { PENDING, TAKEN, SKIPPED }

/**
 * Resolves the display information for when to take this medication:
 * - timeSlot: e.g. "Morning", "Afternoon", "Night", "Bedtime"
 * - foodRelation: e.g. "After Breakfast (After Food)", "Before Lunch (Before Food)"
 * - icon: 🌅, ☀️, 🌙, 🛌
 */
fun MedicationReminder.resolveTimingInstruction(): Triple<String, String, String> {
    val time = scheduledTime.trim()
    val hour = time.split(":").firstOrNull()?.toIntOrNull() ?: 8

    val timeSlot = when {
        mealTiming.contains("morning", ignoreCase = true) -> "Morning"
        mealTiming.contains("noon", ignoreCase = true) || mealTiming.contains("lunch", ignoreCase = true) || mealTiming.contains("afternoon", ignoreCase = true) -> "Afternoon"
        mealTiming.contains("night", ignoreCase = true) || mealTiming.contains("dinner", ignoreCase = true) -> "Night"
        mealTiming.contains("bedtime", ignoreCase = true) -> "Bedtime"
        hour < 11 -> "Morning"
        hour in 11..15 -> "Afternoon"
        hour in 16..20 -> "Night"
        else -> "Bedtime"
    }

    val icon = when (timeSlot) {
        "Morning" -> "🌅"
        "Afternoon" -> "☀️"
        "Night" -> "🌙"
        else -> "🛌"
    }

    val foodRelation = when {
        mealTiming.contains("before breakfast", ignoreCase = true) -> "Before Breakfast (Before Food)"
        mealTiming.contains("after breakfast", ignoreCase = true) -> "After Breakfast (After Food)"
        mealTiming.contains("before lunch", ignoreCase = true) -> "Before Lunch (Before Food)"
        mealTiming.contains("after lunch", ignoreCase = true) -> "After Lunch (After Food)"
        mealTiming.contains("before dinner", ignoreCase = true) -> "Before Dinner (Before Food)"
        mealTiming.contains("after dinner", ignoreCase = true) -> "After Dinner (After Food)"
        mealTiming.contains("before", ignoreCase = true) || instructions.contains("before", ignoreCase = true) -> "Before Food"
        mealTiming.contains("after", ignoreCase = true) || instructions.contains("after", ignoreCase = true) -> "After Food"
        timeSlot == "Morning" -> "After Breakfast (After Food)"
        timeSlot == "Afternoon" -> "After Lunch (After Food)"
        timeSlot == "Night" -> "After Dinner (After Food)"
        else -> "Before Sleep"
    }

    return Triple(timeSlot, foodRelation, icon)
}
