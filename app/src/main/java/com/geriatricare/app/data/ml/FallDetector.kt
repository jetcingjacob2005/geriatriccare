package com.geriatricare.app.data.ml

import android.util.Log
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class FallStatus {
    NORMAL,
    FALL_DETECTED,
    UNCONSCIOUS
}

/**
 * Result from a single pose analysis frame.
 * @param status The detected fall state.
 * @param boundingBox Normalized bounding box (0f..1f) [left, top, right, bottom], or null if no human detected.
 * @param isMovingOnFloor True if the person is detected moving/struggling while down on the floor.
 * @param fallDurationMs Milliseconds elapsed since the fall started (0 if normal).
 */
data class PoseAnalysisResult(
    val status: FallStatus,
    val boundingBox: FloatArray? = null,
    val isMovingOnFloor: Boolean = false,
    val fallDurationMs: Long = 0L
)

/**
 * Camera-only screening aid. [floorZoneStartY] is the lower-edge normalized
 * boundary of the real floor in this camera view. It should be calibrated per
 * room; the conservative default prevents a normal bed-height posture from
 * being treated as a floor event in typical wall-mounted camera views.
 */
class FallDetector(private val floorZoneStartY: Float = 0.68f) {

    companion object {
        private const val TAG = "FallDetector"

        // Dynamic fall thresholds (normalized 0..1 coordinates)
        private const val MIN_DROP_DISTANCE = 0.18f          // Must drop at least 18% of frame height
        private const val MIN_DROP_VELOCITY = 0.30f          // Downward velocity of at least 30% frame height / sec
        private const val MOVEMENT_THRESHOLD = 0.020f        // Inter-frame landmark displacement for movement
        private const val MIN_TORSO_CONFIDENCE = 0.50f       // Ignore partial / uncertain body poses
        // A camera pose is not able to determine consciousness.  Thirty seconds is
        // deliberately only a *second escalation* after a confirmed fall, never a
        // diagnosis of unconsciousness.  The care team must make the assessment.
        private const val UNCONSCIOUS_DELAY_MS = 30_000L
        private const val STANDING_RECOVERY_MS = 1000L       // 1.0 second continuous upright -> Recover to Normal
        private const val FALL_CONFIRMATION_MS = 1200L       // Confirm the collapsed posture after a rapid descent
        private const val FALL_CANDIDATE_TIMEOUT_MS = 3500L  // Allow descent and floor posture to occur across frames
        private const val FLOOR_POSE_GRACE_MS = 600L         // Tolerate a brief uncertain/missed pose frame
    }

    // State
    private var currentStatus = FallStatus.NORMAL
    private var fallStartTime: Long = 0L
    private var lastMotionTime: Long = 0L
    private var uprightStartTime: Long = 0L
    private var rapidDropTime: Long = 0L
    private var lastFloorPoseTime: Long = 0L
    private var isMovingOnFloor: Boolean = false

    // History of normalized body Y positions: Pair<TimestampMs, CenterY>
    private val poseHistory = ArrayDeque<Pair<Long, Float>>(30)

    // Previous key landmarks for inter-frame movement estimation: Map<LandmarkType, Pair<X, Y>>
    private var previousKeyPoints = mutableMapOf<Int, Pair<Float, Float>>()

    fun reset() {
        currentStatus = FallStatus.NORMAL
        fallStartTime = 0L
        lastMotionTime = 0L
        uprightStartTime = 0L
        rapidDropTime = 0L
        lastFloorPoseTime = 0L
        isMovingOnFloor = false
        poseHistory.clear()
        previousKeyPoints.clear()
    }

