package com.geriatricare.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    data class Success(val user: FirebaseUser) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) {
    val currentUser: FirebaseUser? get() = firebaseAuth.currentUser
    val isLoggedIn : Boolean       get() = firebaseAuth.currentUser != null
    val currentUid : String        get() = firebaseAuth.currentUser?.uid ?: ""

    suspend fun login(email: String, password: String): AuthResult {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email.trim(), password).await()
            val user   = result.user ?: return AuthResult.Error("Login failed. Please try again.")
            AuthResult.Success(user)
        } catch (e: Exception) {
            AuthResult.Error(friendlyMessage(e.message))
        }
    }

    suspend fun register(email: String, password: String): AuthResult {
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user   = result.user ?: return AuthResult.Error("Registration failed.")
            AuthResult.Success(user)
        } catch (e: Exception) {
            AuthResult.Error(friendlyMessage(e.message))
        }
    }

    fun logout() = firebaseAuth.signOut()

    private fun friendlyMessage(raw: String?): String = when {
        raw == null -> "An unknown error occurred."
        "password" in raw.lowercase() -> "Incorrect password. Please try again."
        "no user" in raw.lowercase() || "email" in raw.lowercase() -> "No account found with this email."
        "network" in raw.lowercase() -> "Network error. Check your connection."
        else -> raw
    }
}
