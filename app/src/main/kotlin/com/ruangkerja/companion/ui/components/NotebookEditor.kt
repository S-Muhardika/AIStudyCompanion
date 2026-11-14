package com.ruangkerja.companion.ui.components

import android.content.Context
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.editor.core.Block
import com.ruangkerja.companion.editor.core.BlockId
import com.ruangkerja.companion.editor.core.headingLevelActiveAt
import com.ruangkerja.companion.editor.core.isBulletActiveAt
import com.ruangkerja.companion.editor.core.InlineStyle
import com.ruangkerja.companion.editor.core.isInlineStyleActiveAt
import com.ruangkerja.companion.editor.core.isNumberedActiveAt
import com.ruangkerja.companion.editor.controller.BlockManager
import com.ruangkerja.companion.editor.controller.CommandDispatcher
import com.ruangkerja.companion.editor.controller.HistoryManager
import com.ruangkerja.companion.editor.commands.EditorCommand
import com.ruangkerja.companion.editor.controller.parseBlocks
import com.ruangkerja.companion.editor.controller.blocksToText
import com.ruangkerja.companion.editor.controller.textBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Model blok — dokumen dipecah jadi urutan blok Teks & Tabel. Tabel SELALU
// tampil sebagai grid asli langsung di alur dokumen. Teks sekarang pakai Compose
// Rich Editor (RichTextState) — WYSIWYG beneran (com.mohamedrejeb.richeditor),
// bukan lagi markdown mentah yang syntax-nya disembunyiin doang secara visual.
//
// GANTI ARSITEKTUR BESAR dari versi sebelumnya — beberapa catatan penting:
// 1. DEPENDENCY BARU wajib ditambahin manual ke build.gradle (nggak ada di zip ini):
//      implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.0.0")
// 2. Tabel TETAP hybrid pakai kode custom (TableGrid di TableEditor.kt) — library-nya nggak
//    punya dukungan tabel bawaan, jadi bagian ini nggak disentuh sama sekali.
// 3. Checklist toolbar masih DIBUANG — nggak ketemu API resmi buat itu di dokumentasi
//    library-nya, dari pada nebak nama method yang mungkin nggak ada. Blockquote ("Kotak
//    Contoh") SEBALIKNYA udah ditambahin lagi di v54 — bukan lewat API library (nggak ada),
//    tapi blok terpisah custom yang nyimpen prefix "> " manual, pola sama kayak tabel.
// 4. Aksi AI sekarang kerja di LEVEL SATU BLOK PENUH (toMarkdown/setMarkdown),
//    bukan lagi bisa pilih sebagian teks doang — soalnya posisi seleksi
//    (state.selection) itu di ruang teks-hasil-render, sedangkan markdown mentah
//    beda panjang karena ada syntax char; nggak ada API confirmed buat translate
//    antara dua ruang itu, jadi disederhanain ke seluruh isi blok biar aman.
// 5. Insert tabel sekarang nambah blok BARU setelah blok yang lagi fokus (bukan
//    motong persis di posisi kursor kayak sebelumnya) — alasan sama kayak no.4.
// 6. Insert gambar nempel di AKHIR blok yang fokus, bukan presisi di kursor.
// 7. Undo/redo BALIK LAGI, tapi sekarang HYBRID CUSTOM (persis kayak tabel) — bukan pakai
//    RichTextState bawaan (state.undo()/state.redo() TIDAK ADA, konfirmasi gagal compile
//    "Unresolved reference" di rc13). Nyimpen snapshot markdown seluruh dokumen sendiri
//    (undoStack/redoStack di dalam Composable), restore-nya lewat parseBlocks ulang. Histori-nya
//    jadi SATU gabungan se-dokumen (bukan per-blok) — lihat blok kode di dekat "liveText".
// 8. Toolbar disatuin jadi SATU baris aja (nggak ada lagi mode "lagi nyeleksi"
//    vs "normal" yang gonta-ganti) — lebih simpel & nggak butuh deteksi seleksi.
//
// GANTI KOMPONEN di v62: dari RichTextEditor (material3) ke BasicRichTextEditor —
// dugaan kuat RichTextEditor punya default background terisi ngikutin konvensi TextField
// Material3 (beda sama OutlinedTextField/BasicTextField yang transparan), itu penyebab
// Kotak Contoh kelihatan kayak kotak terpisah walau nggak ada background eksplisit yang
// ditambahin sendiri. BasicRichTextEditor CONFIRMED ada (kelihatan di riwayat rilis
// library-nya — "Expose BasicRichTextEditor"), tapi:
//   - Path package-nya (com.mohamedrejeb.richeditor.ui.BasicRichTextEditor, tanpa
//     .material3) itu inferensi dari konvensi library, bukan liat langsung di source.
//   - Parameter `placeholder` KEMUNGKINAN nggak ada di versi Basic ini (beda sama
//     BasicTextField Compose yang juga nggak punya placeholder bawaan) — makanya
//     placeholder Block.Text diganti Box overlay manual baca liveText, dan placeholder
//     Block.Quote dibuang aja (udah pre-fill "Contoh: " pas disisipkan, jadi jarang beneran
//     butuh state kosong).
//   - `textStyle` kemungkinan besar tetap ada (BasicTextField Compose juga punya ini).
//
// GANTI BESAR di v65: Heading ("Judul"/"Subjudul") sebelumnya diterapin lewat
// toggleSpanStyle(fontSize=X, fontWeight=Bold) doang — ini CUMA ngebesarin & nebelin teks,
// TANPA penanda semantik apa pun. Efeknya: kelihatan kayak heading pas ngedit, tapi pas
// disimpen (toMarkdown) nggak keluar sebagai "#"/"##", cuma jadi teks tebal biasa — hilang
// pas dibuka lagi di penampil. Ketemu akar & solusinya langsung dari dokumentasi RESMI
// (https://mohamedrejeb.github.io/compose-rich-editor/headings/, bukan tebakan lagi):
// library-nya PUNYA API heading khusus (`HeadingStyle` enum: Normal/H1..H6,
// `state.setHeadingStyle(HeadingStyle.H2)`, `state.currentHeadingStyle`) yang CONFIRMED
// round-trip lossless ke "#"/"##" markdown standar — dokumentasinya bahkan eksplisit bilang
// pendekatan SpanStyle lama itu "breaks the moment a user applies partial formatting",
// persis masalah yang kejadian. Sekarang "Judul" → HeadingStyle.H1, "Subjudul" → H2.
// Block & newId() PINDAH ke editor/core/Block.kt (Fase 1 migrasi arsitektur "jalan tengah" —
// compose-rich-editor TETAP dipakai apa adanya sebagai Layer 1, gak di-fork; Block jadi bagian
// Layer 2 "RKR Editor Engine"). Diimport di atas — bentuk & isi persis sama, murni pindah lokasi.

