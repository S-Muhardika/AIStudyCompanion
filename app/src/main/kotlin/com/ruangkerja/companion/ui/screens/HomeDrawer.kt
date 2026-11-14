package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.ruangkerja.companion.data.db.Draft
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.util.draftDisplayTitle
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*

// [DIPINDAH, audit item 2 — 27 Sep] HomeDrawerContent & HomeDrawerRow dipindah dari
// WorkspaceListScreen.kt (waktu itu 1358 baris nyampur 3 concern: layar Beranda, drawer, &
// draft editor) ke file sendiri — MURNI pindah kode, nggak ada logic yang berubah. Aman tanpa
// perubahan import di MainActivity.kt: package-nya SAMA (com.ruangkerja.companion.ui.screens),
// dan visibilitas Kotlin itu per-package bukan per-file, jadi HomeDrawerContent (public) tetap
// langsung kepanggil dari sana persis kayak sebelumnya.

// ── Side drawer Beranda, niru pola sidebar chat AI (list ringkas + "baru" di atas +
// pengaturan di bawah). Isinya DRAFT (catatan cepat) — bukan Ruang Kerja, itu tetap di
// konten utama Beranda kayak sebelumnya.
// [FIX, 22 Sep] Gak private lagi — dipanggil dari RuangKerjaApp (MainActivity.kt) sekarang,
// soalnya ModalNavigationDrawer yang make dia udah pindah ke sana. ──
@Composable
fun HomeDrawerContent(drafts: List<Draft>, pendingSuggestionCount: Int, onOpenDraft: (Draft) -> Unit, onNewDraft: () -> Unit, onDeleteDraft: (Draft) -> Unit, onMoveDraft: (Draft) -> Unit, onSettings: () -> Unit, onNotifications: () -> Unit) {
    // Lebar dinaikin sedikit (280→312dp) & radius sudut dihapus total (RectangleShape) —
    // drawer sekarang persegi tegas, bukan melengkung di sisi kanannya.
    ModalDrawerSheet(drawerContainerColor = AppBgCard, drawerContentColor = AppText, drawerShape = RectangleShape, modifier = Modifier.width(312.dp)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Text("Draft", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 2.dp, end = 20.dp, bottom = 12.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppAccentSoft)
                    .clickable(onClick = onNewDraft)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = AppAccent, modifier = Modifier.size(18.dp))
                Text("Draft Baru", color = AppAccent, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = AppBorder)

            if (drafts.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Belum ada draft", color = AppTextDim, fontSize = 12.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(drafts, key = { it.id }) { d ->
                        HomeDrawerRow(d, onClick = { onOpenDraft(d) }, onDelete = { onDeleteDraft(d) }, onMove = { onMoveDraft(d) })
                    }
                }
            }

            HorizontalDivider(color = AppBorder)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNotifications)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Notifications, null, tint = AppTextMuted, modifier = Modifier.size(17.dp))
                Text("Notifikasi", color = AppTextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (pendingSuggestionCount > 0) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(AppAccentSoft).padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text("$pendingSuggestionCount", color = AppAccent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Settings, null, tint = AppTextMuted, modifier = Modifier.size(17.dp))
                Text("Pengaturan", color = AppTextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HomeDrawerRow(d: Draft, onClick: () -> Unit, onDelete: () -> Unit, onMove: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    // [UBAH, 19 Okt] Logic cuplikan diekstrak ke util/ChapterNamer.kt (draftDisplayTitle) biar
    // dipakai bareng popup saran & NotificationCenterScreen juga — bukan cuma di drawer ini.
    val preview = draftDisplayTitle(d)
    val editedDate = remember(d.updatedAt) { DateUtils.formatDisplayDate(d.updatedAt) }
    val createdDate = remember(d.createdAt) { DateUtils.formatDisplayDate(d.createdAt) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Edit, null, tint = AppTextMuted, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                preview, color = AppText, fontSize = 13.5.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(editedDate, color = AppTextDim, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        CardOverflowMenu(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            actions = listOf(
                CardMenuAction("Dibuat $createdDate", Icons.Default.CalendarToday, enabled = false, onClick = {}),
                // [BARU, 18 Okt — masukan William: "siapkan juga pemindahan custom jadi user
                // bisa pindahin itu sesuka hati"] Entry point pemindahan MANUAL — beda dari
                // saran otomatis (ekosistem draft), ini user yang milih sendiri Ruang Kerja
                // tujuannya, kapan aja, gak harus nunggu ada kecocokan kata kunci dulu.
                // Icons.Default.Folder dipilih (bukan ikon panah/"move") biar aman dari resiko
                // mismatch namespace AutoMirrored kayak yang sempat bikin build gagal sebelumnya.
                CardMenuAction("Pindahkan", Icons.Default.Folder, onClick = onMove),
                CardMenuAction("Hapus", Icons.Outlined.DeleteOutline, danger = true, onClick = onDelete)
            )
        )
    }
}
