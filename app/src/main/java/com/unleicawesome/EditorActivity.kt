package com.unleicawesome

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresPermission
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

class EditorActivity : ComponentActivity() {

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var fileState: LeicaEngine.FileState? = null

    private val sliderKelvin = mutableIntStateOf(650)
    private val sliderTint = mutableIntStateOf(500)
    private val sliderExp = mutableIntStateOf(500)
    private val sliderBias = mutableIntStateOf(5)
    private val statusText = mutableStateOf("Loading...")
    private val fileLabel = mutableStateOf("No file loaded")
    private val isLoaded = mutableStateOf(false)
    private val isProcessing = mutableStateOf(false)
    private val isTele = mutableStateOf(false)
    private val currentGalleryIndex = mutableIntStateOf(0)
    private var galleryUris: List<Uri> = emptyList()
    private var galleryNames: List<String> = emptyList()

    private val sourceBitmap = mutableStateOf<Bitmap?>(null)

    private val colorShader = RuntimeShader("""
        uniform shader inputImage;
        uniform float mulR;
        uniform float mulG;
        uniform float mulB;

        float toLinear(float v) {
            return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4);
        }

        float toSrgb(float v) {
            v = clamp(v, 0.0, 1.0);
            return v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1.0 / 2.4) - 0.055;
        }

        float softClip(float v) {
            if (v <= 0.6) return v;
            float over = (v - 0.6) / 0.4;
            return 0.6 + 0.4 * (1.0 - exp(-over));
        }

        half4 main(float2 fragCoord) {
            half4 c = inputImage.eval(fragCoord);

            float r = softClip(toLinear(float(c.r)) * mulR);
            float g = softClip(toLinear(float(c.g)) * mulG);
            float b = softClip(toLinear(float(c.b)) * mulB);

            return half4(half(toSrgb(r)), half(toSrgb(g)), half(toSrgb(b)), c.a);
        }
    """)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            UnleicawesomeTheme {
                EditorScreen()
            }
        }

        val uri = intent.data
        val filename = intent.getStringExtra("filename")
        if (uri != null) {
            val extraUris = intent.getStringArrayListExtra("gallery_uris")
                ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
            val extraNames = intent.getStringArrayListExtra("gallery_names")
                ?.takeIf { it.size == extraUris?.size }

            galleryUris = extraUris ?: listOf(uri)
            galleryNames = extraNames ?: listOf(filename ?: "Loading...")
            currentGalleryIndex.intValue = intent.getIntExtra("gallery_index", 0)
                .coerceIn(0, galleryUris.lastIndex)

            fileLabel.value = galleryNames.getOrElse(currentGalleryIndex.intValue) { filename ?: "Loading..." }
            loadFile(galleryUris[currentGalleryIndex.intValue])
        } else {
            statusText.value = "ERROR: No file specified"
            Toast.makeText(this, "No file specified", Toast.LENGTH_SHORT).show()
            haptic()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
        sourceBitmap.value?.recycle()
        sourceBitmap.value = null
        fileState?.sourceBitmap?.recycle()
        fileState?.sourceBitmap = null
    }

    private fun computeMultipliers(sk: Int, st: Int, se: Int, sb: Int, origTemp: Int): FloatArray {
        val wb = LeicaEngine.getWBMultipliers(sk, st)
        var wbR = wb[0]; var wbG = wb[1]; var wbB = wb[2]

        if (origTemp > 0) {
            val biasTemp = sb * 1000
            val deviation = (biasTemp - origTemp) / 10000f
            wbR *= (1f - deviation * 0.15f)
            wbB *= (1f + deviation * 0.15f)
        }

        val ev = LeicaEngine.sliderToEV(se)
        val expMul = if (ev < 0) 2f.pow(ev * 1.5f) else 2f.pow(ev)
        val ampB = if (wbB > 1f) 5.5f else 4f
        val aR = wbR.pow(4f) * expMul
        val aG = wbG.pow(4f) * expMul
        val aB = wbB.pow(ampB) * expMul

        return floatArrayOf(aR, aG, aB)
    }

    @Composable
    private fun EditorScreen() {
        var controlsOpen by remember { mutableStateOf(false) }
        val controlsHeight by animateDpAsState(
            targetValue = if (controlsOpen) 430.dp else 0.dp,
            animationSpec = tween(durationMillis = 280),
            label = "controlsHeight"
        )
        val controlsAlpha by animateFloatAsState(
            targetValue = if (controlsOpen) 1f else 0f,
            animationSpec = tween(durationMillis = 180),
            label = "controlsAlpha"
        )
        BackHandler(enabled = controlsOpen) {
            haptic()
            controlsOpen = false
        }

        Scaffold(
            containerColor = Color(0xFF111111)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(
                        start = innerPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                        end = innerPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                        bottom = innerPadding.calculateBottomPadding()
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF111111))
                        .pointerInput(galleryUris, currentGalleryIndex.intValue, isProcessing.value) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onHorizontalDrag = { change, dragAmount ->
                                    totalDrag += dragAmount
                                    change.consume()
                                },
                                onDragEnd = {
                                    if (abs(totalDrag) > size.width * 0.18f) {
                                        if (totalDrag < 0f) {
                                            navigateGallery(1)
                                        } else {
                                            navigateGallery(-1)
                                        }
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = sourceBitmap.value
                    if (bmp != null) {
                        val origTemp = fileState?.origColorTemp ?: 0
                        val effect = remember(
                            sliderKelvin.intValue, sliderTint.intValue,
                            sliderExp.intValue, sliderBias.intValue
                        ) {
                            val mul = computeMultipliers(
                                sliderKelvin.intValue, sliderTint.intValue,
                                sliderExp.intValue, sliderBias.intValue, origTemp
                            )
                            colorShader.setFloatUniform("mulR", mul[0])
                            colorShader.setFloatUniform("mulG", mul[1])
                            colorShader.setFloatUniform("mulB", mul[2])
                            RenderEffect.createRuntimeShaderEffect(colorShader, "inputImage")
                                .asComposeRenderEffect()
                        }

                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    renderEffect = effect
                                }
                        )
                    } else {
                        Text(
                            text = "Loading preview...",
                            color = Color(0xFF888888),
                            fontSize = 16.sp
                        )
                    }

                    if (galleryUris.size > 1) {
                        SwipeHint(
                            text = "<",
                            onClick = { navigateGallery(-1) },
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp)
                        )
                        SwipeHint(
                            text = ">",
                            onClick = { navigateGallery(1) },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)
                        )
                    }

                    if (!controlsOpen) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(14.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x99000000))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .widthIn(max = 320.dp)
                        ) {
                            Text(
                                text = fileLabel.value,
                                color = Color(0xFFE0E0E0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2
                            )
                            Text(
                                text = statusText.value,
                                color = Color(0xFFAAAAAA),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }

                    if (!controlsOpen) {
                        Button(
                            onClick = {
                                haptic()
                                controlsOpen = true
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 18.dp)
                                .height(44.dp),
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xDDE63946),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Edit", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(controlsHeight)
                        .clipToBounds()
                        .graphicsLayer {
                            alpha = controlsAlpha
                        }
                ) {
                    ControlsSheetContent(
                        onClose = { controlsOpen = false },
                        onExport = {
                            controlsOpen = false
                            doExport(sendToCloud = false)
                        },
                        onExportToCloud = {
                            controlsOpen = false
                            doExport(sendToCloud = true)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun SwipeHint(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .size(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Color(0x66000000))
                .clickable {
                    haptic()
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color(0xBFFFFFFF),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun ControlsSheetContent(
        onClose: () -> Unit,
        onExport: () -> Unit,
        onExportToCloud: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .background(Color(0xF21C1C1C))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp, bottom = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Edit",
                    color = Color(0xFFD2D2D2),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Done",
                    color = Color(0xFFE63946),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            haptic()
                            onClose()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Text(
                text = fileLabel.value,
                color = Color(0xFF888888),
                fontSize = 12.sp,
                maxLines = 2,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            SliderControl(
                label = "WB Temp",
                value = sliderKelvin.intValue,
                onValueChange = { sliderKelvin.intValue = it },
                valueRange = 250f..1200f,
                displayValue = "${LeicaEngine.sliderToKelvin(sliderKelvin.intValue)}K"
            )

            SliderControl(
                label = "Tint",
                value = sliderTint.intValue,
                onValueChange = { sliderTint.intValue = it },
                valueRange = 0f..1000f,
                displayValue = String.format("%+.2f", LeicaEngine.sliderToTint(sliderTint.intValue))
            )

            SliderControl(
                label = "Exposure",
                value = sliderExp.intValue,
                onValueChange = { sliderExp.intValue = it },
                valueRange = 0f..1000f,
                displayValue = String.format("%+.2f EV", LeicaEngine.sliderToEV(sliderExp.intValue))
            )

            SliderControl(
                label = "WB Bias",
                value = sliderBias.intValue,
                onValueChange = { sliderBias.intValue = it },
                valueRange = 1f..20f,
                displayValue = "${sliderBias.intValue * 1000}K"
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        haptic()
                        resetSliders()
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color(0xFFD2D2D2)
                    )
                ) {
                    Text("Reset", fontSize = 14.sp)
                }

                Button(
                    onClick = {
                        haptic()
                        onExport()
                    },
                    enabled = isLoaded.value && !isProcessing.value,
                    modifier = Modifier.weight(2f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE63946),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF5A1A1F),
                        disabledContentColor = Color(0xFF888888)
                    )
                ) {
                    Text("Export", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                        onClick = {
                            haptic()
                            onExportToCloud()
                        },
                        enabled = isLoaded.value && !isProcessing.value,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2A6BBF),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF1A3A5C),
                            disabledContentColor = Color(0xFF888888)
                        )
                    ) {
                        Text("Export & Send to Cloud (root)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
        }
    }

    @Composable
    private fun SliderControl(
        label: String,
        value: Int,
        onValueChange: (Int) -> Unit,
        valueRange: ClosedFloatingPointRange<Float>,
        displayValue: String,
        enabled: Boolean = true
    ) {
        Column(modifier = Modifier.padding(bottom = 2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = if (enabled) Color(0xFFD2D2D2) else Color(0xFF666666),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = displayValue,
                    color = if (enabled) Color(0xFFAAAAAA) else Color(0xFF555555),
                    fontSize = 13.sp
                )
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = valueRange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFE63946),
                    activeTrackColor = Color(0xFFE63946),
                    inactiveTrackColor = Color(0xFF444444),
                    disabledThumbColor = Color(0xFF555555),
                    disabledActiveTrackColor = Color(0xFF555555),
                    disabledInactiveTrackColor = Color(0xFF333333)
                )
            )
        }
    }

    private fun resetSliders() {
        sliderTint.intValue = 500
        sliderExp.intValue = 500
        sliderKelvin.intValue = 650

        val state = fileState
        if (state != null && state.origColorTemp > 0) {
            var bias = (state.origColorTemp + 500) / 1000
            if (bias < 1) bias = 5
            if (bias > 20) bias = 20
            sliderBias.intValue = bias
        } else {
            sliderBias.intValue = 5
        }
    }

    private fun navigateGallery(delta: Int) {
        if (galleryUris.size <= 1 || isProcessing.value) return

        val nextIndex = (currentGalleryIndex.intValue + delta)
            .coerceIn(0, galleryUris.lastIndex)
        if (nextIndex == currentGalleryIndex.intValue) {
            haptic()
            return
        }

        haptic()
        currentGalleryIndex.intValue = nextIndex
        fileLabel.value = galleryNames.getOrElse(nextIndex) { "Loading..." }
        loadFile(galleryUris[nextIndex])
    }

    private fun loadFile(uri: Uri) {
        isProcessing.value = true
        isLoaded.value = false
        statusText.value = "Loading..."
        sourceBitmap.value?.recycle()
        sourceBitmap.value = null
        fileState?.sourceBitmap?.recycle()
        fileState?.sourceBitmap = null

        ioExecutor.execute {
            try {
                val data: ByteArray
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    data = inputStream.readBytes()
                } ?: throw Exception("Cannot open file")

                runOnUiThread { statusText.value = "Parsing container..." }

                val state = LeicaEngine.loadFile(data)
                fileState = state

                val previewBmp = if (state.previewSourcePixels != null) {
                    Bitmap.createBitmap(
                        state.previewSourcePixels, state.previewW, state.previewH,
                        Bitmap.Config.ARGB_8888
                    )
                } else null

                runOnUiThread {
                    if (!state.loaded) {
                        statusText.value = state.statusMsg
                        haptic()
                        return@runOnUiThread
                    }

                    fileLabel.value = state.originalFilename
                    isLoaded.value = true
                    isTele.value = state.isTele

                    var bias = (state.origColorTemp + 500) / 1000
                    if (bias < 1) bias = 5
                    if (bias > 20) bias = 20
                    sliderBias.intValue = bias

                    sourceBitmap.value = previewBmp

                    isProcessing.value = false

                    statusText.value = "Ready"

                    haptic()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.value = "ERROR: ${e.message}"
                    Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
                    haptic()
                    isProcessing.value = false
                }
            }
        }
    }

    private fun doExport(sendToCloud: Boolean = false) {
        val state = fileState ?: return
        if (!state.loaded || isProcessing.value) return
        isProcessing.value = true
        statusText.value = "Exporting..."

        val sk = sliderKelvin.intValue
        val st = sliderTint.intValue
        val se = sliderExp.intValue
        val sb = sliderBias.intValue

        ioExecutor.execute {
            try {
                runOnUiThread { statusText.value = "Applying WB & exposure..." }

                val result = LeicaEngine.doExport(state, sk, st, se, sb)

                val suffix = LeicaEngine.buildParamSuffix(sk, st, se, sb, state.origColorTemp)
                val origName = state.originalFilename
                val dot = origName.lastIndexOf('.')
                val preferredName = if (dot > 0)
                    origName.substring(0, dot) + suffix + origName.substring(dot)
                else
                    origName + suffix + ".jpg"
                val outName = uniqueCameraDisplayName(preferredName)

                runOnUiThread { statusText.value = "Saving..." }

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, outName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                }

                val outUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )

                if (outUri != null) {
                    contentResolver.openOutputStream(outUri)?.use { os: OutputStream ->
                        os.write(result)
                    }
                    runOnUiThread {
                        statusText.value = "Saved: $outName"
                        haptic()
                        isProcessing.value = false
                        if (sendToCloud) {
                            sendToCloud(outUri)
                        }
                    }
                } else {
                    runOnUiThread {
                        statusText.value = "ERROR: Cannot create output file!"
                        Toast.makeText(this, "Cannot create output file!", Toast.LENGTH_LONG).show()
                        haptic()
                        isProcessing.value = false
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.value = "ERROR: ${e.message}"
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    haptic()
                    isProcessing.value = false
                }
            }
        }
    }

    private fun uniqueCameraDisplayName(preferredName: String): String {
        if (!cameraDisplayNameExists(preferredName)) return preferredName

        val dot = preferredName.lastIndexOf('.')
        val base = if (dot > 0) preferredName.substring(0, dot) else preferredName
        val ext = if (dot > 0) preferredName.substring(dot) else ".jpg"

        var index = 2
        while (index < 1000) {
            val candidate = "${base}_u$index$ext"
            if (!cameraDisplayNameExists(candidate)) return candidate
            index++
        }

        return "${base}_u${System.currentTimeMillis()}$ext"
    }

    private fun cameraDisplayNameExists(name: String): Boolean {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(name, "DCIM/Camera/")

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            return cursor.moveToFirst()
        }

        return false
    }

    private fun sendToCloud(uri: Uri) {
        try {
            val intent = android.content.Intent()
            intent.component = android.content.ComponentName(
                "com.miui.mediaeditor",
                "com.miui.mediaeditor.legendary.LegendaryActivity"
            )
            intent.setDataAndType(uri, "image/jpeg")
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

            Thread {
                try {
                    Thread.sleep(1500)

                    val dm = resources.displayMetrics
                    val cx = dm.widthPixels / 2
                    val cy = (dm.heightPixels * 0.86).toInt()
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $cx $cy")).waitFor()

                    Thread.sleep(500)
                    val backIntent = android.content.Intent(this, MainActivity::class.java)
                    backIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(backIntent)
                } catch (_: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Auto-tap failed: root required", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open Leica processor: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun haptic() {
        val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