// isTableRow/isTableSeparator/isQuoteLine/stripQuotePrefix/addQuotePrefix/textBlock/quoteBlock/
// parseBlocks/blocksToText PINDAH ke editor/controller/BlockManager.kt (Fase 1 lanjutan —
// pindahan murni, gak ubah logic). Diimport di atas.

// ── [DIHAPUS, tahap 4 dead-code] Slash command ("/"): enum SlashCommand, commitSlashCommand(),
// SlashCommandMenu, CaretPopupPositionProvider, dan konstanta SLASH_TRIGGER_ID (editor/core/Block.kt).
// Terverifikasi tidak ada pemanggilnya, dan tidak ada satu pun registerTrigger("slash") di kode
// aktif — sejak mesin editor pindah ke EditText native, deteksi "/" memang tidak ada (lihat
// catatan di atas BlockManager.kt), jadi menu ini mustahil muncul. Kalau fitur ini mau dibangun
// ulang, mulai dari deteksi "/" di blok Text native; versi lama (berbasis Trigger RichTextState)
// ada di companion-v2.2.3-20260930.zip. ──

/**
 * Editor materi WYSIWYG — pakai Compose Rich Editor (com.mohamedrejeb.richeditor), lihat
 * catatan lengkap di komentar atas file soal dependency baru & batasan hasil migrasi.
 *
 * Auto-save didebounce 800ms, selalu jalan tanpa toggle, plus ikon Simpan manual di header.
 *
 * @param onClose dipanggil saat user tap panah kembali di header (satu-satunya jalur keluar —
 *        selalu save dulu sebelum nutup).
 * @param onPickImage suspend function yang nyalin gambar terpilih ke storage lokal app dan
 *        balikin path absolut buat disisipkan ke Markdown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookEditor(
    initialTitle: String,
    initialContent: String,
    onSave: (title: String, content: String) -> Unit,
    onClose: () -> Unit,
    onPickImage: suspend (Uri) -> String?,
    onAIAction: (suspend (action: String, text: String) -> String?)? = null,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf(initialTitle) }
    val blocks = remember { parseBlocks(initialContent).toMutableStateList() }
    // [FIX7] Kalau materi diakhiri Tabel/Kotak Contoh (bukan Teks), tambahin satu Block.Text
    // kosong di akhir — LOKAL di sini doang, parseBlocks() di BlockManager.kt gak disentuh
    // (dipakai di tempat lain juga, gak mau ubah kontraknya). Ini biar SELALU ada Block.Text
    // asli di ujung buat "nyerap" tap di ruang kosong bawah layar lewat .heightIn(min=...)
    // (lihat EditorBlockList) — bukan lewat requestFocus() terprogram yang IME-nya bisa nolak.
    if (blocks.lastOrNull() !is Block.Text) {
        blocks.add(textBlock(""))
    }
    var liveText by remember { mutableStateOf(blocksToText(blocks)) }
    val focusedBlockIndexState = remember { mutableIntStateOf((blocks.size - 1).coerceAtLeast(0)) }
    var focusedBlockIndex by focusedBlockIndexState
    val blockManager = remember { BlockManager(blocks, focusedBlockIndexState) }
    val dispatcher = remember { CommandDispatcher(blockManager) }
    // [FIX5] Token kepisah dari focusedBlockIndex — masalahnya: onFocusBlock (lihat di bawah)
    // dulu cuma `focusedBlockIndex = idx`. Kalau idx-nya SAMA kayak yang lagi kesimpen (kasus
    // paling umum: dokumen isi 1 blok, atau tap-area-kosong nunjuk balik ke blok yang emang
    // udah "focused" secara logika walau IME-nya lagi ketutup manual), Compose nganggep NILAI
    // GAK BERUBAH — apapun yang harusnya bereaksi ke situ (LaunchedEffect(isFocused) di Quote,
    // atau NativeTextField yang baca shouldHaveFocus) gak pernah keulang, keyboard gak pernah
    // nongol lagi. focusRequestSeq SELALU naik tiap ada PERMINTAAN fokus baru (baik dari tap
    // area kosong maupun dari blok lain yang di-tap langsung), dialirin ke EditorBlockList buat
    // dipakai sebagai token pembeda "ini permintaan BARU" di NativeTextField/Quote's effect.
    val focusRequestSeq = remember { mutableIntStateOf(0) }

    var savedTick by remember { mutableStateOf(false) }
    var aiMenuOpen by remember { mutableStateOf(false) }
    var aiLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val wordCount = remember(liveText) { liveText.trim().let { t -> if (t.isBlank()) 0 else t.split(Regex("\\s+")).size } }
    val charCount = liveText.length

    // ── Undo/redo HYBRID custom — dibangun sendiri di luar library (persis kayak tabel),
    // biar nggak gantung ke API RichTextState yang nggak ketemu buktinya. Nyimpen snapshot
    // markdown SELURUH DOKUMEN (gabungan semua blok, bukan per-blok). Restore-nya parseBlocks
    // ulang dari snapshot itu. Stack bookkeeping-nya di HistoryManager (Fase 3). ──
    val historyManager = remember { HistoryManager() }
    var suppressHistoryPush by remember { mutableStateOf(false) }
    fun restoreSnapshot(snapshot: String) {
        suppressHistoryPush = true
        val fresh = parseBlocks(snapshot)
        // Sebelumnya selalu lompat ke blok TERAKHIR abis undo/redo (makanya kerasa "geser-geser"
        // — posisi baca/scroll ketarik ke bawah tiap kali). Sekarang dipertahanin di INDEX yang
        // sama (di-clamp biar tetep valid), jadi nggak lompat kalau lagi ngedit di tengah dokumen.
        val keepIndex = focusedBlockIndex.coerceIn(0, (fresh.size - 1).coerceAtLeast(0))
        blocks.clear()
        blocks.addAll(fresh)
        focusedBlockIndex = keepIndex
        liveText = snapshot
    }
    fun performUndo() {
        val snap = historyManager.undo() ?: return
        restoreSnapshot(snap)
    }
    fun performRedo() {
        val snap = historyManager.redo() ?: return
        restoreSnapshot(snap)
    }

    fun doSave() = onSave(title, liveText)

    // ── PENTING: sebelumnya pakai derivedStateOf { blocksToText(blocks) } buat ngedeteksi
    // perubahan, TERNYATA nggak ke-trigger pas ngetik di RichTextEditor (undo/redo & auto-save
    // jadi mandek — dilaporkan langsung dari device). Kemungkinan besar RichTextState (state
    // punya library luar) nggak nge-propagate lewat derivedStateOf kalau cuma dibaca via
    // .toMarkdown() dari luar. Diganti total ke POLLING manual tiap 300ms yang baca
    // blocksToText(blocks) langsung — nggak gantung asumsi reactivity Compose ke state
    // punya library lain. wordCount/charCount/auto-save/histori-undo semua dari SATU sumber
    // (liveText, `mutableStateOf<String>` biasa yang dijamin reaktif) di loop ini. ──
    LaunchedEffect(Unit) {
        historyManager.seed(liveText)
        var lastSeen = liveText
        var lastSeenTitle = title
        var stableTicks = 0
        while (true) {
            delay(300)
            val now = blocksToText(blocks)
            liveText = now
            val nowTitle = title
            if (now == lastSeen && nowTitle == lastSeenTitle) {
                stableTicks++
            } else {
                stableTicks = 0
                lastSeen = now
                lastSeenTitle = nowTitle
            }
            if (stableTicks == 2) {
                if (!suppressHistoryPush) historyManager.pushIfChanged(now)
                suppressHistoryPush = false
                doSave()
                savedTick = true
                delay(1000)
                savedTick = false
            }
        }
    }

    // richState/focusedTextBlock/defaultTableRows/insertTableAtCursor/insertQuoteAtCursor/
    // removeTableBlock/removeQuoteBlock PINDAH ke BlockManager (Fase 1) — diakses lewat
    // blockManager.xxx() di bawah, pindahan murni gak ubah logic sama sekali.

    // ── Aksi AI di SELURUH ISI blok yang fokus — dua jalur (Text=NativeTextState/
    // Quote=RichTextState), API baca/tulis teksnya beda nama (.text vs .toMarkdown()). ──
    fun applyAIAction(action: String) {
        if (onAIAction == null || aiLoading) return
        val focused = blockManager.focusedBlock() ?: return
        val source = when (focused) {
            is Block.Text -> focused.nativeState.text
            is Block.Quote -> focused.state.toMarkdown()
            else -> return
        }
        if (source.isBlank()) return
        aiLoading = true
        scope.launch {
            // [FIX] Dulu kegagalan AI = hasil null = tidak ada umpan balik sama sekali. Sekarang
            // pesan kegagalannya (koneksi / server / API) tampil lewat Toast.
            var failure: String? = null
            val result = try {
                onAIAction(action, source)
            } catch (e: kotlinx.coroutines.CancellationException) {
                aiLoading = false
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "AI tidak dapat memproses teks ini. Coba lagi."
                null
            }
            aiLoading = false
            if (result.isNullOrBlank()) {
                Toast.makeText(ctx, failure ?: "AI tidak mengembalikan teks. Coba lagi.", Toast.LENGTH_LONG).show()
            } else when (focused) {
                is Block.Text -> focused.nativeState.setMarkdown(result)
                is Block.Quote -> focused.state.setMarkdown(result)
                else -> {}
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val path = onPickImage(uri)
                if (path != null) {
                    when (val focused = blockManager.focusedBlock()) {
                        is Block.Text -> focused.nativeState.setMarkdown(focused.nativeState.toMarkdown().trimEnd() + "\n\n![]($path)\n")
                        is Block.Quote -> focused.state.setMarkdown(focused.state.toMarkdown().trimEnd() + "\n\n![]($path)\n")
                        else -> {}
                    }
                }
            }
        }
    }

    // ── PENTING (v84, fix cursor/seleksi berat): dua callback ini di-`remember` EKSPLISIT
    // (bukan lambda inline biasa) supaya referensinya STABIL lintas recomposition — biar
    // EditorBlockList(...) di bawah bisa di-SKIP Compose pas cuma status toolbar yang berubah
    // (currentSpanStyle/currentHeadingStyle/dst, baca di bawah). Kalau lambda-nya dibiarin
    // dibikin ulang tiap recomposition, EditorBlockList bakal keanggep "parameternya beda"
    // tiap kali walau isinya sama persis — parameter lain (blocks/blockManager) SUDAH stabil
    // dengan sendirinya (SnapshotStateList & class biasa yang di-remember sekali), cuma dua
    // lambda closure ini yang butuh dijamin manual. ──
    val onFocusBlock = remember { { idx: Int -> focusedBlockIndex = idx; focusRequestSeq.intValue += 1 } }

    Column(modifier.fillMaxSize().background(AppBg).windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom).union(WindowInsets.ime))) {
        // ── Header: AppHeader yang sama kayak semua layar lain. Judulnya SEKARANG ikut judul
        // materi yang lagi diketik (live), bukan label generik "Editor Materi" lagi — jadi
        // header nunjukin materi APA yang lagi dibuka, bukan sekadar nama layarnya. Fallback
        // "Tanpa Judul" pas masih kosong (dokumen baru), niru pola umum app catatan lain.
        // Undo/Redo balik lagi di sini, sekarang manggil performUndo/performRedo custom
        // (lihat catatan di atas file) — bukan lagi state.undo()/redo() yang nggak ada. ──
        // ── Header: AppHeader yang sama kayak semua layar lain. Judul SEKARANG diketik
        // LANGSUNG di sini (titleContent) — field besar terpisah yang dulu ada di badan
        // halaman (di bawah toolbar) UDAH DIBUANG, ini satu-satunya tempat edit judul.
        // Style disamain kayak Text() default AppHeader (18sp SemiBold) biar konsisten sama
        // header layar lain, bukan sebesar 21sp/Bold yang dulu (itu ukuran buat konteks
        // badan halaman, kegedean buat baris header yang sempit). ──
        AppHeader(
            title = title.ifBlank { "Tanpa Judul" },
            onBack = { doSave(); onClose() },
            titleContent = {
                BasicTextField(
                    value = title, onValueChange = { v -> title = v },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                    cursorBrush = SolidColor(AppAccent), singleLine = true,
                    decorationBox = { inner ->
                        if (title.isEmpty()) Text("Tanpa Judul", color = AppTextDim, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        inner()
                    }
                )
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = { performUndo() }, enabled = historyManager.canUndo, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Urungkan", tint = if (historyManager.canUndo) AppTextMuted else AppTextDim, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { performRedo() }, enabled = historyManager.canRedo, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Redo, "Ulangi", tint = if (historyManager.canRedo) AppTextMuted else AppTextDim, modifier = Modifier.size(16.dp))
                }
                Box(Modifier.width(1.dp).height(18.dp).background(AppBorder))
                IconButton(onClick = { doSave(); Toast.makeText(ctx, "Tersimpan", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Save, "Simpan", tint = AppAccent, modifier = Modifier.size(17.dp))
                }
            }
        }

        // ── Status format di posisi kursor SEKARANG — dibaca LANGSUNG (bukan derivedStateOf/
        // di-remember) tiap recomposition, biar toolbar keupdate real-time pas kursor pindah.
        // [v92] Sekarang NYABANG tergantung tipe blok fokus: Block.Quote masih baca API
        // RichTextState (currentSpanStyle dkk, TETAP jalan persis kayak sebelumnya); Block.Text
        // (native) baca dari isInlineStyleActiveAt(inlineStyles, ...) — cek langsung ke daftar
        // rentang gaya, bukan scan teks lagi (v90/v91) — hasilnya dinormalisasi ke set boolean
        // yang SAMA biar kode toolbar di bawah (StyleChip/ToolbarBtn) gak perlu tau/peduli itu
        // dari jalur mana. ──
        val focusedBlockNow = blockManager.focusedBlock()
        val richActive = (focusedBlockNow as? Block.Quote)?.state
        val nativeActive = (focusedBlockNow as? Block.Text)?.nativeState
        val curSpan = richActive?.currentSpanStyle
        val isBoldNow = if (nativeActive != null) isInlineStyleActiveAt(nativeActive.inlineStyles, nativeActive.selectionEnd, InlineStyle.Bold) else curSpan?.fontWeight == FontWeight.Bold
        val isItalicNow = if (nativeActive != null) isInlineStyleActiveAt(nativeActive.inlineStyles, nativeActive.selectionEnd, InlineStyle.Italic) else curSpan?.fontStyle == FontStyle.Italic
        val isUnderlineNow = if (nativeActive != null) isInlineStyleActiveAt(nativeActive.inlineStyles, nativeActive.selectionEnd, InlineStyle.Underline) else curSpan?.textDecoration?.contains(TextDecoration.Underline) == true
        val isStrikeNow = if (nativeActive != null) isInlineStyleActiveAt(nativeActive.inlineStyles, nativeActive.selectionEnd, InlineStyle.Strikethrough) else curSpan?.textDecoration?.contains(TextDecoration.LineThrough) == true
        val isBulletNow = if (nativeActive != null) isBulletActiveAt(nativeActive.text, nativeActive.selectionEnd) else richActive?.isUnorderedList == true
        val isNumberedNow = if (nativeActive != null) isNumberedActiveAt(nativeActive.text, nativeActive.selectionEnd) else richActive?.isOrderedList == true
        val isCodeNow = if (nativeActive != null) isInlineStyleActiveAt(nativeActive.inlineStyles, nativeActive.selectionEnd, InlineStyle.Code) else richActive?.isCodeSpan == true
        val nativeHeadingLevel = if (nativeActive != null) headingLevelActiveAt(nativeActive.headingLines, nativeActive.selectionEnd) else 0
        val isNormalNow = if (nativeActive != null) nativeHeadingLevel == 0 else richActive?.currentHeadingStyle == HeadingStyle.Normal
        val isJudulNow = if (nativeActive != null) nativeHeadingLevel == 1 else richActive?.currentHeadingStyle == HeadingStyle.H1
        val isSubjudulNow = if (nativeActive != null) nativeHeadingLevel == 2 else richActive?.currentHeadingStyle == HeadingStyle.H2

        // ── Toolbar — satu baris chip gaya + satu baris ikon format (TETAP tampil terus) ──
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            StyleChip("Normal", isHeading = false, active = isNormalNow) { dispatcher.dispatch(EditorCommand.SetHeading(HeadingStyle.Normal)) }
            StyleChip("Judul", isHeading = true, active = isJudulNow) { dispatcher.dispatch(EditorCommand.SetHeading(HeadingStyle.H1)) }
            StyleChip("Subjudul", isHeading = true, big = false, active = isSubjudulNow) { dispatcher.dispatch(EditorCommand.SetHeading(HeadingStyle.H2)) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarBtn(Icons.Default.FormatBold, "Bold", active = isBoldNow) { dispatcher.dispatch(EditorCommand.ToggleBold) }
            ToolbarBtn(Icons.Default.FormatItalic, "Italic", active = isItalicNow) { dispatcher.dispatch(EditorCommand.ToggleItalic) }
            ToolbarBtn(Icons.Default.FormatUnderlined, "Underline", active = isUnderlineNow) { dispatcher.dispatch(EditorCommand.ToggleUnderline) }
            ToolbarBtn(Icons.Default.StrikethroughS, "Coret", active = isStrikeNow) { dispatcher.dispatch(EditorCommand.ToggleStrikethrough) }
            Box(Modifier.width(1.dp).height(20.dp).background(AppBorder))
            ToolbarBtn(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet", active = isBulletNow) { dispatcher.dispatch(EditorCommand.ToggleBulletList) }
            ToolbarBtn(Icons.Default.FormatListNumbered, "Nomor", active = isNumberedNow) { dispatcher.dispatch(EditorCommand.ToggleNumberedList) }
            ToolbarBtn(Icons.Default.Code, "Kode", active = isCodeNow) { dispatcher.dispatch(EditorCommand.ToggleCode) }
            Box(Modifier.width(1.dp).height(20.dp).background(AppBorder))
            ToolbarBtn(Icons.Default.Image, "Gambar") { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            ToolbarBtn(Icons.Default.TableChart, "Tabel") { dispatcher.dispatch(EditorCommand.InsertTable) }
            ToolbarBtn(Icons.Default.FormatQuote, "Kotak Contoh") { dispatcher.dispatch(EditorCommand.InsertCallout) }
            if (onAIAction != null) {
                Box(Modifier.width(1.dp).height(20.dp).background(AppBorder))
                Box {
                    ToolbarBtn(Icons.Default.AutoAwesome, "AI") { aiMenuOpen = true }
                    DropdownMenu(
                        expanded = aiMenuOpen, onDismissRequest = { aiMenuOpen = false },
                        containerColor = AppBgCard, shape = RoundedCornerShape(Radius.card),
                        modifier = Modifier.border(1.dp, AppBorder, RoundedCornerShape(Radius.card))
                    ) {
                        AiMenuItem("Lanjutkan", Icons.Default.AutoAwesome) { aiMenuOpen = false; applyAIAction("continue") }
                        AiMenuItem("Tulis Ulang", Icons.Default.Refresh) { aiMenuOpen = false; applyAIAction("rewrite") }
                        AiMenuItem("Perluas", Icons.Default.UnfoldMore) { aiMenuOpen = false; applyAIAction("expand") }
                        AiMenuItem("Persingkat", Icons.Default.UnfoldLess) { aiMenuOpen = false; applyAIAction("shorten") }
                        AiMenuItem("Perbaiki", Icons.Default.AutoFixHigh) { aiMenuOpen = false; applyAIAction("improve") }
                        AiMenuItem("Ubah Nada", Icons.Default.RecordVoiceOver) { aiMenuOpen = false; applyAIAction("tone") }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppBorder))

        // ── Konten — nggak pakai card lagi, langsung nempel di background halaman ──
        Column(Modifier.weight(1f).fillMaxWidth()) {
            // ── v84: loop blok DIPINDAH ke composable terpisah EditorBlockList (definisi di
            // bawah fun ini) — lihat komentar panjang di deket onFocusBlock/onCommitSlash di
            // atas buat alasannya. Parameter di bawah ini SENGAJA cuma yang beneran dibutuhin
            // isinya (bukan seluruh state NotebookEditor), biar Compose bisa nge-skip manggil
            // ulang seluruh isi EditorBlockList pas activeState.currentSpanStyle/dst di toolbar
            // (bawah dikit dari sini, atas file) berubah tiap drag seleksi. ──
            EditorBlockList(
                blocks = blocks,
                focusedBlockIndex = focusedBlockIndex,
                onFocusBlock = onFocusBlock,
                focusRequestSeq = focusRequestSeq.intValue,
                blockManager = blockManager,
                liveText = liveText,
                aiLoading = aiLoading,
                savedTick = savedTick,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppBorder))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$wordCount kata", color = AppTextMuted, fontSize = 10.5.sp)
                Text("$charCount karakter", color = AppTextMuted, fontSize = 10.5.sp)
            }
        }
    }
}

// ── EditorBlockList (BARU v84) — seluruh loop render blok (Teks/Quote/Tabel) + tap-catcher
// area kosong + status auto-save floating, DIPISAH dari NotebookEditor buat fix "cursor/
// seleksi berat pas drag". AKAR MASALAH yang ketemu: di NotebookEditor, baris baca
// activeState.currentSpanStyle/currentHeadingStyle/isUnorderedList/dst (buat nyalain tombol
// toolbar sesuai posisi kursor) ada di SCOPE COMPOSABLE YANG SAMA kayak seluruh loop blok ini
// — dan properti-properti itu BERUBAH TIAP FRAME pas nge-drag seleksi (bukan cuma pas ganti
// blok). Karena gak ada BATAS composable antara pembacaan itu sama loop blok, Compose kepaksa
// nyusurin ULANG SELURUH isi loop (walau isi tiap blok akhirnya di-skip individual) tiap kali
// itu kejadian — biayanya numpuk sebanding jumlah blok di dokumen, jalan di TIAP FRAME drag.
// Motong jadi composable terpisah kayak di bawah ini bikin Compose bisa SKIP manggil ulang
// SELURUH function ini kalau parameter-nya (blocks/focusedBlockIndex/liveText/dst) gak
// beneran berubah — toolbar di NotebookEditor tetep recompose tiap drag (WAJAR, dia emang
// harus nampilin status format live), tapi EditorBlockList di bawah TIDAK IKUT KETARIK lagi.
//
// PARAMETER PENTING: onFocusBlock & onCommitSlash HARUS lambda yang di-`remember` stabil dari
// pemanggil (lihat NotebookEditor) — bukan lambda inline biasa, soalnya lambda baru tiap
// recomposition bikin Compose ngira parameter-nya "beda" walau isinya sama, yang balikin lagi
// masalah yang mau difix. `blocks` (SnapshotStateList) & `blockManager` (class biasa yang
// di-remember sekali di NotebookEditor) udah stabil dengan sendirinya, gak perlu perlakuan
// khusus.
//
// key(block.id) di tiap iterasi: identitas eksplisit per blok (BUKAN cuma posisi index) buat
// Compose — biar insert/hapus blok di tengah dokumen (Tabel/Kotak Contoh) gak bikin state
// blok-blok SETELAHNYA (FocusRequester/BringIntoViewRequester yang udah di-remember(block.id))
// ketuker slot-nya. Sejalan sama pola remember(block.id) yang emang udah dipake dari awal,
// cuma sekarang ditegasin di level composable-nya juga.
//
// CATATAN JUJUR: fix ini ngilangin biaya "nyusurin semua blok tiap frame drag" di LEVEL APP.
// KALAU habis ini di-test di HP masih kerasa berat, itu artinya sisa bebannya emang dari
// DALEM BasicRichTextEditor sendiri (kerja internal library ngitung ulang layout/annotated
// string tiap event seleksi, di LUAR kendali kode kita) — baru di titik itu opsi (a) balik ke
// markdown-mentah+preview jadi relevan lagi. Coba fix ini dulu sebelum mikir opsi (a). ──
@Composable
private fun EditorBlockList(
    blocks: SnapshotStateList<Block>,
    focusedBlockIndex: Int,
    onFocusBlock: (Int) -> Unit,
    focusRequestSeq: Int,
    blockManager: BlockManager,
    liveText: String,
    aiLoading: Boolean,
    savedTick: Boolean,
    modifier: Modifier = Modifier
) {
    // [FIX8] Box -> BoxWithConstraints — maxHeight dari sini dipakai buat tinggi minimum blok
    // Teks TERAKHIR di bawah (gantiin angka tebakan 400dp yang kebukti nyisain "zona mati" di
    // draft/materi yang isinya dikit — user ngirim tangkapan layar nunjukin persis batasnya).
    BoxWithConstraints(modifier) {
        // [FIX9] Ditangkep ke val biasa DI SINI — BoxWithConstraintsScope.maxHeight ke-blok
        // Kotlin (@LayoutScopeMarker) kalau diakses implisit dari DALAM scope lain (ColumnScope
        // dari Column{} di bawah). Val Dp biasa gak punya masalah itu.
        val availableHeight = maxHeight
        val contentScroll = rememberScrollState()
        // [FIX5] Buat blok Quote (BasicRichTextEditor, Compose murni — beda dari Block.Text yang
        // native EditText di atas) — requestFocus() doang gak jamin IME kebuka LAGI kalau View
        // ini UDAH "focused" dari sebelumnya (IME ditutup manual gak ngilangin fokus Compose-nya
        // juga). Diminta muncul eksplisit lewat keyboardController.show() sekalian.
        val keyboardController = LocalSoftwareKeyboardController.current
        val view = LocalView.current
        // ── Tap di area KOSONG (di bawah/sekitar konten, gak kena blok mana pun) =
        // fokus ke blok teks TERAKHIR, kursor ke UJUNG teksnya — niru "klik di bawah
        // baris terakhir" ala Word, biar gak harus presisi nge-tap pas di teksnya.
        // DI BELAKANG (Z-order sebelum) Column konten — tap yang KENA blok asli tetap
        // ditangani blok itu sendiri duluan (blok di depan/atas selalu menang), ini
        // cuma nadah tap yang "tembus" karena gak ada apa-apa di posisi itu. ──
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                val idx = blockManager.lastTextBlockIndex() ?: return@clickable
                when (val b = blocks.getOrNull(idx)) {
                    is Block.Text -> { val end = b.nativeState.text.length; b.nativeState.applyStyled(b.nativeState.text, b.nativeState.inlineStyles, end, end) }
                    is Block.Quote -> b.state.selection = TextRange(b.state.annotatedString.text.length)
                    else -> {}
                }
                onFocusBlock(idx)
            }
        )
        Column(Modifier.fillMaxSize().verticalScroll(contentScroll).padding(horizontal = 8.dp)) {
            // Hardening key(block.id) — SARAN AUDIT: id di-generate UUID lewat newId()/textBlock()/
            // quoteBlock() di BlockManager, jadi HARUSNYA selalu unik — tapi itu cuma dijaga lewat
            // DISIPLIN di semua call site pembuatan Block, bukan jaminan struktural. key() Compose
            // MEWAJIBKAN key unik antar-saudara di scope yang sama; kalau suatu saat ada jalur baru
            // yang somehow ngasih id yang udah kepake (mis. undo/redo restore snapshot lama, fitur
            // duplicate-block, atau nanti pull dari sync cloud yang bawa id yang udah ada di lokal),
            // Compose CRASH KERAS ("Key ... was already used"), bukan cuma glitch kecil.
            // Guard ini: kalau ketemu id yang somehow udah muncul sebelumnya di render PASS ini,
            // block yang duplikat jatuh ke key gabungan sama index (kehilangan continuity state-nya
            // doang, mis. posisi fokus/scroll blok itu) alih-alih bikin SELURUH layar crash. Jalur
            // normal (id selalu unik, kondisi hari ini) sama sekali gak kepengaruh — tetep key murni
            // block.id kayak sebelumnya.
            val seenBlockIds = HashSet<BlockId>()
            blocks.forEachIndexed { idx, block ->
                val keyValue: Any = if (seenBlockIds.add(block.id)) block.id else "${block.id.value}#dup$idx"
                key(keyValue) {
                    when (block) {
                        is Block.Text -> {
                            val bringIntoView = remember(block.id) { BringIntoViewRequester() }
                            val isFocused = focusedBlockIndex == idx
                            LaunchedEffect(isFocused) {
                                if (isFocused) {
                                    delay(50)
                                    bringIntoView.bringIntoView()
                                }
                            }
                            // BasicRichTextEditor DIGANTI NativeTextField (v90, mesin EditText native
                            // — lihat NativeTextField.kt) — placeholder-nya masih pola sama, dicek dari
                            // liveText yang confirmed reaktif, bukan baca state langsung.
                            Box(Modifier.fillMaxWidth()) {
                                if (liveText.isBlank() && blocks.size == 1) {
                                    Text("Mulai tulis di sini…", color = AppTextDim, fontSize = 12.sp)
                                }
                                NativeTextField(
                                    state = block.nativeState,
                                    // [FIX7]+[FIX8] Blok Teks TERAKHIR dikasih tinggi minimum =
                                    // SISA RUANG BENERAN (maxHeight dari BoxWithConstraints,
                                    // bukan angka tebakan lagi) — tap di ruang kosong bawah jadi
                                    // tap ASLI ke EditText native ini (touch->focus->IME bawaan
                                    // Android, TERBUKTI reliable), bukan lewat requestFocus()
                                    // terprogram doang yang ternyata gak cukup buat manggil
                                    // keyboard balik walau fokus/kursor Compose-nya kepindah
                                    // (lihat NativeTextField.kt [FIX5]/[FIX6] — itu tetep
                                    // dipertahankan sebagai jaring pengaman kasus tepi).
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (idx == blocks.lastIndex) Modifier.heightIn(min = availableHeight) else Modifier)
                                        .bringIntoViewRequester(bringIntoView),
                                    textColor = AppText,
                                    fontSizeSp = 12f,
                                    shouldHaveFocus = isFocused,
                                    focusRequestToken = focusRequestSeq,
                                    onFocusChanged = { hasFocus -> if (hasFocus) onFocusBlock(idx) }
                                )
                            }
                        }
                        is Block.Quote -> {
                            val bringIntoView = remember(block.id) { BringIntoViewRequester() }
                            val focusRequester = remember(block.id) { FocusRequester() }
                            val isFocused = focusedBlockIndex == idx
                            // [FIX5] Ditambah focusRequestSeq sebagai key kedua — dulu cuma
                            // LaunchedEffect(isFocused), jadi kalau blok INI SAJA yang di-tap
                            // ulang (isFocused TETEP true dari awal, gak pernah balik false),
                            // efek ini gak pernah restart & requestFocus() gak pernah kepanggil
                            // lagi. focusRequestSeq naik tiap ada permintaan fokus baru dari
                            // onFocusBlock (lihat NotebookEditor), walau ke blok yang sama.
                            LaunchedEffect(isFocused, focusRequestSeq) {
                                if (isFocused) {
                                    delay(50)
                                    bringIntoView.bringIntoView()
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                    // [FIX6] Jaring pengaman: panggilan tegas SHOW_FORCED
                                    // langsung, buat jaga-jaga keyboardController.show() di
                                    // internal-nya senasib sama SHOW_IMPLICIT (bisa diabaikan
                                    // sistem abis user nutup manual).
                                    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                    imm?.showSoftInput(view, InputMethodManager.SHOW_FORCED)
                                }
                            }
                            val onBlockFocusChanged = remember(block.id, idx) {
                                { fs: FocusState -> if (fs.isFocused) onFocusBlock(idx) }
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(IntrinsicSize.Min)) {
                                Box(Modifier.width(3.dp).fillMaxHeight().background(AppAccent, RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(12.dp))
                                BasicRichTextEditor(
                                    state = block.state,
                                    modifier = Modifier
                                        .weight(1f)
                                        .bringIntoViewRequester(bringIntoView)
                                        .focusRequester(focusRequester)
                                        .onFocusChanged(onBlockFocusChanged),
                                    textStyle = TextStyle(color = AppTextMuted, fontSize = 12.sp)
                                )
                                IconButton(onClick = { blockManager.removeQuoteBlock(idx) }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Outlined.Close, "Hapus kotak contoh", tint = AppTextDim, modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                        is Block.Table -> TableGrid(rows = block.rows, onDelete = { blockManager.removeTableBlock(idx) }, onFocus = { onFocusBlock(idx) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // ── Status auto-save FLOATING di atas konten — sebelumnya di Row TERPISAH
        // di bawah yang tinggi-nya berubah tergantung ada-nggaknya teks (kosong = cuma
        // padding, ada teks = padding+tinggi teks), jadi kolom konten di atasnya (yang
        // pakai weight(1f)) ikut nyusut-melebar tiap kali status itu muncul/ilang — itu
        // penyebab kerasa "geser-geser". Sekarang float di ATAS konten via Box overlay,
        // sama sekali nggak ikut alur layout, jadi nggak ada yang ke-reflow lagi. ──
        androidx.compose.animation.AnimatedVisibility(
            visible = aiLoading || savedTick,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { h -> -h / 2 },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { h -> -h / 2 }
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppBgCard)
                    .border(1.dp, AppBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (aiLoading) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = AppAccent)
                    Text("AI sedang menulis…", color = AppAccent, fontSize = 10.5.sp)
                } else if (savedTick) {
                    Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(12.dp))
                    Text("Disimpan otomatis", color = AppTextMuted, fontSize = 10.5.sp)
                }
            }
        }
    }
}

@Composable
private fun StyleChip(label: String, isHeading: Boolean, big: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) AppAccent.copy(alpha = 0.18f) else AppBgMuted)
            .border(1.dp, if (active) AppAccent else AppBorder, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            label, color = if (isHeading) AppAccent else AppTextMuted,
            fontSize = if (isHeading && big) 12.sp else 11.sp,
            fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun AiMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = AppText, fontSize = 13.sp) },
        leadingIcon = { Icon(icon, null, tint = AppAccent, modifier = Modifier.size(16.dp)) },
        onClick = onClick
    )
}

@Composable
private fun ToolbarBtn(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (active) AppAccent else AppBgMuted).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.onPrimary else AppTextMuted, modifier = Modifier.size(16.dp)) }
}
