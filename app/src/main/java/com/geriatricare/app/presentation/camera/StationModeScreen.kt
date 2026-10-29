package com.geriatricare.app.presentation.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.data.ml.FallStatus
import com.geriatricare.app.ui.theme.*
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationModeScreen(
    onBack: () -> Unit,
    viewModel: StationModeViewModel = hiltViewModel()
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fallStatus      by viewModel.fallStatus.collectAsState()
    val fallTimestamp   by viewModel.fallTimestamp.collectAsState()
    val isMovingOnFloor by viewModel.isMovingOnFloor.collectAsState()
    val boundingBox     by viewModel.personBoundingBox.collectAsState()

    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(fallStatus, fallTimestamp) {
        if (fallStatus != FallStatus.NORMAL && fallTimestamp > 0L) {
            while (true) {
                elapsedSeconds = ((System.currentTimeMillis() - fallTimestamp) / 1000L).coerceAtLeast(0L)
                delay(500L)
            }
        } else {
            elapsedSeconds = 0L
        }
    }

    var hasCameraPermission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    ) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasCameraPermission = isGranted }

    // Re-check permission every time the screen resumes (e.g. user returns
    // from Settings after manually granting the permission).  Without this,
    // the Samsung One UI flow — which redirects to Settings instead of
    // showing the runtime dialog — would leave the screen stuck on the
    // "permission required" UI even after the user grants access.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }

        // Keep screen on and dim slightly
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = activity?.window?.attributes
        params?.screenBrightness = 0.15f
        activity?.window?.attributes = params
    }

    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = activity?.window?.attributes
            params?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity?.window?.attributes = params
        }
    }

    // Pulsing animation for fall alert
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = White)
                    }
                },
                title = { Text("Station Monitor — 24/7", color = White, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f))
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (!hasCameraPermission) {
                // Permission not granted UI
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = AlertYellow, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Camera permission required", color = White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap the button below to open Settings, then enable Camera permission for JeevaMitra.",
                        color = Gray500, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 32.dp))
                    Spacer(Modifier.height(16.dp))
                    // Open the app's own Settings page so the user can toggle
                    // the Camera permission manually.  On Samsung One UI
                    // (Galaxy A50 etc.) the runtime permission dialog won't
                    // appear after the first denial, so this is the only
                    // reliable path.
                    Button(onClick = {
                        try {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // Fallback: try the runtime dialog anyway
                            launcher.launch(Manifest.permission.CAMERA)
                        }
                    }) {
                        Text("Open Settings")
                    }
                }
                return@Scaffold
            }

            // ── Camera Preview ──────────────────────────────────────────────
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    val analysisExecutor = Executors.newSingleThreadExecutor()

                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            // Target 640×480 for analysis — the Galaxy A50 (Exynos 9610)
                            // and similar mid-range Samsung SoCs cannot negotiate a shared
                            // camera session with both Preview and ImageAnalysis at the
                            // sensor's native resolution (25 MP). Pose detection only
                            // needs a low-res feed anyway.
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setTargetResolution(android.util.Size(640, 480))
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .build().also {
                                    it.setAnalyzer(analysisExecutor) { proxy ->
                                        viewModel.processImageProxy(proxy)
                                    }
                                }
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, imageAnalysis
                            )
                        } catch (e: Exception) {
                            Log.e("StationMode", "Camera init/bind failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── Bounding Box Canvas Overlay ─────────────────────────────────
            Canvas(modifier = Modifier.fillMaxSize()) {
                val box = boundingBox
                if (box != null && box.size == 4) {
                    val left   = box[0] * size.width
                    val top    = box[1] * size.height
                    val right  = box[2] * size.width
                    val bottom = box[3] * size.height

                    val boxColor = when (fallStatus) {
                        FallStatus.NORMAL         -> Color(0xFF25D366) // Green
                        FallStatus.FALL_DETECTED  -> Color.Red
                        FallStatus.UNCONSCIOUS    -> Color.Red
                    }

                    // Draw bounding box
                    drawRect(
                        color     = boxColor,
                        topLeft   = Offset(left, top),
                        size      = Size(right - left, bottom - top),
                        style     = Stroke(width = 4.dp.toPx())
                    )

                    // Draw corner accents (like a real detector UI)
                    val cornerLen = 20.dp.toPx()
                    val strokeW   = 4.dp.toPx()

                    // Top-left corner
                    drawLine(boxColor, Offset(left, top), Offset(left + cornerLen, top), strokeW)
                    drawLine(boxColor, Offset(left, top), Offset(left, top + cornerLen), strokeW)
                    // Top-right
                    drawLine(boxColor, Offset(right, top), Offset(right - cornerLen, top), strokeW)
                    drawLine(boxColor, Offset(right, top), Offset(right, top + cornerLen), strokeW)
                    // Bottom-left
                    drawLine(boxColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW)
                    drawLine(boxColor, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW)
                    // Bottom-right
                    drawLine(boxColor, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW)
                    drawLine(boxColor, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW)
                }
            }

            // ── Human Detected Label ────────────────────────────────────────
            if (boundingBox != null) {
                val labelColor = when (fallStatus) {
                    FallStatus.NORMAL        -> Color(0xFF25D366)
                    else                     -> Color.Red
                }
                val labelText = when (fallStatus) {
                    FallStatus.NORMAL        -> "Human Detected"
                    FallStatus.FALL_DETECTED -> "Human Detected"
                    FallStatus.UNCONSCIOUS   -> "Human Detected"
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 12.dp)
                        .background(labelColor.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, null, tint = White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(labelText, color = White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            // ── Fall / Unconscious Alert Banner ─────────────────────────────
            AnimatedVisibility(
                visible = fallStatus != FallStatus.NORMAL,
                enter   = fadeIn(),
                exit    = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val alertTitle = when (fallStatus) {
                    FallStatus.FALL_DETECTED -> "⚠ FALL DETECTED!"
                    FallStatus.UNCONSCIOUS   -> "🚨 MOTIONLESS AFTER FALL — CHECK NOW"
                    else                     -> ""
                }
                val alertSubtitle = when {
                    fallStatus == FallStatus.UNCONSCIOUS -> "Person motionless after a confirmed fall for 30+ sec"
                    isMovingOnFloor                      -> "Person down • Movement detected on floor"
                    else                                 -> "Person down on floor"
                }
                val timeFormatted = if (elapsedSeconds < 60) {
                    "${elapsedSeconds}s"
                } else {
                    "${elapsedSeconds / 60}m ${elapsedSeconds % 60}s"
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    color  = AlertRed.copy(alpha = pulseAlpha),
                    shape  = RoundedCornerShape(16.dp),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = White, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text       = alertTitle,
                                    color      = White,
                                    fontWeight = FontWeight.ExtraBold,
                                    style      = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text  = alertSubtitle,
                                    color = White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Live Elapsed Timer Badge
                            Surface(
                                color = Color.Black.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text       = "Time down: $timeFormatted",
                                        color      = White,
                                        fontWeight = FontWeight.Bold,
                                        style      = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            // Dismiss / Reset button
                            Button(
                                onClick = { viewModel.resetFallAlert() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = White.copy(alpha = 0.25f),
                                    contentColor = White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "I'm Okay / Reset",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            // ── Streaming Status Pill ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF25D366), RoundedCornerShape(50))
                )
                Spacer(Modifier.width(6.dp))
                Text("Streaming to Family Dashboard", color = White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
