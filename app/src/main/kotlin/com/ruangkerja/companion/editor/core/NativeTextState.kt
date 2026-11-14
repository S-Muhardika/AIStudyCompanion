package com.ruangkerja.companion.editor.core

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Pengganti RichTextState buat Block.Text. [text] = teks POLOS (gak ada karakter markdown
 * inline ATAU heading sama sekali — lihat MarkdownStyleCodec.kt), [inlineStyles] = daftar
 * rentang gaya terpisah, [headingLines] = daftar baris yang berstatus heading (lihat
 * [HeadingLineMarker]). Markdown Cuma di-generate PAS DIBUTUHIN lewat [toMarkdown] (simpen/AI
 * action/laporan), bukan disimpen sebagai bentuk utama.
 * [FIX15] headingLines per-BARIS (bukan satu Int headingLevel per-blok kayak [FIX13] yang
 * dibatalkan) — satu blok emang bisa berisi banyak paragraf+heading campur (lihat catatan v95
 * di BlockManager.kt), jadi heading HARUS independen per-baris, niru pola inlineStyles.
 */
@Stable
internal class NativeTextState(initialMarkdown: String = "") {
    private val initialStyled = markdownToStyledText(initialMarkdown)

    var text: String by mutableStateOf(initialStyled.text)
        private set
    var inlineStyles: List<InlineStyleRange> by mutableStateOf(initialStyled.inlineStyles)
        private set
    var headingLines: List<HeadingLineMarker> by mutableStateOf(initialStyled.headingLines)
        private set
    var selectionStart: Int by mutableIntStateOf(initialStyled.text.length)
        private set
    var selectionEnd: Int by mutableIntStateOf(initialStyled.text.length)
        private set

    /** Dipanggil dari TextWatcher/listener seleksi EditText — teks/gaya/heading/seleksi
     * BERUBAH KARENA USER NGETIK/NGEDRAG/TOGGLE LEWAT SPAN LANGSUNG; EditText udah reflect ini
     * duluan, kita cuma nyalin ke Compose state biar toolbar & bagian lain ikut update.
     * [FIX15] headingLines SEKARANG ikut disinkron di sini juga (diekstrak dari span
     * HeadingLineSpan lewat extractHeadingLines() — lihat NativeLiveStyle.kt) — soalnya span
     * heading SEKARANG beneran nge-track posisi baris (termasuk molor kalau user lanjut
     * ngetik di ujungnya), gak lagi sekadar properti statis per-blok. */
    fun syncFromEditable(newText: String, newStyles: List<InlineStyleRange>, newHeadingLines: List<HeadingLineMarker>, selStart: Int, selEnd: Int) {
        text = newText
        inlineStyles = newStyles
        headingLines = newHeadingLines
        selectionStart = selStart
        selectionEnd = selEnd
    }

    /** Dipanggil dari toolbar/command (BUKAN dari EditText) — kebalikan dari syncFromEditable,
     * ini yang bikin EditText perlu di-update PAKSA (lihat NativeTextField.kt bagian update{}). */
    fun applyStyled(newText: String, newStyles: List<InlineStyleRange>, selStart: Int, selEnd: Int) {
        text = newText
        inlineStyles = newStyles
        selectionStart = selStart
        selectionEnd = selEnd
    }

    /** [FIX15] Toggle heading di SATU BARIS SPESIFIK doang ([lineStart]..[lineEnd], biasanya
     *  dari lineBoundsAt() di posisi kursor) — BUKAN seluruh blok lagi. Baris lain (paragraf
     *  atau heading lain) di blok yang sama SAMA SEKALI gak kesentuh. Toggle: kalau baris itu
     *  UDAH persis level ini, buang markernya (balik jadi paragraf biasa); kalau enggak, ganti/
     *  tambah marker level baru buat baris itu (marker heading LAMA di baris yang SAMA, kalau
     *  ada level lain sebelumnya, otomatis kegusur — satu baris cuma boleh satu level heading). */
    fun toggleHeadingAtLine(lineStart: Int, lineEnd: Int, level: Int) {
        val existing = headingLines.firstOrNull { it.start == lineStart && it.end == lineEnd }
        headingLines = if (existing != null && existing.level == level) {
            headingLines.filterNot { it === existing }
        } else {
            headingLines.filterNot { it.start == lineStart && it.end == lineEnd } + HeadingLineMarker(lineStart, lineEnd, level)
        }
    }

    fun setMarkdown(markdown: String) {
        val s = markdownToStyledText(markdown)
        text = s.text
        inlineStyles = s.inlineStyles
        headingLines = s.headingLines
        selectionStart = s.text.length
        selectionEnd = s.text.length
    }

    fun toMarkdown(): String = styledTextToMarkdown(StyledText(text, inlineStyles, headingLines))
}
