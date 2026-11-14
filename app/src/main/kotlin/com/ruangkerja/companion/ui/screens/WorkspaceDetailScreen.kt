package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.Chapter
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceDetailScreen(wsId: String, vm: AppViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val layoutGrid by vm.layoutGrid.collectAsStateWithLifecycle()
    val chapterGenLoading by vm.chapterGenLoading.collectAsStateWithLifecycle()
    val chapterGenState by vm.chapterGenState.collectAsStateWithLifecycle()
    val ws = remember(workspaces, wsId) { workspaces.find { it.id == wsId } }
    var showCreate by remember { mutableStateOf(false) }
    var showBulkAdd by remember { mutableStateOf(false) }
    var showAiGenerate by remember { mutableStateOf(false) }
    var showScanChooser by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf<Chapter?>(null) }
    var editChapter by remember { mutableStateOf<Chapter?>(null) }
    var moveChapterTarget by remember { mutableStateOf<Chapter?>(null) }

    // ── Scan Daftar Isi: infrastruktur OCR/PDF sama persis kayak yang dipakai TabMateri,
    // bedanya hasil teksnya dikirim ke vm.generateChaptersFromText (bukan vm.addMaterial).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ocrLoading by remember { mutableStateOf(false) }
    var ocrProgress by remember { mutableStateOf("") }
    var ocrError by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    fun runScanOcr(uri: android.net.Uri, isPdf: Boolean) {
        ocrLoading = true; ocrError = null
        scope.launch {
            try {
                val text = if (isPdf) {
                    com.ruangkerja.companion.ocr.PdfTextExtractor.extractText(context, uri) { p, t -> ocrProgress = "Membaca halaman $p dari $t..." }
                } else {
                    ocrProgress = "Membaca teks..."
                    com.ruangkerja.companion.ocr.OcrHelper.recognizeFromUri(context, uri)
                }
                ocrLoading = false
                if (text.isBlank()) { ocrError = "Tidak ada teks terdeteksi dari pemindaian ini."; return@launch }
                vm.generateChaptersFromText(wsId, text)
            } catch (e: Exception) { ocrError = e.message; ocrLoading = false }
        }
    }

    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) runScanOcr(uri, false) }
    val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runScanOcr(uri, true) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok -> if (ok) pendingCameraUri?.let { runScanOcr(it, false) } }
    val camPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val u = com.ruangkerja.companion.ocr.createCaptureUri(context); pendingCameraUri = u; cameraLauncher.launch(u) }
        else ocrError = "Izin kamera diperlukan."
    }

    // FAB dipindah dari slot `floatingActionButton` bawaan Scaffold (posisi default Material3)
    // ke overlay manual dengan padding PERSIS sama kayak SpeedDialFab yang dipakai di halaman
    // Materi (bottom=20dp, end=16dp) — biar tingginya konsisten pas pindah bab↔materi.
    // FAB sendiri sekarang SpeedDialFab (4 opsi) bukan FAB tunggal — lihat catatan di bawah.
    Scaffold(
        containerColor = AppBg,
        topBar = {
            AppHeader(title = ws?.name ?: "Ruang Kerja", subtitle = "${chapters.size} bab", onBack = onBack) {
                IconButton(onClick = { vm.toggleLayoutGrid() }) {
                    Icon(
                        if (layoutGrid) Icons.Default.ViewList else Icons.Default.GridView,
                        if (layoutGrid) "Tampilan Daftar" else "Tampilan Grid",
                        tint = AppTextMuted, modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (chapters.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                    EmptyView(
                        Icons.Outlined.MenuBook,
                        "Belum ada bab",
                        "Bab membagi materi dalam ruang kerja ini jadi bagian-bagian yang lebih mudah dipelajari.",
                        actionLabel = "Tambah Bab",
                        actionIcon = Icons.Default.Add,
                        onAction = { showCreate = true }
                    )
                }
            } else if (layoutGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.lg, Spacing.md, Spacing.lg, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(chapters, key = { it.id }) { ch ->
                        ChapterCardGrid(ch, chapters.indexOf(ch) + 1, vm, onClick = { onOpen(ch.id) }, onEdit = { editChapter = ch }, onMove = { moveChapterTarget = ch }, onDelete = { confirmDel = ch })
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.lg, Spacing.md, Spacing.lg, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    itemsIndexed(chapters, key = { _, it -> it.id }) { idx, ch ->
                        EntranceItem(idx) { ChapterRow(ch, idx + 1, vm, onClick = { onOpen(ch.id) }, onEdit = { editChapter = ch }, onMove = { moveChapterTarget = ch }, onDelete = { confirmDel = ch }) }
                    }
                }
            }

            // Status pembuatan bab otomatis (scan OCR / AI dari topik) — overlay ngambang di atas,
            // lepas dari state kosong/grid/daftar karena bisa kepicu dari kondisi mana pun.
            if (ocrLoading || chapterGenLoading || ocrError != null || chapterGenState.error != null) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(Spacing.lg)) {
                    Column(
                        Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        if (ocrLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = AppAccent)
                                Text(ocrProgress.ifBlank { "Memproses scan..." }, color = AppTextMuted, fontSize = 12.sp)
                            }
                        }
                        if (chapterGenLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = AppAccent)
                                Text("AI menyusun bab...", color = AppTextMuted, fontSize = 12.sp)
                            }
                        }
                        if (ocrError != null) Text(ocrError!!, color = Danger, fontSize = 12.sp)
                        if (chapterGenState.error != null) Text(chapterGenState.error!!, color = Danger, fontSize = 12.sp)
                    }
                }
            }

            SpeedDialFab(
                items = listOf(
                    SpeedDialItem("Tambah Bab", Icons.Default.Add) { showCreate = true },
                    SpeedDialItem("Tambah Banyak Bab", Icons.Default.FormatListNumbered) { showBulkAdd = true },
                    SpeedDialItem("Buat Otomatis (AI)", Icons.Default.AutoAwesome) { showAiGenerate = true },
                    SpeedDialItem("Scan Daftar Isi", Icons.Outlined.CloudUpload) { showScanChooser = true }
                )
            )
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AppDialog("Bab Baru", { showCreate = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column { EyebrowLabel("Nama Bab", modifier = Modifier.padding(bottom = 6.dp)); AppInput(name, { name = it }, "cth: Sistem Basis Data") }
                Column { EyebrowLabel("Deskripsi (Opsional)", modifier = Modifier.padding(bottom = 6.dp)); AppInput(desc, { desc = it }, "Topik yang dipelajari...", singleLine = false, minLines = 2) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 4.dp)) {
                    GhostBtn("Batal", { showCreate = false }, Modifier.weight(1f))
                    PrimaryBtn("Tambah", { if (name.isNotBlank()) { vm.addChapter(wsId, name.trim(), desc.trim()); showCreate = false } }, Modifier.weight(2f), enabled = name.isNotBlank())
                }
            }
        }
    }

    if (showBulkAdd) {
        var pasteText by remember { mutableStateOf("") }
        var quickN by remember { mutableStateOf<Int?>(null) }
        AppDialog("Tambah Banyak Bab", { showBulkAdd = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    EyebrowLabel("Tempel Daftar Judul", modifier = Modifier.padding(bottom = 6.dp))
                    AppInput(pasteText, { pasteText = it; if (it.isNotBlank()) quickN = null }, "Satu judul per baris...\nBab 1\nBab 2\nBab 3", singleLine = false, minLines = 4)
                }
                Text("— atau buat otomatis bernomor —", color = AppTextDim, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 20).forEach { n ->
                        FilterChip(
                            selected = quickN == n,
                            onClick = { quickN = if (quickN == n) null else n; if (quickN != null) pasteText = "" },
                            label = { Text("$n Bab", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppAccent.copy(alpha = 0.15f), selectedLabelColor = AppAccent, containerColor = AppBgMuted, labelColor = AppTextMuted),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = quickN == n, borderColor = AppBorder, selectedBorderColor = AppAccent)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 4.dp)) {
                    GhostBtn("Batal", { showBulkAdd = false }, Modifier.weight(1f))
                    PrimaryBtn("Tambah", {
                        val fromPaste = pasteText.lines().map { it.trim() }.filter { it.isNotBlank() }
                        val items = if (fromPaste.isNotEmpty()) fromPaste.map { it to "" }
                                    else if (quickN != null) (1..quickN!!).map { "Bab $it" to "" }
                                    else emptyList()
                        if (items.isNotEmpty()) { vm.addChaptersBulk(wsId, items); showBulkAdd = false }
                    }, Modifier.weight(2f), enabled = pasteText.isNotBlank() || quickN != null)
                }
            }
        }
    }

    if (showAiGenerate) {
        var topic by remember { mutableStateOf("") }
        var quickCount by remember { mutableStateOf<Int?>(null) }
        AppDialog("Buat Otomatis (AI)", { showAiGenerate = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column { EyebrowLabel("Topik", modifier = Modifier.padding(bottom = 6.dp)); AppInput(topic, { topic = it }, "cth: Kalkulus 2, Sejarah Indonesia Kolonial") }
                Column {
                    EyebrowLabel("Jumlah Bab", modifier = Modifier.padding(bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "Otomatis", 5 to "5 Bab", 10 to "10 Bab", 15 to "15 Bab").forEach { (v, l) ->
                            FilterChip(
                                selected = quickCount == v,
                                onClick = { quickCount = v },
                                label = { Text(l, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppAccent.copy(alpha = 0.15f), selectedLabelColor = AppAccent, containerColor = AppBgMuted, labelColor = AppTextMuted),
                                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = quickCount == v, borderColor = AppBorder, selectedBorderColor = AppAccent)
                            )
                        }
                    }
                }
                if (chapterGenLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = AppAccent)
                        Text("AI menyusun bab...", color = AppTextMuted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 4.dp)) {
                    GhostBtn("Batal", { showAiGenerate = false }, Modifier.weight(1f))
                    PrimaryBtn("Buat", {
                        if (topic.isNotBlank() && !chapterGenLoading) {
                            vm.generateChaptersFromTopic(wsId, topic.trim(), quickCount) { showAiGenerate = false }
                        }
                    }, Modifier.weight(2f), enabled = topic.isNotBlank() && !chapterGenLoading, icon = Icons.Default.AutoAwesome, loading = chapterGenLoading)
                }
            }
        }
    }

    if (showScanChooser) {
        AppDialog("Scan Daftar Isi", { showScanChooser = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ambil teks dari foto atau PDF daftar isi/silabus — AI akan susun jadi bab otomatis.", color = AppTextMuted, fontSize = 12.5.sp, lineHeight = 18.sp)
                GhostBtn("Foto dari Galeri", {
                    showScanChooser = false
                    photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, Modifier.fillMaxWidth(), icon = Icons.Default.Image)
                GhostBtn("Ambil Foto", {
                    showScanChooser = false
                    val perm = android.Manifest.permission.CAMERA
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) { val u = com.ruangkerja.companion.ocr.createCaptureUri(context); pendingCameraUri = u; cameraLauncher.launch(u) } else camPermLauncher.launch(perm)
                }, Modifier.fillMaxWidth(), icon = Icons.Default.PhotoCamera)
                GhostBtn("Impor PDF", {
                    showScanChooser = false
                    pdfPicker.launch(arrayOf("application/pdf"))
                }, Modifier.fillMaxWidth(), icon = Icons.Default.PictureAsPdf)
            }
        }
    }

    editChapter?.let { ch ->
        var name by remember(ch.id) { mutableStateOf(ch.name) }
        var desc by remember(ch.id) { mutableStateOf(ch.description) }
        AppDialog("Edit Bab", { editChapter = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column { EyebrowLabel("Nama Bab", modifier = Modifier.padding(bottom = 6.dp)); AppInput(name, { name = it }, "cth: Sistem Basis Data") }
                Column { EyebrowLabel("Deskripsi (Opsional)", modifier = Modifier.padding(bottom = 6.dp)); AppInput(desc, { desc = it }, "Topik yang dipelajari...", singleLine = false, minLines = 2) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 4.dp)) {
                    GhostBtn("Batal", { editChapter = null }, Modifier.weight(1f))
                    PrimaryBtn("Simpan", {
                        if (name.isNotBlank()) { vm.updateChapterInfo(ch.copy(name = name.trim(), description = desc.trim())); editChapter = null }
                    }, Modifier.weight(2f), enabled = name.isNotBlank())
                }
            }
        }
    }

    moveChapterTarget?.let { ch ->
        val others = workspaces.filter { it.id != wsId }
        AppDialog("Pindahkan ke Ruang Kerja Lain", { moveChapterTarget = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (others.isEmpty()) {
                    Text("Belum ada ruang kerja lain untuk dipindahkan.", color = AppTextMuted, fontSize = 13.sp)
                } else {
                    others.forEach { target ->
                        GhostBtn(target.name, {
                            vm.moveChapter(ch, target.id)
                            moveChapterTarget = null
                        }, Modifier.fillMaxWidth(), icon = Icons.Outlined.FolderOpen)
                    }
                }
                GhostBtn("Batal", { moveChapterTarget = null }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }

    confirmDel?.let { ch ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            containerColor   = AppBgCard,
            shape            = RoundedCornerShape(Radius.dialog),
            icon             = { Icon(Icons.Default.DeleteForever, null, tint = Danger) },
            title            = { Text("Hapus Bab?", color = AppText, fontWeight = FontWeight.Bold) },
            text             = { Text("\"${ch.name}\" beserta semua materi, ringkasan, dan mind map di dalamnya akan dihapus permanen.", color = AppTextMuted, lineHeight = 20.sp) },
            confirmButton    = { TextButton(onClick = { vm.deleteChapter(ch.id); confirmDel = null }) { Text("Hapus", color = Danger, fontWeight = FontWeight.Bold) } },
            dismissButton    = { TextButton(onClick = { confirmDel = null }) { Text("Batal", color = AppTextMuted) } }
        )
    }
}

// ── Aksi menu kartu Bab. [DIGABUNG, audit duplikasi 2 Okt] Dulu daftar aksi + fungsi ekspor bab ditulis
// dua kali persis sama di ChapterCardGrid dan ChapterRow (grid vs daftar). Sekarang satu sumber. ──
@Composable
private fun chapterMenuActions(ch: Chapter, vm: AppViewModel, onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit): List<CardMenuAction> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return listOf(
        CardMenuAction("Edit", Icons.Outlined.EditNote, onClick = onEdit),
        CardMenuAction("Duplikat", Icons.Outlined.ContentCopy, onClick = { vm.duplicateChapter(ch) }),
        CardMenuAction("Pindahkan", Icons.Outlined.DriveFileMove, onClick = onMove),
        CardMenuAction("Ekspor Bab", Icons.Outlined.IosShare, onClick = {
            launchExportShare(scope, ctx, "application/json", "Ekspor Bab", "Gagal ekspor bab.") { vm.buildChapterExportFile(ch.id) }
        }),
        CardMenuAction("Hapus", Icons.Outlined.DeleteOutline, danger = true, onClick = onDelete)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterCardGrid(ch: Chapter, idx: Int, vm: AppViewModel, onClick: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }


    // Samain gaya sama kartu Ruang Kerja di Beranda: TANPA fill/background, tapi dikasih
    // outline 1dp tipis biar tetep ada batas antar tile bab. Angka bab TETAP tanpa kotak
    // latar (teks polos). Ukuran font disamain persis sama ChapterRow (list) di bawah
    // biar "ukurannya konsisten" walau tampilannya grid vs list.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .border(1.dp, AppBorder.copy(alpha = 0.5f), RoundedCornerShape(Radius.card))
            .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text("$idx", color = AppTextMuted, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            CardOverflowMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                actions = chapterMenuActions(ch, vm, onEdit, onMove, onDelete)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(ch.name, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (ch.description.isNotBlank()) {
            Text(ch.description, color = AppTextMuted, fontSize = 11.5.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        if (ch.summary != null || ch.notes.isNotBlank()) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ch.summary != null) Pill("Ringkasan", Success, Icons.Outlined.CheckCircleOutline)
                if (ch.notes.isNotBlank()) Pill("Catatan", AppAccent, Icons.Outlined.EditNote)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(ch: Chapter, idx: Int, vm: AppViewModel, onClick: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }


    // TANPA AppCard & TANPA kotak latar di angka, tapi dikasih garis pembatas tipis di
    // bawah tiap baris (sama kayak ChapterRow-nya Ruang Kerja) — polos total ternyata bikin
    // batas antar bab nggak kerasa, apalagi di mode terang. Panah ">" di ujung kanan DIHAPUS
    // — titik tiga (CardOverflowMenu) udah cukup jadi satu-satunya affordance.
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                Text("$idx", color = AppTextMuted, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(ch.name, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1)
                if (ch.description.isNotBlank()) Text(ch.description, color = AppTextMuted, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                if (ch.summary != null || ch.notes.isNotBlank()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ch.summary != null) Pill("Ringkasan", Success, Icons.Outlined.CheckCircleOutline)
                        if (ch.notes.isNotBlank()) Pill("Catatan", AppAccent, Icons.Outlined.EditNote)
                    }
                }
            }
            CardOverflowMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                actions = chapterMenuActions(ch, vm, onEdit, onMove, onDelete)
            )
        }
        HorizontalDivider(color = AppBorder.copy(alpha = 0.5f))
    }
}
