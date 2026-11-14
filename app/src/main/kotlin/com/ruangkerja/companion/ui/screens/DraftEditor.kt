package com.ruangkerja.companion.ui.screens

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ruangkerja.companion.data.db.Draft
import com.ruangkerja.companion.editor.core.lineBoundsAt
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel
import java.util.*
import kotlinx.coroutines.delay

// [DIPINDAH, audit item 2 — 27 Sep] DraftEditorOverlay + semua helper format/parser/serializer-nya
// dipindah dari WorkspaceListScreen.kt (waktu itu 1358 baris nyampur 3 concern) ke file sendiri —
// MURNI pindah kode, nggak ada logic yang berubah. Parser/serializer (parseDraftBlocks dkk.) ikut
// pindah SEKALIAN ke sini (bukan dipecah lagi ke file ketiga) karena cuma dipakai DraftEditorOverlay
// doang, gak ada pemanggil lain. DraftEditorOverlay sendiri berubah dari `private` jadi public —
// dipanggil dari WorkspaceListScreen.kt (package sama, jadi gak perlu import tambahan di sana).

// ── DraftEditorOverlay — dulu dialog kecil (AppInput berkotak, minLines=5, tombol Simpan
// manual). Sekarang overlay LAYAR PENUH: BasicTextField polos langsung di atas AppBg (TANPA
// kotak/border, sama filosofinya kayak NotebookEditor buat materi), autosave debounced
// berjalan sendiri di belakang layar — gak ada lagi tombol Simpan yang perlu ditekan.
// initial=null berarti draft baru; draft baru & draft lama lewat overlay yang SAMA.
// ── Helper format Draft — dipakai toolbar baru di atas (checklist/bold/heading/tabel/divider).
// SEMUA beroperasi di atas TextFieldValue (bukan String polos) khusus biar tau POSISI KURSOR
// SEKARANG, jadi insert/toggle-nya kejadian PERSIS di titik yang diketik user, bukan asal
// nempel di akhir teks. Sengaja dipisah dari mesin block-based NotebookEditor — Draft tetap
// satu String panjang (nggak ada migrasi data model / skema sync), "rich"-nya murni dari
// syntax ringan (☐/☑, **, #, |tabel|, ---) + VisualTransformation pas nge-render.
// Alasan lengkap kenapa kedua editor TERPISAH (dan apa saja yang boleh dibagi): editor/ARSITEKTUR_EDITOR.md.
private const val CHECKBOX_UNCHECKED = "☐"
private const val CHECKBOX_CHECKED = "☑"
private val DRAFT_BOLD_REGEX = Regex("\\*\\*(.+?)\\*\\*")
// [FIX] Dulu tombol Divider nyisipin "---" doang (3 dash) — nyaris gak keliatan sebagai garis di
// layar HP biasa, dan gak ada styling apapun buat itu (VisualTransformation di bawah gak nyentuh
// dash/pipe sama sekali sebelumnya). 30 dash tetep valid thematic-break Markdown (CommonMark: 3+
// dash berturut = <hr>) — jadi >=3 dipertahanin di regex biar draft LAMA yang masih pakai "---"
// manual ikut kena styling dim juga (backward compatible), gak cuma draft baru.
private val DRAFT_DIVIDER_LINE = "-".repeat(30)
private val DRAFT_DIVIDER_REGEX = Regex("^-{3,}$")
// Baris separator tabel (mis. "|---------|---------|" atau baris kosong "|         |         |")
// — murni struktural/belum keisi, jadi didim + dipaksa monospace biar gak ngalihin perhatian dari
// baris isi/header beneran (yang cuma dipaksa monospace tanpa didim, lihat when-block di bawah).
private val DRAFT_TABLE_SEPARATOR_REGEX = Regex("^\\|[\\s:\\-|]+\\|$")

/** Toggle prefix (dipakai buat "# " Heading) di baris tempat kursor SEKARANG berada — kalau baris
 *  itu udah diawali prefix-nya (abaikan spasi di depan), dibuang; kalau belum, ditambahin.
 *  [FIX] Checklist DULU juga lewat fungsi generik ini dengan prefix "☐ " — masalahnya fungsi ini
 *  cuma ngerti SATU prefix tetap, jadi nge-tap tombol Checklist di baris yang udah "☑ " (checked)
 *  gak match stripped.startsWith("☐ ") dan malah numpuk jadi "☐ ☑ ...", DAN gak ada jalan lain buat
 *  nulis "☑ " ke teks sama sekali (simbolnya cuma pernah dipakai buat STYLING di
 *  rememberDraftRichTextTransformation) — checklist gak akan bisa dicentang selama-lamanya.
 *  Checklist sekarang pakai toggleChecklistLine() sendiri di bawah yang ngerti dua-duanya &
 *  cycle 3 state. */
