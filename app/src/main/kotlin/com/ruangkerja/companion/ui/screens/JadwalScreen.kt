package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.ScheduleEntry
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.viewmodel.AppViewModel

// ═══════════════════════════════════════════════════════════════════════════════════════════
// [BARU, 18 Nov — Task #50 "Layar Kalender/Jadwal penuh", bagian dari redesain nav bawah jadi 3
// tab] Promosi ScheduleEntry dari sekadar "Jadwal Hari Ini" di Beranda (WorkspaceListScreen.kt,
// TIDAK diubah/dihapus — tetap ada apa adanya di sana) jadi layar TAB tersendiri yang nampilin
// SEMUA ScheduleEntry, dikelompokkan per hari (Senin..Minggu) — bukan cuma jadwal hari ini.
//
// CRUD (tambah/ubah/hapus) REUSE ScheduleEntryDialog/JadwalEntryRow/scheduleEntryMinutes dari
// WorkspaceListScreen.kt APA ADANYA (baru diubah private → internal di sana buat ronde ini,
// lihat catatan di situ) — zero logic/validasi/field baru. Tujuannya biar perilaku simpan/hapus
// jadwal dari layar ini 100% konsisten dengan dari Beranda, bukan 2 implementasi kebetulan
// mirip yang berpotensi menyimpang seiring waktu.
//
// [BELUM DIUJI] Sama seperti file lain di ronde ini — tidak ada compiler Kotlin di sandbox ini
// (lihat CHANGELOG), sudah dicek brace-balance + dibaca ulang logis terhadap pola yang SUDAH
// terbukti jalan di WorkspaceListScreen.kt, TAPI belum pernah benar-benar di-render/dijalankan.
// ═══════════════════════════════════════════════════════════════════════════════════════════

@Composable
fun JadwalScreen(vm: AppViewModel) {
    val scheduleEntries by vm.scheduleEntries.collectAsStateWithLifecycle()
    // Pola identik WorkspaceListScreen.kt (todayDow) — gagal AMAN ke 0 (gak pernah cocok 1-7)
    // kalau DateUtils sempat melempar, bukan nge-crash seluruh layar.
    val todayDow = remember { try { DateUtils.currentZoned().dayOfWeek.value } catch (e: Exception) { 0 } }
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleEntry?>(null) }

    val dayNames = listOf(1 to "Senin", 2 to "Selasa", 3 to "Rabu", 4 to "Kamis", 5 to "Jumat", 6 to "Sabtu", 7 to "Minggu")
    val grouped = remember(scheduleEntries) {
        dayNames.map { (num, name) ->
            Triple(num, name, scheduleEntries.filter { num in it.daysOfWeek }.sortedBy { scheduleEntryMinutes(it.startTime) })
        }
    }

    Scaffold(
        containerColor = AppBg,
        topBar = {
            AppHeader(
                title = "Jadwal",
                subtitle = if (scheduleEntries.isEmpty()) "Belum ada jadwal" else "${scheduleEntries.size} jadwal berulang mingguan",
                actions = { IconActionBtn(Icons.Default.Add, "Tambah Jadwal", 20.dp) { editing = null; showDialog = true } }
            )
        }
    ) { padding ->
        if (scheduleEntries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CalendarToday, null, tint = AppTextDim, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Belum ada jadwal berulang. Ketuk \"+\" di atas buat nambah slot jadwal mingguan (cth: kelas, kerja kelompok, les).",
                        color = AppTextDim, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(Spacing.lg, Spacing.md, Spacing.lg, 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                grouped.forEach { (num, name, entries) ->
                    if (entries.isNotEmpty()) {
                        item(key = "header-$num") {
                            EyebrowLabel(if (num == todayDow) "$name · Hari Ini" else name, color = if (num == todayDow) AppAccent else AppTextMuted)
                        }
                        item(key = "card-$num") {
                            Column(Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card))) {
                                entries.forEachIndexed { idx, entry ->
                                    if (idx > 0) HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
                                    JadwalEntryRow(entry) { editing = entry; showDialog = true }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog tambah/ubah — REUSE ScheduleEntryDialog (WorkspaceListScreen.kt) apa adanya, pola
    // pemanggilan & pemetaan onSave/onDelete disalin PERSIS dari sana.
    if (showDialog) {
        val currentEditing = editing
        ScheduleEntryDialog(
            initial = currentEditing,
            onDismiss = { showDialog = false },
            onSave = { title, start, end, loc, days ->
                if (currentEditing == null) vm.addScheduleEntry(title, start, end, loc, days)
                else vm.updateScheduleEntry(currentEditing.copy(title = title.trim(), startTime = start.trim(), endTime = end.trim(), location = loc.trim(), daysOfWeek = days))
                showDialog = false
            },
            onDelete = currentEditing?.let { entry -> { vm.deleteScheduleEntry(entry.id); showDialog = false } }
        )
    }
}
