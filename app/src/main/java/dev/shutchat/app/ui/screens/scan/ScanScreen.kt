package dev.shutchat.app.ui.screens.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.shutchat.app.core.Log
import dev.shutchat.app.util.QrGenerator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR scanner.
 *
 * Only QR formats are enabled, which keeps the detector fast enough to run on
 * every frame, and a payload is only accepted when it parses as a ShutChat code —
 * so pointing the camera at an unrelated QR does nothing rather than filling the
 * field with noise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onCode: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var handled by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Point at the code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
        ) {
            if (!hasPermission) {
                Text(
                    text = "The camera is needed to read the QR. The code is not sent " +
                        "anywhere: it is read on the device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CameraPreview(
                    onDetected = { payload ->
                        if (handled) return@CameraPreview
                        if (!QrGenerator.looksLikeInvite(payload)) {
                            Log.d(TAG, "QR ignored: it is not a ShutChat code")
                            return@CameraPreview
                        }
                        handled = true
                        onCode(payload)
                    },
                )
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(18.dp),
                        ),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Put the QR inside the frame. It is accepted only if it is a valid " +
                    "ShutChat code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** CameraX preview plus a zxing analyser that only looks for QR codes. */
@Composable
private fun CameraPreview(onDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // zxing rather than ML Kit: the model is pure Java, needs no download from
    // Google and no Play Services, so scanning works on stock Android too.
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
    }
    val delivered = remember { AtomicBoolean(false) }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            try {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    // Only the newest frame matters for a scanner.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    analyze(proxy, reader, delivered) { code ->
                        if (delivered.compareAndSet(false, true)) onDetected(code)
                    }
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                Log.w(TAG, "camera not available", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * Decodes one camera frame.
 *
 * zxing works on the grey plane, which YUV hands over already: no bitmap
 * conversion, no copy into the GPU, and the frame is released as soon as the
 * attempt is over so the camera keeps its buffer.
 */
private fun analyze(
    proxy: ImageProxy,
    reader: MultiFormatReader,
    delivered: AtomicBoolean,
    onDetected: (String) -> Unit,
) {
    try {
        if (delivered.get()) return
        val plane = proxy.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val y = ByteArray(buffer.remaining())
        buffer.get(y)
        val rotated = rotateLuminance(y, proxy.width, proxy.height, plane.rowStride, proxy.imageInfo.rotationDegrees)
        val source = PlanarYUVLuminanceSource(
            rotated.data,
            rotated.width,
            rotated.height,
            0,
            0,
            rotated.width,
            rotated.height,
            false,
        )
        val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }
            .getOrNull()
            ?.text
            ?.takeIf { it.isNotBlank() }
        reader.reset()
        if (text != null) onDetected(text)
    } catch (e: IllegalStateException) {
        Log.d(TAG, "read failed: ${e.message}")
    } finally {
        proxy.close()
    }
}

/** A greyscale frame ready for the decoder. */
internal data class Luminance(val data: ByteArray, val width: Int, val height: Int)

/**
 * Turns the camera's grey plane into an upright image.
 *
 * The plane is taller than the stride accounts for: each row starts at
 * `rowStride` rather than at `width`, so the padding has to be dropped while
 * rotating. The decoder only ever sees dense rows.
 */
internal fun rotateLuminance(
    y: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    degrees: Int,
): Luminance {
    val stride = if (rowStride <= 0) width else rowStride
    fun at(x: Int, yIndex: Int): Byte = y[yIndex * stride + x]

    return when (((degrees % 360) + 360) % 360) {
        90 -> {
            // Clockwise: (x, y) -> (height - 1 - y, x).
            val out = ByteArray(width * height)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    out[col * height + (height - 1 - row)] = at(col, row)
                }
            }
            Luminance(out, height, width)
        }

        180 -> {
            val out = ByteArray(width * height)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    out[(height - 1 - row) * width + (width - 1 - col)] = at(col, row)
                }
            }
            Luminance(out, width, height)
        }

        270 -> {
            // Counter-clockwise: (x, y) -> (y, width - 1 - x).
            val out = ByteArray(width * height)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    out[(width - 1 - col) * height + row] = at(col, row)
                }
            }
            Luminance(out, height, width)
        }

        else -> {
            if (stride == width) {
                Luminance(y.copyOf(width * height), width, height)
            } else {
                val out = ByteArray(width * height)
                for (row in 0 until height) {
                    y.copyInto(out, row * width, row * stride, row * stride + width)
                }
                Luminance(out, width, height)
            }
        }
    }
}

private const val TAG = "ScanScreen"
