package com.ruangkerja.companion.editor.controller

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.mohamedrejeb.richeditor.model.RichTextState
import com.ruangkerja.companion.editor.core.Block
import com.ruangkerja.companion.editor.core.BlockId
import com.ruangkerja.companion.editor.core.BlockType
import com.ruangkerja.companion.editor.core.removeTextRangeWithStyles
import com.ruangkerja.companion.editor.core.NativeTextState
import com.ruangkerja.companion.editor.core.newId
import com.ruangkerja.companion.ui.components.buildTableMarkdown
import com.ruangkerja.companion.ui.components.parseTableLines
import com.ruangkerja.companion.util.isQuoteLine
import com.ruangkerja.companion.util.isTableRow
import com.ruangkerja.companion.util.isTableSeparator

// ── Layer 2 (RKR Editor Engine) — Fase 1 migrasi arsitektur "jalan tengah" (LAMA, udah kelar).
//
// ── [PEROMBAKAN BESAR — mesin native] Block.Text pindah dari RichTextState ke NativeTextState
// (lihat editor/core/NativeTextState.kt untuk alasan lengkap: drag-seleksi EditText native
// jauh lebih mulus dibanding VisualTransformation RichTextState yang di-rebuild tiap
// perubahan). Block.Quote TETAP RichTextState sesi ini (scope sengaja dibatasin). Konsekuensi
// paling gede: `focusedTextBlock()` yang DULU balikin `Pair<Int, RichTextState>` (asumsi
// SEMUA blok teks punya RichTextState) UDAH GAK BISA dipertahankan bentuknya — sekarang
// dipecah jadi `focusedTextBlockIndex(): Int?` (posisi doang) + `focusedBlock(): Block?`
// (blok-nya, biar pemanggil pattern-match sendiri Text/Quote-nya). Semua method yang dulu
// nyentuh RichTextState buat Text (insertQuoteAtCursor, updateBlockContent, convertBlockType,
// removeAndMergeAdjacentText) ditulis ulang buat cabang dua jalur.
//
// KEHILANGAN SEMENTARA yang SADAR & SENGAJA: slash-command ("/" popup) itu fitur BAWAAN
// RichTextState (Trigger/TriggerQuery) — EditText native GAK PUNYA ekuivalennya, dan
// nge-bangun ulang deteksi "/" dari nol itu scope TERPISAH yang belum digarap sesi ini.
// Toolbar TETAP jalan penuh (itu jalur utama insert Tabel/Kotak Contoh/format) — cuma
// shortcut "/" di tengah ngetik yang sementara nonaktif buat Block.Text. ──
internal class BlockManager(
    val blocks: SnapshotStateList<Block>,
    private val focusedBlockIndexState: MutableIntState
) {
    private var focusedBlockIndex: Int
        get() = focusedBlockIndexState.intValue
        set(value) { focusedBlockIndexState.intValue = value }

    private fun isTextBearing(b: Block?): Boolean = b is Block.Text || b is Block.Quote

    /** Index blok "teks" (Text ATAU Quote) terdekat dari posisi fokus sekarang — cari di index
     * fokus dulu, kalau bukan mundur cari ke atas, fallback blok teks TERAKHIR di dokumen.
     * Dipisah dari state-nya sendiri (beda dari sebelum ini) karena Text & Quote sekarang
     * punya TIPE STATE BEDA (NativeTextState vs RichTextState) — pemanggil yang pattern-match. */
    fun focusedTextBlockIndex(): Int? {
        if (isTextBearing(blocks.getOrNull(focusedBlockIndex))) return focusedBlockIndex
        for (i in focusedBlockIndex downTo 0) {
            if (isTextBearing(blocks.getOrNull(i))) return i
        }
        val idx = blocks.indexOfLast { isTextBearing(it) }
        return if (idx == -1) null else idx
    }

    fun focusedBlock(): Block? = focusedTextBlockIndex()?.let { blocks.getOrNull(it) }

    /** Blok teks TERAKHIR di dokumen — dipake buat "tap area kosong di bawah konten = fokus
     * ke akhir dokumen", niru konsep klik di bawah baris terakhir ala Word. */
    fun lastTextBlockIndex(): Int? {
        val idx = blocks.indexOfLast { isTextBearing(it) }
        return if (idx == -1) null else idx
    }

    // [FIX13] applyHeadingShortcut() (pemecah blok buat shortcut ngetik "# "+spasi) DIHAPUS —
    // fitur shortcut-ketik-otomatis ditunda dulu (lihat diskusi sesi ini), heading SEKARANG
    // cukup lewat toggleHeadingAtLine() di NativeTextState langsung (dipanggil dari
    // CommandDispatcher, gak butuh BlockManager buat kasus ini — satu baris = satu marker,
    // gak perlu mecah blok apa pun).

    fun defaultTableRows(): SnapshotStateList<SnapshotStateList<String>> = mutableStateListOf(
        mutableStateListOf("Kolom 1", "Kolom 2"),
        mutableStateListOf("isi", "isi")
    )

    // ── Sisip tabel sebagai blok BARU setelah blok yang lagi fokus. ──
    fun insertTableAtCursor() {
        val insertAt = focusedTextBlockIndex()?.let { it + 1 } ?: blocks.size
        blocks.add(insertAt, Block.Table(newId(), defaultTableRows()))
        if (insertAt + 1 >= blocks.size) blocks.add(textBlock(""))
        focusedBlockIndex = insertAt + 1
    }

    /** Sisip kotak "Contoh" (blockquote) sebagai blok BARU setelah blok yang lagi fokus. Kalau
     * ada teks TERPILIH di blok sumber (Text ATAU Quote), teks itu DIPINDAH (disalin ke Kotak
     * Contoh, dihapus dari sumber) — dua jalur terpisah karena tipe state sumbernya beda. */
    fun insertQuoteAtCursor() {
        val focusedIdx = focusedTextBlockIndex()
        val insertAt = focusedIdx?.let { it + 1 } ?: blocks.size
        val sourceBlock = focusedIdx?.let { blocks.getOrNull(it) }
        val qState = RichTextState()
        val selectedText: String? = when (sourceBlock) {
            is Block.Text -> {
                val s = sourceBlock.nativeState
                val lo = minOf(s.selectionStart, s.selectionEnd); val hi = maxOf(s.selectionStart, s.selectionEnd)
                if (lo != hi) s.text.substring(lo, hi) else null
            }
            is Block.Quote -> {
                val sel = sourceBlock.state.selection
                if (!sel.collapsed) sourceBlock.state.annotatedString.text.substring(sel.min, sel.max) else null
            }
            else -> null
        }
        qState.setMarkdown(selectedText ?: "Contoh: ")
        blocks.add(insertAt, Block.Quote(newId(), qState))
        if (selectedText != null) {
            when (sourceBlock) {
                is Block.Text -> {
                    val s = sourceBlock.nativeState
                    val lo = minOf(s.selectionStart, s.selectionEnd); val hi = maxOf(s.selectionStart, s.selectionEnd)
                    val (newText, newStyles) = removeTextRangeWithStyles(s.text, s.inlineStyles, lo, hi)
                    s.applyStyled(newText, newStyles, lo, lo)
                }
                is Block.Quote -> sourceBlock.state.removeTextRange(sourceBlock.state.selection)
                else -> {}
            }
        }
        if (insertAt + 1 >= blocks.size) blocks.add(textBlock(""))
        // Fokus ke blok SETELAH Kotak Contoh (bukan Kotak Contoh itu sendiri) — confirmed
        // fix lama, LaunchedEffect(isFocused) kepicu sebelum layout stabil kalau diarahin ke
        // blok yang baru dibuat.
        focusedBlockIndex = insertAt + 1
    }

    fun removeTableBlock(idx: Int) = removeAndMergeAdjacentText(idx)
    fun removeQuoteBlock(idx: Int) = removeAndMergeAdjacentText(idx)

    // ── [Migrasi Cascade Fase 1] 5 method — niru vocabulary operasi struktural EditorAction
    // Cascade. BELUM ada trigger UI buat kelimanya. ──

    fun insertBlock(type: BlockType, atIndex: Int? = null) {
        val insertAt = (atIndex ?: (focusedTextBlockIndex()?.let { it + 1 } ?: blocks.size))
            .coerceIn(0, blocks.size)
        val newBlock: Block = when (type) {
            BlockType.Text -> textBlock("")
            BlockType.Quote -> quoteBlock("")
            BlockType.Table -> Block.Table(newId(), defaultTableRows())
        }
        blocks.add(insertAt, newBlock)
    }

    fun deleteBlock(id: BlockId) {
        val index = blocks.indexOfFirst { it.id == id }
        if (index == -1 || blocks.size <= 1) return
        blocks.removeAt(index)
        if (focusedBlockIndex >= blocks.size) focusedBlockIndex = blocks.size - 1
    }

    /** Ganti isi teks blok Text/Quote by id — dua jalur, tipe state beda. */
    fun updateBlockContent(id: BlockId, text: String) {
        when (val b = blocks.find { it.id == id }) {
            is Block.Text -> b.nativeState.setMarkdown(text)
            is Block.Quote -> b.state.setMarkdown(text)
            else -> {}
        }
    }

    /** Ganti tipe satu blok. CATATAN: beda dari sebelum perombakan native — dulu instance
     * RichTextState yang SAMA dipindah ke Block baru (cursor/histori ikut persis). Sekarang
     * Text↔Quote pindah MESIN (NativeTextState↔RichTextState), jadi cuma TEKS-nya yang
     * dipindah, cursor/seleksi reset ke akhir teks. Belum ada trigger UI buat ini, jadi belum
     * kerasa dampaknya ke pemakaian nyata. */
    fun convertBlockType(id: BlockId, newType: BlockType) {
        val index = blocks.indexOfFirst { it.id == id }
        if (index == -1) return
        val current = blocks[index]
        val converted: Block = when {
            current is Block.Text && newType == BlockType.Quote -> {
                val q = RichTextState(); q.setMarkdown(current.nativeState.toMarkdown())
                Block.Quote(current.id, q)
            }
            current is Block.Quote && newType == BlockType.Text ->
                Block.Text(current.id, NativeTextState(current.state.toMarkdown()))
            else -> return
        }
        blocks[index] = converted
    }

    fun moveBlock(id: BlockId, toIndex: Int) {
        val fromIndex = blocks.indexOfFirst { it.id == id }
        if (fromIndex == -1) return
        val target = toIndex.coerceIn(0, blocks.size - 1)
        if (fromIndex == target) return
        val moved = blocks.removeAt(fromIndex)
        blocks.add(target, moved)
        if (focusedBlockIndex == fromIndex) focusedBlockIndex = target
    }

    private fun removeAndMergeAdjacentText(idx: Int) {
        val before = blocks.getOrNull(idx - 1) as? Block.Text
        val after = blocks.getOrNull(idx + 1) as? Block.Text
        if (before != null && after != null) {
            val merged = textBlock(before.nativeState.toMarkdown() + "\n" + after.nativeState.toMarkdown())
            blocks.removeAt(idx + 1); blocks.removeAt(idx); blocks.removeAt(idx - 1)
            blocks.add(idx - 1, merged)
            focusedBlockIndex = idx - 1
        } else {
            blocks.removeAt(idx)
            focusedBlockIndex = (idx - 1).coerceAtLeast(0)
        }
    }
}