private fun toggleLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start
    val bounds = lineBoundsAt(text, cursor)
    val start = bounds.first; val end = bounds.last
    val line = text.substring(start, end)
    val stripped = line.trimStart()
    val leadingWs = line.length - stripped.length
    return if (stripped.startsWith(prefix)) {
        val newLine = line.substring(0, leadingWs) + stripped.removePrefix(prefix)
        val newText = text.substring(0, start) + newLine + text.substring(end)
        val newCursor = (cursor - prefix.length).coerceAtLeast(start + leadingWs)
        value.copy(text = newText, selection = TextRange(newCursor))
    } else {
        val newLine = line.substring(0, leadingWs) + prefix + stripped
        val newText = text.substring(0, start) + newLine + text.substring(end)
        value.copy(text = newText, selection = TextRange(cursor + prefix.length))
    }
}

/** Cycle checklist di baris kursor SEKARANG: (gak ada checkbox) -> "☐ " -> "☑ " -> (gak ada
 *  checkbox) -> ulang lagi. Ditulis TERPISAH dari toggleLinePrefix (lihat [FIX] di doc-nya) karena
 *  butuh ngerti DUA prefix berbeda, bukan cuma satu. "☐" & "☑" sama-sama 1 code unit UTF-16 (BMP)
 *  jadi tukar ☐<->☑ gak geser panjang teks / posisi kursor sama sekali — cuma cabang HAPUS & TAMBAH
 *  yang perlu koreksi posisi kursor, pola sama kayak toggleLinePrefix. */
private fun toggleChecklistLine(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start
    val bounds = lineBoundsAt(text, cursor)
    val start = bounds.first; val end = bounds.last
    val line = text.substring(start, end)
    val stripped = line.trimStart()
    val leadingWs = line.length - stripped.length
    val uncheckedPrefix = "$CHECKBOX_UNCHECKED "
    val checkedPrefix = "$CHECKBOX_CHECKED "
    return when {
        stripped.startsWith(uncheckedPrefix) -> {
            val newLine = line.substring(0, leadingWs) + checkedPrefix + stripped.removePrefix(uncheckedPrefix)
            val newText = text.substring(0, start) + newLine + text.substring(end)
            value.copy(text = newText) // panjang gak berubah -> posisi kursor gak perlu digeser
        }
        stripped.startsWith(checkedPrefix) -> {
            val newLine = line.substring(0, leadingWs) + stripped.removePrefix(checkedPrefix)
            val newText = text.substring(0, start) + newLine + text.substring(end)
            val newCursor = (cursor - checkedPrefix.length).coerceAtLeast(start + leadingWs)
            value.copy(text = newText, selection = TextRange(newCursor))
        }
        else -> {
            val newLine = line.substring(0, leadingWs) + uncheckedPrefix + stripped
            val newText = text.substring(0, start) + newLine + text.substring(end)
            value.copy(text = newText, selection = TextRange(cursor + uncheckedPrefix.length))
        }
    }
}

/** Bungkus teks yang lagi diseleksi dengan "**...**". Kalau gak ada seleksi (cuma kursor doang),
 *  sisipin "****" dengan kursor pas di tengah, siap diketik langsung. */
private fun wrapSelectionBold(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selStart = value.selection.min
    val selEnd = value.selection.max
    return if (selStart == selEnd) {
        val newText = text.substring(0, selStart) + "****" + text.substring(selStart)
        value.copy(text = newText, selection = TextRange(selStart + 2))
    } else {
        val selected = text.substring(selStart, selEnd)
        val newText = text.substring(0, selStart) + "**" + selected + "**" + text.substring(selEnd)
        value.copy(text = newText, selection = TextRange(selStart, selEnd + 4))
    }
}

/** Sisip teks di posisi kursor SEKARANG (dipakai tombol Garis pembatas; Tabel sekarang pakai
 *  insertTableBlock() di DraftEditorOverlay, bukan sisip teks lagi — lihat [FIX2] di bawah). */
private fun insertAtCursor(value: TextFieldValue, insertion: String): TextFieldValue {
    val cursor = value.selection.start
    val newText = value.text.substring(0, cursor) + insertion + value.text.substring(cursor)
    return value.copy(text = newText, selection = TextRange(cursor + insertion.length))
}

// ── [FIX2] Tabel Draft SEBELUMNYA "rich"-nya cuma teks pipe di-monospace-in lewat
// VisualTransformation — kelurus tapi tetep teks polos, bukan tabel beneran (gak ada
// border/cell/tombol tambah baris-kolom). VisualTransformation emang gak bisa gambar grid;
// dia cuma bisa ganti GAYA teks, gak bisa bikin layout 2D. Jadi sekarang content Draft
// dipecah jadi daftar blok Teks/Tabel DI RUNTIME doang (DraftBlock) — blok Tabel dirender
// sebagai grid Compose beneran (border + BasicTextField per cell) lewat TableGrid — komposabel
// yang SAMA dengan tabel di NotebookEditor (TableEditor.kt), yang juga jadi sumber
// parseTableLines()/buildTableMarkdown() (bukan nulis ulang parser).
// Yang DISIMPAN ke DB/sync TETAP satu String polos kayak sebelumnya (serializeDraftBlocks)
// — draft lama/baru tetap kompatibel, TIDAK ada migrasi skema.
// Konversi teks->tabel HANYA kejadian pas: (1) draft dibuka pertama kali (parseDraftBlocks
// baca ulang konten tersimpan), atau (2) tombol Tabel ditekan (insertTableBlock, bikin blok
// baru langsung tanpa lewat teks). ENGGAK ada scan-ulang tiap keystroke — ngetik manual
// "|begini|" gak otomatis jadi grid sampai draft ditutup-buka lagi (dihindari sengaja,
// scan tiap keystroke = segmen berubah tiap ketikan = fokus/kursor gampang keganggu).
private sealed class DraftBlock(val id: Int) {
    class Txt(id: Int, initial: TextFieldValue) : DraftBlock(id) {
        var tfv by mutableStateOf(initial)
        // [FIX3] Tiap blok Teks punya FocusRequester SENDIRI (dulu cuma blok pertama yang
        // punya) — biar fokus bisa dipindah ke BLOK MANA PUN secara terprogram: tap area
        // kosong di bawah semua blok (focusEndOrCreateTrailingBlock) atau abis insertTableBlock.
        val focusRequester = FocusRequester()
    }
    class Tbl(id: Int, initialRows: List<List<String>>) : DraftBlock(id) {
        val rows: SnapshotStateList<SnapshotStateList<String>> = mutableStateListOf<SnapshotStateList<String>>().apply {
            initialRows.forEach { r -> add(mutableStateListOf(*r.toTypedArray())) }
        }
    }
}

