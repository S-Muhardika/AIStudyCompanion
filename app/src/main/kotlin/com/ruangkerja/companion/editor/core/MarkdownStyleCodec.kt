package com.ruangkerja.companion.editor.core

// ── [PEROMBAKAN — markdown jadi format serialisasi, bukan buffer edit, UNTUK INLINE STYLE]
// Sebelumnya (v90/v91) buffer yang diedit itu MARKDOWN MENTAH, syntax-nya cuma disamarkan
// (font 1px+transparan) — makanya sesaat sebelum pasangan `**...**` lengkap, syntax-nya
// kelihatan apa adanya ("berasa nulis kode"). SEKARANG (v92) buffer yang diedit itu TEKS
// POLOS + daftar rentang gaya terpisah (InlineStyleRange) — TIDAK ADA karakter `**`/`*`/`~~`/
// `` ` ``/`<u>` SAMA SEKALI di buffer, kapan pun. Markdown cuma di-generate PAS DIBUTUHIN
// (simpen/AI action/laporan) lewat [styledTextToMarkdown], bukan lagi query tiap keystroke.
//
// SCOPE AWALNYA DIBATASIN ke gaya INLINE doang (Bold/Italic/Underline/Strikethrough/Code) —
// BUKAN heading/bullet/nomor. Alasan waktu itu: heading DIASUMSIKAN otomatis jadi blok
// tersendiri (fix v87), jadi gak pernah campur sama paragraf lain — dan prefix "- "/"1. " buat
// bullet/nomor itu jarang diketik manual karakter-per-karakter (biasanya lewat toolbar,
// langsung lengkap), beda kasus sama "**" yang keliatan RUSAK di tengah proses ngetik.
//
// [FIX13] (DIBATALKAN) Nyoba heading jadi struktur per-BLOK (satu Int headingLevel per
// NativeTextState). SALAH — parseBlocks() (BlockManager.kt) SENGAJA TIDAK mengisolasi heading
// jadi blok sendiri sejak v95 (demi benerin regresi seleksi lintas-heading/paragraf), jadi
// SATU blok rutin berisi BEBERAPA paragraf+heading sekaligus. Akibatnya: toggle Subjudul di
// SATU baris bikin SELURUH blok (semua paragraf di dalamnya) ikut kegedein, dan heading yang
// gak di awal blok gak kedeteksi sama sekali.
//
// [FIX15] Heading SEKARANG per-BARIS — [HeadingLineMarker] daftar (start,end,level), niru
// PERSIS pola InlineStyleRange tapi cakupannya satu baris penuh (bukan rentang karakter
// bebas). Ini yang bener: satu blok bisa punya banyak baris heading + paragraf biasa
// campur-campur, masing-masing independen. Parsing & serialisasi jalan DALAM SATU PASS yang
// SAMA (lihat markdownToStyledText) — heading prefix di-strip DULU per-baris, offset baris
// hasil-strip itu DILACAK BARENGAN scan inline-style yang sudah ada (bukan dua pass terpisah),
// karena "**" dkk yang dibuang JUGA menggeser posisi kalau dilakukan belakangan. ──

internal enum class InlineStyle { Bold, Italic, Underline, Strikethrough, Code }

internal data class InlineStyleRange(val start: Int, val end: Int, val style: InlineStyle)

/** [FIX15] Satu baris penuh (dari [start] sampai [end], EKSKLUSIF newline pembatasnya) yang
 *  berstatus heading level [level] (1=H1/Judul, 2=H2/Subjudul, 3=H3). Gak ada hierarki yang
 *  dipaksain antar level — H2 boleh dipakai tanpa H1 duluan, sama kayak Word/Google Docs;
 *  bedanya cuma bobot visual. */
internal data class HeadingLineMarker(val start: Int, val end: Int, val level: Int)

internal fun headingPrefixText(level: Int): String = if (level in 1..3) "#".repeat(level) + " " else ""

/** [text] = teks POLOS tanpa karakter markdown INLINE ATAU prefix heading "#"/"##"/"### "
 * SAMA SEKALI (boleh masih ada prefix baris lain kayak "- " apa adanya buat bullet, itu di
 * luar tanggung jawab codec ini). [inlineStyles] = rentang gaya relatif ke [text]. [headingLines]
 * = daftar baris (relatif ke [text] juga) yang berstatus heading — lihat [HeadingLineMarker]. */
internal data class StyledText(
    val text: String,
    val inlineStyles: List<InlineStyleRange> = emptyList(),
    val headingLines: List<HeadingLineMarker> = emptyList()
)

/** Parser markdown → StyledText. [FIX15]: heading prefix di-strip PER BARIS dulu (bukan cuma
 * cek awal string), BARU inline-style di-scan di teks hasil-strip itu — dan scan inline-style
 * ini SEKALIAN nge-track offset awal tiap baris di OUTPUT (posisi HASIL AKHIR, setelah "**" dkk
 * ikut dibuang), biar HeadingLineMarker presisi walau baris itu JUGA punya bold/italic/dst. */
