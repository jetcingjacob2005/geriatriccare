package com.geriatricare.app.data.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRoleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("user_role_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ROLE_DOCTOR = "DOCTOR"
        const val ROLE_CAREGIVER = "CAREGIVER"
        const val ROLE_FAMILY = "FAMILY"
        private const val KEY_ROLE = "saved_user_role"
    }

    fun saveRole(role: String) {
        prefs.edit().putString(KEY_ROLE, role).apply()
    }

    fun getRole(): String? {
        return prefs.getString(KEY_ROLE, null)
    }

    fun clear() {
        prefs.edit().remove(KEY_ROLE).apply()
    }
}