internal fun textBlock(markdown: String): Block.Text = Block.Text(newId(), NativeTextState(markdown))

internal fun quoteBlock(markdown: String): Block.Quote {
    val state = RichTextState()
    state.setMarkdown(markdown)
    return Block.Quote(newId(), state)
}

/** [PEROMBAKAN BESAR — mesin native] Alasan awal angka ini kecil (500) itu ngirit ukuran
 * RichTextState per instance (VisualTransformation-nya mahal buat teks gede — akar masalah
 * "berat pas drag" & magnifier garbled). Block.Text SEKARANG NativeTextState (EditText asli),
 * gak punya biaya sejenis itu — jadi gak ada alasan kuat motong-motong paragraf pendek lagi.
 * Dinaikin jauh (4000) — TETAP ada, bukan dihapus mekanismenya, buat jaga-jaga kasus ekstrem
 * (satu paragraf sepanjang berhalaman-halaman ke-paste sekaligus), tapi buat pemakaian normal
 * efeknya: paragraf-paragraf nyambung jadi SATU blok kayak sebelum v86, seleksi lintas-paragraf
 * balik alami — plus BONUS nutup celah "cuma bisa select satu baris" yang sempet muncul waktu
 * blok kepotong kekecilan.
 *
 * [v95, FIX REGRESI] Heading (#/##/###) DULU (v87) SELALU jadi batas blok tersendiri —
 * alasannya SAMA kayak paragraf pendek di atas (ngirit ukuran RichTextState), tapi gak pernah
 * dicabut pas mesinnya pindah ke native. Efeknya: tiap heading jadi EditText SENDIRI, dan
 * seleksi gak bisa nyebrang dari satu heading ke heading lain / ke paragraf sekitarnya —
 * PERSIS masalah "cuma bisa select satu baris" yang sama, cuma manifest di heading. SEKARANG
 * heading numpuk kayak baris biasa, kena threshold yang SAMA kayak paragraf — rendering
 * visualnya (bold/besar/warna) TIDAK kepengaruh, itu dihitung ulang dari isi teks tiap kali
 * (lihat NativeLiveStyle.kt), gak peduli di blok mana dia berakhir. Tabel & Kotak Contoh TETAP
 * jadi batas blok tersendiri (alasan beda: itu emang UI/struktur yang beda total, bukan soal
 * ukuran/performa). */
