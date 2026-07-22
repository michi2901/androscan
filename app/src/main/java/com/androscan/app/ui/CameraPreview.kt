package com.androscan.app.ui

import android.graphics.Rect
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_CODABAR
                )
                .build()
            val scanner = BarcodeScanning.getClient(options)

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

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
                            val payload = stripLeading251(
                                normalizeBarcodeValue(barcode.rawValue?.trim().orEmpty())
                            )
                            payload.isNotEmpty() &&
                                isFullyInsidePreview(
                                    boundingBox = barcode.boundingBox,
                                    imageProxy = imageProxy,
                                    rotationDegrees = rotation,
                                    viewWidth = view.width,
                                    viewHeight = view.height
                                )
                        } ?: return@addOnSuccessListener

                        val payload = stripLeading251(
                            normalizeBarcodeValue(candidate.rawValue?.trim().orEmpty())
                        )
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
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
        )
    }
}

/** Strip AIM symbology prefix "]C1" before validation / storage. */
internal fun normalizeBarcodeValue(value: String): String {
    return if (value.startsWith("]C1")) value.drop(3) else value
}

/** After a successful scan, remove leading GS1 AI "251" (source entity / eartag). */
internal fun stripLeading251(value: String): String {
    return if (value.startsWith("251")) value.drop(3) else value
}

/**
 * Accept supported cattle eartags (HU, CZ, SK, SI, PL, RO, DE, AT),
 * optionally prefixed with GS1 AI 251.
 */
internal fun isAcceptedBarcodeValue(value: String): Boolean {
    val payload = stripLeading251(value)
    return EartagCheckDigit.validate(payload) is EartagCheckDigit.ValidationResult.Valid
}

/**
 * FILL_CENTER: only accept barcodes whose bounding box lies fully inside the
 * visible PreviewView crop of the analysis frame.
 */
internal fun isFullyInsidePreview(
    boundingBox: Rect?,
    imageProxy: ImageProxy,
    rotationDegrees: Int,
    viewWidth: Int,
    viewHeight: Int
): Boolean {
    if (boundingBox == null || viewWidth <= 0 || viewHeight <= 0) return false

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

    return boundingBox.left >= cropLeft &&
        boundingBox.top >= cropTop &&
        boundingBox.right <= cropRight &&
        boundingBox.bottom <= cropBottom
}

