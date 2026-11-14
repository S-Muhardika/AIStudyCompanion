package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.DraftSuggestion
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.util.draftDisplayTitle
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.launch

// [BARU, Fase 2 ekosistem draft] "RKR Notification Center" — riwayat saran draft->workspace,
// dikelompokkan per hari (spek bagian 7B). BEDA dari notifikasi Android (Fase 3, belum
// digarap): ini persisten, nyimpen histori & status, hidup di dalam app doang.
// [UBAH, 18 Okt — masukan William: "buat ada beberapa kategori... belum direspon... nanti...
// udah dilihat belum dipindah... diabaikan"] Dulu CUMA dikelompokkan per TANGGAL, semua status
// kecampur jadi satu list panjang. Sekarang ada TAB status dulu (pola "kotak masuk"), baru di
// DALAM tab itu dikelompokkan per tanggal kayak sebelumnya (groupSuggestionsByDay gak berubah
// sama sekali, cuma sekarang dipanggil di list yang UDAH difilter per status). Urutan tab dari
// yang paling perlu aksi ke yang udah "kelar": Belum Direspon (PENDING) → Nanti (SNOOZED) →
// Dilihat (SEEN) → Diabaikan (DISMISSED) → Dipindahkan (ACCEPTED). Default buka di tab PENDING.
private val STATUS_TABS = listOf(
    "PENDING" to "Belum Direspon",
    "SNOOZED" to "Nanti",
    "SEEN" to "Dilihat",
    "DISMISSED" to "Diabaikan",
    "ACCEPTED" to "Dipindahkan"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotificationCenterScreen(vm: AppViewModel, onBack: () -> Unit) {
    val history by vm.suggestionHistory.collectAsStateWithLifecycle()
    // [UBAH, 19 Okt — masukan William: "buat agak bisa pindah dari tab satu ke tab lain
    // dengan cara di slide mirip kayak di halaman bab"] selectedTab lokal diganti pagerState —
    // pola SAMA persis dengan ChapterDetailScreen.kt (tab row + HorizontalPager, indikator &
    // Tab.selected ngikutin pagerState.currentPage) biar kedua layar yang sama-sama punya tab
    // konsisten caranya pindah tab: tap ATAU geser, bukan cuma tap kayak dulu.
    val pagerState = rememberPagerState(pageCount = { STATUS_TABS.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = AppBg,
        topBar = { AppHeader(title = "Notifikasi", onBack = onBack) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (history.isNotEmpty()) {
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage, containerColor = AppBg, edgePadding = Spacing.lg) {
                    STATUS_TABS.forEachIndexed { i, (status, label) ->
                        val count = history.count { it.status == status }
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = { Text(if (count > 0) "$label ($count)" else label, fontSize = 12.5.sp) }
                        )
                    }
                }
            }
            if (history.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada saran pemindahan draft. Saran akan muncul di sini apabila terdapat draft yang kata kuncinya sesuai dengan salah satu Ruang Kerja Anda.",
                        color = AppTextDim, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    val status = STATUS_TABS[page].first
                    val filtered = remember(history, status) { history.filter { it.status == status } }
                    val grouped = remember(filtered) { groupSuggestionsByDay(filtered) }
                    if (filtered.isEmpty()) {
                        // [BARU, 18 Okt] Beda dari pesan kosong total di atas — ini kosong KARENA
                        // tab yang lagi dipilih memang tidak memiliki isi, bukan karena belum pernah ada saran.
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Tidak ada saran berstatus \"${STATUS_TABS[page].second}\" saat ini.",
                                color = AppTextDim, fontSize = 13.sp, textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                            grouped.forEach { (label, items) ->
                                item(key = "header_$label") {
                                    Text(
                                        label, color = AppTextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.sm)
                                    )
                                }
                                items(items, key = { it.id }) { s ->
                                    // [UBAH, 19 Okt — masukan William: "untuk munculin pop up nya
                                    // nggak usah di tutup halaman notifikasi biar aja tetep di
                                    // dalam sana, ribet kalau harus buka tutup lagi"] Dulu onBack()
                                    // dipanggil abis reopenSuggestion() biar pop-up-nya (yang waktu
                                    // itu CUMA dirender di WorkspaceListScreen/Beranda) kelihatan.
                                    // Sekarang pop-up draftMatch (& overlay edit draft, chapterPicker,
                                    // newChapterPrompt) dipindah ke level RuangKerjaApp (MainActivity.
                                    // kt) — nempel DI ATAS NavHost, jadi otomatis muncul di layar MANA
                                    // PUN termasuk di sini. onBack() gak perlu lagi — malah
                                    // nutup halaman ini tiap kali, yang sekarang justru dihindari.
                                    SuggestionRow(s, onClick = { vm.reopenSuggestion(s) })
                                }
                            }
                            item { Spacer(Modifier.height(Spacing.lg)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(s: DraftSuggestion, onClick: () -> Unit) {
    // Cuma saran yang belum "kelar" (masih SEEN/SNOOZED/PENDING) yang bisa di-tap buat munculin
    // lagi pop-up 4-aksinya — yang udah ACCEPTED/DISMISSED itu histori doang, dibaca aja.
    val actionable = s.status == "SEEN" || s.status == "SNOOZED" || s.status == "PENDING"
    // [FIX, 18 Okt — masukan William: "jangan 'kemungkinan cocok', kayak plin-plan"] Sama
    // persis dengan popup di WorkspaceListScreen.kt: ganti hedging "Kemungkinan sesuai dengan"
    // jadi pernyataan langsung, tetap dibedain lewat kadar kata-katanya per confidence.
    val (icon, desc) = when (s.status) {
        "ACCEPTED" -> "✓" to "Telah dipindahkan ke ${s.workspaceNameSnapshot}${s.resolvedChapterNameSnapshot?.let { " / $it" } ?: ""}"
        "DISMISSED" -> "✕" to "Diabaikan — sebelumnya ${if (s.confidence == "HIGH") "cocok dengan" else "ada kaitan dengan"} ${s.workspaceNameSnapshot}"
        "SNOOZED" -> "⏱" to "Ditunda — ${if (s.confidence == "HIGH") "cocok dengan" else "ada kaitan dengan"} ${s.workspaceNameSnapshot}"
        else -> "💡" to "${if (s.confidence == "HIGH") "Cocok dengan" else "Ada kaitan dengan"} ${s.workspaceNameSnapshot}"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (actionable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Spacing.sm)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Text(icon, fontSize = 15.sp)
            Column(Modifier.weight(1f)) {
                Text(desc, color = if (actionable) AppText else AppTextMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Text(
                    "\"${draftDisplayTitle(s.draftTitleSnapshot, s.draftContentSnapshot)}\"",
                    color = AppTextDim, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
    HorizontalDivider(color = AppBorder)
}

/** Kelompokkan per tanggal (berdasar updatedAt, biar riwayat status TERBARU yang nentuin
 *  posisinya, bukan kapan pertama kali disaranin), label "Hari ini"/"Kemarin" buat 2 hari
 *  terakhir, tanggal biasa buat yang lebih lama — sesuai contoh di spek bagian 7B. */
private fun groupSuggestionsByDay(list: List<DraftSuggestion>): List<Pair<String, List<DraftSuggestion>>> {
    val todayStr = DateUtils.formatDisplayDate(System.currentTimeMillis())
    val yesterdayStr = DateUtils.formatDisplayDate(System.currentTimeMillis() - 86_400_000L)
    return list
        .sortedByDescending { it.updatedAt }
        .groupBy { DateUtils.formatDisplayDate(it.updatedAt) }
        .toList()
        .sortedByDescending { (_, items) -> items.maxOf { it.updatedAt } }
        .map { (dateStr, items) ->
            val label = when (dateStr) { todayStr -> "Hari ini"; yesterdayStr -> "Kemarin"; else -> dateStr }
            label to items
        }
}