private const val PARAGRAPH_SPLIT_THRESHOLD = 4000

/** Pecah teks markdown mentah jadi urutan blok Teks/Quote/Tabel. Round-trip aman ke [blocksToText]. */
internal fun parseBlocks(text: String): List<Block> {
    val lines = text.split("\n")
    val blocks = mutableListOf<Block>()
    val textLines = mutableListOf<String>()
    var pendingBlankLine = false
    fun flushText() {
        pendingBlankLine = false
        if (textLines.isEmpty()) return
        blocks.add(textBlock(textLines.joinToString("\n")))
        textLines.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            flushText()
            val tableLines = mutableListOf(line, lines[i + 1])
            var j = i + 2
            while (j < lines.size && isTableRow(lines[j])) { tableLines.add(lines[j]); j++ }
            val rows = parseTableLines(tableLines)
            blocks.add(Block.Table(newId(), rows.map { r -> r.toMutableStateList() }.toMutableStateList()))
            i = j
        } else if (isQuoteLine(line)) {
            flushText()
            val quoteLines = mutableListOf(stripQuotePrefix(line))
            var j = i + 1
            while (j < lines.size && isQuoteLine(lines[j])) { quoteLines.add(stripQuotePrefix(lines[j])); j++ }
            blocks.add(quoteBlock(quoteLines.joinToString("\n")))
            i = j
        } else if (line.isBlank()) {
            if (textLines.isNotEmpty()) {
                if (textLines.joinToString("\n").length >= PARAGRAPH_SPLIT_THRESHOLD) {
                    flushText()
                } else {
                    pendingBlankLine = true
                }
            }
            i++
        } else {
            if (pendingBlankLine) { textLines.add(""); pendingBlankLine = false }
            textLines.add(line)
            i++
        }
    }
    flushText()
    return if (blocks.isEmpty()) listOf(textBlock("")) else blocks
}

