package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.launch

/**
 * Host/scaffold layar detail bab — cuma ngatur header + tab row + pindah
 * antar tab. Isi tiap tab ada di file terpisah biar nggak numpuk jadi satu
 * file raksasa:
 *   - TabMateri.kt     → tab Materi (+ editor notebook, import OCR/URL/PDF)
 *   - TabCatatan.kt    → tab Catatan
 *   - TabRingkasan.kt  → tab Ringkasan
 *   - TabMindMap.kt    → tab Mind Map (+ dialog edit, outline, kategori)
 *   - TabFlashcard.kt  → tab Flashcard
 *   - TabDiskusi.kt    → tab Diskusi/chat
 * Semua tetap satu package (ui.screens) jadi saling kelihatan tanpa import
 * tambahan — fungsi tiap tab ditandai `internal` (bukan `private`) supaya
 * bisa dipanggil dari file ini.
 */
private val TABS = listOf(
    "Materi"    to Icons.AutoMirrored.Outlined.LibraryBooks,
    "Catatan"   to Icons.Outlined.EditNote,
    "Ringkasan" to Icons.Outlined.Summarize,
    "Mind Map"  to Icons.Outlined.AccountTree,
    "Flashcard" to Icons.Outlined.Style,
    "Diskusi"   to Icons.Outlined.ChatBubbleOutline
)

// Tab Mind Map pakai gesture pan/zoom sendiri (detectTransformGestures) buat geser kanvas —
// kalau swipe pager tetep nyala pas lagi di tab ini, geser buat mindahin kanvas malah
// ke-tangkep pager jadi ganti tab. Tab lain aman: scroll horizontal tabel di NotebookEditor
// ngikutin nested-scroll standar Compose, jadi nggak konflik kayak gesture custom kanvas.
private const val MINDMAP_PAGE = 3

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChapterDetailScreen(chId: String, vm: AppViewModel, onBack: () -> Unit, onOpenEditor: () -> Unit) {
    val ch by vm.chapter.collectAsStateWithLifecycle()
    val mats by vm.materials.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()

    // Dulu Column polos tanpa Scaffold — beda dari TaskScreen/WorkspaceDetailScreen yang
    // sama-sama pakai AppHeader lewat slot `topBar`. Konsekuensinya: layar ini nggak dapet
    // padding bawah otomatis buat navigation bar (kerasa kepotong/mepet di HP yang pakai
    // 3 tombol navigasi, bukan gesture). Disamain strukturnya di sini.
    Scaffold(
        containerColor = AppBg,
        topBar = { AppHeader(title = ch?.name ?: "Bab", subtitle = ch?.description?.takeIf { it.isNotBlank() }, onBack = onBack) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(AppBg)) {
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = AppBg,
                contentColor = AppAccent,
                edgePadding = Spacing.md,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        val pos = tabPositions[pagerState.currentPage]
                        Box(
                            Modifier.wrapContentSize(Alignment.BottomStart)
                                .offset(x = pos.left).width(pos.width).height(2.dp)
                                .background(AppAccent, RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                        )
                    }
                },
                divider = { HorizontalDivider(color = AppBorder, thickness = 1.dp) }
            ) {
                TABS.forEachIndexed { i, (label, icon) ->
                    Tab(
                        selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        modifier = Modifier.height(46.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                            Icon(icon, null, modifier = Modifier.size(14.dp), tint = if (pagerState.currentPage == i) AppAccent else AppTextDim)
                            Text(label, fontSize = 12.5.sp, color = if (pagerState.currentPage == i) AppAccent else AppTextDim, fontWeight = if (pagerState.currentPage == i) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }

            val chapter = ch
            if (chapter == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppAccent)
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = pagerState.currentPage != MINDMAP_PAGE
                ) { page ->
                    when (page) {
                        0 -> TabMateri(chapter, mats, vm, onOpenEditor)
                        1 -> TabCatatan(chapter, vm)
                        2 -> TabRingkasan(chapter, mats, vm)
                        3 -> TabMindMap(chapter, vm)
                        4 -> TabFlashcard(chapter, vm)
                        5 -> TabDiskusi(chapter, vm)
                    }
                }
            }
        }
    }
}
