package com.ruangkerja.companion.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.Workspace
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.launch

/** Tombol ikon header — POLOS (cuma ikon, tanpa kotak/border), biar Beranda gak numpuk
 *  "kotak" di mana-mana. Nama & signature dipertahankan sama biar 4 pemanggilnya di bawah
 *  (Menu/Cari/Grid/Pengaturan) gak perlu diubah satu-satu — cuma isinya yang berubah. */
@Composable
private fun HeaderIconBtn(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconActionBtn(icon, contentDescription, 20.dp, onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceListScreen(vm: AppViewModel, onOpen: (String) -> Unit, onOpenChapter: (String) -> Unit, onSettings: () -> Unit, onNotifications: () -> Unit, onOpenDrawer: () -> Unit) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val isReady by vm.isReady.collectAsStateWithLifecycle()
    val layoutGrid by vm.layoutGrid.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    // [FIX, 22 Sep] showCreateDraft dipindah ke AppViewModel — dipicu dari 2 tempat beda file:
    // FAB di sini, DAN side drawer yang udah pindah ke RuangKerjaApp/MainActivity.kt (lihat
    // komentar di sana buat alasan drawer-nya pindah). State-nya harus dibagi lewat ViewModel,
    // gak bisa lagi local `remember` punya layar ini.
    // [DIPINDAH, 19 Okt] draftEditTarget (overlay-nya) udah pindah RENDER-nya ke
    // RuangKerjaApp/MainActivity.kt — lihat catatan relokasi di bawah, dekat bekas lokasinya.
    val showCreateDraft by vm.showCreateDraft.collectAsStateWithLifecycle()
    var fabMenuOpen by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf<Workspace?>(null) }
    var renameTarget by remember { mutableStateOf<Workspace?>(null) }
    // [FIX, 11 Okt — audit] Calendar.getInstance() (ikut timezone DEVICE) diganti
    // DateUtils.currentZoned() (userZoneId terpusat, lihat util/DateUtils.kt) — pelanggaran
    // prinsip Date & Time Standardization yang kelewat pas migrasi 9 file 26-27 Sep (baris ini
    // baru ada di file ini belakangan, abis split WorkspaceListScreen 28 Sep). import java.util.*
    // yang jadi nggak kepake sama sekali di file ini ikut dibuang. Perilaku berubah dikit DENGAN
    // SENGAJA: salam sekarang tetap ngikutin WIB walau timezone device beda, sesuai keputusan
    // "zona waktu aplikasi" di dokumen audit 11 Okt — remember{} tetap sekali-hitung per
    // komposisi, TIDAK ditambah mekanisme refresh baru (scope perbaikan ini cuma ganti sumber jam).
    val hour = remember { DateUtils.currentZoned().hour }
    val greeting = when { hour in 4..10 -> "Selamat pagi"; hour in 11..14 -> "Selamat siang"; hour in 15..17 -> "Selamat sore"; else -> "Selamat malam" }
    // [FIX, 22 Sep] Side drawer Beranda ("ala chat AI") DIPINDAH ke RuangKerjaApp
    // (MainActivity.kt), ngebungkus Scaffold PALING LUAR di sana (termasuk bottom nav bar-nya)
    // — bukan di sini lagi. Alasannya: dibungkus di LAYAR INI doang bikin drawer cuma nutupin
    // konten NavHost, sedangkan bottom nav bar (slot Scaffold luar yang terpisah) gak ikut
    // ketutup — dulu ditambal dengan nyembunyiin bottom bar manual pas drawer kebuka, tapi itu
    // JUSTRU bikin Scaffold luar resize mendadak (isi kelihatan "digeser ke bawah", animasinya
    // nabrak). Trigger-nya (ikon Menu di header bawah) tetap di sini — cuma buka drawer-nya
    // sekarang lewat callback onOpenDrawer dari RuangKerjaApp, bukan drawerState lokal lagi.
    //
    // FAB Beranda dibikin lebih kecil (SmallFloatingActionButton) & lebih rendah (bottom=16dp,
    // sedikit di bawah punya bab/materi yang 20dp) — dipindah dari slot Scaffold ke overlay
    // manual biar ukurannya nggak dipaksa jadi ukuran FAB standar 56dp.
    Scaffold(
        containerColor = AppBg,
        topBar = {
            Column(Modifier.fillMaxWidth().background(AppBg).padding(top = cappedStatusBarTopPadding() + 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = 6.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderIconBtn(Icons.Default.Menu, "Menu", onOpenDrawer)
                    Column(Modifier.weight(1f)) {
                        Text(greeting, color = AppTextMuted, fontSize = 12.sp)
                        Text("Ruang Kerja", color = AppText, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, modifier = Modifier.padding(top = 1.dp))
                    }
                    HeaderIconBtn(Icons.Default.Search, "Cari") { showSearch = true }
                    HeaderIconBtn(if (layoutGrid) Icons.Default.ViewList else Icons.Default.GridView, if (layoutGrid) "Tampilan Daftar" else "Tampilan Grid") { vm.toggleLayoutGrid() }
                    HeaderIconBtn(Icons.Default.Settings, "Pengaturan", onSettings)
                }
                HorizontalDivider(color = AppBorder)
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                // ── Kartu ringkasan (Ruang Kerja/Aktif/Selesai) dipindah KELUAR dari
                // LazyColumn/LazyVerticalGrid — sebelumnya jadi baris pertama LazyColumn doang,
                // jadi ikut ke-scroll ilang DAN gak pernah muncul di mode grid sama sekali.
                // Sekarang statis di atas, dipakai bareng dua mode. ──
                if (isReady && workspaces.isNotEmpty()) {
                    // [DIHAPUS] 3 pil "Ruang Kerja/Aktif/Selesai" — dobel sama tab Progres (yang
                    // malah lebih detail) dan gak actionable buat layar pilih-Ruang-Kerja ini,
                    // plus jadi elemen paling "berkotak" yang tersisa padahal arah Beranda udah
                    // dikurangin kotaknya dari beberapa ronde sebelumnya. Banner overdue TETAP
                    // ada karena itu genuinely actionable, beda kelas sama statistik vanity.
                    val overdueTasks = tasks.count { it.status != 2 && DateUtils.isDeadlineOverdue(it.deadline) }
                    if (overdueTasks > 0) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                                .clip(RoundedCornerShape(12.dp)).background(Danger.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Icon(Icons.Outlined.WarningAmber, null, tint = Danger, modifier = Modifier.size(17.dp))
                            Text(
                                if (overdueTasks == 1) "1 tugas telah melewati deadline" else "$overdueTasks tugas telah melewati deadline",
                                color = Danger, fontSize = 12.5.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (!isReady) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppAccent)
                        }
                    } else if (workspaces.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                            EmptyView(
                                Icons.Outlined.FolderOpen,
                                "Belum ada ruang kerja",
                                "Ruang kerja membantumu mengelompokkan bab, materi, dan progres belajar per topik atau mata kuliah.",
                                actionLabel = "Buat Ruang Kerja",
                                actionIcon = Icons.Default.Add,
                                onAction = { showCreate = true }
                            )
                        }
                    } else if (layoutGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(Spacing.lg, Spacing.sm, Spacing.lg, 96.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            items(workspaces, key = { it.id }) { ws ->
                                WorkspaceCardGrid(ws, vm, onOpen, onRename = { renameTarget = ws }, onDelete = { confirmDel = ws })
                            }
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(Spacing.lg, Spacing.sm, Spacing.lg, 96.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            itemsIndexed(workspaces, key = { _, it -> it.id }) { idx, ws ->
                                EntranceItem(idx) { WorkspaceCard(ws, vm, onOpen, onRename = { renameTarget = ws }, onDelete = { confirmDel = ws }) }
                            }
                        }
                    }
                }
            }
            // FAB dibikin IDENTIK persis sama semua layar (Beranda/bab/materi) — komponen sama
            // (FloatingActionButton standar 56dp), shape sama, ukuran ikon sama, padding sama
            // persis (bottom=20dp, end=16dp). Kalau beda-beda dikit aja, pas transisi geser
            // antar halaman FAB-nya kelihatan "loncat" karena dia ikut kebawa animasi slide
            // layar — bukan animasi khusus yang dipasang ke FAB itu sendiri. Sekarang buka
            // menu 2 pilihan (Draft/Ruang Kerja) dulu — tampilan FAB-nya sendiri TETAP sama.
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 16.dp)) {
                FloatingActionButton(
                    onClick = { fabMenuOpen = true },
                    containerColor = AppAccent,
                    contentColor   = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp)
                ) { Icon(Icons.Default.Add, "Baru", Modifier.size(22.dp)) }
            }
            // [FIX, 22 Sep — masukan William] Menu 2-pilihan (Draft/Ruang Kerja) tadinya
            // DropdownMenu polos (bubble kecil nempel FAB) — sekarang ModalBottomSheet dengan
            // judul+deskripsi per opsi, niru gambar referensi yang dikasih William ("Mulai
            // sesuatu" ala Drive). Ditaruh sebagai overlay TERPISAH dari Box FAB di atas (bukan
            // anchored kayak DropdownMenu), soalnya ModalBottomSheet emang bukan dropdown — dia
            // nempatin dirinya sendiri relatif ke layar, bukan ke composable pemicunya.
            if (fabMenuOpen) {
                CreateMenuSheet(
                    onDismiss = { fabMenuOpen = false },
                    onCreateWorkspace = { showCreate = true },
                    onCreateDraft = { vm.setShowCreateDraft(true) }
                )
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AppDialog("Ruang Kerja Baru", { showCreate = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column { EyebrowLabel("Nama", modifier = Modifier.padding(bottom = 6.dp)); AppInputFlat(name, { name = it }, "cth: Kuliah Semester 5") }
                Column { EyebrowLabel("Deskripsi (Opsional)", modifier = Modifier.padding(bottom = 6.dp)); AppInputFlat(desc, { desc = it }, "Topik atau tujuan belajar...", singleLine = false, minLines = 2) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 4.dp)) {
                    GhostBtn("Batal", { showCreate = false }, Modifier.weight(1f))
                    PrimaryBtn("Buat", { if (name.isNotBlank()) { vm.addWorkspace(name.trim(), desc.trim()); showCreate = false } }, Modifier.weight(2f), enabled = name.isNotBlank())
                }
            }
        }
    }

    // ── Draft — dulu dialog kecil (AppInput berkotak, minLines=5, tombol Simpan manual).
    // Sekarang overlay LAYAR PENUH tanpa kotak/border (mirip rasa nulis di notebook beneran),
    // autosave berjalan sendiri (gak perlu tombol Simpan) — lihat DraftEditorOverlay di bawah.
    // showCreateDraft sebagai TRIGGER tetap sama persis kayak sebelumnya, cuma yang
    // di-render pas aktif yang berubah.
    if (showCreateDraft) {
        DraftEditorOverlay(vm = vm, initial = null, onDismiss = { vm.setShowCreateDraft(false) })
    }

    // [DIPINDAH, 19 Okt — masukan William: "untuk munculin pop up nya nggak usah di tutup
    // halaman notifikasi biar aja tetep di dalam sana, ribet kalau harus buka tutup lagi"]
    // draftEditTarget (overlay "Lihat"), draftMatch (popup saran — dulu di sini, dengan
    // Ingatkan-nanti/Lihat/Pindahkan/Batal), DAN chapterPicker (lanjutan draftMatch, muncul
    // kalau tombol "Pindahkan" ditekan & workspace tujuan punya >1 bab) semuanya dipindah ke
    // RuangKerjaApp (MainActivity.kt) — dirender DI ATAS NavHost di sana sekarang, bukan di
    // sini lagi. Alasannya: trigger-nya (SuggestionRow di NotificationCenterScreen) ada di
    // LAYAR LAIN (bukan Beranda), jadi kalau popup-nya masih dirender di sini, dia gak akan
    // pernah kebaca sama sekali selama user masih di halaman Notifikasi — NavHost cuma
    // nge-compose SATU rute aktif pada satu waktu. State & isi dialognya tetap sama persis di
    // AppViewModel, cuma lokasi RENDER-nya yang pindah. newChapterPrompt (lanjutan chapterPicker,
    // kalau user pilih "+ Buat Bab Baru") ikut pindah juga — lihat catatan di bekas lokasinya,
    // tepat di bawah manualMoveDraft ini.
    //
    // [BARU, 18 Okt — masukan William: "siapkan juga pemindahan custom jadi user bisa pindahin
    // itu sesuka hati"] Beda dari draftMatch (itu OTOMATIS dari kecocokan kata kunci, sekarang
    // dirender di MainActivity.kt — lihat catatan relokasi di atas) — ini user MANUAL milih
    // Ruang Kerja tujuan sendiri, dari mana aja (lihat vm.startManualMove, dipicu dari menu
    // titik tiga draft di drawer). Abis pilih Ruang Kerja, nyambung ke chapterPicker yang SAMA
    // persis (ikut dirender di MainActivity.kt juga sekarang) — gak bikin alur/dialog Bab
    // terpisah.
    val manualMoveDraft by vm.manualMoveWorkspacePicker.collectAsStateWithLifecycle()
    manualMoveDraft?.let { draft ->
        AppDialog("Pindahkan ke Ruang Kerja", { vm.cancelManualMove() }) {
            if (workspaces.isEmpty()) {
                Text("Belum ada Ruang Kerja. Buat Ruang Kerja terlebih dahulu.", color = AppTextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    workspaces.forEach { ws ->
                        GhostBtn(ws.name.ifBlank { "(Tanpa Nama)" }, { vm.pickWorkspaceForManualMove(ws) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    // [DIPINDAH, 19 Okt] newChapterPrompt ("Muncul abis user mutusin draft perlu Bab BARU, baik
    // dari alur otomatis draftMatch maupun pemindahan manual di atas — field nama udah keisi
    // SARAN dari generateChapterName(), util/ChapterNamer.kt") ikut pindah ke MainActivity.kt
    // bareng draftMatch/chapterPicker — alasan sama persis: dia lanjutan chapterPicker ("+ Buat
    // Bab Baru"), jadi harus kebaca juga walau trigger-nya dari luar Beranda.

    renameTarget?.let { ws ->
        var name by remember(ws.id) { mutableStateOf(ws.name) }
        AppDialog("Ubah Nama", { renameTarget = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppInputFlat(name, { name = it }, "Nama ruang kerja")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 4.dp)) {
                    GhostBtn("Batal", { renameTarget = null }, Modifier.weight(1f))
                    PrimaryBtn("Simpan", {
                        if (name.isNotBlank()) { vm.updateWorkspace(ws.copy(name = name.trim())); renameTarget = null }
                    }, Modifier.weight(2f), enabled = name.isNotBlank())
                }
            }
        }
    }

    confirmDel?.let { ws ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            containerColor   = AppBgCard,
            shape            = RoundedCornerShape(Radius.dialog),
            icon             = { Icon(Icons.Default.DeleteForever, null, tint = Danger) },
            title            = { Text("Hapus Ruang Kerja?", color = AppText, fontWeight = FontWeight.Bold) },
            text             = { Text("\"${ws.name}\" beserta semua bab dan materi di dalamnya akan dihapus permanen.", color = AppTextMuted, lineHeight = 20.sp) },
            confirmButton    = { TextButton(onClick = { vm.deleteWorkspace(ws.id); confirmDel = null }) { Text("Hapus", color = Danger, fontWeight = FontWeight.Bold) } },
            dismissButton    = { TextButton(onClick = { confirmDel = null }) { Text("Batal", color = AppTextMuted) } }
        )
    }

    if (showSearch) {
        SearchOverlay(vm, onOpenWorkspace = onOpen, onOpenChapter = onOpenChapter, onDismiss = { showSearch = false })
    }
}

// ── Menu FAB Beranda — ModalBottomSheet "Mulai sesuatu" niru gambar referensi William
// (pola ala Drive: judul+subjudul di atas, opsi dikelompokkan dalam satu kartu dengan
// ikon+judul+deskripsi+chevron per baris, Batal berdiri sendiri di bawah kartu). Pola
// hide()-dulu-baru-dismiss sama persis kayak CardOverflowMenu di CommonComponents.kt —
// biar animasi nutupnya mulus, bukan ujug-ujug ilang. ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateMenuSheet(onDismiss: () -> Unit, onCreateWorkspace: () -> Unit, onCreateDraft: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun closeSheet() { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppBgCard,
        contentColor = AppText,
        dragHandle = { BottomSheetDefaults.DragHandle(color = AppBorder) }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.lg).padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(Icons.Outlined.EditNote, null, tint = AppText, modifier = Modifier.size(22.dp))
                Column {
                    Text("Mulai sesuatu", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Pilih apa yang ingin Anda buat.", color = AppTextMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(AppBgMuted)) {
                CreateMenuRow(Icons.Outlined.FolderOpen, "Workspace", "Buat ruang kerja baru untuk materi belajar Anda.") {
                    onCreateWorkspace(); closeSheet()
                }
                HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
                CreateMenuRow(Icons.Outlined.Description, "Draft", "Catatan cepat, kapan saja.") {
                    onCreateDraft(); closeSheet()
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.card))
                    .clickable { closeSheet() }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).border(1.dp, AppTextMuted, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, null, tint = AppTextMuted, modifier = Modifier.size(13.dp))
                }
                Text("Batal", color = AppText, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun CreateMenuRow(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Icon(icon, null, tint = AppAccent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AppText, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = AppTextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = AppTextDim, modifier = Modifier.size(18.dp))
    }
}

// ── Aksi menu kartu Ruang Kerja. [DIGABUNG, audit duplikasi 2 Okt] Dulu daftar aksi (Ubah nama, Duplikat,
// Ekspor, Laporan PDF, Pin, Hapus) PLUS fungsi ekspornya ditulis dua kali persis sama di WorkspaceCardGrid
// dan WorkspaceCard (tampilan grid vs daftar) — menambah satu menu di salah satunya membuat dua tampilan
// itu beda diam-diam. Sekarang satu sumber; kedua tampilan cuma beda tata letak. ──
@Composable
private fun workspaceMenuActions(ws: Workspace, vm: AppViewModel, onRename: () -> Unit, onDelete: () -> Unit): List<CardMenuAction> {
    val date = remember(ws.createdAt) { DateUtils.formatDisplayDate(ws.createdAt) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return listOf(
        CardMenuAction("Dibuat $date", Icons.Default.CalendarToday, enabled = false, onClick = {}),
        CardMenuAction("Ubah nama", Icons.Outlined.EditNote, onClick = onRename),
        CardMenuAction("Duplikat", Icons.Outlined.ContentCopy, onClick = { vm.duplicateWorkspace(ws) }),
        CardMenuAction("Ekspor", Icons.Outlined.IosShare, onClick = {
            launchExportShare(scope, ctx, "application/json", "Ekspor Ruang Kerja", "Gagal ekspor ruang kerja.") { vm.buildWorkspaceExportFile(ws.id) }
        }),
        CardMenuAction("Buat Laporan (PDF)", Icons.Outlined.PictureAsPdf, onClick = {
            scope.launch {
                try {
                    val uri = vm.buildWorkspaceReportPdf(ws.id)
                    if (uri == null) { Toast.makeText(ctx, "Gagal membuat laporan (perlu Android 10 ke atas).", Toast.LENGTH_LONG).show(); return@launch }
                    Toast.makeText(ctx, "Laporan tersimpan di Documents/Ruang Kerja", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { Toast.makeText(ctx, "Gagal membuat laporan: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }),
        CardMenuAction(if (ws.pinned) "Lepas Pin" else "Pin ke atas", Icons.Outlined.PushPin, onClick = { vm.toggleWorkspacePin(ws) }),
        CardMenuAction("Hapus", Icons.Outlined.DeleteOutline, danger = true, onClick = onDelete)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceCardGrid(ws: Workspace, vm: AppViewModel, onOpen: (String) -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }


    // TANPA fill/background (bukan AppCard), tapi dikasih outline 1dp tipis biar tetep
    // ada batas antar tile — kalau polos tanpa apa-apa, di grid malah lebih parah nggak
    // kelihatan batasnya dibanding mode list (nggak ada garis pemisah natural kayak divider).
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .border(1.dp, AppBorder.copy(alpha = 0.5f), RoundedCornerShape(Radius.card))
            .combinedClickable(onClick = { onOpen(ws.id) }, onLongClick = { menuExpanded = true })
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.FolderOpen, null, tint = AppTextMuted, modifier = Modifier.size(30.dp))
            CardOverflowMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                actions = workspaceMenuActions(ws, vm, onRename, onDelete)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (ws.pinned) Icon(Icons.Outlined.PushPin, null, tint = AppAccent, modifier = Modifier.size(10.dp))
            Text(ws.name, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (ws.description.isNotBlank()) {
            Text(ws.description, color = AppTextMuted, fontSize = 11.5.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceCard(ws: Workspace, vm: AppViewModel, onOpen: (String) -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }


    // TANPA AppCard, tapi dikasih garis pembatas tipis di bawah tiap baris — pas dicoba
    // ternyata tanpa itu beneran nggak kerasa ada batas antar item (apalagi di mode terang,
    // AppBgCard cuma beda dikit banget dari AppBg).
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { onOpen(ws.id) }, onLongClick = { menuExpanded = true })
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.FolderOpen, null, tint = AppTextMuted, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (ws.pinned) Icon(Icons.Outlined.PushPin, null, tint = AppAccent, modifier = Modifier.size(12.dp))
                    Text(ws.name, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                }
                if (ws.description.isNotBlank()) Text(ws.description, color = AppTextMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
            }
            CardOverflowMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                actions = workspaceMenuActions(ws, vm, onRename, onDelete)
            )
        }
        HorizontalDivider(color = AppBorder.copy(alpha = 0.5f))
    }
}
