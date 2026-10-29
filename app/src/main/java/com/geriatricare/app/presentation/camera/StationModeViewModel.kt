package com.geriatricare.app.presentation.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.ml.FallDetector
import com.geriatricare.app.data.ml.FallStatus
import com.geriatricare.app.data.alert.EmergencyAlertManager
import com.geriatricare.app.data.repository.PatientRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@HiltViewModel
class StationModeViewModel @Inject constructor(
    private val patientRepo: PatientRepository,
    private val alertManager: EmergencyAlertManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val patientId: String = savedStateHandle["patientId"] ?: ""
    private val fallDetector = FallDetector()

    private val options = PoseDetectorOptions.Builder()
        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
        .build()
    // Lazy init — on some devices (e.g. Galaxy A50) the ML Kit module may not
    // be downloaded yet when the screen opens.  Eager init in the constructor
    // would crash during Hilt ViewModel creation.
    private val poseDetector by lazy {
        try {
            PoseDetection.getClient(options)
        } catch (e: Exception) {
            null
        }
    }

    private var lastUploadTime = 0L
    private val UPLOAD_INTERVAL_MS = 3000L

    private val _fallStatus = MutableStateFlow(FallStatus.NORMAL)
    val fallStatus: StateFlow<FallStatus> = _fallStatus

    // Timestamp (epochMillis) when the current fall/unconscious event started, 0 when normal
    private val _fallTimestamp = MutableStateFlow(0L)
    val fallTimestamp: StateFlow<Long> = _fallTimestamp

    // True if person is struggling/moving while on the floor
    private val _isMovingOnFloor = MutableStateFlow(false)
    val isMovingOnFloor: StateFlow<Boolean> = _isMovingOnFloor

    // Normalized bounding box: FloatArray(left, top, right, bottom) or null if no person
    private val _personBoundingBox = MutableStateFlow<FloatArray?>(null)
    val personBoundingBox: StateFlow<FloatArray?> = _personBoundingBox

    fun resetFallAlert() {
        fallDetector.reset()
        _fallStatus.value = FallStatus.NORMAL
        _fallTimestamp.value = 0L
        _isMovingOnFloor.value = false
        updateFirestoreStatus(FallStatus.NORMAL)
    }

    override fun onCleared() {
        super.onCleared()
        try { poseDetector?.close() } catch (_: Exception) {}
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun processImageProxy(imageProxy: androidx.camera.core.ImageProxy) {
        val detector = poseDetector
        if (detector == null) { imageProxy.close(); return }

        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            val rawWidth = imageProxy.width
            val rawHeight = imageProxy.height

            // When rotation is 90 or 270 (portrait), ML Kit's landmark coordinate space
            // has swapped width and height relative to raw buffer dimensions.
            val poseFrameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
            val poseFrameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight

            // ── Capture snapshot bytes SYNCHRONOUSLY (before proxy is closed) ──
            val now = System.currentTimeMillis()
            val shouldUpload = now - lastUploadTime > UPLOAD_INTERVAL_MS
            val snapshotNv21: ByteArray? = if (shouldUpload) {
                try {
                    yuvToNv21(mediaImage, rawWidth, rawHeight)
                } catch (_: Exception) {
                    null
                }
            } else null

            detector.process(image)
                .addOnSuccessListener { pose ->
                    val result = fallDetector.processPose(pose, poseFrameWidth, poseFrameHeight)

                    _personBoundingBox.value = result.boundingBox
                    _isMovingOnFloor.value = result.isMovingOnFloor

                    if (result.status != FallStatus.NORMAL) {
                        if (_fallTimestamp.value == 0L) {
                            val elapsed = result.fallDurationMs
                            _fallTimestamp.value = if (elapsed > 0L) (System.currentTimeMillis() - elapsed) else System.currentTimeMillis()
                        }
                    } else {
                        _fallTimestamp.value = 0L
                    }

                    if (result.status != _fallStatus.value) {
                        _fallStatus.value = result.status
                        updateFirestoreStatus(result.status, result.fallDurationMs)
                    }

                    // Upload the pre-captured snapshot if it's time
                    if (snapshotNv21 != null) {
                        lastUploadTime = System.currentTimeMillis()
                        uploadSnapshot(snapshotNv21, rawWidth, rawHeight, rotationDegrees)
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } catch (e: Exception) {
            imageProxy.close()
        }
    }

    private fun updateFirestoreStatus(status: FallStatus, durationMs: Long = 0L) {
        viewModelScope.launch {
            patientRepo.getPatient(patientId)?.let { patient ->
                patientRepo.updatePatient(patient.copy(fallStatus = status.name))
                if (status != FallStatus.NORMAL) {
                    alertManager.triggerFallAlert(
                        patient = patient,
                        isUnconscious = (status == FallStatus.UNCONSCIOUS),
                        fallDurationMs = durationMs
                    )
                }
            }
        }
    }

    /**
     * Convert a YUV_420_888 [Image] to NV21 byte array.
     *
     * Properly handles interleaved U/V planes (pixelStride == 2) which is the
     * common layout on most Android devices including Android 11. The previous
     * implementation assumed contiguous planes and produced corrupted output or
     * buffer-overflow crashes on devices with interleaved chroma.
     */
    private fun yuvToNv21(image: Image, width: Int, height: Int): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = width * height
        val uvHeight = height / 2
        val uvWidth = width / 2
        val nv21 = ByteArray(ySize + width * uvHeight) // Y + interleaved VU

        // Copy Y plane (row by row to handle rowStride != width)
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // Copy UV planes into interleaved NV21 (VUVUVU...) format
        val uvPixelStride = vPlane.pixelStride
        val uvRowStride = vPlane.rowStride

        if (uvPixelStride == 2 && uvRowStride == width) {
            // Fast path: V and U planes are already interleaved in memory (VUVU...),
            // which is exactly NV21 chroma layout. Just copy from V plane.
            vBuffer.get(nv21, ySize, width * uvHeight - 1)
            // The last byte (U of last pixel) may not be covered by V plane
            nv21[ySize + width * uvHeight - 1] = uBuffer.get(uBuffer.limit() - 1)
        } else {
            // Slow path: copy pixel by pixel (handles any stride/interleave)
            var offset = ySize
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val vIndex = row * uvRowStride + col * uvPixelStride
                    val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                    nv21[offset++] = vBuffer.get(vIndex)
                    nv21[offset++] = uBuffer.get(uIndex)
                }
            }
        }

        return nv21
    }

    /**
     * Compress pre-captured NV21 bytes into JPEG, rotate, scale, and upload
     * as a Base64 snapshot to Firestore. Runs entirely on a coroutine — no
     * dependency on the camera Image/ImageProxy which may already be closed.
     */
    private fun uploadSnapshot(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        viewModelScope.launch {
            try {
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 40, out)
                val imageBytes = out.toByteArray()

                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val scaled = Bitmap.createScaledBitmap(bitmap, width / 2, height / 2, true)
                val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)

                val outR = ByteArrayOutputStream()
                rotated.compress(Bitmap.CompressFormat.JPEG, 20, outR)
                val base64 = Base64.encodeToString(outR.toByteArray(), Base64.NO_WRAP)

                // Recycle bitmaps to free memory on older/lower-RAM devices
                bitmap.recycle()
                scaled.recycle()
                rotated.recycle()

                patientRepo.getPatient(patientId)?.let {
                    patientRepo.updatePatient(it.copy(latestCameraFrame = base64))
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }
}