private var draftBlockIdSeq = 0
private fun nextDraftBlockId(): Int = draftBlockIdSeq++

/** Baris dianggap bagian tabel kalau (setelah di-trim) diawali & diakhiri "|" dan panjangnya
 *  >1 (biar satu karakter "|" nyasar doang gak keanggep tabel). Heuristik SAMA persis kayak
 *  yang dulu dipakai VisualTransformation, cuma sekarang dipakai buat SEGMENTASI blok. */
private fun isDraftTableLine(line: String): Boolean {
    val t = line.trim()
    return t.length > 1 && t.startsWith("|") && t.endsWith("|")
}

/** Pecah content mentah jadi daftar blok Teks/Tabel. Udah disimulasikan di Python dulu
 *  (round-trip parse->serialize buat ~10 kasus tepi: kosong, tabel di awal/akhir/tengah,
 *  dua tabel berdempet, baris kosong di sekitar tabel, dst) sebelum ditulis di sini. */
private fun parseDraftBlocks(text: String): List<DraftBlock> {
    val lines = text.split("\n")
    val blocks = mutableListOf<DraftBlock>()
    var curText = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        if (isDraftTableLine(lines[i])) {
            if (curText.isNotEmpty()) {
                blocks.add(DraftBlock.Txt(nextDraftBlockId(), TextFieldValue(curText.joinToString("\n"))))
                curText = mutableListOf()
            }
            val tableLines = mutableListOf<String>()
            while (i < lines.size && isDraftTableLine(lines[i])) { tableLines.add(lines[i]); i++ }
            blocks.add(DraftBlock.Tbl(nextDraftBlockId(), parseTableLines(tableLines)))
        } else {
            curText.add(lines[i]); i++
        }
    }
    if (curText.isNotEmpty()) {
        blocks.add(DraftBlock.Txt(nextDraftBlockId(), TextFieldValue(curText.joinToString("\n"))))
    }
    if (blocks.isEmpty()) {
        blocks.add(DraftBlock.Txt(nextDraftBlockId(), TextFieldValue("")))
    }
    // [FIX7] Kalau draft diakhiri Tabel (jarang, biasanya cuma konten lama), tetep tambahin
    // satu blok Teks kosong di akhir — biar SELALU ada field asli buat "nyerap" tap di ruang
    // kosong bawah layar (lihat pemakaian .heightIn(min=...) di blok TERAKHIR, DraftEditorOverlay).
    if (blocks.last() is DraftBlock.Tbl) {
        blocks.add(DraftBlock.Txt(nextDraftBlockId(), TextFieldValue("")))
    }
    return blocks
}

/** Kebalikan parseDraftBlocks — gabung semua blok balik jadi satu String buat disimpan.
 *  Blok Tabel lewat buildTableMarkdown() (sama persis dipakai TableEditor.kt/NotebookEditor). */
private fun serializeDraftBlocks(blocks: List<DraftBlock>): String =
    blocks.joinToString("\n") { b ->
        when (b) {
            is DraftBlock.Txt -> b.tfv.text
            is DraftBlock.Tbl -> buildTableMarkdown(b.rows.map { it.toList() })
        }
    }

/** VisualTransformation READ-TIME doang (teks yang BENERAN tersimpan tetap markdown polos) —
 *  nge-bold rentang "**...**" & mbesarin baris "# "/"## ", nge-dim+coret baris checklist yang
 *  udah dicentang, plus [FIX] nge-dim baris divider ("---" dst) & bikin baris tabel (diawali "|")
 *  monospace biar kolomnya kebaca sejajar (baris separator "|---------|---------|" ikut didim
 *  karena struktural, bukan isi). Sebelumnya tabel & divider PLAIN aja, gak disentuh sama sekali
 *  di sini walau komentar desain di atas DraftEditorOverlay udah nyebut keduanya sebagai bagian
 *  dari "rich". OffsetMapping.Identity karena panjang teks yang dirender SAMA PERSIS kayak
 *  aslinya (cuma nambah gaya, bukan nyembunyiin karakter) — gak perlu mapping manual.
 *  Warna (AppTextDim) WAJIB ditangkep di sini (konteks composable), BUKAN di dalam lambda
 *  VisualTransformation-nya sendiri — itu jalan di luar composition, sama kasusnya kayak Str
 *  yang sempet salah dipanggil di dalam scope.launch beberapa sesi lalu. */
