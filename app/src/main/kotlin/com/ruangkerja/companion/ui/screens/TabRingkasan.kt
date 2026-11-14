package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.*
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel

// ── TAB: RINGKASAN ────────────────────────────────────────
@Composable
internal fun TabRingkasan(ch: Chapter, mats: List<Material>, vm: AppViewModel) {
    val state by vm.sumState.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(Spacing.lg, Spacing.md, Spacing.lg, 32.dp), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (ch.summary != null) {
            item {
                Column(Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        EyebrowLabel("Ringkasan")
                        TextButton(onClick = { vm.summarize(ch) }, enabled = !state.loading && mats.isNotEmpty()) {
                            Text(if (state.loading) "Memperbarui…" else "Perbarui", color = AppTextDim, fontSize = 12.sp)
                        }
                    }
                    MarkdownText(ch.summary)
                }
            }
        } else {
            item {
                EmptyView(
                    Icons.Outlined.Summarize,
                    "Belum ada ringkasan",
                    if (mats.isEmpty()) "Tambahkan materi dulu di tab Materi, baru ringkasan bisa dibuat AI."
                    else "AI bisa merangkum semua materi yang sudah Anda tambahkan di bab ini.",
                    actionLabel = if (mats.isNotEmpty()) "Buat Ringkasan dari Materi" else null,
                    actionIcon = if (mats.isNotEmpty()) Icons.Default.AutoAwesome else null,
                    actionLoading = state.loading,
                    onAction = if (mats.isNotEmpty()) ({ vm.summarize(ch) }) else null
                )
            }
        }
        if (state.error != null) {
            item {
                Column(Modifier.fillMaxWidth().background(Danger.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).border(1.dp, Danger.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Danger, modifier = Modifier.size(14.dp))
                        Text("Tidak bisa membuat ringkasan", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(state.error!!, color = AppTextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    TextButton(onClick = { vm.clearSumError() }, modifier = Modifier.align(Alignment.End)) { Text("Tutup", color = AppTextDim, fontSize = 11.sp) }
                }
            }
        }
    }
}
