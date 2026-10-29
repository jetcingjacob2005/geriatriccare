package com.geriatricare.app.presentation.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onLogin: () -> Unit,
    onPatientList: () -> Unit,
    onFamilyHome: () -> Unit = {},
    onDoctorDashboard: () -> Unit = {},
    viewModel: SplashViewModel = hiltViewModel()
) {
    val destination by viewModel.destination.collectAsState()

    // Pulse animation for the heart icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(800),
        label = "alpha"
    )

    LaunchedEffect(destination) {
        if (destination != null) {
            delay(2200)
            when (destination) {
                SplashDestination.CAREGIVER -> onPatientList()
                SplashDestination.FAMILY    -> onFamilyHome()
                SplashDestination.DOCTOR    -> onDoctorDashboard()
                else                        -> onLogin()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Navy900, Navy800, Navy700))),
        contentAlignment = Alignment.Center
    ) {
        // Glow rings behind icon
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(CyanAccent.copy(alpha = 0.05f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(130.dp)
                .background(CyanAccent.copy(alpha = 0.08f), CircleShape)
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Heart icon with pulse animation
            Surface(
                modifier  = Modifier.size(88.dp).scale(pulse),
                shape     = CircleShape,
                color     = Navy600,
                tonalElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector    = Icons.Default.Favorite,
                        contentDescription = "JeevaMitra",
                        tint           = CyanAccent,
                        modifier       = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text  = "JeevaMitra",
                style = MaterialTheme.typography.displayLarge,
                color = White,
                fontWeight = FontWeight.ExtraBold,
                modifier   = Modifier.alpha(alpha)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text  = "Wear Your Wellness.",
                style = MaterialTheme.typography.bodyLarge,
                color = CyanAccent,
                modifier = Modifier.alpha(alpha)
            )

            Spacer(Modifier.height(64.dp))

            CircularProgressIndicator(
                color       = CyanAccent,
                strokeWidth = 2.dp,
                modifier    = Modifier.size(28.dp).alpha(alpha)
            )
        }
    }
}