@Composable
private fun rememberDraftRichTextTransformation(): VisualTransformation {
    val dimColor = AppTextDim
    return remember(dimColor) {
        VisualTransformation { annotated ->
            val raw = annotated.text
            val builder = AnnotatedString.Builder(raw)
            DRAFT_BOLD_REGEX.findAll(raw).forEach { m ->
                builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
            }
            var offset = 0
            raw.lineSequence().forEach { line ->
                val lineEnd = offset + line.length
                val trimmed = line.trim()
                when {
                    line.startsWith("## ") -> builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp), offset, lineEnd)
                    line.startsWith("# ") -> builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp), offset, lineEnd)
                    line.trimStart().startsWith(CHECKBOX_CHECKED) -> builder.addStyle(SpanStyle(color = dimColor, textDecoration = TextDecoration.LineThrough), offset, lineEnd)
                    DRAFT_DIVIDER_REGEX.matches(trimmed) -> builder.addStyle(SpanStyle(color = dimColor, letterSpacing = 2.sp), offset, lineEnd)
                    DRAFT_TABLE_SEPARATOR_REGEX.matches(trimmed) -> builder.addStyle(SpanStyle(color = dimColor, fontFamily = FontFamily.Monospace), offset, lineEnd)
                    trimmed.startsWith("|") && trimmed.endsWith("|") -> builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), offset, lineEnd)
                }
                offset = lineEnd + 1
            }
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }
    }
}

@Composable
private fun DraftToolbarBtn(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconActionBtn(icon, contentDescription, 19.dp, onClick)
}

