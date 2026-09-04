package com.androscan.app.ui

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androscan.app.util.EartagCheckDigit
import com.androscan.app.util.ScanFeedback
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CameraPreview"
private const val SCAN_COOLDOWN_MS = 2000L
private val PREVIEW_HEIGHT_DP = 150.dp

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    enabled: Boolean,
    onBarcodeDetected: (String) -> Unit,
    onScanError: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanning = remember { AtomicBoolean(false) }
    val nextScanAllowedAt = remember { AtomicLong(0L) }
    val acceptScans = remember { AtomicBoolean(enabled) }
    acceptScans.set(enabled)
    val latestCallback = rememberUpdatedState(onBarcodeDetected)
    val latestError = rememberUpdatedState(onScanError)
    val viewSize = remember { AtomicReference(Size(0, 0)) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView — respects Compose clipping (unlike SurfaceView).
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            clipToOutline = true
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                viewSize.set(Size(right - left, bottom - top))
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val listener = Runnable {
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider failed", e)
                return@Runnable
            }

            cameraProvider.unbindAll()

            val previewResolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()

            // Prefer a high analysis resolution so mid-range devices (e.g. A53) can
            // fully decode longer Code-128 payloads such as 040… / 251040….
            val analysisResolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(previewResolution)
            enableContinuousAutofocus(previewBuilder)

            val preview = previewBuilder
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_ITF
                )
                .build()
            val scanner = BarcodeScanning.getClient(options)

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            enableContinuousAutofocus(analysisBuilder)

            val analysis = analysisBuilder.build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                if (!acceptScans.get() || scanning.get()) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val rotation = imageProxy.imageInfo.rotationDegrees
                val input = InputImage.fromMediaImage(mediaImage, rotation)

                scanning.set(true)
                scanner.process(input)
                    .addOnSuccessListener { barcodes ->
                        if (!acceptScans.get()) return@addOnSuccessListener
                        val now = System.currentTimeMillis()
                        if (now < nextScanAllowedAt.get()) return@addOnSuccessListener

                        val view = viewSize.get()
                        val candidate = barcodes.firstOrNull { barcode ->
                            val raw = barcodeRaw(barcode)
                            val payload = prepareBarcodePayload(raw)
                            payload.isNotEmpty() &&
                                isAcceptableInPreview(
                                    boundingBox = barcode.boundingBox,
                                    imageProxy = imageProxy,
                                    rotationDegrees = rotation,
                                    viewWidth = view.width,
                                    viewHeight = view.height
                                )
                        } ?: return@addOnSuccessListener

                        val raw = barcodeRaw(candidate)
                        val payload = prepareBarcodePayload(raw)
                        Log.d(TAG, "raw='$raw' prepared='$payload'")

                        // After stripping ]C1 / 251 (and 040→AT), require ≥2 non-numeric chars.
                        if (!hasAtLeastTwoNonNumericChars(payload)) {
                            Log.d(TAG, "rejected: need ≥2 non-numeric after prep (raw='$raw')")
                            return@addOnSuccessListener
                        }
                        when (val result = EartagCheckDigit.validate(payload)) {
                            is EartagCheckDigit.ValidationResult.Valid -> {
                                nextScanAllowedAt.set(now + SCAN_COOLDOWN_MS)
                                ScanFeedback.peep()
                                ScanFeedback.vibrateOnce(context)
                                latestCallback.value(payload)
                            }
                            is EartagCheckDigit.ValidationResult.InvalidLength,
                            is EartagCheckDigit.ValidationResult.InvalidCheckDigit -> {
                                nextScanAllowedAt.set(now + SCAN_COOLDOWN_MS)
                                result.errorMessage?.let { latestError.value(it) }
                            }
                            is EartagCheckDigit.ValidationResult.Unsupported -> {
                                // ignore unrelated barcodes
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Barcode analysis failed", e)
                    }
                    .addOnCompleteListener {
                        scanning.set(false)
                        imageProxy.close()
                    }
            }

            try {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }

        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching {
                cameraProviderFuture.get().unbindAll()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT_DP)
            .clip(RoundedCornerShape(8.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(RoundedCornerShape(6.dp))
        )
    }
}

