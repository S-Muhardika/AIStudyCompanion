package com.ruangkerja.companion.ui.screens

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.*
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.util.markdownToPlainPreview
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── TAB: MATERI ───────────────────────────────────────────
// [FIX, 12 Nov — Tahap 1 arsitektur, audit "markdown mentah kelihatan"] SEBELUMNYA cuma strip
// "###"/"##"/"#" di depan + "**" kalau kebetulan nutup satu baris penuh — bold di TENGAH baris,
// italic, list marker ("- "), dkk tetap lolos mentah (mis. baris pertama "**Catatan** penting"
// bakal nyisain "** penting" nongol di judul). Ganti ke markdownToPlainPreview() yang nutup
// semua kasus itu (lihat util/MarkdownPreview.kt) — perilaku lain (ambil baris pertama non-
// blank, batas 60 karakter, fallback "Tanpa judul") TETAP SAMA.
private fun materialDisplayTitle(mat: Material): String {
    if (mat.title.isNotBlank()) return mat.title
    val firstLine = mat.content.lineSequence().firstOrNull { it.isNotBlank() } ?: return "Tanpa judul"
    return markdownToPlainPreview(firstLine).take(60).ifBlank { "Tanpa judul" }
}

// Foto kamera modern gampang 4000x3000px+ (puluhan MB kalau di-decode mentah-mentah).
// Sebelum ini, gambar di-copy APA ADANYA tanpa dikecilin sama sekali — resiko OutOfMemory
// pas ditampilkan dan boros storage. Sekarang: turunin resolusi ke maks ~1280px sisi
// terpanjang + kompres JPEG, plus betulin rotasi EXIF (soalnya decode-ulang-lalu-compress
// nggak otomatis bawa metadata rotasi asli, jadi kalau di-skip foto bisa keliatan miring).
private suspend fun copyImageToLocal(context: android.content.Context, uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
    try {
        val dir = java.io.File(context.filesDir, "materi_images").apply { mkdirs() }
        val outFile = java.io.File(dir, "${java.util.UUID.randomUUID()}.jpg")

        val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, boundsOpts) }
        val origW = boundsOpts.outWidth; val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return@withContext null

        val maxDim = 1280
        var sampleSize = 1
        while (origW / (sampleSize * 2) >= maxDim || origH / (sampleSize * 2) >= maxDim) sampleSize *= 2

        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return@withContext null

        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.media.ExifInterface(stream).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) { android.media.ExifInterface.ORIENTATION_NORMAL }

        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }

        outFile.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out) }
        bitmap.recycle()
        outFile.absolutePath
    } catch (e: Exception) { null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabMateri(ch: Chapter, mats: List<Material>, vm: AppViewModel, onOpenEditor: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var ocrLoading by remember { mutableStateOf(false) }
    var ocrProgress by remember { mutableStateOf("") }
    var ocrError by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var urlLoading by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    // [BARU] Laporan William: back HP pas mode pilih (selectionMode) malah pindah ke layar
    // sebelumnya (keluar Bab), bukan batalin pilihan dulu — padahal tombol "Batal" di toolbar
    // atas udah ngelakuin itu, cuma back HP yang belum ditangani sama sekali (NavHost yang
    // nangkep duluan, langsung pop). BackHandler cuma aktif (`enabled`) pas selectionMode
    // nyala, jadi dia yang mengonsumsi back SEKALI itu; begitu selectionMode mati lagi,
    // `enabled = false` bikin event back jatuh tembus ke NavHost seperti biasa (nggak perlu
    // kode tambahan buat "kembalikan" perilaku default).
    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    if (showUrlDialog) {
        AppDialog("Impor dari URL", { showUrlDialog = false; urlInput = "" }) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                EyebrowLabel("URL Halaman Web", modifier = Modifier.padding(bottom = 4.dp))
                AppInput(urlInput, { urlInput = it }, "https://elearning.kampus.ac.id/materi/...")
                Text("Konten teks akan diekstraksi otomatis dan disimpan sebagai materi.", color = AppTextDim, fontSize = 12.sp, lineHeight = 17.sp)
                if (urlLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = AppAccent)
                        Text("Mengambil konten...", color = AppTextMuted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    GhostBtn("Batal", { showUrlDialog = false; urlInput = "" }, Modifier.weight(1f))
                    PrimaryBtn("Impor", {
                        if (urlInput.isNotBlank() && !urlLoading) {
                            urlLoading = true
                            scope.launch {
                                try {
                                    vm.importFromUrl(ch.id, urlInput.trim())
                                    showUrlDialog = false; urlInput = ""
                                } catch (e: Exception) { ocrError = "Gagal: ${e.message}"; showUrlDialog = false }
                                finally { urlLoading = false }
                            }
                        }
                    }, Modifier.weight(2f), enabled = urlInput.isNotBlank() && !urlLoading, icon = Icons.Default.Link)
                }
            }
        }
    }

    fun runOcr(uri: android.net.Uri, isPdf: Boolean) {
        ocrLoading = true; ocrError = null
        scope.launch {
            try {
                val text = if (isPdf) com.ruangkerja.companion.ocr.PdfTextExtractor.extractText(context, uri) { p, t -> ocrProgress = "Halaman $p dari $t..." }
                else { ocrProgress = "Membaca teks..."; com.ruangkerja.companion.ocr.OcrHelper.recognizeFromUri(context, uri) }
                if (text.isBlank()) ocrError = "Tidak ada teks terdeteksi." else vm.addMaterial(ch.id, text)
            } catch (e: Exception) { ocrError = e.message }
            finally { ocrLoading = false; ocrProgress = "" }
        }
    }

    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) runOcr(uri, false) }
    val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runOcr(uri, true) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok -> if (ok) pendingCameraUri?.let { runOcr(it, false) } }
    val camPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val u = com.ruangkerja.companion.ocr.createCaptureUri(context); pendingCameraUri = u; cameraLauncher.launch(u) }
        else ocrError = "Izin kamera diperlukan."
    }

    fun openManualEditor() {
        vm.openEditor(java.util.UUID.randomUUID().toString(), ch.id, "", "", System.currentTimeMillis())
        onOpenEditor()
    }

    val speedDialItems = listOf(
        SpeedDialItem("Tulis Materi", Icons.Default.EditNote) { openManualEditor() },
        SpeedDialItem("Impor dari URL", Icons.Default.Link) { showUrlDialog = true },
        SpeedDialItem("Foto dari Galeri", Icons.Default.Image) {
            photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        SpeedDialItem("Ambil Foto", Icons.Default.PhotoCamera) {
            val perm = android.Manifest.permission.CAMERA
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) { val u = com.ruangkerja.companion.ocr.createCaptureUri(context); pendingCameraUri = u; cameraLauncher.launch(u) } else camPermLauncher.launch(perm)
        },
        SpeedDialItem("Impor PDF", Icons.Default.PictureAsPdf) { pdfPicker.launch(arrayOf("application/pdf")) }
    )

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = Spacing.lg, end = Spacing.lg, bottom = 96.dp,
                top = if (selectionMode) Spacing.md + 64.dp else Spacing.md
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // Indikator loading/error OCR ini tetap ada meski card "Teks Materi" (paste-text)
            // udah dihapus — soalnya dipakai bareng buat impor PDF/foto lewat SpeedDialFab juga,
            // bukan cuma buat paste-text manual yang sekarang dihilangkan.
            if (ocrLoading || ocrError != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        if (ocrLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = AppAccent)
                                Text(ocrProgress, color = AppTextMuted, fontSize = 12.sp)
                            }
                        }
                        if (ocrError != null) Text(ocrError!!, color = Danger, fontSize = 12.sp)
                    }
                }
            }
            if (mats.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        EmptyView(
                            Icons.Outlined.Description,
                            "Belum ada materi di bab ini",
                            "Tulis manual, atau impor dari URL, foto, maupun PDF lewat tombol + di kanan bawah.",
                            actionLabel = "Tulis Materi",
                            actionIcon = Icons.Default.EditNote,
                            onAction = { openManualEditor() }
                        )
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        EyebrowLabel("Materi Tersimpan (${mats.size})")
                        if (!selectionMode) {
                            Text("Tahan untuk memilih", color = AppTextDim, fontSize = 11.sp, modifier = Modifier.padding(end = 2.dp))
                        }
                    }
                }
                items(mats, key = { it.id }) { mat ->
                    val displayTitle = remember(mat.id, mat.title, mat.content) { materialDisplayTitle(mat) }
                    val identity = remember(mat.id) { identityColorFor(mat.id) }
                    val isSelected = mat.id in selectedIds

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(AppBgMuted, RoundedCornerShape(12.dp))
                            .then(if (isSelected) Modifier.border(2.dp, AppAccent, RoundedCornerShape(12.dp)) else Modifier)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        selectedIds = if (isSelected) selectedIds - mat.id else selectedIds + mat.id
                                        if (selectedIds.isEmpty()) selectionMode = false
                                    } else {
                                        vm.openEditor(mat.id, ch.id, mat.title, mat.content, mat.createdAt)
                                        onOpenEditor()
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) { selectionMode = true; selectedIds = setOf(mat.id) }
                                }
                            )
                    ) {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(identity, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)))
                        Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                                    if (mat.pinned) Icon(Icons.Outlined.PushPin, null, tint = AppAccent, modifier = Modifier.size(11.dp).padding(top = 2.dp))
                                    Text(displayTitle, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (selectionMode) {
                                    Icon(
                                        if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                        null, tint = if (isSelected) AppAccent else AppTextDim,
                                        modifier = Modifier.size(18.dp).padding(start = 4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // [FIX, 12 Nov — audit sama kayak materialDisplayTitle() di atas]
                            // SEBELUMNYA mat.content ditampilin mentah (cuma newline->spasi),
                            // jadi kalau materinya mulai dengan "# Judul"/"**bold**"/list/dst,
                            // syntax-nya nongol apa adanya di preview kartu ini.
                            Text(
                                markdownToPlainPreview(mat.content).take(90), color = AppTextDim, fontSize = 11.5.sp,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(DateUtils.relativeTime(mat.updatedAt), color = AppTextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (selectionMode) {
            Surface(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = AppBgCard, shadowElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectionMode = false; selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, "Batal", tint = AppText)
                        }
                        Text("${selectedIds.size} dipilih", color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            val selected = mats.filter { it.id in selectedIds }
                            val targetPinned = selected.any { !it.pinned }
                            selected.forEach { if (it.pinned != targetPinned) vm.togglePinMaterial(it) }
                            selectionMode = false; selectedIds = emptySet()
                        }) { Icon(Icons.Outlined.PushPin, "Sematkan/Lepas Sematan", tint = AppTextDim, modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = {
                            val selected = mats.filter { it.id in selectedIds }
                            scope.launch {
                                try {
                                    val uris = withContext(Dispatchers.IO) {
                                        selected.map { mat ->
                                            val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                            val safe = materialDisplayTitle(mat).replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).ifBlank { "materi" }
                                            val file = java.io.File(dir, "${safe}_${System.currentTimeMillis()}_${mat.id.take(6)}.md")
                                            file.writeText(mat.content)
                                            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        }
                                    }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "text/markdown"
                                        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "Ekspor Materi"))
                                    selectionMode = false; selectedIds = emptySet()
                                } catch (e: Exception) { Toast.makeText(context, "Gagal ekspor: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                        }) { Icon(Icons.Outlined.Description, "Ekspor Markdown", tint = AppTextDim, modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = {
                            val selected = mats.filter { it.id in selectedIds }
                            val combined = selected.joinToString("\n\n---\n\n") { "${materialDisplayTitle(it)}\n\n${it.content}" }
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, combined)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Bagikan Materi"))
                            selectionMode = false; selectedIds = emptySet()
                        }) { Icon(Icons.Outlined.IosShare, "Bagikan", tint = AppTextDim, modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { showBulkDeleteConfirm = true }) {
                            Icon(Icons.Outlined.DeleteOutline, "Hapus", tint = Danger, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        if (!ocrLoading && !selectionMode) SpeedDialFab(items = speedDialItems, mainIcon = Icons.Default.Add)
    }

    if (showBulkDeleteConfirm) {
        AppDialog("Hapus ${selectedIds.size} materi?", { showBulkDeleteConfirm = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("Materi yang dihapus tidak bisa dikembalikan.", color = AppTextDim, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    GhostBtn("Batal", { showBulkDeleteConfirm = false }, Modifier.weight(1f))
                    GhostBtn(
                        "Hapus",
                        {
                            selectedIds.forEach { id -> vm.deleteMaterial(id) }
                            showBulkDeleteConfirm = false; selectionMode = false; selectedIds = emptySet()
                        },
                        Modifier.weight(1f), color = Danger, icon = Icons.Outlined.DeleteOutline
                    )
                }
            }
        }
    }
}

/** Destinasi navigasi buat Editor Materi — dulu Dialog, sekarang layar biasa (lihat catatan
 *  panjang di AppViewModel.EditorTarget soal kenapa). Baca target dari state VM yang di-set
 *  lewat vm.openEditor(...); kalau null (kebuka lewat cara lain / state ke-reset), mundur aja. */
@Composable
fun NotebookEditorScreen(vm: AppViewModel, onBack: () -> Unit) {
    val target by vm.editorTarget.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(target) { if (target == null) onBack() }

    target?.let { t ->
        NotebookEditor(
            initialTitle = t.title,
            initialContent = t.content,
            onSave = { title, content -> if (content.isNotBlank() || title.isNotBlank()) vm.updateMaterial(t.materialId, t.chapterId, content, t.createdAt, title) },
            onClose = { vm.closeEditor(); onBack() },
            onPickImage = { uri -> copyImageToLocal(context, uri) },
            onAIAction = { action, text -> vm.runTextAction(action, text) },
            modifier = Modifier.fillMaxSize()
        )
    }
}
