package com.unleicawesome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "gallery_prefs"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val MIN_GRID_COLUMNS = 2
        private const val MAX_GRID_COLUMNS = 6
    }

    data class GalleryItem(
        val uri: Uri,
        val filename: String,
        val size: Long,
        val supportPath: String = "",
        val rawWidth: Int = 0,
        val rawHeight: Int = 0,
        val declaredStride: Int = 0,
        val effectiveStride: Int = 0,
        val rawLength: Int = 0,
        val rawOrient: Int = 0,
        var thumbnail: Bitmap? = null
    )

    private val items = mutableStateListOf<GalleryItem>()
    private val statusText = mutableStateOf("Scanning...")
    private val isScanning = mutableStateOf(false)
    private val selectedItem = mutableStateOf<GalleryItem?>(null)
    private val gridColumns = mutableIntStateOf(3)
    private val thumbExecutor = Executors.newFixedThreadPool(4)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        haptic()
        if (granted) {
            scanForLeicaPhotos()
        } else {
            statusText.value = "Permission denied: cannot access photos"
            Toast.makeText(this, "Permission denied: cannot access photos", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gridColumns.intValue = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_GRID_COLUMNS, 3)
            .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)

        setContent {
            UnleicawesomeTheme {
                GalleryScreen()
            }
        }

        checkPermissionAndScan()
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
            == PackageManager.PERMISSION_GRANTED
        ) {
            scanForLeicaPhotos()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbExecutor.shutdownNow()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GalleryScreen() {
        Scaffold(
            containerColor = Color(0xFF111111)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(innerPadding)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Unleicawesome",
                        color = Color(0xFFE63946),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " ${appVersionName()}",
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusText.value,
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            haptic()
                            setGridColumns(gridColumns.intValue + 1)
                        },
                        enabled = gridColumns.intValue < MAX_GRID_COLUMNS,
                        modifier = Modifier.height(34.dp).widthIn(min = 36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFF888888),
                            disabledContentColor = Color(0xFF444444)
                        )
                    ) {
                        Text("-", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = {
                            haptic()
                            setGridColumns(gridColumns.intValue - 1)
                        },
                        enabled = gridColumns.intValue > MIN_GRID_COLUMNS,
                        modifier = Modifier.height(34.dp).widthIn(min = 36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFF888888),
                            disabledContentColor = Color(0xFF444444)
                        )
                    ) {
                        Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = {
                            haptic()
                            scanForLeicaPhotos(force = true)
                        },
                        enabled = !isScanning.value,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFFE63946),
                            disabledContentColor = Color(0xFF555555)
                        )
                    ) {
                        Text(
                            text = if (isScanning.value) "Scanning" else "Refresh",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns.intValue),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp)
                        .pointerInput(Unit) {
                            var zoomTotal = 1f
                            detectTransformGestures { _, _, zoom, _ ->
                                zoomTotal *= zoom
                                if (zoomTotal > 1.2f) {
                                    setGridColumns(gridColumns.intValue - 1)
                                    zoomTotal = 1f
                                } else if (zoomTotal < 0.83f) {
                                    setGridColumns(gridColumns.intValue + 1)
                                    zoomTotal = 1f
                                }
                            }
                        },
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        GalleryItemCard(item)
                    }
                }
            }

            selectedItem.value?.let { item ->
                ModalBottomSheet(
                    onDismissRequest = { selectedItem.value = null },
                    containerColor = Color(0xFF1A1A1A),
                    contentColor = Color(0xFFD2D2D2)
                ) {
                    FileDetailSheet(item)
                }
            }
        }
    }

    @Composable
    private fun GalleryItemCard(item: GalleryItem) {
        val thumbState = remember(item.uri) { mutableStateOf(item.thumbnail) }

        LaunchedEffect(item.uri) {
            if (thumbState.value == null) {
                withContext(Dispatchers.IO) {
                    try {
                        val thumb = contentResolver.loadThumbnail(
                            item.uri, Size(320, 320), CancellationSignal()
                        )
                        item.thumbnail = thumb
                        thumbState.value = thumb
                    } catch (_: Exception) {}
                }
            }
        }

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A1A))
                .clickable {
                    haptic()
                    openEditor(item)
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(Color(0xFF1A1A1A))
            ) {
                val bmp = thumbState.value
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = item.filename,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xAA000000))
                        .clickable {
                            haptic()
                            selectedItem.value = item
                        }
                ) {
                    Text(
                        text = "i",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            Text(
                text = item.filename,
                color = Color(0xFF888888),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 4.dp, vertical = 3.dp)
            )
        }
    }

    @Composable
    private fun FileDetailSheet(item: GalleryItem) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = item.filename,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            DetailRow("Support", item.supportPath.ifEmpty { "Unknown" })
            DetailRow("File size", formatBytes(item.size))
            DetailRow("RAW size", if (item.rawLength > 0) formatBytes(item.rawLength.toLong()) else "Unknown")
            DetailRow(
                "RAW dimensions",
                if (item.rawWidth > 0 && item.rawHeight > 0) "${item.rawWidth} x ${item.rawHeight}" else "Unknown"
            )
            DetailRow("Declared stride", item.declaredStride.takeIf { it > 0 }?.toString() ?: "Unknown")
            DetailRow("Effective stride", item.effectiveStride.takeIf { it > 0 }?.toString() ?: "Unknown")
            DetailRow("RAW orientation", item.rawOrient.toString())
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    haptic()
                    selectedItem.value = null
                    openEditor(item)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE63946),
                    contentColor = Color.White
                )
            ) {
                Text("Open Editor", fontWeight = FontWeight.Bold)
            }
        }
    }

    private fun openEditor(item: GalleryItem) {
        val index = items.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        val intent = Intent(this@MainActivity, EditorActivity::class.java)
        intent.data = item.uri
        intent.putExtra("filename", item.filename)
        intent.putStringArrayListExtra("gallery_uris", ArrayList(items.map { it.uri.toString() }))
        intent.putStringArrayListExtra("gallery_names", ArrayList(items.map { it.filename }))
        intent.putExtra("gallery_index", index)
        startActivity(intent)
    }

    private fun setGridColumns(value: Int) {
        val next = value.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        if (next == gridColumns.intValue) return

        gridColumns.intValue = next
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_GRID_COLUMNS, next)
            .apply()
    }

    @Composable
    private fun DetailRow(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Color(0xFF888888), fontSize = 13.sp)
            Text(
                text = value,
                color = Color(0xFFD2D2D2),
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }

    private fun checkPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            scanForLeicaPhotos()
        }
    }

    private fun scanForLeicaPhotos(force: Boolean = false) {
        if (isScanning.value && !force) return
        isScanning.value = true
        statusText.value = "Scanning for Leica photos..."

        Thread {
            try {

            val candidates = mutableListOf<GalleryItem>()

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val selection = "${MediaStore.Images.Media.SIZE} > ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?"
            val selectionArgs = arrayOf("10000000", "image/jpeg")
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val relPath = cursor.getString(pathCol) ?: continue
                    if (!relPath.startsWith("DCIM/Camera")) continue

                    val name = cursor.getString(nameCol) ?: continue

                    if (name.startsWith(".trashed")) continue
                    if ("_wb" in name || "_ev" in name || "_t+" in name ||
                        "_t-" in name || "_bias" in name || "_edited" in name) continue

                    val id = cursor.getLong(idCol)
                    val size = cursor.getLong(sizeCol)

                    candidates.add(
                        GalleryItem(
                            uri = Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                            ),
                            filename = name,
                            size = size
                        )
                    )
                }
            }

            runOnUiThread {
                statusText.value = "Checking ${candidates.size} files..."
            }

            val verified = mutableListOf<GalleryItem>()

            for (candidate in candidates) {
                try {
                    contentResolver.openInputStream(candidate.uri)?.use { inputStream ->
                        val metadata = readJpegMetadata(inputStream)
                        if (metadata.size > 100) {
                            val hdr = String(metadata, Charsets.ISO_8859_1)
                            val hasSupportedRaw = LeicaEngine.hasSupportedRawHeader(metadata, metadata.size)
                            val isProcessed = "legend_" in hdr
                            if (hasSupportedRaw && !isProcessed) {
                                val info = LeicaEngine.parseContainer(metadata)
                                val effectiveStride = LeicaEngine.effectivePackedRawStride(
                                    info.rawWidth,
                                    info.rawStride,
                                    info.rawLength
                                )
                                verified.add(
                                    candidate.copy(
                                        supportPath = "MIPI RAW10",
                                        rawWidth = info.rawWidth,
                                        rawHeight = info.rawHeight,
                                        declaredStride = info.rawStride,
                                        effectiveStride = effectiveStride,
                                        rawLength = info.rawLength,
                                        rawOrient = info.rawOrient
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {

                }
            }

            runOnUiThread {
                items.clear()
                items.addAll(verified)
                statusText.value = if (verified.isEmpty())
                    "No unprocessed Leica shots found"
                else
                    "Found ${verified.size} Leica shots"
                isScanning.value = false

                if (verified.isEmpty()) {
                    Toast.makeText(this, "No Leica shots found", Toast.LENGTH_SHORT).show()
                }
                haptic()
            }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.value = "Scan failed: ${e.message}"
                    isScanning.value = false
                    Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                    haptic()
                }
            }
        }.start()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    private fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun readJpegMetadata(inputStream: InputStream): ByteArray {
        fun readByte(): Int = inputStream.read()

        val out = ByteArrayOutputStream()
        val soi0 = readByte()
        val soi1 = readByte()
        if (soi0 != 0xFF || soi1 != 0xD8) return ByteArray(0)
        out.write(soi0)
        out.write(soi1)

        while (true) {
            var prefix = readByte()
            if (prefix < 0) break
            if (prefix != 0xFF) break

            var marker = readByte()
            while (marker == 0xFF) marker = readByte()
            if (marker < 0) break

            out.write(0xFF)
            out.write(marker)

            if (marker == 0xDA || marker == 0xD9) break
            if (marker == 0x01 || marker in 0xD0..0xD7) continue

            val lenHi = readByte()
            val lenLo = readByte()
            if (lenHi < 0 || lenLo < 0) break
            out.write(lenHi)
            out.write(lenLo)

            val segmentLength = (lenHi shl 8) or lenLo
            if (segmentLength < 2) break

            var remaining = segmentLength - 2
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val read = inputStream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) return out.toByteArray()
                out.write(buffer, 0, read)
                remaining -= read
            }
        }

        return out.toByteArray()
    }

    private fun haptic() {
        val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