internal fun markdownToStyledText(markdownWithHeading: String): StyledText {
    val rawLines = markdownWithHeading.split("\n")
    val headingLevelByLineIndex = HashMap<Int, Int>()
    val headingRegex = Regex("^(#{1,3}) ")
    val strippedLines = rawLines.mapIndexed { idx, line ->
        val m = headingRegex.find(line)
        if (m != null) {
            headingLevelByLineIndex[idx] = m.groupValues[1].length
            line.substring(m.range.last + 1)
        } else line
    }
    val markdown = strippedLines.joinToString("\n")

    val out = StringBuilder()
    val styles = mutableListOf<InlineStyleRange>()
    val lineStartOffsets = mutableListOf(0) // lineStartOffsets[k] = offset awal baris ke-k di [out]
    var i = 0
    while (i < markdown.length) {
        when {
            markdown[i] == '\n' -> {
                out.append('\n'); i++
                lineStartOffsets.add(out.length)
            }
            markdown.startsWith("**", i) -> {
                val close = markdown.indexOf("**", i + 2)
                if (close == -1) { out.append(markdown[i]); i++; continue }
                val s = out.length
                out.append(markdown, i + 2, close)
                styles.add(InlineStyleRange(s, out.length, InlineStyle.Bold))
                i = close + 2
            }
            markdown.startsWith("<u>", i) -> {
                val close = markdown.indexOf("</u>", i + 3)
                if (close == -1) { out.append(markdown[i]); i++; continue }
                val s = out.length
                out.append(markdown, i + 3, close)
                styles.add(InlineStyleRange(s, out.length, InlineStyle.Underline))
                i = close + 4
            }
            markdown.startsWith("~~", i) -> {
                val close = markdown.indexOf("~~", i + 2)
                if (close == -1) { out.append(markdown[i]); i++; continue }
                val s = out.length
                out.append(markdown, i + 2, close)
                styles.add(InlineStyleRange(s, out.length, InlineStyle.Strikethrough))
                i = close + 2
            }
            markdown.startsWith("`", i) -> {
                val close = markdown.indexOf("`", i + 1)
                if (close == -1) { out.append(markdown[i]); i++; continue }
                val s = out.length
                out.append(markdown, i + 1, close)
                styles.add(InlineStyleRange(s, out.length, InlineStyle.Code))
                i = close + 1
            }
            (markdown[i] == '*' && !markdown.startsWith("**", i)) || markdown[i] == '_' -> {
                val marker = markdown[i]
                // Syarat markdown standar: gak ada spasi nempel di dalam pembuka/penutup —
                // ini yang bedain "*emphasis*" dari "2 * 3" (tanda kali rumus fisika/matematika).
                val validOpen = i + 1 < markdown.length && !markdown[i + 1].isWhitespace()
                if (!validOpen) { out.append(markdown[i]); i++; continue }
                val close = markdown.indexOf(marker, i + 1)
                val validClose = close != -1 && close > i + 1 && !markdown[close - 1].isWhitespace()
                if (!validClose) { out.append(markdown[i]); i++; continue }
                val s = out.length
                out.append(markdown, i + 1, close)
                styles.add(InlineStyleRange(s, out.length, InlineStyle.Italic))
                i = close + 1
            }
            else -> { out.append(markdown[i]); i++ }
        }
    }
    val text = out.toString()
    val headingLines = headingLevelByLineIndex.map { (lineIdx, level) ->
        val start = lineStartOffsets[lineIdx]
        val end = if (lineIdx + 1 < lineStartOffsets.size) lineStartOffsets[lineIdx + 1] - 1 else text.length
        HeadingLineMarker(start, end, level)
    }
    return StyledText(text, styles, headingLines)
}

/** Serializer StyledText → markdown. Gabung dua lapis per baris: prefix heading dulu (kalau
 * baris itu match [HeadingLineMarker]), BARU wrapping inline-style di isinya — rentang gaya
 * yang TUMPANG TINDIH SEBAGIAN (bukan bersarang rapi) itu kasus yang gak dijamin sempurna
 * hasilnya, batasan markdown sendiri, bukan cuma implementasi ini. */
internal fun styledTextToMarkdown(styled: StyledText): String {
    val headingByStart = styled.headingLines.associateBy { it.start }
    val sortedStyles = styled.inlineStyles.filter { it.start < it.end }.sortedBy { it.start }
    val text = styled.text
    val result = StringBuilder()
    var lineStart = 0
    var styleIdx = 0
    while (lineStart <= text.length) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl == -1) text.length else nl
        val heading = headingByStart[lineStart]
        if (heading != null && heading.end == lineEnd) result.append(headingPrefixText(heading.level))
        var cursor = lineStart
        while (styleIdx < sortedStyles.size && sortedStyles[styleIdx].start < lineEnd) {
            val span = sortedStyles[styleIdx]
            if (span.start < cursor) { styleIdx++; continue } // tumpang tindih ganjil, skip
            result.append(text, cursor, span.start)
            val (open, close) = markerFor(span.style)
            result.append(open).append(text, span.start, span.end.coerceAtMost(lineEnd)).append(close)
            cursor = span.end.coerceAtMost(lineEnd)
            styleIdx++
        }
        result.append(text, cursor, lineEnd)
        if (nl == -1) break
        result.append('\n')
        lineStart = lineEnd + 1
    }
    return result.toString()
}

private fun markerFor(style: InlineStyle): Pair<String, String> = when (style) {
    InlineStyle.Bold -> "**" to "**"
    InlineStyle.Italic -> "*" to "*"
    InlineStyle.Underline -> "<u>" to "</u>"
    InlineStyle.Strikethrough -> "~~" to "~~"
    InlineStyle.Code -> "`" to "`"
}