/** Continuous autofocus for barcode scanning. */
@OptIn(ExperimentalCamera2Interop::class)
private fun <T> enableContinuousAutofocus(builder: ExtendableBuilder<T>) {
    Camera2Interop.Extender(builder)
        .setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
}

/** Prefer rawValue; some devices only populate displayValue reliably. */
private fun barcodeRaw(barcode: Barcode): String {
    return barcode.rawValue?.trim().orEmpty()
        .ifEmpty { barcode.displayValue?.trim().orEmpty() }
}

/** Strip AIM symbology prefix "]C1" / "]C0" / "]E0" before validation / storage. */
internal fun normalizeBarcodeValue(value: String): String {
    if (value.length >= 3 && value[0] == ']') {
        val sym = value.substring(1, 3).uppercase()
        if (sym == "C1" || sym == "C0" || sym == "E0" || sym == "D1") {
            return value.drop(3)
        }
    }
    return value
}

/** Remove leading GS1 AI "251" (source entity / eartag), also "(251)" form. */
internal fun stripLeading251(value: String): String {
    return when {
        value.startsWith("(251)") -> value.drop(5)
        value.startsWith("251") -> value.drop(3)
        else -> value
    }
}

private val ISO_TO_ALPHA = mapOf(
    "040" to "AT",
    "276" to "DE",
    "348" to "HU",
    "203" to "CZ",
    "703" to "SK",
    "705" to "SI",
    "616" to "PL",
    "642" to "RO"
)

/**
 * After ]C1 / 251 are removed: if the payload starts with a numeric ISO country
 * code (first 2 chars numeric, leading 3 == e.g. "040"), replace with alpha (AT).
 * Also recovers cases where 1–2 junk digits precede 040 (seen on some devices).
 */
internal fun replaceIso040WithAt(value: String): String {
    return replaceLeadingIsoWithAlpha(value)
}

internal fun replaceLeadingIsoWithAlpha(value: String): String {
    if (value.length < 3) return value
    val upper = value.uppercase()

    // Standard: starts with ISO numeric country code
    if (upper[0].isDigit() && upper[1].isDigit()) {
        val iso = upper.take(3)
        val alpha = ISO_TO_ALPHA[iso]
        if (alpha != null) {
            return alpha + upper.drop(3)
        }
    }

    // Mid-range cameras sometimes prepend a stray digit before 040…
    for (iso in ISO_TO_ALPHA.keys) {
        val idx = upper.indexOf(iso)
        if (idx in 1..2 && upper.drop(idx + 3).all { it.isDigit() }) {
            val prefix = upper.take(idx)
            if (prefix.all { it.isDigit() }) {
                return ISO_TO_ALPHA.getValue(iso) + upper.drop(idx + 3)
            }
        }
    }
    return value
}

/** Remove GS1 separators / control chars that some Samsung builds embed in rawValue. */
internal fun scrubBarcodeRaw(raw: String): String {
    return raw
        .replace("\u001d", "") // GS
        .replace("\u001e", "") // RS
        .replace("\u001f", "") // US
        .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
        .replace(" ", "")
        .replace("-", "")
        .trim()
}

/** Full prep pipeline before validation / storage. */
internal fun prepareBarcodePayload(raw: String): String {
    val cleaned = scrubBarcodeRaw(raw)
    return replaceLeadingIsoWithAlpha(stripLeading251(normalizeBarcodeValue(cleaned)))
}

/** True if the payload contains at least two non-digit characters. */
internal fun hasAtLeastTwoNonNumericChars(value: String): Boolean {
    return value.count { !it.isDigit() } >= 2
}

