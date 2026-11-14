package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.delay

/**
 * Search universal — cari lintas workspace/bab/materi sekaligus, ditampilkan sebagai
 * overlay full-screen (bukan route NavHost baru, biar gampang dipanggil dari mana aja).
 * Hasil "materi" ngarah ke ID bab-nya (materi nggak punya layar sendiri).
 */
@Composable
fun SearchOverlay(
    vm: AppViewModel,
    onOpenWorkspace: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<AppViewModel.SearchResult>()) }
    val focusRequester = remember { FocusRequester() }

    // Debounce kecil biar nggak nge-filter ulang di SETIAP karakter kalau user ngetik cepat
    LaunchedEffect(query) {
        delay(150)
        results = vm.search(query)
    }
    LaunchedEffect(Unit) {
        delay(120) // kasih waktu Dialog selesai animasi muncul dulu sebelum minta fokus
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AppBg) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tutup", tint = AppText) }
                    AppInput(
                        query, { query = it }, "Cari materi, bab, atau ruang kerja…",
                        modifier = Modifier.weight(1f).focusRequester(focusRequester)
                    )
                }

                when {
                    query.isBlank() -> SearchHint("Ketik untuk mulai mencari", Icons.Outlined.Search, muted = true)
                    results.isEmpty() -> SearchHint("Tidak ada hasil untuk \"$query\"", Icons.Outlined.SearchOff)
                    else -> LazyColumn(
                        contentPadding = PaddingValues(Spacing.md, Spacing.sm, Spacing.md, 32.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(results, key = { it.type + it.id + it.title }) { r ->
                            SearchResultRow(r) {
                                if (r.type == "workspace") onOpenWorkspace(r.id) else onOpenChapter(r.id)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(text: String, icon: ImageVector, muted: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp).emptyEntrance()) {
            EmptyIconBadge(
                icon,
                tint = if (muted) AppTextDim else AppAccent,
                bg = if (muted) AppBgMuted else AppAccentSoft,
                size = 56
            )
            Spacer(Modifier.height(14.dp))
            Text(text, color = AppTextMuted, fontSize = 13.5.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SearchResultRow(r: AppViewModel.SearchResult, onClick: () -> Unit) {
    val (icon, label, tint) = when (r.type) {
        "workspace" -> Triple(Icons.Outlined.FolderOpen, "Ruang Kerja", AppAccent)
        "chapter"   -> Triple(Icons.Outlined.MenuBook, "Bab", Warn)
        else        -> Triple(Icons.Outlined.Description, "Materi", Success)
    }
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            IconBox(icon, tint = tint, bg = tint.copy(alpha = 0.12f), size = 38)
            Column(Modifier.weight(1f)) {
                Text(r.title, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.subtitle, color = AppTextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
