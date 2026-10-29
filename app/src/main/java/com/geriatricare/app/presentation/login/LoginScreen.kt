package com.geriatricare.app.presentation.login

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.data.auth.UserRoleManager
import com.geriatricare.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (LoginDestination) -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var email     by remember { mutableStateOf("") }
    var password  by remember { mutableStateOf("") }
    var showPass  by remember { mutableStateOf(false) }
    var isRegMode by remember { mutableStateOf(false) }
    var visible   by remember { mutableStateOf(false) }

    val isDoctor = state.selectedRole == UserRoleManager.ROLE_DOCTOR
    val isFamily = state.selectedRole == UserRoleManager.ROLE_FAMILY

    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(state.isSuccess) { if (state.isSuccess) onLoginSuccess(state.destination) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Navy900, Navy800, Navy700)))
    ) {
        // Decorative glow blobs
        Box(
            modifier = Modifier
                .size(320.dp)
                .offset(x = (-100).dp, y = (-100).dp)
                .background(
                    Brush.radialGradient(listOf((if (isDoctor) MedicalBlueLt else CyanAccent).copy(alpha = 0.07f), Navy900.copy(alpha = 0f))),
                    RoundedCornerShape(50)
                )
        )
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 80.dp, y = 80.dp)
                .background(
                    Brush.radialGradient(listOf(SpO2Purple.copy(alpha = 0.07f), Navy900.copy(alpha = 0f))),
                    RoundedCornerShape(50)
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── Logo ──────────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(700)) + slideInVertically(tween(700)) { -40 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(76.dp),
                        shape    = RoundedCornerShape(22.dp),
                        color    = Navy600
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isDoctor) Icons.Default.AddCircle else Icons.Default.Favorite,
                                null,
                                tint = CyanAccent,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (isDoctor) "Chill Cheyyu Hospital" else "JeevaMitra",
                        style      = MaterialTheme.typography.headlineLarge,
                        color      = White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isDoctor) "Hospital Clinical Portal" else "Wear Your Wellness.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Gray500
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Role Selector ────────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Navy800.copy(alpha = 0.7f),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(4.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val roles = listOf(
                        Triple(UserRoleManager.ROLE_CAREGIVER, "Caretaker", Icons.Default.Person),
                        Triple(UserRoleManager.ROLE_DOCTOR, "Doctor", Icons.Default.AddCircle),
                        Triple(UserRoleManager.ROLE_FAMILY, "Family", Icons.Default.Home)
                    )

                    roles.forEach { (roleKey, label, icon) ->
                        val isSelected = state.selectedRole == roleKey
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) CyanAccent else Color.Transparent,
                            onClick = { viewModel.setRole(roleKey) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    icon,
                                    null,
                                    tint = if (isSelected) Navy900 else Gray300,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) Navy900 else Gray300,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Login Card ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(700, 200)) + slideInVertically(tween(700, 200)) { 60 }
            ) {
                Surface(
                    modifier       = Modifier.fillMaxWidth(),
                    shape          = RoundedCornerShape(24.dp),
                    color          = CardBackground,
                    tonalElevation = 4.dp
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            when {
                                isDoctor -> if (isRegMode) "Doctor Registration" else "Doctor Portal Login"
                                isFamily -> if (isRegMode) "Family Registration" else "Family Portal Login"
                                else     -> if (isRegMode) "Caretaker Registration" else "Welcome Back"
                            },
                            style      = MaterialTheme.typography.titleLarge,
                            color      = White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when {
                                isDoctor -> "Dr. Jetcin — Chill Cheyyu Hospital"
                                isFamily -> "Sign in for remote family telemetry"
                                else     -> "Sign in to manage and monitor patients"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDoctor) CyanAccent else Gray500
                        )
                        Spacer(Modifier.height(24.dp))

                        // Email
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; viewModel.clearError() },
                            label = { Text("Email Address") },
                            leadingIcon = { Icon(Icons.Default.Email, null, tint = CyanAccent) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            singleLine = true,
                            modifier   = Modifier.fillMaxWidth(),
                            colors     = textFieldColors(),
                            shape      = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(14.dp))

                        // Password
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; viewModel.clearError() },
                            label = { Text("Password") },
                            leadingIcon  = { Icon(Icons.Default.Lock, null, tint = CyanAccent) },
                            trailingIcon = {
                                IconButton(onClick = { showPass = !showPass }) {
                                    Icon(
                                        if (showPass) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        null, tint = Gray500
                                    )
                                }
                            },
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            singleLine = true,
                            modifier   = Modifier.fillMaxWidth(),
                            colors     = textFieldColors(),
                            shape      = RoundedCornerShape(12.dp)
                        )

                        // Error banner
                        AnimatedVisibility(visible = state.error.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = AlertRed.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = AlertRed, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(state.error, color = AlertRed, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Primary Button
                        Button(
                            onClick = {
                                if (isRegMode) viewModel.register(email, password)
                                else           viewModel.login(email, password)
                            },
                            modifier  = Modifier.fillMaxWidth().height(52.dp),
                            shape     = RoundedCornerShape(12.dp),
                            colors    = ButtonDefaults.buttonColors(
                                containerColor = if (isDoctor) CyanAccent else CyanAccent,
                                contentColor   = Navy900
                            ),
                            enabled = !state.isLoading
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(Modifier.size(20.dp), color = Navy900, strokeWidth = 2.dp)
                            } else {
                                Text(
                                    when {
                                        isDoctor -> if (isRegMode) "Register as Doctor" else "Doctor Sign In"
                                        isFamily -> if (isRegMode) "Register as Family" else "Family Sign In"
                                        else     -> if (isRegMode) "Create Account" else "Sign In"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    style      = MaterialTheme.typography.titleMedium
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Toggle register / login
                        TextButton(
                            onClick  = { isRegMode = !isRegMode; viewModel.clearError() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isRegMode) "Already have an account? Sign In"
                                else           "New here? Create an account",
                                color  = CyanAccent,
                                style  = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = CyanAccent,
    unfocusedBorderColor = CardBorder,
    focusedLabelColor    = CyanAccent,
    unfocusedLabelColor  = Gray500,
    focusedTextColor     = White,
    unfocusedTextColor   = White,
    cursorColor          = CyanAccent,
    focusedLeadingIconColor   = CyanAccent,
    unfocusedLeadingIconColor = Gray500
)
