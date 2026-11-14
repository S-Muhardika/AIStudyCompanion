package com.ruangkerja.companion.editor.core

// ── [v92 — dirombak total] Toggle Bold/Italic/Underline/Strikethrough/Code SEKARANG cuma
// tambah/hapus InlineStyleRange — TEKS-NYA SENDIRI GAK PERNAH BERUBAH (beda total dari v90/91
// yang nyisip/nyabut "**" di teks mentah). Toggle heading/bullet/nomor TETAP manipulasi teks
// (prefix baris, "# "/"- "/"1. " — itu di luar cakupan gaya inline, lihat catatan di
// NativeLiveStyle.kt kenapa), TAPI sekarang WAJIB ikut geser [InlineStyleRange] yang posisinya
// setelah titik sisip/hapus prefix — kalau enggak, gaya bold/italic yang udah ada bakal
// nempel ke karakter yang SALAH begitu prefix baris berubah panjang. ──

internal data class StyledFormatResult(val text: String, val styles: List<InlineStyleRange>, val selectionStart: Int, val selectionEnd: Int)

/** Batas baris yang memuat [pos]. Dibaca lewat first/last (bukan sebagai IntRange sungguhan): first = awal
 * baris, last = akhir baris EKSKLUSIF (indeks '\n' atau panjang teks), jadi langsung cocok buat
 * `substring(first, last)`. Dipakai bersama DraftEditor (dulu punya salinan sendiri).
 * [FIX tahap 5] Dulu `(pos - 1).coerceAtLeast(0)` menyamakan pos 0 dengan pos 1: kursor di posisi 0 pada
 * teks yang diawali baris kosong ("\nabc") menghasilkan start=1 > end=0, dan substring(first, last) di
 * toggleBulletPrefix/isBulletActiveAt melempar StringIndexOutOfBoundsException. Disimulasikan brute-force
 * (27.884 kasus): hasil baru SAMA dengan salinan Draft di semua kasus; yang berubah hanya pos 0. */
internal fun lineBoundsAt(text: String, pos: Int): IntRange {
    val start = if (pos <= 0) 0 else text.lastIndexOf('\n', pos - 1).let { if (it == -1) 0 else it + 1 }
    val end = text.indexOf('\n', pos.coerceIn(0, text.length)).let { if (it == -1) text.length else it }
    return start..end
}

/** Toggle satu gaya inline di rentang [selStart, selEnd]. Seleksi KOSONG (cuma kursor) = no-op
 * SENGAJA — gak ada lagi "mode ngetik lagi aktif" buat dilacak (beda dari toggle markdown
 * lama yang nyisip pasangan kosong); alur normalnya seleksi teks dulu baru toggle, sama kayak
 * pola pakai toolbar format kebanyakan app. */
internal fun toggleInlineStyle(text: String, styles: List<InlineStyleRange>, selStart: Int, selEnd: Int, style: InlineStyle): StyledFormatResult {
    val lo = minOf(selStart, selEnd).coerceIn(0, text.length)
    val hi = maxOf(selStart, selEnd).coerceIn(0, text.length)
    if (lo == hi) return StyledFormatResult(text, styles, selStart, selEnd)

    val alreadyFullyCovered = styles.any { it.style == style && it.start <= lo && it.end >= hi }
    val newStyles = if (alreadyFullyCovered) {
        // Lepas: potong/hapus semua rentang gaya SEJENIS yang overlap [lo, hi].
        styles.flatMap { r ->
            if (r.style != style || r.end <= lo || r.start >= hi) listOf(r)
            else buildList {
                if (r.start < lo) add(r.copy(end = lo))
                if (r.end > hi) add(r.copy(start = hi))
            }
        }
    } else {
        // Pasang: buang rentang SEJENIS yang overlap (biar gak dobel), gabung jadi satu span baru.
        val kept = styles.filterNot { it.style == style && it.start < hi && it.end > lo }
        kept + InlineStyleRange(lo, hi, style)
    }
    return StyledFormatResult(text, newStyles, lo, hi)
}

internal fun isInlineStyleActiveAt(styles: List<InlineStyleRange>, pos: Int, style: InlineStyle): Boolean =
    styles.any { it.style == style && pos >= it.start && pos < it.end }

/** [FIX15] Buat toolbar (tab Normal/Judul/Subjudul aktif yang mana) — end INCLUSIVE (beda dari
 *  isInlineStyleActiveAt yang exclusive) soalnya kursor WAJAR nangkring PERSIS di ujung baris
 *  abis selesai ngetik heading-nya, itu tetep harus keitung "lagi di baris heading ini". */
