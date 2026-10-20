package app.nouri.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.TapToFocusInfo
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.camera.BarcodeAnalyzer
import app.nouri.core.ai.PortionHints
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.InfoBanner
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.SimulatedBanner
import app.nouri.ui.components.toUserDouble
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CameraScreen(nav: AppNavigator, route: Route.Camera) {
    val container = LocalAppContainer.current
    val vm = viewModel { CameraViewModel(container, runCatching { ScanMode.valueOf(route.mode) }.getOrDefault(ScanMode.MEAL), route.barcode) }
    val state by vm.state.collectAsStateWithLifecycle()
    val next by vm.next.collectAsStateWithLifecycle()

    LaunchedEffect(next) {
        when (val n = next) {
            is CameraNext.Review -> nav.replace(
                when (n.mode) {
                    ScanMode.MEAL -> Route.MealReview(n.captureId)
                    ScanMode.INGREDIENTS -> Route.IngredientReview(n.captureId)
                    ScanMode.LABEL -> Route.LabelReview(n.captureId)
                },
            )
            null -> Unit
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris -> vm.addFromGallery(uris) }
    val pickPhotos = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    if (state.reviewing) {
        PhotoReview(state, vm, onClose = nav::back, onAddFromGallery = pickPhotos)
    } else {
        CameraCapture(state, vm, onClose = nav::back, onGallery = pickPhotos)
    }

    state.consentFor?.let { provider ->
        AlertDialog(
            onDismissRequest = vm::declineConsent,
            icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
            title = { Text("Send this photo for analysis?") },
            text = {
                Text(
                    "Nouri will send a resized copy of your photo (at most 1600 pixels, with location and camera details removed) to $provider to identify the food. " +
                        "Calories are then calculated on your phone from reference data. You can withdraw this permission in Settings › Privacy.",
                )
            },
            confirmButton = { TextButton(onClick = vm::grantConsent) { Text("Agree and analyse") } },
            dismissButton = { TextButton(onClick = vm::declineConsent) { Text("Not now") } },
        )
    }
}

private fun Context.hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun CameraCapture(state: CameraUiState, vm: CameraViewModel, onClose: () -> Unit, onGallery: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var denials by rememberSaveable { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) denials++
    }
    LaunchedEffect(Unit) { if (!granted && denials == 0) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            Viewfinder(state, vm, onGallery)
        } else {
            PermissionPrompt(
                permanentlyDenied = denials >= 2 || (denials >= 1 && !(context as? Activity).shouldShowCameraRationale()),
                onRequest = { permission.launch(Manifest.permission.CAMERA) },
                onSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                },
                onGallery = onGallery,
            )
        }
        Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.discardIfUnused(); onClose() }, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                Icon(Icons.Outlined.Close, contentDescription = "Close camera")
            }
            Spacer(Modifier.weight(1f))
            if (granted && state.mode != ScanMode.LABEL) {
                IconButton(onClick = vm::nextFlash, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                    Icon(
                        when (state.flash) { FlashSetting.OFF -> Icons.Outlined.FlashOff; FlashSetting.AUTO -> Icons.Outlined.FlashAuto; FlashSetting.ON -> Icons.Outlined.FlashOn },
                        contentDescription = state.flash.label,
                    )
                }
            }
        }
    }
}

private fun Activity?.shouldShowCameraRationale(): Boolean =
    this != null && androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)

@Composable
private fun PermissionPrompt(permanentlyDenied: Boolean, onRequest: () -> Unit, onSettings: () -> Unit, onGallery: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AddAPhoto, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Camera access is needed to scan food", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Photos stay on your phone unless you choose to analyse them. You can also pick photos from your gallery.",
            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (permanentlyDenied) Button(onClick = onSettings) { Text("Open app settings") } else Button(onClick = onRequest) { Text("Allow camera") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onGallery) { Text("Choose from gallery", color = Color.White) }
    }
}

