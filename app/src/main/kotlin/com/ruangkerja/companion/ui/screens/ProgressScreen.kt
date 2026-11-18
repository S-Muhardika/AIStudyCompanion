package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel

// ── Layout section ini diadaptasi dari referensi Kotlin/Compose yang dikasih user (dashboard
// bergaya "bento": cincin lingkaran buat rasio penyelesaian, kartu per-ruang-kerja pakai bar,
// sudut lebih membulat) — TAPI dikonversi ke token warna gelap kita sendiri, bukan warna terang
// aslinya. Bagian yang murni fitur baru di referensi (AI Coach/feedback, Focus Timer Pomodoro,
// shortcut ke Tugas) sengaja NGGAK diikutin, sesuai instruksi "fitur tetap sama".
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(vm: AppViewModel) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val done = tasks.count { it.status == 2 }
    val active = tasks.count { it.status != 2 }
    val completionRatio = if (tasks.isEmpty()) 0f else done.toFloat() / tasks.size

    Scaffold(containerColor = AppBg, topBar = { AppHeader(title = "Progres Belajar", subtitle = "Pantau konsistensi belajarmu") }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(Spacing.lg, Spacing.md, Spacing.lg, 32.dp), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            item {
                Row(
                    Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    CompletionRing(completionRatio)
                    Column {
                        Text("Selesai", color = AppTextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("$done Tugas", color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        Text("Sisa Aktif", color = AppTextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("$active Tugas", color = AppAccent, fontSize = 15.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            item {
                val stats = listOf(
                    Triple(Icons.Default.Folder, "Ruang Kerja", workspaces.size),
                    Triple(Icons.Default.PendingActions, "Aktif", active),
                    Triple(Icons.Default.TaskAlt, "Selesai", done),
                    Triple(Icons.Default.ListAlt, "Total", tasks.size)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    stats.forEach { (icon, label, value) -> StatTile(icon, label, value, Modifier.weight(1f)) }
                }
            }
            item {
                // Sengaja TANPA kartu — cuma kartu ringkasan paling atas yang dipertahankan
                // sebagai "hero", sisanya jadi section polos biar Progres gak numpuk kartu.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    EyebrowLabel("Tugas per Status")
                    listOf(Triple("Belum", tasks.count { it.status == 0 }, Danger), Triple("Dikerjakan", tasks.count { it.status == 1 }, Warn), Triple("Selesai", done, Success)).forEach { (l, c, col) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(l, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("$c", color = col, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else c.toFloat() / tasks.size }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)), color = col, trackColor = AppBgMuted)
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    EyebrowLabel("Peta Pembagian Tugas")
                    if (workspaces.isEmpty()) {
                        EmptyHint(Icons.Default.Folder, "Belum ada ruang kerja.", modifier = Modifier.padding(top = 4.dp))
                    } else {
                        workspaces.take(5).forEach { ws ->
                            val wsTasks = tasks.filter { it.workspaceId == ws.id }
                            val wsDone = wsTasks.count { it.status == 2 }
                            val wsTotal = wsTasks.size
                            val ratio = if (wsTotal > 0) wsDone.toFloat() / wsTotal else 0f
                            val identity = remember(ws.id) { identityColorFor(ws.id) }
                            Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(ws.name, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(
                                        if (wsTotal > 0) "$wsDone/$wsTotal Selesai" else "Belum ada tugas",
                                        color = identity, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = Spacing.sm)
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(progress = { if (ratio > 0f) ratio else 0.02f }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)), color = identity, trackColor = AppBgMuted)
                            }
                        }
                        if (workspaces.size > 5) {
                            Text("Menampilkan 5 ruang kerja teratas", color = AppTextDim, fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Cincin rasio penyelesaian digambar manual pakai Canvas — diadaptasi dari referensi, tapi
 *  warnanya pakai token gelap kita (AppBgMuted buat track, Success buat progres).
 *  [FIX, 16 Nov — dipakai bareng Beranda] Dulu `private` (cuma kepake di file ini). Diubah jadi
 *  `internal` (satu kata doang, TANPA ubah isi/logic) biar WorkspaceListScreen.kt (satu package
 *  yang sama: com.ruangkerja.companion.ui.screens) bisa pakai cincin yang SAMA PERSIS buat kartu
 *  "Progres" di Beranda — biar nggak ada dua implementasi canvas-ring yang kebetulan mirip tapi
 *  diam-diam beda kalau salah satu diubah nanti. */
@Composable
internal fun CompletionRing(ratio: Float, modifier: Modifier = Modifier) {
    val trackColor = AppBgMuted
    Box(modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(84.dp)) {
            val stroke = 8.dp.toPx()
            drawArc(color = trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
            if (ratio > 0f) drawArc(color = Success, startAngle = -90f, sweepAngle = ratio * 360f, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("${(ratio * 100).toInt()}%", color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: Int, modifier: Modifier = Modifier) {
    // Sengaja TANPA background/border kartu — 4 kotak berjejer kayak kemarin kelihatan
    // penuh kartu (arahan terbaru: Progres jangan terlalu ngandelin card). Badge ikon kecil
    // di atas cukup buat identitas visual per stat, tanpa bingkai di sekeliling tile-nya.
    Column(modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(30.dp).background(AppAccentSoft, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AppAccent, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("$value", color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = AppTextDim, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