/**
 * Accept supported cattle eartags (HU, CZ, SK, SI, PL, RO, DE, AT),
 * optionally prefixed with GS1 AI 251 / AIM "]C1".
 * After stripping those prefixes (and mapping 040→AT), at least two
 * non-numeric characters are required.
 */
internal fun isAcceptedBarcodeValue(value: String): Boolean {
    val payload = prepareBarcodePayload(value)
    return hasAtLeastTwoNonNumericChars(payload) &&
        EartagCheckDigit.validate(payload) is EartagCheckDigit.ValidationResult.Valid
}

/**
 * Accept barcode if its center lies in the visible FILL_CENTER crop,
 * with a small margin so mid-range sensors (A53) don't reject long Code-128 boxes
 * that slightly overflow due to resolution / aspect differences.
 */
internal fun isAcceptableInPreview(
    boundingBox: Rect?,
    imageProxy: ImageProxy,
    rotationDegrees: Int,
    viewWidth: Int,
    viewHeight: Int
): Boolean {
    // If layout size unknown yet, don't block the scan.
    if (viewWidth <= 0 || viewHeight <= 0) return true
    if (boundingBox == null) return true

    val imageWidth = imageProxy.width
    val imageHeight = imageProxy.height
    val (rotatedW, rotatedH) = if (rotationDegrees % 180 == 0) {
        imageWidth to imageHeight
    } else {
        imageHeight to imageWidth
    }

    val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
    val imageAspect = rotatedW.toFloat() / rotatedH.toFloat()

    val cropLeft: Float
    val cropTop: Float
    val cropRight: Float
    val cropBottom: Float

    if (imageAspect > viewAspect) {
        val visibleWidth = rotatedH * viewAspect
        val offset = (rotatedW - visibleWidth) / 2f
        cropLeft = offset
        cropTop = 0f
        cropRight = rotatedW - offset
        cropBottom = rotatedH.toFloat()
    } else {
        val visibleHeight = rotatedW / viewAspect
        val offset = (rotatedH - visibleHeight) / 2f
        cropLeft = 0f
        cropTop = offset
        cropRight = rotatedW.toFloat()
        cropBottom = rotatedH - offset
    }

    // ~4% margin + center-point rule (more tolerant than strict full containment)
    val marginX = (cropRight - cropLeft) * 0.04f
    val marginY = (cropBottom - cropTop) * 0.04f
    val left = cropLeft - marginX
    val top = cropTop - marginY
    val right = cropRight + marginX
    val bottom = cropBottom + marginY

    val cx = boundingBox.exactCenterX()
    val cy = boundingBox.exactCenterY()
    if (cx < left || cx > right || cy < top || cy > bottom) return false

    // Require most of the box to overlap the (margined) crop
    val overlapLeft = maxOf(boundingBox.left.toFloat(), left)
    val overlapTop = maxOf(boundingBox.top.toFloat(), top)
    val overlapRight = minOf(boundingBox.right.toFloat(), right)
    val overlapBottom = minOf(boundingBox.bottom.toFloat(), bottom)
    val overlapW = (overlapRight - overlapLeft).coerceAtLeast(0f)
    val overlapH = (overlapBottom - overlapTop).coerceAtLeast(0f)
    val overlapArea = overlapW * overlapH
    val boxArea = boundingBox.width().toFloat() * boundingBox.height().toFloat()
    if (boxArea <= 0f) return true
    return overlapArea / boxArea >= 0.75f
}

/** @deprecated Use [isAcceptableInPreview]; kept for call-site compatibility. */
internal fun isFullyInsidePreview(
    boundingBox: Rect?,
    imageProxy: ImageProxy,
    rotationDegrees: Int,
    viewWidth: Int,
    viewHeight: Int
): Boolean = isAcceptableInPreview(
    boundingBox, imageProxy, rotationDegrees, viewWidth, viewHeight
)