@Composable
fun DraftEditorOverlay(vm: AppViewModel, initial: Draft?, onDismiss: () -> Unit) {
    val draftId = remember(initial?.id) { initial?.id ?: UUID.randomUUID().toString() }
    val createdAt = remember(initial?.id) { initial?.createdAt ?: System.currentTimeMillis() }
    val originalTitle = remember(initial?.id) { initial?.title ?: "" }
    val originalContent = remember(initial?.id) { initial?.content ?: "" }
    var title by remember(initial?.id) { mutableStateOf(originalTitle) }
    // [FIX2] content TextFieldValue TUNGGAL diganti daftar blok (lihat DraftBlock di atas) —
    // biar blok Tabel bisa dirender grid beneran, bukan teks pipe. focusedBlockId nge-track
    // blok Teks mana yang lagi diketik SEKARANG, dipakai tombol format (Checklist/Tebal/Judul/
    // Garis) buat nentuin blok mana yang harus diubah — tombol2 itu no-op kalau fokusnya lagi
    // di cell tabel (gak ada blok Txt yang id-nya cocok di focusedBlockId).
    val blocks = remember(initial?.id) { mutableStateListOf<DraftBlock>().apply { addAll(parseDraftBlocks(originalContent)) } }
    val firstTxtBlockId = remember(initial?.id) { (blocks.firstOrNull() as? DraftBlock.Txt)?.id }
    var focusedBlockId by remember(initial?.id) { mutableStateOf(firstTxtBlockId) }
    // [FIX4] Token kepisah dari focusedBlockId — LaunchedEffect(focusedBlockId) yang lama BUG:
    // begitu keyboard ditutup manual (mis. back sekali, cuma nutup IME, overlay TETAP kebuka)
    // terus tap area kosong LAGI buat manggil focusEndOrCreateTrailingBlock(), blok yang
    // dituju SERING kali blok yang SAMA kayak sebelumnya (draft pendek cuma 1 blok teks) —
    // focusedBlockId di-set ke nilai yang UDAH SAMA, Compose nganggep "gak berubah", jadi
    // LaunchedEffect(focusedBlockId) gak restart & requestFocus() gak pernah kepanggil lagi.
    // Makanya "cuma bisa sekali doang". focusRequestSeq SELALU naik tiap ada permintaan fokus
    // terprogram (biar LaunchedEffect PASTI restart), walau target blok-nya sama kayak
    // sebelumnya. requestFocusOnBlock() ini yang dipanggil dari focusEndOrCreateTrailingBlock()
    // & insertTableBlock() (BUKAN dari onFocusChanged tap-langsung — itu udah dapet fokus asli
    // dari tap-nya sendiri, gak perlu diminta ulang secara terprogram).
    var focusRequestSeq by remember { mutableStateOf(0) }
    fun requestFocusOnBlock(id: Int?) {
        focusedBlockId = id
        focusRequestSeq++
    }
    // [FIX] Sebelumnya cuma ngebandingin content.isBlank() doang — LaunchedEffect(content) di
    // bawah SELALU jalan sekali pas pertama kali composable ini muncul (perilaku standar
    // Compose, bukan cuma pas content BERUBAH), jadi buka draft lama yang isinya udah ada,
    // diemin 700ms tanpa diketik apa-apa, tetep ke-autosave dan updatedAt ke-stempel ulang
    // seolah-olah baru diedit. lastSavedTitle/lastSavedContent ini nge-track APA YANG BENERAN
    // TERSIMPAN SEKARANG (bukan cuma "waktu dibuka") — autosave & commit di bawah cuma nulis
    // kalau title ATAU content SEKARANG beda dari ini, bukan cuma beda dari kondisi awal.
    var lastSavedTitle by remember(initial?.id) { mutableStateOf(originalTitle) }
    var lastSavedContent by remember(initial?.id) { mutableStateOf(originalContent) }
    // Udah "eksis" (ada barisnya di DB) sejak awal kalau ini draft LAMA; draft baru baru
    // dianggap eksis begitu autosave pertama kali beneran kesimpen (isinya udah nggak kosong).
    var exists by remember(initial?.id) { mutableStateOf(initial != null) }
    var menuExpanded by remember { mutableStateOf(false) }
    val transformation = rememberDraftRichTextTransformation()
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current

    // Serialize SEMUA blok balik jadi satu String — sumber tunggal buat autosave, word count,
    // & commitAndClose. Dihitung ULANG tiap recomposition (bukan derivedStateOf) — draft
    // pendek, murah dihitung ulang, dan ini yang bikin scope composable ini ke-subscribe ke
    // SEMUA state blok (tfv tiap Txt, rows tiap Tbl) sekaligus tanpa wiring tambahan.
    val flatContent = serializeDraftBlocks(blocks)

    /** Terapin transform ke blok Teks yang lagi fokus. No-op kalau gak ada blok Teks yang
     *  fokus (mis. lagi ngetik di cell tabel, atau abis insertTableBlock ngerusak fokus lama). */
    fun applyToFocusedText(transform: (TextFieldValue) -> TextFieldValue) {
        val target = blocks.firstOrNull { it.id == focusedBlockId } as? DraftBlock.Txt ?: return
        target.tfv = transform(target.tfv)
    }

    /** [FIX3] Dulu (SATU BasicTextField fillMaxSize) nge-tap DI MANA PUN di area konten selalu
     *  fokusin field-nya, biar area kosong di bawah teks yang pendek pun tetep bisa dipake
     *  buka keyboard. Sekarang tiap blok cuma setinggi isinya sendiri (fillMaxWidth doang),
     *  jadi area kosong sisa di Column gak nempel ke composable manapun — makanya harus tap
     *  PERSIS di baris teks. Fix: Box konten dikasih .clickable TRANSPARAN (indication=null)
     *  yang cuma kepanggil kalau tap-nya JATUH DI RUANG KOSONG (gak kena field/tabel manapun,
     *  yang duluan nangkep tap-nya sendiri) — mindahin kursor+fokus ke AKHIR blok Teks
     *  TERAKHIR, atau bikin blok Teks baru kalau draft-nya diakhiri Tabel (gak ada blok Teks
     *  buat disambung). */
    fun focusEndOrCreateTrailingBlock() {
        val last = blocks.lastOrNull()
        if (last is DraftBlock.Txt) {
            last.tfv = last.tfv.copy(selection = TextRange(last.tfv.text.length))
            requestFocusOnBlock(last.id)
        } else {
            val trailing = DraftBlock.Txt(nextDraftBlockId(), TextFieldValue(""))
            blocks.add(trailing)
            requestFocusOnBlock(trailing.id)
        }
    }

    /** [FIX2] Tombol Tabel SEKARANG bikin DraftBlock.Tbl langsung (bukan sisip teks pipe lagi
     *  buat nunggu di-parse ulang) — blok Teks yang fokus dipotong PERSIS di posisi kursor jadi
     *  [sebelum, TABEL BARU 2x2, sesudah], niru pola split yang udah disimulasikan Python.
     *  [FIX3] Fokus dipindah ke blok "sesudah" yang baru (dulu di-null-in abis insert, jadi
     *  user harus tap manual lagi) — sekarang bisa langsung lanjut ngetik abis nekan Tabel,
     *  lewat requestFocusOnBlock() ([FIX4]) yang sama kayak focusEnd...() di atas. */
    fun insertTableBlock() {
        val idx = blocks.indexOfFirst { it.id == focusedBlockId }
        val target = blocks.getOrNull(idx) as? DraftBlock.Txt ?: return
        val cursor = target.tfv.selection.start.coerceIn(0, target.tfv.text.length)
        val before = target.tfv.text.substring(0, cursor)
        val after = target.tfv.text.substring(cursor)
        val afterBlock = DraftBlock.Txt(nextDraftBlockId(), TextFieldValue(after))
        blocks[idx] = DraftBlock.Txt(nextDraftBlockId(), TextFieldValue(before, TextRange(before.length)))
        blocks.add(idx + 1, DraftBlock.Tbl(nextDraftBlockId(), listOf(listOf("Kolom 1", "Kolom 2"), listOf("", ""))))
        blocks.add(idx + 2, afterBlock)
        requestFocusOnBlock(afterBlock.id)
    }

    fun commitAndClose() {
        val trimmedTitle = title.trim()
        val trimmedContent = flatContent.trim()
        if (trimmedTitle.isBlank() && trimmedContent.isBlank()) {
            if (exists) vm.deleteDraft(draftId) // dikosongin total -> hapus, jangan nyisain draft kosong
            onDismiss()
        } else {
            // [BARU] dismissedMatchWorkspaceId(s) & snoozedMatchUntil dibawa dari `initial` (draft
            // LAMA) — kalau nggak, status "udah pernah di-dismiss/di-snooze" ke-reset tiap draft
            // ini disimpan ulang, saran ekosistem draft yang sama bakal nyeletuk lagi padahal
            // udah pernah ditolak/ditunda. [FIX, 16 Okt] dismissedMatchWorkspaceIds (Set, lihat
            // Entities.kt) butuh perlakuan sama persis — gampang kelewat soalnya Draft(...) di
            // sini dibikin dari nol (bukan initial.copy()), jadi field yang gak disebut eksplisit
            // ke-reset ke default, BUKAN ke-preserve dari initial kayak .copy() biasanya.
            val current = Draft(
                id = draftId, title = trimmedTitle, content = trimmedContent, createdAt = createdAt,
                dismissedMatchWorkspaceId = initial?.dismissedMatchWorkspaceId,
                dismissedMatchWorkspaceIds = initial?.dismissedMatchWorkspaceIds ?: emptySet(),
                snoozedMatchUntil = initial?.snoozedMatchUntil ?: 0L
            )
            // Cuma upsert (dan nge-bump updatedAt) kalau isinya BENERAN beda dari yang udah
            // tersimpan — nutup draft tanpa ngedit apa-apa TIDAK menyentuh updatedAt sama sekali.
            val shouldSave = trimmedTitle != lastSavedTitle || trimmedContent != lastSavedContent
            // [FIX, laporan William 20 Sep: delay kemunculan saran di Notification Center;
            // FIX lagi 21 Sep: trigger sempat balik ilang karena rememberCoroutineScope() milik
            // Composable ini ke-cancel duluan pas Dialog di-dismiss sistem] Penyimpanan &
            // pengecekan sekarang jalan di viewModelScope (lihat saveDraftAndCheckMatch di
            // AppViewModel.kt) — onDismiss() (lewat callback onDone) baru dipanggil SETELAH
            // keduanya bener-bener kelar, dan coroutine-nya nggak keputus walau Dialog Compose
            // di sini keburu hilang dari layar duluan.
            vm.saveDraftAndCheckMatch(current, shouldSave) { onDismiss() }
        }
    }

    // Autosave debounced — nunggu 700ms jeda ketikan dulu, biar gak nulis ke disk tiap huruf.
    // LaunchedEffect(title, flatContent) otomatis batal-mulai-ulang tiap salah satunya berubah
    // (perilaku standar Compose), jadi cuma keystroke TERAKHIR yang beneran nyampe nyimpen.
    // [FIX] Guard "gak ada yang beda dari lastSaved" di awal nyegah autosave "hantu" pas draft
    // CUMA dibuka buat dibaca doang (LaunchedEffect tetap jalan sekali di awal walau gak
    // diapa-apain — itu kelakuan normal Compose, bukan berarti user ngedit sesuatu).
    LaunchedEffect(title, flatContent) {
        val trimmedTitle = title.trim()
        val trimmedContent = flatContent.trim()
        if ((trimmedTitle.isBlank() && trimmedContent.isBlank()) ||
            (trimmedTitle == lastSavedTitle && trimmedContent == lastSavedContent)) return@LaunchedEffect
        delay(700)
        vm.upsertDraft(Draft(
            id = draftId, title = trimmedTitle, content = trimmedContent, createdAt = createdAt,
            dismissedMatchWorkspaceId = initial?.dismissedMatchWorkspaceId,
            dismissedMatchWorkspaceIds = initial?.dismissedMatchWorkspaceIds ?: emptySet(),
            snoozedMatchUntil = initial?.snoozedMatchUntil ?: 0L
        ))
        lastSavedTitle = trimmedTitle
        lastSavedContent = trimmedContent
        exists = true
    }

    // [FIX4] Keyed ke focusRequestSeq (bukan focusedBlockId lagi) — lihat komentar di deklarasi
    // focusRequestSeq di atas buat alasannya. delay(120) niru pola SearchScreen.kt (dulu
    // overlay ini BUKAN Dialog jadi gak perlu; sekarang udah dibungkus Dialog, requestFocus()
    // sebelum animasi window-nya kelar bisa gak kena).
    // [FIX5] keyboardController?.show() ditambah — requestFocus() SENDIRIAN gak jamin IME
    // kebuka LAGI kalau field ini UDAH "focused" dari sebelumnya (back-press cuma nutup IME,
    // TIDAK ngilangin fokus Compose-nya) — makanya token di atas doang belum cukup, keyboard
    // tetep harus diminta muncul SECARA EKSPLISIT, gak boleh cuma efek samping requestFocus().
    // [FIX6] Ditambah panggilan InputMethodManager.SHOW_FORCED langsung juga sebagai jaring
    // pengaman — kalau keyboardController.show() internalnya ternyata pakai flag yang senasib
    // sama SHOW_IMPLICIT (bisa diabaikan sistem abis user nutup manual), panggilan tegas ini
    // yang mastiin keyboard beneran muncul.
    LaunchedEffect(focusRequestSeq) {
        val target = blocks.firstOrNull { it.id == focusedBlockId } as? DraftBlock.Txt ?: return@LaunchedEffect
        delay(120)
        target.focusRequester.requestFocus()
        keyboardController?.show()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_FORCED)
    }

    // [FIX] Sebelumnya overlay ini di-compose langsung di dalam NavHost tab "home", yang
    // di-padding Scaffold terluar (MainActivity.kt) buat nyisain tinggi NavigationBar bawah
    // (Beranda/Tugas/Progres) — imePadding() di Column jadinya ngitung "dasar layar" dari batas
    // ATAS NavigationBar itu, bukan dasar layar FISIK, jadi pas keyboard muncul toolbar
    // ke-dorong ngambang TERLALU TINGGI di atas keyboard (gap kosong warna AppBg sebesar tinggi
    // NavigationBar, antara toolbar & keyboard). Dialog(usePlatformDefaultWidth=false) niru pola
    // yang UDAH kebukti jalan di SearchScreen.kt: bikin WINDOW terpisah yang lepas total dari
    // padding Scaffold/NavHost, jadi fillMaxSize() di bawah beneran penuh layar fisik.
    // onDismissRequest diarahin ke commitAndClose() (bukan onDismiss polos) biar back-press
    // sistem TETAP lewat autosave/hapus-draft-kosong — BackHandler manual yang lama jadi gak
    // perlu lagi (malah berpotensi dobel-handle bareng onDismissRequest bawaan Dialog).
    Dialog(onDismissRequest = { commitAndClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AppBg) {
        // [FIX] navigationBarsPadding() ditambahin SEBELUM imePadding() — proteksi dari system
        // nav bar/gesture area dulu kebawa gratis dari padding Scaffold, yang sekarang dilepas
        // lewat Dialog di atas; tanpa ini toolbar bisa ketutup gesture bar pas keyboard LAGI
        // KETUTUP. Urutan .navigationBarsPadding().imePadding() ini pola resmi Compose buat
        // "hindari yang lebih tinggi antara nav bar atau keyboard".
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(top = cappedStatusBarTopPadding() + 8.dp, start = Spacing.sm, end = Spacing.lg, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { commitAndClose() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = AppText)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Titik-tiga cuma buat Hapus (draft lama doang) — tombol format (checklist/
                    // bold/heading/tabel/divider) sengaja ditaruh di toolbar KHUSUS di bawah
                    // (dekat keyboard), bukan di menu ini, biar gak perlu buka menu tiap format.
                    if (initial != null) {
                        CardOverflowMenu(
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = it },
                            actions = listOf(
                                CardMenuAction("Hapus", Icons.Outlined.DeleteOutline, danger = true, onClick = { vm.deleteDraft(draftId); onDismiss() })
                            )
                        )
                    }
                    TextButton(onClick = { commitAndClose() }) {
                        Text("Selesai", color = AppAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }

            BasicTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
                textStyle = TextStyle(color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
                cursorBrush = SolidColor(AppAccent),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) Text("Judul", color = AppTextDim, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        inner()
                    }
                }
            )
            Spacer(Modifier.height(Spacing.sm))

            // [FIX3] .clickable transparan ditambah di Box ini — tap yang JATUH DI RUANG KOSONG
            // (gak kena field/tabel manapun, itu duluan nangkep tap-nya sendiri lewat pointer
            // input masing-masing) lolos ke sini, manggil focusEndOrCreateTrailingBlock().
            // indication=null biar gak ada efek ripple, area kosong emang harusnya berasa
            // "nyambung" sama teks di atasnya, bukan kayak nge-tap tombol.
            // [FIX8] Box -> BoxWithConstraints — angka 400dp tetap (fixed) sebelumnya cuma
            // tebakan; di draft yang isinya dikit (sisa layar > 400dp) selalu nyisain "zona
            // mati" di bawahnya yang balik ngandelin .clickable+requestFocus() (TERBUKTI gak
            // reliable manggil keyboard, foto yang dikirim nunjukin persis batas ini). maxHeight
            // dari BoxWithConstraints itu tinggi AREA INI YANG SEBENERNYA — otomatis nyesuain
            // ke kondisi keyboard terbuka/tertutup (karena imePadding() di Column udah ngurangin
            // tinggi total SEBELUM nyampe sini) — jadi blok terakhir SELALU isi persis sisa
            // layar yang keliatan, gak lebih gak kurang, gak ada zona mati lagi.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        focusEndOrCreateTrailingBlock()
                    }
            ) {
                // [FIX9] Ditangkep ke val biasa DI SINI — BoxWithConstraintsScope.maxHeight
                // ke-blok Kotlin (@LayoutScopeMarker) kalau diakses implisit dari DALAM scope
                // lain (ColumnScope dari Column{} di bawah). Val Dp biasa gak punya masalah itu.
                val availableHeight = maxHeight
                val isEmpty = blocks.size == 1 && (blocks[0] as? DraftBlock.Txt)?.tfv?.text?.isEmpty() == true
                if (isEmpty) {
                    Text("Tulis apa aja…", color = AppTextDim, fontSize = 16.sp, lineHeight = 26.sp)
                }
                // [FIX2] Dulu SATU BasicTextField isi seluruh content. Sekarang blocks di-render
                // satu-satu — blok Teks tetep BasicTextField (format & cursor behavior gak
                // berubah), blok Tabel jadi TableGrid beneran. verticalScroll ditambah di
                // sini (dulu gak perlu, satu BasicTextField ngurus scroll-nya sendiri; sekarang
                // beberapa composable ditumpuk jadi Column-nya sendiri yang harus discroll).
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    blocks.forEachIndexed { i, block ->
                        when (block) {
                            is DraftBlock.Txt -> BasicTextField(
                                value = block.tfv,
                                onValueChange = { block.tfv = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // [FIX7]+[FIX8] Blok TERAKHIR dikasih tinggi minimum = SISA
                                    // RUANG BENERAN (maxHeight dari BoxWithConstraints, bukan
                                    // angka tebakan lagi) — niru fillMaxSize() versi lama, TAPI
                                    // cuma buat blok ini doang. Tujuannya: tap di "ruang kosong"
                                    // jadi tap ASLI ke BasicTextField ini (native Android
                                    // touch->focus->IME, TERBUKTI reliable — "tap presisi di
                                    // teks tetep jalan"), bukan lewat .clickable overlay +
                                    // requestFocus() terprogram yang ternyata TIDAK cukup buat
                                    // manggil keyboard balik. .clickable di atas TETAP
                                    // dipertahankan sebagai jaring pengaman buat kasus tepi
                                    // (mis. blok terakhir kebetulan lebih tinggi dari maxHeight
                                    // karena isinya udah panjang, jadi ruang kosong SEBENERNYA
                                    // udah abis di layar itu).
                                    .then(if (i == blocks.lastIndex) Modifier.heightIn(min = availableHeight) else Modifier)
                                    .focusRequester(block.focusRequester)
                                    .onFocusChanged { fs -> if (fs.isFocused) focusedBlockId = block.id },
                                textStyle = TextStyle(color = AppText, fontSize = 16.sp, lineHeight = 26.sp),
                                cursorBrush = SolidColor(AppAccent),
                                visualTransformation = transformation
                            )
                            is DraftBlock.Tbl -> TableGrid(rows = block.rows, onDelete = { blocks.remove(block) })
                        }
                    }
                }
            }

            val wordCount = flatContent.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            Text(
                buildString {
                    if (wordCount > 0) append("$wordCount kata")
                    if (initial != null) { if (wordCount > 0) append("  •  "); append("Diubah ${DateUtils.relativeTime(initial.updatedAt)}") }
                },
                color = AppTextDim, fontSize = 10.5.sp,
                modifier = Modifier.align(Alignment.End).padding(horizontal = Spacing.xl, vertical = Spacing.sm)
            )

            // ── Toolbar format — dibangun khusus buat Draft, bukan migrasi ke mesin NotebookEditor
            // (itu block-based, dirancang buat Materi terstruktur; Draft tetap satu String panjang
            // buat DISIMPAN-nya, "rich"-nya dari syntax ringan + VisualTransformation read-time +
            // [FIX2] blok Tabel yang di-render grid beneran, biar gak perlu migrasi skema sync).
            //
            // Checklist & Heading pakai TOGGLE-DI-BARIS-KURSOR, BUKAN deteksi-tap-di-atas-glyph —
            // sengaja dihindari karena itu butuh TextLayoutResult + pointerInput buat mapping
            // posisi-tap ke baris, riskan meleset tanpa bisa dites compile langsung. Toggle lewat
            // tombol lebih eksplisit: taruh kursor di baris yang mau diubah, baru tap tombolnya.
            // Semua tombol format lewat applyToFocusedText() sekarang (no-op kalau fokus lagi di
            // cell tabel), Tabel lewat insertTableBlock() (lihat [FIX2] di atasnya).
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AppBgCard)
                    .border(width = 1.dp, color = AppBorder.copy(alpha = 0.5f))
                    .padding(horizontal = Spacing.sm, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                DraftToolbarBtn(Icons.Default.CheckBox, "Checklist") {
                    applyToFocusedText { toggleChecklistLine(it) }
                }
                DraftToolbarBtn(Icons.Default.FormatBold, "Tebal") {
                    applyToFocusedText { wrapSelectionBold(it) }
                }
                DraftToolbarBtn(Icons.Default.Title, "Judul bagian") {
                    applyToFocusedText { toggleLinePrefix(it, "# ") }
                }
                DraftToolbarBtn(Icons.Default.TableChart, "Tabel") {
                    insertTableBlock()
                }
                DraftToolbarBtn(Icons.Default.HorizontalRule, "Garis pembatas") {
                    // [FIX] Dulu cuma "---" (3 dash, nyaris gak keliatan) — sekarang pakai
                    // DRAFT_DIVIDER_LINE (30 dash) biar berasa kayak garis penuh di layar HP.
                    applyToFocusedText { insertAtCursor(it, "\n$DRAFT_DIVIDER_LINE\n") }
                }
            }
        }
        }
    }
}
