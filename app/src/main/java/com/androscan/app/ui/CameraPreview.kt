package com.androscan.app.ui

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CameraPreview"
private const val DEBOUNCE_MS = 1500L
private val PREVIEW_HEIGHT_DP = 150.dp

@Composable
fun CameraPreview(
    enabled: Boolean,
    onBarcodeDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanning = remember { AtomicBoolean(false) }
    val lastValue = remember { AtomicLong(0L) }
    val lastCode = remember { AtomicReference<String?>(null) }
    val acceptScans = remember { AtomicBoolean(enabled) }
    acceptScans.set(enabled)
    val latestCallback = rememberUpdatedState(onBarcodeDetected)
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
                        val view = viewSize.get()
                        val match = barcodes.firstOrNull { barcode ->
                            val raw = barcode.rawValue?.trim().orEmpty()
                            raw.isNotEmpty() &&
                                isAcceptedBarcodeValue(raw) &&
                                isFullyInsidePreview(
                                    boundingBox = barcode.boundingBox,
                                    imageProxy = imageProxy,
                                    rotationDegrees = rotation,
                                    viewWidth = view.width,
                                    viewHeight = view.height
                                )
                        } ?: return@addOnSuccessListener

                        val raw = match.rawValue?.trim().orEmpty()
                        val now = System.currentTimeMillis()
                        val previous = lastCode.get()
                        val elapsed = now - lastValue.get()
                        if (raw != previous || elapsed >= DEBOUNCE_MS) {
                            lastCode.set(raw)
                            lastValue.set(now)
                            vibrate(context)
                            latestCallback.value(raw)
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

/** Accept codes starting with 251, or whose first two characters are both non-digits. */
internal fun isAcceptedBarcodeValue(value: String): Boolean {
    if (value.startsWith("251")) return true
    if (value.length < 2) return false
    return !value[0].isDigit() && !value[1].isDigit()
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

@Suppress("DEPRECATION")
private fun vibrate(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                vibrator?.vibrate(40)
            }
        }
    } catch (_: Exception) {
        // ignore missing vibrator
    }
}