@Composable
private fun Viewfinder(state: CameraUiState, vm: CameraViewModel, onGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val controller = remember { LifecycleCameraController(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var showExposure by remember { mutableStateOf(false) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var focusText by remember { mutableStateOf<String?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(lifecycleOwner) {
        controller.isTapToFocusEnabled = true
        controller.isPinchToZoomEnabled = true
        controller.bindToLifecycle(lifecycleOwner)
        val focusObserver = androidx.lifecycle.Observer<TapToFocusInfo> { info ->
            focusPoint = info.tapPoint?.let { Offset(it.x, it.y) }
            focusText = when (info.focusState) {
                CameraController.TAP_TO_FOCUS_STARTED -> "Focusing…"
                CameraController.TAP_TO_FOCUS_FOCUSED -> "Focused"
                CameraController.TAP_TO_FOCUS_NOT_FOCUSED -> "Couldn't focus — try moving back a little"
                CameraController.TAP_TO_FOCUS_FAILED -> "Focus unavailable"
                else -> null
            }
        }
        controller.tapToFocusInfoState.observe(lifecycleOwner, focusObserver)
        onDispose {
            controller.tapToFocusInfoState.removeObserver(focusObserver)
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analysisExecutor.shutdown()
        }
    }

    // Live barcode reading only in label mode; other modes don't analyse frames at all.
    DisposableEffect(state.mode) {
        var analyzer: BarcodeAnalyzer? = null
        if (state.mode == ScanMode.LABEL) {
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            analyzer = BarcodeAnalyzer { code -> vm.onBarcode(code) }
            controller.setImageAnalysisAnalyzer(analysisExecutor, analyzer)
        } else {
            controller.clearImageAnalysisAnalyzer()
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            analyzer?.close()
        }
    }

    LaunchedEffect(state.flash) {
        controller.imageCaptureFlashMode = when (state.flash) {
            FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
        }
    }
    LaunchedEffect(state.barcode) { if (state.barcode != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Camera preview. Tap to focus." },
        )
        FrameGuide(state.mode)
        focusPoint?.let { p ->
            val density = LocalDensity.current
            val ring = 64.dp
            val half = with(density) { (ring / 2).toPx() }
            Box(
                Modifier
                    .offset { IntOffset((p.x - half).toInt(), (p.y - half).toInt()) }
                    .size(ring)
                    .border(2.dp, if (focusText == "Focused") Color(0xFF8FD1AE) else Color.White, CircleShape),
            )
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            focusText?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Text(state.mode.hint, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (state.demoMode && state.mode != ScanMode.LABEL) {
                Text("Demo mode: results will be simulated", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            }
            state.error?.let { ErrorBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

            state.barcode?.let { code ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                            Text("Barcode found", style = MaterialTheme.typography.titleSmall)
                            Text(code, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = vm::clearBarcode) { Text("Rescan") }
                        Button(onClick = vm::lookUpBarcode) { Text("Look up") }
                    }
                }
            }

            if (showExposure) {
                val range = controller.cameraInfo?.exposureState?.exposureCompensationRange
                if (range != null && range.lower < range.upper) {
                    Slider(
                        value = exposure,
                        onValueChange = { v ->
                            exposure = v
                            controller.cameraControl?.setExposureCompensationIndex(v.toInt())
                        },
                        valueRange = range.lower.toFloat()..range.upper.toFloat(),
                        modifier = Modifier.padding(horizontal = 32.dp).semantics { contentDescription = "Exposure" },
                    )
                } else {
                    Text("Exposure control isn't available on this camera", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }

            ModeSelector(state.mode, enabled = state.photos.isEmpty(), onSelect = vm::setMode)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Start) {
                    IconButton(onClick = onGallery, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Choose from gallery")
                    }
                    IconButton(onClick = { showExposure = !showExposure }, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Outlined.Exposure, contentDescription = if (showExposure) "Hide exposure control" else "Adjust exposure")
                    }
                }
                ShutterButton(processing = state.processing) {
                    vm.startProcessing()
                    val file = File(context.cacheDir, "capture_${System.nanoTime()}.jpg")
                    controller.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) { vm.addCaptured(file) }
                            override fun onError(exception: ImageCaptureException) {
                                file.delete()
                                vm.captureFailed("The photo couldn't be taken. Please try again.")
                            }
                        },
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (state.photos.isNotEmpty()) {
                        TextButton(onClick = vm::finishCapturing) { Text("Done (${state.photos.size})", color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameGuide(mode: ScanMode) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val stroke = BorderStroke(2.dp, Color.White.copy(alpha = 0.7f))
        when (mode) {
            ScanMode.MEAL -> Box(Modifier.fillMaxWidth(0.8f).aspectRatio(1f).border(stroke, CircleShape))
            ScanMode.INGREDIENTS -> Box(Modifier.fillMaxWidth(0.88f).aspectRatio(0.8f).border(stroke, RoundedCornerShape(24.dp)))
            ScanMode.LABEL -> Box(Modifier.fillMaxWidth(0.8f).aspectRatio(1.4f).border(stroke, RoundedCornerShape(12.dp)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(selected: ScanMode, enabled: Boolean, onSelect: (ScanMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp)) {
        ScanMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = m == selected,
                onClick = { onSelect(m) },
                enabled = enabled || m == selected,
                shape = SegmentedButtonDefaults.itemShape(i, ScanMode.entries.size),
                colors = SegmentedButtonDefaults.colors(inactiveContainerColor = Color.Black.copy(alpha = 0.4f), inactiveContentColor = Color.White),
            ) { Text(m.title, maxLines = 1) }
        }
    }
}

@Composable
private fun ShutterButton(processing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (processing) {
            CircularProgressIndicator(color = Color.White)
        } else {
            IconButton(
                onClick = onClick,
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Take photo" },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White),
            ) {}
        }
    }
}

/** Check photos before analysis: rotate, crop, retake, add angles and optional portion details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoReview(state: CameraUiState, vm: CameraViewModel, onClose: () -> Unit, onAddFromGallery: () -> Unit) {
    var cropping by remember { mutableStateOf<Int?>(null) }
    Scaffold(topBar = { NouriTopBar(state.mode.title, onBack = { vm.discardIfUnused(); onClose() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.demoMode && state.mode != ScanMode.LABEL) SimulatedBanner()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.photos.forEachIndexed { i, img ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PhotoThumb(img.file, contentDescription = "Photo ${i + 1}", modifier = Modifier.size(220.dp).clip(MaterialTheme.shapes.medium))
                        Row {
                            IconButton(onClick = { vm.rotate(i) }) { Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = "Rotate photo ${i + 1}") }
                            IconButton(onClick = { cropping = i }) { Icon(Icons.Outlined.Crop, contentDescription = "Crop photo ${i + 1}") }
                            IconButton(onClick = { vm.remove(i) }) { Icon(Icons.Outlined.Delete, contentDescription = "Remove photo ${i + 1}") }
                        }
                    }
                }
            }
            if (state.processing) Text("Preparing photo…", style = MaterialTheme.typography.bodySmall)
            state.error?.let { ErrorBanner(it) }

            if (state.canAddMore) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::addAnother) {
                        Icon(Icons.Outlined.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.mode == ScanMode.MEAL) "Add another angle" else "Take another")
                    }
                    OutlinedButton(onClick = onAddFromGallery) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gallery")
                    }
                }
                if (state.mode == ScanMode.MEAL && state.photos.size == 1) {
                    Text("A second photo from the side helps estimate portion height.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (state.mode == ScanMode.MEAL) PortionDetails(state.hints, vm::setHints)

            if (state.demoMode && state.demoScenarios.isNotEmpty()) {
                Text("Demo scenario", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.demoScenario == null, onClick = { vm.setDemoScenario(null) }, label = { Text("Automatic") })
                    state.demoScenarios.forEach { key ->
                        FilterChip(selected = state.demoScenario == key, onClick = { vm.setDemoScenario(key) }, label = { Text(key.replace('_', ' ')) })
                    }
                }
            }

            InfoBanner(
                text = when {
                    state.mode == ScanMode.LABEL -> "Labels are read on your phone first. Nothing is sent unless you choose AI reading later."
                    state.demoMode -> "Demo mode: your photo isn't sent anywhere."
                    else -> "Only a resized copy without location data is sent to ${state.providerLabel}. Calories are calculated on your phone."
                },
                icon = Icons.Outlined.Shield,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = vm::analyse, enabled = state.photos.isNotEmpty() && !state.processing, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.mode == ScanMode.LABEL) "Read label" else "Analyse")
            }
        }
    }

    cropping?.let { index ->
        var fraction by remember(index) { mutableFloatStateOf(1f) }
        AlertDialog(
            onDismissRequest = { cropping = null },
            title = { Text("Crop to centre") },
            text = {
                Column {
                    state.photos.getOrNull(index)?.let { img ->
                        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                            PhotoThumb(img.file, contentDescription = null, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize(fraction).border(2.dp, MaterialTheme.colorScheme.primary))
                        }
                    }
                    Slider(value = fraction, onValueChange = { fraction = it }, valueRange = 0.4f..1f, modifier = Modifier.semantics { contentDescription = "Crop size" })
                    Text("Keep ${(fraction * 100).toInt()}% of the photo", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.crop(index, fraction); cropping = null }, enabled = fraction < 0.99f) { Text("Crop") } },
            dismissButton = { TextButton(onClick = { cropping = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortionDetails(hints: PortionHints, onChange: (PortionHints) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Portion details (optional)", style = MaterialTheme.typography.titleSmall)
                    Text("Improves the estimate. Weighed amounts are used exactly.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Add") }
            }
            if (expanded) {
                Text("Where is it from?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(null to "Not sure", true to "Homemade", false to "Restaurant")
                    options.forEachIndexed { i, (value, label) ->
                        SegmentedButton(selected = hints.homemade == value, onClick = { onChange(hints.copy(homemade = value)) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                    }
                }
                Text("Plate size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Not sure", 20.0 to "Small (20 cm)", 26.0 to "Standard (26 cm)", 30.0 to "Large (30 cm)").forEach { (cm, label) ->
                        FilterChip(selected = hints.plateDiameterCm == cm, onClick = { onChange(hints.copy(plateDiameterCm = cm)) }, label = { Text(label) })
                    }
                }
                var weight by rememberSaveable { mutableStateOf(hints.totalWeightGrams?.toInt()?.toString() ?: "") }
                NumberField(
                    weight, { weight = it; onChange(hints.copy(totalWeightGrams = it.toUserDouble()?.takeIf { g -> g > 0 })) },
                    "Weighed total (food only)", Modifier.fillMaxWidth().padding(top = 8.dp), suffix = "g",
                )
                var reference by rememberSaveable { mutableStateOf(hints.referenceObject ?: "") }
                OutlinedTextField(
                    value = reference,
                    onValueChange = { reference = it; onChange(hints.copy(referenceObject = it.ifBlank { null })) },
                    label = { Text("Object in the photo for scale (e.g. fork, hand)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                var note by rememberSaveable { mutableStateOf(hints.userNote ?: "") }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200); onChange(hints.copy(userNote = note.ifBlank { null })) },
                    label = { Text("Anything else? (e.g. cooked in ghee)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}