/** Gabung ulang semua blok jadi satu string markdown — dipakai buat simpan/word-count.
 *  [FIX12] Block.Text sebelumnya pakai nativeState.text MENTAH — sejak migrasi ke
 *  NativeTextState, .text itu SENGAJA polos tanpa "**"/"*"/dst (ada di .inlineStyles
 *  terpisah, lihat MarkdownStyleCodec.kt/NativeTextState.kt), jadi Bold/Italic/Underline/
 *  Strikethrough/Code yang dipasang lewat toolbar KETINGGALAN diam-diam tiap kali materi
 *  disimpan — cuma teks polosnya doang yang nyangkut. Block.Quote di baris bawahnya udah
 *  BENER manggil .toMarkdown() dari awal; Block.Text sekarang disamain. */
internal fun blocksToText(blocks: List<Block>): String =
    blocks.joinToString("\n\n") { block ->
        when (block) {
            is Block.Text -> block.nativeState.toMarkdown()
            is Block.Quote -> block.state.toMarkdown().lines().joinToString("\n") { l -> addQuotePrefix(l) }
            is Block.Table -> buildTableMarkdown(block.rows.map { row -> row.toList() })
        }
    }

private fun stripQuotePrefix(line: String): String = line.trimStart().removePrefix(">").removePrefix(" ")

private fun addQuotePrefix(line: String): String = if (line.isBlank()) ">" else "> $line"
