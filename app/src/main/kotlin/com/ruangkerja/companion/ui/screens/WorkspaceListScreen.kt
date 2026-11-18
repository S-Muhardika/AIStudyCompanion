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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import com.ruangkerja.companion.data.db.*
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
    // [BARU, 16 Nov — redesain Beranda: Progres ring, Akses Cepat, "Lanjutkan", Jadwal Hari Ini]
    // Lihat AppViewModel.kt buat sumber scheduleEntries/lastChapterCard. Dasbor ini SENGAJA
    // nambah balik elemen "berkotak" yang SEBELUMNYA sengaja dihapus dari Beranda (lihat catatan
    // "[DIHAPUS] 3 pil..." di bawah) — keputusan EKSPLISIT William (desain referensi + minta
    // fitur Todo/Jadwal beneran dibikin, bukan cuma kosmetik), BUKAN kelupaan soal keputusan lama.
    val scheduleEntries by vm.scheduleEntries.collectAsStateWithLifecycle()
    val lastChapterCard by vm.lastChapterCard.collectAsStateWithLifecycle()
    val doneTasks = tasks.count { it.status == 2 }
    val activeTasks = tasks.count { it.status != 2 }
    val completionRatio = if (tasks.isEmpty()) 0f else doneTasks.toFloat() / tasks.size
    // DateUtils.currentZoned()/.todayDateString() — pola & sumber jam SAMA PERSIS kayak `hour`
    // di bawah (userZoneId terpusat), BUKAN Calendar.getInstance()/java.util.Date langsung.
    // [HOTFIX, 17 Nov — William lapor force close setelah v2.2.42] Dibungkus try/catch — baris
    // `.dayOfWeek.value` ini jalan TANPA SYARAT tiap Beranda dirender (Beranda = layar pembuka
    // app), jadi kalau ini yang nge-throw di device William (compile sukses TIDAK berarti aman
    // di runtime — saya gak punya salinan DateUtils.kt buat mastiin), efeknya PERSIS app force
    // close tiap dibuka tanpa pesan error apa pun, karena exception di tengah composition Compose
    // nutup activity-nya langsung. Kalau gagal, todayDow jatuh ke 0 (gak pernah cocok 1-7),
    // "Jadwal Hari Ini" cuma gak nampilin ScheduleEntry — gagal AMAN, bukan ngehang seluruh app.
    val todayDow = remember { try { DateUtils.currentZoned().dayOfWeek.value } catch (e: Exception) { 0 } }
    val todayStr = remember { try { DateUtils.todayDateString() } catch (e: Exception) { "" } }
    val todayScheduleEntries = remember(scheduleEntries, todayDow) {
        scheduleEntries.filter { todayDow in it.daysOfWeek }.sortedBy { scheduleEntryMinutes(it.startTime) }
    }
    // [CATATAN JUJUR, lihat CHANGELOG] "Tugas hari ini" di sini dicocokkan ke
    // DateUtils.todayDateString() APA ADANYA (string sama persis), BUKAN parsing tanggal manual —
    // kalau format Task.deadline ternyata beda dari todayDateString(), daftar ini akan tampil
    // kosong (gagal AMAN, bukan nampilin data salah/crash), bukan ketebak-tebak formatnya di sini.
    val tasksToday = remember(tasks, todayStr) { tasks.filter { it.status != 2 && it.deadline == todayStr } }
    var showCreate by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ScheduleEntry?>(null) }
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
                        // [UBAH, 16 Nov] Dulu Box statis isinya EmptyView doang. Sekarang
                        // LazyColumn — dasbor (Progres/Akses Cepat/Lanjutkan/Jadwal) HARUS tetap
                        // kelihatan walau belum ada Ruang Kerja sama sekali (Jadwal/Progres independen
                        // dari Ruang Kerja), EmptyView jadi item PALING BAWAH, bukan satu-satunya isi.
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(Spacing.lg, Spacing.sm, Spacing.lg, 96.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
                        ) {
                            item {
                                BerandaDashboard(
                                    doneTasks, activeTasks, completionRatio, lastChapterCard, tasks,
                                    todayScheduleEntries, tasksToday, onOpenChapter,
                                    onSearch = { showSearch = true },
                                    onAddSchedule = { editingSchedule = null; showScheduleDialog = true },
                                    onEditSchedule = { editingSchedule = it; showScheduleDialog = true }
                                )
                            }
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = Spacing.md), contentAlignment = Alignment.Center) {
                                    EmptyView(
                                        Icons.Outlined.FolderOpen,
                                        "Belum ada ruang kerja",
                                        "Ruang kerja membantumu mengelompokkan bab dan materi per topik atau mata kuliah.",
                                        actionLabel = "Buat Ruang Kerja",
                                        actionIcon = Icons.Default.Add,
                                        onAction = { showCreate = true }
                                    )
                                }
                            }
                        }
                    } else if (layoutGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(Spacing.lg, Spacing.sm, Spacing.lg, 96.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                BerandaDashboard(
                                    doneTasks, activeTasks, completionRatio, lastChapterCard, tasks,
                                    todayScheduleEntries, tasksToday, onOpenChapter,
                                    onSearch = { showSearch = true },
                                    onAddSchedule = { editingSchedule = null; showScheduleDialog = true },
                                    onEditSchedule = { editingSchedule = it; showScheduleDialog = true }
                                )
                            }
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
                            item {
                                BerandaDashboard(
                                    doneTasks, activeTasks, completionRatio, lastChapterCard, tasks,
                                    todayScheduleEntries, tasksToday, onOpenChapter,
                                    onSearch = { showSearch = true },
                                    onAddSchedule = { editingSchedule = null; showScheduleDialog = true },
                                    onEditSchedule = { editingSchedule = it; showScheduleDialog = true }
                                )
                                Spacer(Modifier.height(Spacing.sm))
                            }
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

    // [BARU, 16 Nov] Dialog tambah/ubah ScheduleEntry — dipicu dari tombol "+" ATAU tap salah
    // satu baris jadwal di section "Jadwal Hari Ini" (lihat BerandaDashboard/JadwalHariIniSection
    // di bawah file ini).
    if (showScheduleDialog) {
        ScheduleEntryDialog(
            initial = editingSchedule,
            onDismiss = { showScheduleDialog = false },
            onSave = { title, start, end, loc, days ->
                val existing = editingSchedule
                if (existing == null) vm.addScheduleEntry(title, start, end, loc, days)
                else vm.updateScheduleEntry(existing.copy(title = title.trim(), startTime = start.trim(), endTime = end.trim(), location = loc.trim(), daysOfWeek = days))
                showScheduleDialog = false
            },
            onDelete = editingSchedule?.let { entry -> { vm.deleteScheduleEntry(entry.id); showScheduleDialog = false } }
        )
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
                CreateMenuRow(Icons.Outlined.FolderOpen, "Workspace", "Buat ruang kerja baru untuk materi dan bab Anda.") {
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

// ═══════════════════════════════════════════════════════════════════════════════════════════
// [BARU, 16 Nov — redesain Beranda] Dasbor baru: Progres (reuse CompletionRing dari
// ProgressScreen.kt — diubah `internal`, lihat catatan di sana), Akses Cepat (Workspace→buka
// pencarian, AI→lanjut ke bab terakhir dibuka), "Lanjutkan" (bab terakhir dibuka), dan "Jadwal
// Hari Ini" (ScheduleEntry BARU + Task yang deadline-nya hari ini, digabung satu daftar).
//
// Desain referensi William (board "RKR Ruang Kerja Ringkas") dipakai buat STRUKTUR dasbornya
// (ring+akses cepat+kartu lanjut+jadwal), TAPI semua teks berbau "belajar" diganti jadi netral
// sesuai instruksi eksplisit William ("RKR bukan aplikasi belajar") — makanya judul-judul di
// bawah cuma "Progres"/"Lanjutkan", bukan "Progres Belajar"/"Lanjutkan Belajar" kayak referensi
// aslinya. Kalender & Catatan di desain referensi SENGAJA tidak diikutin (dikonfirmasi William:
// "itu cuma kesalahan desain", Catatan udah ada di dalam Bab/Workspace).
// ═══════════════════════════════════════════════════════════════════════════════════════════

@Composable
private fun BerandaDashboard(
    doneTasks: Int,
    activeTasks: Int,
    completionRatio: Float,
    lastChapterCard: Pair<Chapter, Workspace>?,
    tasks: List<Task>,
    todayScheduleEntries: List<ScheduleEntry>,
    tasksToday: List<Task>,
    onOpenChapter: (String) -> Unit,
    onSearch: () -> Unit,
    onAddSchedule: () -> Unit,
    onEditSchedule: (ScheduleEntry) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        ProgresRingRow(doneTasks, activeTasks, completionRatio)
        AksesCepatRow(
            onWorkspace = onSearch,
            onAi = { lastChapterCard?.let { (ch, _) -> onOpenChapter(ch.id) } },
            aiEnabled = lastChapterCard != null
        )
        lastChapterCard?.let { (ch, ws) -> LanjutkanCard(ch, ws, tasks) { onOpenChapter(ch.id) } }
        JadwalHariIniSection(todayScheduleEntries, tasksToday, onAddSchedule, onEditSchedule)
    }
}

/** Kartu ringkasan Progres — ring-nya REUSE CompletionRing dari ProgressScreen.kt (satu
 *  package yang sama, lihat catatan `internal` di sana), BUKAN implementasi Canvas baru yang
 *  kebetulan mirip. Isinya sengaja identik sama hero row tab Progres (done/aktif), cuma versi
 *  ringkas buat Beranda. */
@Composable
private fun ProgresRingRow(done: Int, active: Int, ratio: Float) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        EyebrowLabel("Progres")
        Row(
            Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            CompletionRing(ratio)
            Column {
                Text("Selesai", color = AppTextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("$done Tugas", color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Text("Aktif", color = AppTextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("$active Tugas", color = AppAccent, fontSize = 15.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun AksesCepatRow(onWorkspace: () -> Unit, onAi: () -> Unit, aiEnabled: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        EyebrowLabel("Akses Cepat")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            // "Workspace" buka overlay pencarian yang SUDAH ADA (SearchOverlay, lihat showSearch
            // di WorkspaceListScreen) — dipilih dibanding "scroll ke daftar di bawah" (yang di
            // Beranda sendiri ini keliatan aneh, karena Beranda ITU SENDIRI daftar Workspace) —
            // cara tercepat betulan "akses cepat" ke sebuah Ruang Kerja tertentu adalah nyari.
            AksesCepatItem(Icons.Outlined.FolderOpen, "Workspace", Modifier.weight(1f), onClick = onWorkspace)
            // "AI" lanjut ke LAYAR BAB terakhir dibuka (lewat onOpenChapter yang sama kayak
            // dipakai navigasi bab lainnya) — mendarat di tab DEFAULT bab itu (bukan dipaksa ke
            // tab Diskusi spesifik), lihat catatan jujur di CHANGELOG soal kenapa.
            AksesCepatItem(Icons.Outlined.Forum, "AI", Modifier.weight(1f), enabled = aiEnabled, onClick = onAi)
        }
    }
}

@Composable
private fun AksesCepatItem(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(AppBgCard)
            .border(1.dp, AppBorder.copy(alpha = if (enabled) 0.5f else 0.25f), RoundedCornerShape(Radius.card))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (enabled) AppAccent else AppTextDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) AppText else AppTextDim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Kartu "Lanjutkan" — bab terakhir dibuka. Bar progres di sini JUJUR representasinya: rasio
 *  tugas selesai di RUANG KERJA bab ini berada (bukan progres per-bab, yang memang belum ada
 *  datanya sama sekali di model data — lihat catatan audit soal ini). Label teksnya sengaja
 *  eksplisit "...di ruang kerja ini" biar gak kesan presisi per-bab yang sebenarnya gak ada. */
@Composable
private fun LanjutkanCard(chapter: Chapter, workspace: Workspace, tasks: List<Task>, onClick: () -> Unit) {
    val wsTasks = remember(tasks, workspace.id) { tasks.filter { it.workspaceId == workspace.id } }
    val wsDone = wsTasks.count { it.status == 2 }
    val wsTotal = wsTasks.size
    val ratio = if (wsTotal > 0) wsDone.toFloat() / wsTotal else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(AppAccentSoft)
            .clickable(onClick = onClick)
            .padding(Spacing.lg)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("LANJUTKAN", color = AppAccent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Icon(Icons.Default.ChevronRight, null, tint = AppAccent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(chapter.name, color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(workspace.name, color = AppTextMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        if (wsTotal > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (ratio > 0f) ratio else 0.02f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                color = AppAccent, trackColor = AppBg
            )
            Text("$wsDone/$wsTotal tugas selesai di ruang kerja ini", color = AppTextDim, fontSize = 10.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** "Jadwal Hari Ini" — daftar GABUNGAN ScheduleEntry (fitur BARU, lokal-only) hari ini + Task
 *  yang deadline-nya hari ini. "Terhubung tapi terpisah" sesuai minta William: dua model data
 *  TETAP beda (Task tidak diubah sama sekali), cuma TAMPILANNYA digabung jadi satu daftar. */
@Composable
private fun JadwalHariIniSection(
    todayScheduleEntries: List<ScheduleEntry>,
    tasksToday: List<Task>,
    onAddClick: () -> Unit,
    onEditEntry: (ScheduleEntry) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel("Jadwal Hari Ini")
            IconActionBtn(Icons.Default.Add, "Tambah Jadwal", 18.dp, onAddClick)
        }
        if (todayScheduleEntries.isEmpty() && tasksToday.isEmpty()) {
            EmptyHint(Icons.Default.CalendarToday, "Tidak ada jadwal atau tugas untuk hari ini.", modifier = Modifier.padding(top = 2.dp))
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(AppBgCard).border(1.dp, AppBorder, RoundedCornerShape(Radius.card))) {
                todayScheduleEntries.forEachIndexed { idx, entry ->
                    if (idx > 0) HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
                    JadwalEntryRow(entry) { onEditEntry(entry) }
                }
                if (todayScheduleEntries.isNotEmpty() && tasksToday.isNotEmpty()) HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
                tasksToday.forEachIndexed { idx, t ->
                    if (idx > 0) HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
                    JadwalTaskRow(t)
                }
            }
        }
    }
}

@Composable
private fun JadwalEntryRow(entry: ScheduleEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Outlined.AccessTime, null, tint = AppAccent, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val sub = buildString {
                if (entry.startTime.isNotBlank()) append(entry.startTime)
                if (entry.endTime.isNotBlank()) append(" – ${entry.endTime}")
                if (entry.location.isNotBlank()) { if (isNotEmpty()) append(" · "); append(entry.location) }
            }
            if (sub.isNotBlank()) Text(sub, color = AppTextMuted, fontSize = 11.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = AppTextDim, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun JadwalTaskRow(task: Task) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.TaskAlt, null, tint = Warn, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("Tugas, tenggat hari ini", color = AppTextMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** "HH:mm" → menit sejak 00:00, buat ngurutin ScheduleEntry per jam. Parsing GAGAL AMAN (balik
 *  0, bukan crash) kalau user ngetik format aneh di field teks bebas jam mulai/selesai. */
private fun scheduleEntryMinutes(hhmm: String): Int {
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    return h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59)
}

/** Dialog tambah/ubah ScheduleEntry. Jam mulai/selesai SENGAJA teks bebas (placeholder "08:00"),
 *  BUKAN TimePickerDialog Material3 — keputusan sadar buat ngejaga risiko perubahan tetap kecil
 *  di ronde ini (gak ada compiler buat verifikasi API time-picker yang belum pernah dipakai di
 *  manapun di codebase ini sebelumnya). Lihat CHANGELOG soal ini sebagai follow-up yang masuk akal. */
@Composable
private fun ScheduleEntryDialog(
    initial: ScheduleEntry?,
    onDismiss: () -> Unit,
    onSave: (title: String, start: String, end: String, loc: String, days: Set<Int>) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember(initial) { mutableStateOf(initial?.title ?: "") }
    var startTime by remember(initial) { mutableStateOf(initial?.startTime ?: "") }
    var endTime by remember(initial) { mutableStateOf(initial?.endTime ?: "") }
    var location by remember(initial) { mutableStateOf(initial?.location ?: "") }
    var days by remember(initial) { mutableStateOf(initial?.daysOfWeek ?: emptySet()) }
    val dayLabels = listOf(1 to "Sen", 2 to "Sel", 3 to "Rab", 4 to "Kam", 5 to "Jum", 6 to "Sab", 7 to "Min")
    val valid = title.isNotBlank() && days.isNotEmpty()

    AppDialog(if (initial == null) "Jadwal Baru" else "Ubah Jadwal", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column { EyebrowLabel("Nama", modifier = Modifier.padding(bottom = 6.dp)); AppInputFlat(title, { title = it }, "cth: Kerja Kelompok") }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(Modifier.weight(1f)) { EyebrowLabel("Jam Mulai", modifier = Modifier.padding(bottom = 6.dp)); AppInputFlat(startTime, { startTime = it }, "08:00") }
                Column(Modifier.weight(1f)) { EyebrowLabel("Jam Selesai", modifier = Modifier.padding(bottom = 6.dp)); AppInputFlat(endTime, { endTime = it }, "Opsional") }
            }
            Column { EyebrowLabel("Lokasi", modifier = Modifier.padding(bottom = 6.dp)); AppInputFlat(location, { location = it }, "Opsional") }
            Column {
                EyebrowLabel("Hari", modifier = Modifier.padding(bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayLabels.forEach { (num, label) ->
                        val selected = num in days
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AppAccent else AppBgMuted)
                                .clickable { days = if (selected) days - num else days + num }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else AppTextMuted,
                                fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 4.dp)) {
                GhostBtn("Batal", onDismiss, Modifier.weight(1f))
                PrimaryBtn("Simpan", { if (valid) onSave(title, startTime, endTime, location, days) }, Modifier.weight(2f), enabled = valid)
            }
            if (onDelete != null) {
                Text(
                    "Hapus jadwal ini",
                    color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete).padding(top = 6.dp, bottom = 2.dp)
                )
            }
        }
    }
}
