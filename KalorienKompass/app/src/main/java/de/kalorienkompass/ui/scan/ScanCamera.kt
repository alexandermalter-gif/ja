package de.kalorienkompass.ui.scan

import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * UI-facing state of the scanner camera: torch, preview stream and errors.
 * Only touched on the main thread.
 */
@Stable
internal class ScanCameraState {

    /** The bound camera has a flash unit that can be used as torch. */
    var hasTorch: Boolean by mutableStateOf(false)
        private set

    var torchOn: Boolean by mutableStateOf(false)
        private set

    /** True once the preview shows camera frames. */
    var isStreaming: Boolean by mutableStateOf(false)
        internal set

    /** German, user-facing; null while the camera works. */
    var error: String? by mutableStateOf(null)
        internal set

    /** Incremented by [retry] to bind the camera again. */
    var bindRequest: Int by mutableIntStateOf(0)
        private set

    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var removeObservers: (() -> Unit)? = null

    fun toggleTorch() {
        val cam = camera ?: return
        if (!hasTorch) return
        try {
            cam.cameraControl.enableTorch(!torchOn)
        } catch (e: Exception) {
            Log.w(TAG, "Torch could not be switched", e)
        }
    }

    /** Tap to focus: [point] in preview (= overlay) pixel coordinates. */
    fun focusAt(point: Offset) {
        val cam = camera ?: return
        val view = previewView ?: return
        try {
            val meteringPoint = view.meteringPointFactory.createPoint(point.x, point.y)
            val action = FocusMeteringAction.Builder(
                meteringPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "Tap to focus failed", e)
        }
    }

    /** Binds the camera again after an error. */
    fun retry() {
        error = null
        isStreaming = false
        bindRequest++
    }

    internal fun attach(camera: Camera, view: PreviewView, owner: LifecycleOwner) {
        detach()
        this.camera = camera
        this.previewView = view
        val info = camera.cameraInfo
        hasTorch = info.hasFlashUnit()
        error = null

        val torchObserver = Observer<Int> { state -> torchOn = state == TorchState.ON }
        val stateObserver = Observer<CameraState> { state ->
            error = state.error?.let { cameraErrorMessage(it.code) }
        }
        info.torchState.observe(owner, torchObserver)
        info.cameraState.observe(owner, stateObserver)
        removeObservers = {
            info.torchState.removeObserver(torchObserver)
            info.cameraState.removeObserver(stateObserver)
        }
    }

    internal fun detach() {
        removeObservers?.invoke()
        removeObservers = null
        camera = null
        previewView = null
        hasTorch = false
        torchOn = false
    }

    private companion object {
        const val TAG = "ScanCamera"
    }
}

/** German message for a [CameraState] error code. */
internal fun cameraErrorMessage(code: Int): String = when (code) {
    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
        "Die Kamera wird gerade von einer anderen App verwendet. Schließe die andere App und versuche es erneut."
    CameraState.ERROR_CAMERA_DISABLED ->
        "Die Kamera ist auf diesem Gerät deaktiviert."
    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
        "Die Kamera ist gesperrt, solange „Nicht stören“ aktiv ist."
    else ->
        "Die Kamera konnte nicht geöffnet werden."
}

/**
 * Camera preview ([PreviewView] in an [AndroidView]) with an [ImageAnalysis] use case running
 * [analyzer] on a dedicated background thread. The camera is bound to the current
 * (navigation back stack entry) lifecycle and released when this composable leaves the screen.
 */
@Composable
internal fun ScanCameraPreview(
    state: ScanCameraState,
    analyzer: ImageAnalysis.Analyzer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // TextureView based: plays well with Compose overlays and navigation transitions.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // One background thread for the analyzer; shut down after the use cases are unbound
    // (effects are disposed in reverse order, so this one runs last).
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(analysisExecutor) {
        onDispose { analysisExecutor.shutdown() }
    }

    DisposableEffect(lifecycleOwner, analyzer, state.bindRequest) {
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                // ~720p gives 1D barcodes enough pixels without slowing the detector down.
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()

        try {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener(
                {
                    if (disposed) return@addListener
                    try {
                        val provider = providerFuture.get()
                        cameraProvider = provider
                        val selector = when {
                            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                            else -> null
                        }
                        if (selector == null) {
                            state.error = "Auf diesem Gerät wurde keine nutzbare Kamera gefunden."
                            return@addListener
                        }
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        analysis.setAnalyzer(analysisExecutor, analyzer)
                        provider.unbindAll()
                        val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                        state.attach(camera, previewView, lifecycleOwner)
                    } catch (e: Exception) {
                        Log.w(TAG, "Binding the camera failed", e)
                        state.error = "Die Kamera konnte nicht gestartet werden."
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        } catch (e: Exception) {
            Log.w(TAG, "CameraX is not available", e)
            state.error = "Die Kamera konnte nicht gestartet werden."
        }

        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            state.detach()
            try {
                cameraProvider?.unbind(preview, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "Unbinding the camera failed", e)
            }
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val observer = Observer<PreviewView.StreamState> { streamState ->
            state.isStreaming = streamState == PreviewView.StreamState.STREAMING
        }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private const val TAG = "ScanCameraPreview"
