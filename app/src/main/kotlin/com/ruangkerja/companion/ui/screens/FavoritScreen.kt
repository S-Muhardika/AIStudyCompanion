package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.Chapter
import com.ruangkerja.companion.data.db.Draft
import com.ruangkerja.companion.data.db.Material
import com.ruangkerja.companion.data.db.Task
import com.ruangkerja.companion.data.db.Workspace
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.util.draftDisplayTitle
import com.ruangkerja.companion.viewmodel.AppViewModel

// ═══════════════════════════════════════════════════════════════════════════════════════════
// [BARU, 18-19 Nov — Task #49 "Bangun sistem Favorit"] Layar Favorit — bagian KEDUA dari fitur
// Favorit (bagian PERTAMA: field `favorited` di 5 entity + toggleFavoriteXxx() di AppViewModel,
// sudah dikerjakan & diverifikasi duluan, lihat Entities.kt/AppViewModel.kt). Sesuai instruksi
// eksplisit William: "Yang bisa di masukkan ke favorit itu semua, tapi nanti di dalam favorit
// ada kategori nya sendiri, dan itu di tandai dengan icon heart, atau hati(love)" — LINTAS JENIS
// (Workspace/Chapter/Material/Task/Draft), dikategorikan per jenis, ikon hati buat toggle.
//
// Layar ini SENDIRI yang filter `.favorited` dari flow GLOBAL (vm.workspaces/allChapters/
// allMaterials/tasks/drafts — SEMUANYA sudah ada, allChapters/allMaterials BARU ditambah ronde
// ini) — bukan AppViewModel yang expose list ter-filter, biar nggak nambah StateFlow baru lagi
// tiap butuh kombinasi filter (lihat catatan di AppViewModel.kt dekat toggleFavoriteWorkspace).
//
// Navigasi per jenis (disesuaikan rute yang BENERAN ada di NavHost, MainActivity.kt — TIDAK ada
// rute "material/{id}" atau "task/{id}" di codebase ini):
//   - Workspace favorit → buka Workspace-nya langsung (onOpenWorkspace).
//   - Chapter favorit   → buka Chapter-nya langsung (onOpenChapter).
//   - Material favorit  → BELUM ada layar detail Materi tersendiri, materi dilihat di dalam tab
//     Materi suatu Bab — jadi diarahkan ke Chapter INDUK materinya (onOpenChapter(chapterId)),
//     BUKAN dibuka langsung ke materinya. [BELUM DIUJI/asumsi jujur, lihat CHANGELOG]
//   - Task favorit      → TaskScreen.kt juga TIDAK punya rute per-tugas (daftar datar semua
//     tugas) — diarahkan ke layar Tugas (onOpenTasks), BUKAN ke tugas spesifiknya. Keterbatasan
//     ini APA ADANYA, bukan disembunyikan.
//   - Draft favorit     → overlay draftEditTarget yang SAMA PERSIS dipakai di drawer Beranda
//     (vm.setDraftEditTarget), dipanggil LANGSUNG dari sini (overlay-nya dirender global di
//     MainActivity.kt, bereaksi ke state vm ini ada di layar mana pun).
// ═══════════════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenTasks: () -> Unit
) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val allChapters by vm.allChapters.collectAsStateWithLifecycle()
    val allMaterials by vm.allMaterials.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()

    val wsById = remember(workspaces) { workspaces.associateBy { it.id } }
    val chById = remember(allChapters) { allChapters.associateBy { it.id } }

    val favWorkspaces = remember(workspaces) { workspaces.filter { it.favorited } }
    val favChapters = remember(allChapters) { allChapters.filter { it.favorited } }
    val favMaterials = remember(allMaterials) { allMaterials.filter { it.favorited } }
    val favTasks = remember(tasks) { tasks.filter { it.favorited } }
    val favDrafts = remember(drafts) { drafts.filter { it.favorited } }

    val totalFav = favWorkspaces.size + favChapters.size + favMaterials.size + favTasks.size + favDrafts.size

    Scaffold(
        containerColor = AppBg,
        topBar = {
            AppHeader(
                title = "Favorit",
                subtitle = if (totalFav == 0) "Belum ada yang ditandai favorit" else "$totalFav item ditandai favorit",
                onBack = onBack
            )
        }
    ) { padding ->
        if (totalFav == 0) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.FavoriteBorder, null, tint = AppTextDim, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Belum ada favorit. Ketuk ikon hati di Ruang Kerja, Bab, Materi, Tugas, atau Draft buat nandain di sini.",
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
                if (favWorkspaces.isNotEmpty()) {
                    item(key = "hdr-ws") { EyebrowLabel("Ruang Kerja") }
                    item(key = "card-ws") {
                        FavoriteGroupCard(favWorkspaces) { ws ->
                            FavoriteRow(
                                icon = Icons.Outlined.FolderOpen,
                                title = ws.name,
                                subtitle = ws.description.ifBlank { null },
                                favorited = true,
                                onToggleFavorite = { vm.toggleFavoriteWorkspace(ws) },
                                onClick = { onOpenWorkspace(ws.id) }
                            )
                        }
                    }
                }
                if (favChapters.isNotEmpty()) {
                    item(key = "hdr-ch") { EyebrowLabel("Bab") }
                    item(key = "card-ch") {
                        FavoriteGroupCard(favChapters) { ch ->
                            FavoriteRow(
                                icon = Icons.Outlined.MenuBook,
                                title = ch.name.ifBlank { "(Tanpa Judul Bab)" },
                                subtitle = wsById[ch.workspaceId]?.name,
                                favorited = true,
                                onToggleFavorite = { vm.toggleFavoriteChapter(ch) },
                                onClick = { onOpenChapter(ch.id) }
                            )
                        }
                    }
                }
                if (favMaterials.isNotEmpty()) {
                    item(key = "hdr-mat") { EyebrowLabel("Materi") }
                    item(key = "card-mat") {
                        FavoriteGroupCard(favMaterials) { m ->
                            val chapter = chById[m.chapterId]
                            val wsName = chapter?.let { wsById[it.workspaceId]?.name }
                            FavoriteRow(
                                icon = Icons.Outlined.Description,
                                title = m.title.ifBlank { "(Tanpa Judul Materi)" },
                                subtitle = listOfNotNull(chapter?.name, wsName).joinToString(" · ").ifBlank { null },
                                favorited = true,
                                onToggleFavorite = { vm.toggleFavoriteMaterial(m) },
                                // Belum ada layar detail Materi tersendiri — arahkan ke Bab induknya.
                                onClick = { onOpenChapter(m.chapterId) }
                            )
                        }
                    }
                }
                if (favTasks.isNotEmpty()) {
                    item(key = "hdr-task") { EyebrowLabel("Tugas") }
                    item(key = "card-task") {
                        FavoriteGroupCard(favTasks) { t ->
                            FavoriteRow(
                                icon = Icons.Outlined.TaskAlt,
                                title = t.title,
                                subtitle = if (t.deadline.isNotBlank()) "Tenggat: ${t.deadline}" else null,
                                favorited = true,
                                onToggleFavorite = { vm.toggleFavoriteTask(t) },
                                // Belum ada rute per-tugas — arahkan ke layar Tugas (daftar datar).
                                onClick = onOpenTasks
                            )
                        }
                    }
                }
                if (favDrafts.isNotEmpty()) {
                    item(key = "hdr-draft") { EyebrowLabel("Draft") }
                    item(key = "card-draft") {
                        FavoriteGroupCard(favDrafts) { d ->
                            FavoriteRow(
                                icon = Icons.Outlined.EditNote,
                                title = draftDisplayTitle(d),
                                subtitle = null,
                                favorited = true,
                                onToggleFavorite = { vm.toggleFavoriteDraft(d) },
                                onClick = { vm.setDraftEditTarget(d) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Kartu pembungkus satu kategori Favorit — pola kartu+divider PERSIS sama kayak
 *  JadwalHariIniSection di WorkspaceListScreen.kt, cuma generic lewat tipe T. */
@Composable
private fun <T> FavoriteGroupCard(items: List<T>, row: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card))) {
        items.forEachIndexed { idx, item ->
            if (idx > 0) HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
            row(item)
        }
    }
}

@Composable
private fun FavoriteRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = AppTextMuted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = AppTextMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        // [CATATAN] Bukan IconActionBtn biasa (tint-nya SELALU AppTextMuted, lihat
        // CommonComponents.kt) — hati di layar ini perlu kelihatan "aktif" (merah/Danger) biar
        // jelas beda dari ikon biasa, konsisten sama makna universal ikon hati ke-isi.
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
            Icon(
                if (favorited) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                if (favorited) "Hapus dari Favorit" else "Tandai Favorit",
                tint = if (favorited) Danger else AppTextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
