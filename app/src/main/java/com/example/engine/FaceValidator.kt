package com.example.engine

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

data class FaceValidationResult(
    val valid: Boolean,
    val note: String,
    val mouthCenterX: Float = 0.5f,
    val mouthCenterY: Float = 0.54f,
    val mouthWidthNorm: Float = 0.14f
)

/**
 * On-device ML Kit Face Detection validating single frontal presenter framing
 * and extracting normalized mouth geometry for audio-driven lip-sync rendering.
 */
class FaceValidator {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }

    suspend fun validatePortrait(bitmap: Bitmap): FaceValidationResult {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = detector.process(image).await()
            when {
                faces.isEmpty() -> FaceValidationResult(
                    valid = false,
                    note = "No clear face detected. Choose a well-lit frontal portrait."
                )
                faces.size > 1 -> FaceValidationResult(
                    valid = false,
                    note = "Multiple faces detected (${faces.size}). Choose a solo portrait."
                )
                else -> {
                    val face = faces.first()
                    val yaw = abs(face.headEulerAngleY)
                    val roll = abs(face.headEulerAngleZ)
                    val w = bitmap.width.coerceAtLeast(1).toFloat()
                    val h = bitmap.height.coerceAtLeast(1).toFloat()

                    val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
                    val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
                    val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

                    val box = face.boundingBox
                    val centerX = if (mouthLeft != null && mouthRight != null) {
                        ((mouthLeft.x + mouthRight.x) * 0.5f / w).coerceIn(0.15f, 0.85f)
                    } else {
                        (box.exactCenterX() / w).coerceIn(0.15f, 0.85f)
                    }
                    val centerY = when {
                        mouthBottom != null && mouthLeft != null && mouthRight != null ->
                            ((((mouthLeft.y + mouthRight.y) * 0.5f) + mouthBottom.y) * 0.5f / h).coerceIn(0.2f, 0.85f)
                        mouthBottom != null ->
                            (mouthBottom.y / h).coerceIn(0.2f, 0.85f)
                        else ->
                            ((box.top + box.height() * 0.74f) / h).coerceIn(0.2f, 0.85f)
                    }
                    val mouthWidth = if (mouthLeft != null && mouthRight != null) {
                        (abs(mouthRight.x - mouthLeft.x) / w).coerceIn(0.06f, 0.35f)
                    } else {
                        ((box.width() * 0.38f) / w).coerceIn(0.06f, 0.35f)
                    }

                    if (yaw > 26f || roll > 22f) {
                        FaceValidationResult(
                            valid = false,
                            note = "Face is angled away from lens. Look directly forward.",
                            mouthCenterX = centerX,
                            mouthCenterY = centerY,
                            mouthWidthNorm = mouthWidth
                        )
                    } else {
                        FaceValidationResult(
                            valid = true,
                            note = "Frontal face & mouth landmarks locked.",
                            mouthCenterX = centerX,
                            mouthCenterY = centerY,
                            mouthWidthNorm = mouthWidth
                        )
                    }
                }
            }
        } catch (e: Exception) {
            FaceValidationResult(
                valid = false,
                note = e.localizedMessage ?: "Face check could not run on this image."
            )
        }
    }
}