    fun processPose(pose: Pose, frameWidth: Int, frameHeight: Int): PoseAnalysisResult {
        val landmarks = pose.allPoseLandmarks
        val currentTime = System.currentTimeMillis()

        if (landmarks.isEmpty() || frameWidth <= 0 || frameHeight <= 0) {
            val elapsed = if (fallStartTime > 0L) currentTime - fallStartTime else 0L
            return PoseAnalysisResult(
                status = currentStatus,
                boundingBox = null,
                isMovingOnFloor = false,
                fallDurationMs = elapsed
            )
        }

        // ── 1. Calculate Bounding Box (normalized 0..1) ──
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        var visibleCount = 0

        for (lm in landmarks) {
            if (lm.inFrameLikelihood > 0.25f) {
                visibleCount++
                val normX = (lm.position.x / frameWidth).coerceIn(0f, 1f)
                val normY = (lm.position.y / frameHeight).coerceIn(0f, 1f)
                if (normX < minX) minX = normX
                if (normY < minY) minY = normY
                if (normX > maxX) maxX = normX
                if (normY > maxY) maxY = normY
            }
        }

        val boundingBox = if (visibleCount >= 3 && minX < maxX && minY < maxY) {
            floatArrayOf(minX, minY, maxX, maxY)
        } else {
            null
        }

        // ── 2. Extract Key Anatomical Landmarks ──
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)?.takeIf { it.inFrameLikelihood > 0.25f }
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)?.takeIf { it.inFrameLikelihood >= MIN_TORSO_CONFIDENCE }
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)?.takeIf { it.inFrameLikelihood >= MIN_TORSO_CONFIDENCE }
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)?.takeIf { it.inFrameLikelihood >= MIN_TORSO_CONFIDENCE }
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)?.takeIf { it.inFrameLikelihood >= MIN_TORSO_CONFIDENCE }
        val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)?.takeIf { it.inFrameLikelihood > 0.20f }
        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)?.takeIf { it.inFrameLikelihood > 0.20f }

        val noseY = nose?.let { it.position.y / frameHeight }
        val noseX = nose?.let { it.position.x / frameWidth }

        val shoulderY: Float? = when {
            leftShoulder != null && rightShoulder != null -> ((leftShoulder.position.y + rightShoulder.position.y) / 2f) / frameHeight
            leftShoulder != null -> leftShoulder.position.y / frameHeight
            rightShoulder != null -> rightShoulder.position.y / frameHeight
            noseY != null -> noseY + 0.10f
            else -> null
        }
        val shoulderX: Float? = when {
            leftShoulder != null && rightShoulder != null -> ((leftShoulder.position.x + rightShoulder.position.x) / 2f) / frameWidth
            leftShoulder != null -> leftShoulder.position.x / frameWidth
            rightShoulder != null -> rightShoulder.position.x / frameWidth
            noseX != null -> noseX
            else -> null
        }

        val hipY: Float? = when {
            leftHip != null && rightHip != null -> ((leftHip.position.y + rightHip.position.y) / 2f) / frameHeight
            leftHip != null -> leftHip.position.y / frameHeight
            rightHip != null -> rightHip.position.y / frameHeight
            else -> null
        }
        val hipX: Float? = when {
            leftHip != null && rightHip != null -> ((leftHip.position.x + rightHip.position.x) / 2f) / frameWidth
            leftHip != null -> leftHip.position.x / frameWidth
            rightHip != null -> rightHip.position.x / frameWidth
            else -> null
        }

        // Body vertical reference position (track upper torso/head which drops dramatically during a fall)
        val bodyY: Float = shoulderY ?: noseY ?: (if (boundingBox != null) boundingBox[1] else 0.5f)

        // ── 3. Posture Evaluation: Is the person upright or on the floor? ──
        val boxWidth = if (boundingBox != null) (boundingBox[2] - boundingBox[0]) else 0.2f
        val boxHeight = if (boundingBox != null) (boundingBox[3] - boundingBox[1]) else 0.4f
        val aspectRatio = boxWidth / max(0.01f, boxHeight)

        val torsoDx = if (shoulderX != null && hipX != null) abs(shoulderX - hipX) else 0f
        val torsoDy = if (shoulderY != null && hipY != null) abs(shoulderY - hipY) else 0.25f

        // A person is CLEARLY UPRIGHT if:
        // 1. Shoulders are distinctly above hips (vertical distance > horizontal tilt)
        // 2. Head is above shoulders
        // 3. Or full body bounding box is tall (height > width * 1.3)
        val hasVerticalTorso = (shoulderY != null && hipY != null && (hipY - shoulderY) > 0.06f && torsoDy > torsoDx * 1.0f)
        val hasHeadAboveShoulders = (noseY != null && shoulderY != null && noseY < shoulderY)
        val hasHeadClearlyAboveShoulders = (noseY != null && shoulderY != null && noseY < shoulderY - 0.03f && shoulderY < 0.65f)
        val hasTallSilhouette = (boxHeight > boxWidth * 1.2f)

        val isClearlyUpright = hasVerticalTorso || hasHeadClearlyAboveShoulders || (hasTallSilhouette && hasHeadAboveShoulders)

        // A person is ON THE FLOOR / FALLEN if:
        // 1. Torso is genuinely horizontal (tilt Dx > Dy OR shoulders and hips at same level)
        // 2. AND the body/shoulders are low in the frame (near the floor)
        // 3. AND the person is NOT upright
        val isTorsoHorizontal = (shoulderX != null && hipX != null && (torsoDx > torsoDy * 1.2f || torsoDy < 0.07f))
        val isBodyLow = (shoulderY != null && shoulderY > 0.54f) || (noseY != null && noseY > 0.56f)
        val hasReliableTorso = shoulderY != null && hipY != null
        val hasReliableUpperBody = shoulderY != null && noseY != null
        val isHorizontalFloorPosture = hasReliableTorso && !isClearlyUpright && isTorsoHorizontal && isBodyLow

        // Wide collapsed posture on floor (entire body horizontal on floor)
        val isFlatOnFloor = (hasReliableTorso || hasReliableUpperBody) && !isClearlyUpright &&
            aspectRatio > 1.25f && (boundingBox != null && boundingBox[3] > 0.64f)
        val isInConfiguredFloorZone = boundingBox != null && boundingBox[3] >= floorZoneStartY.coerceIn(0.5f, 0.95f)
        val isDownOnFloor = isInConfiguredFloorZone && (isHorizontalFloorPosture || isFlatOnFloor)

        // ── 4. Calculate movement after a confirmed floor fall ──
        val currentPoints = mutableMapOf<Int, Pair<Float, Float>>()
        listOfNotNull(leftShoulder, rightShoulder, leftHip, rightHip, nose).forEach { lm ->
            currentPoints[lm.landmarkType] = Pair(lm.position.x / frameWidth, lm.position.y / frameHeight)
        }

        var totalDisplacement = 0f
        var comparedPoints = 0
        currentPoints.forEach { (type, pt) ->
            previousKeyPoints[type]?.let { prev ->
                val dx = pt.first - prev.first
                val dy = pt.second - prev.second
                totalDisplacement += sqrt(dx * dx + dy * dy)
                comparedPoints++
            }
        }
        previousKeyPoints = currentPoints

        val avgMovement = if (comparedPoints > 0) totalDisplacement / comparedPoints else 0f
        val hasSignificantMotion = avgMovement > MOVEMENT_THRESHOLD

        // ── 5. Track Drop Dynamics (Sudden Fall) ──
        poseHistory.addLast(currentTime to bodyY)
        while (poseHistory.isNotEmpty() && (currentTime - poseHistory.first().first) > 1800L) {
            poseHistory.removeFirst()
        }

        var detectedRapidDrop = false
        if (poseHistory.size >= 3) {
            // Compare with every recent pose, not only the oldest one. Using only
            // the oldest point dilutes a genuine short fall when the person stood
            // still for a moment before descending.
            for ((pastTime, pastY) in poseHistory) {
                val dtSec = (currentTime - pastTime) / 1000f
                if (dtSec !in 0.15f..1.50f) continue
                val dy = bodyY - pastY // positive = moved downwards
                val velocity = dy / dtSec
                // Detect descent independently from the final posture. CameraX may
                // skip the exact impact frame while ML Kit is processing, so the
                // floor confirmation is allowed to arrive in a following frame.
                if (dy >= MIN_DROP_DISTANCE && velocity >= MIN_DROP_VELOCITY) {
                    detectedRapidDrop = true
                    Log.d(TAG, "Rapid fall drop detected! dy=$dy, vel=$velocity")
                    break
                }
            }
        }

        // Never turn static horizontal posture into a fall.  This is the critical
        // distinction that prevents sleeping, resting, or a head laid down on a bed
        // from producing an emergency.  The detector needs a rapid descent first,
        // then requires that the floor posture persists briefly to suppress a bad
        // pose frame or a quick sit-down.
        if (detectedRapidDrop && rapidDropTime == 0L) rapidDropTime = currentTime
        if (isDownOnFloor) lastFloorPoseTime = currentTime
        if (rapidDropTime != 0L && currentTime - rapidDropTime > FALL_CANDIDATE_TIMEOUT_MS) {
            rapidDropTime = 0L
            lastFloorPoseTime = 0L
        }
        val hasRecentFloorPose = lastFloorPoseTime >= rapidDropTime &&
            currentTime - lastFloorPoseTime <= FLOOR_POSE_GRACE_MS
        val confirmedRapidFall = rapidDropTime != 0L && hasRecentFloorPose &&
            currentTime - rapidDropTime >= FALL_CONFIRMATION_MS

        // ── 6. State Machine ──
        when (currentStatus) {
            FallStatus.NORMAL -> {
                // If clearly upright, NEVER trigger fall
                if (isClearlyUpright) {
                    if (rapidDropTime != 0L && currentTime - rapidDropTime > FALL_CONFIRMATION_MS) {
                        rapidDropTime = 0L
                        lastFloorPoseTime = 0L
                    }
                } else if (confirmedRapidFall) {
                    currentStatus = FallStatus.FALL_DETECTED
                    fallStartTime = currentTime
                    lastMotionTime = currentTime
                    uprightStartTime = 0L
                    isMovingOnFloor = hasSignificantMotion
                    rapidDropTime = 0L
                    lastFloorPoseTime = 0L
                    Log.d(TAG, "FALL DETECTED after rapid descent confirmation")
                }
            }

            FallStatus.FALL_DETECTED -> {
                // Check if person stood back up
                if (isClearlyUpright) {
                    if (uprightStartTime == 0L) uprightStartTime = currentTime
                    if (currentTime - uprightStartTime >= STANDING_RECOVERY_MS) {
                        currentStatus = FallStatus.NORMAL
                        fallStartTime = 0L
                        lastMotionTime = 0L
                        uprightStartTime = 0L
                        rapidDropTime = 0L
                        lastFloorPoseTime = 0L
                        isMovingOnFloor = false
                        Log.d(TAG, "RECOVERED TO NORMAL: Person is standing upright")
                    }
                } else {
                    uprightStartTime = 0L

                    if (hasSignificantMotion) {
                        // Person is moving / struggling while down on the floor
                        lastMotionTime = currentTime
                        isMovingOnFloor = true
                        // Stays in FALL_DETECTED!
                    } else {
                        isMovingOnFloor = false
                        // Motionless after a confirmed fall -> urgent escalation only.
                        if (currentTime - lastMotionTime >= UNCONSCIOUS_DELAY_MS) {
                            currentStatus = FallStatus.UNCONSCIOUS
                            Log.d(TAG, "TRANSITION TO PROLONGED-MOTIONLESS: 30s after confirmed fall")
                        }
                    }
                }
            }

            FallStatus.UNCONSCIOUS -> {
                // Check if person stood back up
                if (isClearlyUpright) {
                    if (uprightStartTime == 0L) uprightStartTime = currentTime
                    if (currentTime - uprightStartTime >= STANDING_RECOVERY_MS) {
                        currentStatus = FallStatus.NORMAL
                        fallStartTime = 0L
                        lastMotionTime = 0L
                        uprightStartTime = 0L
                        rapidDropTime = 0L
                        lastFloorPoseTime = 0L
                        isMovingOnFloor = false
                        Log.d(TAG, "RECOVERED TO NORMAL: Person is standing upright")
                    }
                } else {
                    uprightStartTime = 0L

                    if (hasSignificantMotion) {
                        // Person started moving on the floor again
                        currentStatus = FallStatus.FALL_DETECTED
                        lastMotionTime = currentTime
                        isMovingOnFloor = true
                        Log.d(TAG, "Person moving again -> FALL_DETECTED")
                    } else {
                        isMovingOnFloor = false
                    }
                }
            }
        }

        val elapsedFallMs = if (fallStartTime > 0L) currentTime - fallStartTime else 0L

        return PoseAnalysisResult(
            status = currentStatus,
            boundingBox = boundingBox,
            isMovingOnFloor = isMovingOnFloor,
            fallDurationMs = elapsedFallMs
        )
    }
}