internal fun headingLevelActiveAt(headingLines: List<HeadingLineMarker>, pos: Int): Int =
    headingLines.firstOrNull { pos >= it.start && pos <= it.end }?.level ?: 0

private fun shiftStyles(styles: List<InlineStyleRange>, fromPos: Int, delta: Int): List<InlineStyleRange> =
    styles.map { r ->
        val newStart = if (r.start >= fromPos) (r.start + delta).coerceAtLeast(fromPos) else r.start
        val newEnd = if (r.end >= fromPos) (r.end + delta).coerceAtLeast(fromPos) else r.end
        r.copy(start = newStart, end = newEnd)
    }.filter { it.start < it.end }

internal fun toggleBulletPrefix(text: String, styles: List<InlineStyleRange>, pos: Int): StyledFormatResult {
    val bounds = lineBoundsAt(text, pos)
    val lineStart = bounds.first; val lineEnd = bounds.last
    val line = text.substring(lineStart, lineEnd)
    val oldPrefixLen = when {
        line.startsWith("- ") -> 2
        else -> Regex("^\\d+\\.\\s").find(line)?.value?.length ?: 0
    }
    val newLine = if (line.startsWith("- ")) line.removePrefix("- ") else "- " + line.drop(oldPrefixLen)
    val new = text.substring(0, lineStart) + newLine + text.substring(lineEnd)
    val delta = newLine.length - line.length
    val newStyles = shiftStyles(styles, lineStart, delta)
    val newPos = (pos + delta).coerceIn(lineStart, lineStart + newLine.length)
    return StyledFormatResult(new, newStyles, newPos, newPos)
}

internal fun toggleNumberedPrefix(text: String, styles: List<InlineStyleRange>, pos: Int): StyledFormatResult {
    val bounds = lineBoundsAt(text, pos)
    val lineStart = bounds.first; val lineEnd = bounds.last
    val line = text.substring(lineStart, lineEnd)
    val numberedMatch = Regex("^\\d+\\.\\s").find(line)
    val newLine = if (numberedMatch != null) line.removePrefix(numberedMatch.value) else "1. " + line.removePrefix("- ")
    val new = text.substring(0, lineStart) + newLine + text.substring(lineEnd)
    val delta = newLine.length - line.length
    val newStyles = shiftStyles(styles, lineStart, delta)
    val newPos = (pos + delta).coerceIn(lineStart, lineStart + newLine.length)
    return StyledFormatResult(new, newStyles, newPos, newPos)
}

internal fun isBulletActiveAt(text: String, pos: Int): Boolean = text.substring(lineBoundsAt(text, pos).first, lineBoundsAt(text, pos).last).startsWith("- ")
internal fun isNumberedActiveAt(text: String, pos: Int): Boolean = Regex("^\\d+\\.\\s").containsMatchIn(text.substring(lineBoundsAt(text, pos).first, lineBoundsAt(text, pos).last))

/** Posisi [p] setelah [lo,hi) dihapus dari teks: sebelum [lo] gak berubah, setelah/persis di
 * [hi] geser mundur sepanjang rentang yang dihapus, DI DALAM rentang yang dihapus collapse
 * ke [lo] (gak ada lagi "posisi yang sama", paling masuk akal jatuh ke titik awal hapusan). */
private fun mapPosAfterRemoval(p: Int, lo: Int, hi: Int, delta: Int): Int = when {
    p <= lo -> p
    p >= hi -> p - delta
    else -> lo
}

/** Hapus rentang [start,end) dari teks SAMBIL jaga posisi [styles] tetap bener — dipake pas
 * mindahin teks terpilih ke Kotak Contoh (BlockManager.insertQuoteAtCursor), teks itu HARUS
 * ilang dari sumbernya, style di sekitarnya (kalau ada) harus tetep nempel ke konten yang
 * BENERAN masih ada, bukan ketinggalan di posisi lama. */
internal fun removeTextRangeWithStyles(text: String, styles: List<InlineStyleRange>, start: Int, end: Int): Pair<String, List<InlineStyleRange>> {
    val lo = start.coerceIn(0, text.length); val hi = end.coerceIn(0, text.length)
    if (lo >= hi) return text to styles
    val newText = text.removeRange(lo, hi)
    val delta = hi - lo
    val newStyles = styles.mapNotNull { r ->
        val newStart = mapPosAfterRemoval(r.start, lo, hi, delta)
        val newEnd = mapPosAfterRemoval(r.end, lo, hi, delta)
        if (newStart < newEnd) r.copy(start = newStart, end = newEnd) else null
    }
    return newText to newStyles
}
