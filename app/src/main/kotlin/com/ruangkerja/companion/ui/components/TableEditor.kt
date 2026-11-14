package com.ruangkerja.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruangkerja.companion.ui.theme.*

// ── Helper tabel markdown + grid tabel inline. Dipakai NotebookEditor DAN DraftEditor:
// parseTableLines/buildTableMarkdown buat mecah & menyusun markdown tabel, TableGrid buat
// nge-render tabel sebagai grid inline permanen di alur dokumen (live-preview) — bukan lewat
// dialog terpisah lagi. ──

internal fun parseTableLines(lines: List<String>): List<List<String>> {
    val cells = lines.map { r -> r.trim().trim('|').split("|").map { it.trim() } }
    val isSeparator: (List<String>) -> Boolean = { r -> r.isNotEmpty() && r.all { it.isBlank() || it.matches(Regex("^:?-{2,}:?$")) } }
    return cells.filterIndexed { idx, r -> !(idx == 1 && isSeparator(r)) }
}

internal fun buildTableMarkdown(rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    val colCount = rows.maxOf { it.size }
    val norm = rows.map { r -> r + List((colCount - r.size).coerceAtLeast(0)) { "" } }
    val header = "| " + norm[0].joinToString(" | ") + " |"
    val sep = "|" + List(colCount) { "---" }.joinToString("|") { it } + "|"
    val body = norm.drop(1).joinToString("\n") { "| " + it.joinToString(" | ") + " |" }
    return listOf(header, sep, body).filter { it.isNotBlank() }.joinToString("\n")
}

// ── Grid tabel inline PERMANEN (hybrid: kode custom di luar editor teks, karena Compose Rich Editor
// nggak punya tabel bawaan). [DIGABUNG, tahap 5 audit editor] Dulu ada DUA salinan identik —
// InlineTableBlock (NotebookEditor.kt) dan DraftTableGrid (DraftEditor.kt, disalin "apa adanya biar
// konsisten look-nya"); bedanya cuma callback [onFocus]. Sekarang satu komposabel, [onFocus] opsional:
// - NotebookEditor mengisinya: tiap sel BasicTextField lapor lewat onFocus() saat disentuh. Tanpa itu
//   focusedBlockIndex STALE (nunjuk blok lain/lama) kalau user mengetik di sel tabel lalu insert
//   Tabel/Kotak Contoh lagi, dan insert-nya "lompat" ke tempat yang tak diduga (bug lama, sudah di-FIX).
// - DraftEditor TIDAK mengisinya (null): tombol format toolbar Draft sengaja no-op saat fokus di sel
//   tabel, jadi fokusnya tidak perlu dilacak. Tanpa onFocus, sel tidak memasang pengamat fokus sama
//   sekali — perilaku Draft identik dengan sebelum digabung. ──
@Composable
internal fun TableGrid(rows: SnapshotStateList<SnapshotStateList<String>>, onDelete: () -> Unit, onFocus: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, AppBorder, RoundedCornerShape(10.dp))
        ) {
            rows.forEachIndexed { rIdx, row ->
                if (rIdx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(AppBorder))
                Row {
                    row.forEachIndexed { cIdx, cell ->
                        if (cIdx > 0) Box(Modifier.width(1.dp).background(AppBorder))
                        Box(
                            Modifier
                                .width(120.dp)
                                .background(if (rIdx == 0) AppBgMuted else Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            BasicTextField(
                                value = cell,
                                onValueChange = { newVal -> row[cIdx] = newVal },
                                textStyle = TextStyle(color = AppText, fontSize = 12.5.sp, fontWeight = if (rIdx == 0) FontWeight.Bold else FontWeight.Normal),
                                cursorBrush = SolidColor(AppAccent),
                                modifier = Modifier.fillMaxWidth().then(
                                    if (onFocus != null) Modifier.onFocusChanged { fs -> if (fs.isFocused) onFocus() } else Modifier
                                )
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TableGridAction("Baris", Icons.Default.Add) {
                val cols = rows.firstOrNull()?.size ?: 2
                rows.add(mutableStateListOf(*Array(cols) { "" }))
            }
            TableGridAction("Kolom", Icons.Default.Add) { rows.forEach { r -> r.add("") } }
            Spacer(Modifier.weight(1f))
            TableGridAction("Hapus Tabel", Icons.Outlined.DeleteOutline, color = Danger, onClick = onDelete)
        }
    }
}

@Composable
private fun TableGridAction(label: String, icon: ImageVector, color: Color = AppAccent, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(label, color = color, fontSize = 11.sp)
    }
}
