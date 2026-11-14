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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.*
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel

// ── TAB: FLASHCARD ────────────────────────────────────────
@Composable
internal fun TabFlashcard(ch: Chapter, vm: AppViewModel) {
    val cards by vm.flashcards.collectAsStateWithLifecycle()
    val loading by vm.flashcardsLoading.collectAsStateWithLifecycle()
    val cardsState by vm.flashcardsState.collectAsStateWithLifecycle()
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    val flipped = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(contentPadding = PaddingValues(Spacing.lg, Spacing.md, Spacing.lg, 32.dp), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item {
            Column(Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                EyebrowLabel("Buat Flashcard")
                Spacer(Modifier.height(2.dp))
                AppInput(front, { front = it }, "Depan — pertanyaan atau istilah")
                AppInput(back, { back = it }, "Belakang — jawaban atau definisi")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (ch.summary != null) {
                        OutlinedButton(onClick = { vm.generateFlashcards(ch) }, modifier = Modifier.weight(1f), enabled = !loading, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, AppBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTextMuted)) {
                            if (loading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = AppAccent)
                            else { Icon(Icons.Default.AutoAwesome, null, Modifier.size(13.dp)); Spacer(Modifier.width(5.dp)); Text("Bantu", fontSize = 12.sp) }
                        }
                    }
                    PrimaryBtn("Tambah", { if (front.isNotBlank() && back.isNotBlank()) { vm.addFlashcard(front.trim(), back.trim()); front = ""; back = "" } }, Modifier.weight(2f), enabled = front.isNotBlank() && back.isNotBlank(), icon = Icons.Default.Add)
                }
            }
        }
        // [FIX] Error pembuatan flashcard otomatis dulu tidak pernah ditampilkan (state-nya ada di
        // ViewModel tapi tab ini tidak membacanya). Gaya & pola sama dengan TabRingkasan.
        cardsState.error?.let { err ->
            item {
                Column(Modifier.fillMaxWidth().background(Danger.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).border(1.dp, Danger.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Danger, modifier = Modifier.size(14.dp))
                        Text("Tidak bisa membuat flashcard", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(err, color = AppTextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    TextButton(onClick = { vm.clearFlashcardsError() }, modifier = Modifier.align(Alignment.End)) { Text("Tutup", color = AppTextDim, fontSize = 11.sp) }
                }
            }
        }
        if (cards.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    EmptyView(
                        Icons.Outlined.Style, "Belum ada flashcard",
                        if (ch.summary != null) "Buat otomatis dari ringkasan, atau isi manual melalui form di atas."
                        else "Buat manual melalui form di atas, atau buat ringkasan terlebih dahulu di tab Ringkasan agar dapat dibantu secara otomatis.",
                        actionLabel = if (ch.summary != null) "Buat Otomatis dari Ringkasan" else null,
                        actionIcon = if (ch.summary != null) Icons.Default.AutoAwesome else null,
                        actionLoading = loading,
                        onAction = if (ch.summary != null) ({ vm.generateFlashcards(ch) }) else null
                    )
                }
            }
        }
        items(cards, key = { it.id }) { card ->
            val isFlipped = flipped[card.id] == true
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(if (isFlipped) AppBgMuted else AppBgCard).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).clickable { flipped[card.id] = !isFlipped }.padding(Spacing.xl)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EyebrowLabel(if (isFlipped) "Jawaban" else "Pertanyaan")
                    // [FIX, 12 Nov — Tahap 1 arsitektur, audit "markdown mentah kelihatan"]
                    // front/back bisa hasil "Bantu AI" (generateFlashcards) yang bisa aja nulis
                    // **istilah** dll — MarkdownInlineText (ui/components/MarkdownRenderer.kt)
                    // render bold/italic/strikethrough/kode-nya, bukan tampil mentah.
                    MarkdownInlineText(if (isFlipped) card.back else card.front, color = AppText, fontWeight = if (isFlipped) FontWeight.Normal else FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                    Text("Ketuk untuk ${if (isFlipped) "balik" else "lihat jawaban"}", color = AppTextDim, fontSize = 11.sp)
                }
                IconButton(onClick = { vm.deleteFlashcard(card.id) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) { Icon(Icons.Default.Close, null, tint = AppTextDim, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}
