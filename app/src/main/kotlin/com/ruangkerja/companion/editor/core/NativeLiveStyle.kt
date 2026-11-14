package com.ruangkerja.companion.editor.core

import android.graphics.Typeface
import android.text.Editable
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.DisplayMetrics
import android.util.TypedValue

// ── [v92, direvisi v94] Sebelumnya file ini scan MARKDOWN MENTAH (termasuk "**"/"*"/dst
// yang masih ada di buffer) dan nyamarin syntax-nya (font 1px+transparan). SEKARANG gaya
// INLINE (bold/italic/underline/strikethrough/code) UDAH GAK ADA SYNTAX-NYA SAMA SEKALI di
// buffer — itu direpresentasiin sebagai [InlineStyleRange] terpisah (lihat
// MarkdownStyleCodec.kt), dipasang di sini sebagai Span ASLI (StyleSpan/UnderlineSpan/dst),
// BUKAN teks yang disamarkan.
//
// [FIX13→FIX15] Heading (#/##/###) SEMPAT dicoba disamarkan lewat teks (font 1px+transparan,
// v90/91 style) lalu jadi properti per-BLOK ([FIX13], DIBATALKAN — salah asumsi, lihat catatan
// panjang di MarkdownStyleCodec.kt). SEKARANG heading niru PERSIS pola inline style di atas:
// [HeadingLineMarker] per-BARIS (bukan karakter "#" yang disamarkan, bukan juga satu Int per-
// blok) — dipasang sebagai span PENANDA (HeadingLineSpan) yang beneran ngelacak posisi baris
// itu (termasuk molor kalau user lanjut ngetik di ujungnya — lihat flag SPAN_EXCLUSIVE_INCLUSIVE
// di bawah), dibaca balik lewat extractHeadingLines() sama persis kayak extractInlineStyles().
//
// Bullet/nomor/checklist/code-fence/image TETAP KELIHATAN APA ADANYA (gak disamarkan,
// gak jadi struktur terpisah) — itu jarang diketik manual karakter-per-karakter (beda dari
// "**"/heading yang jadi masalah kalau disamarkan lewat teks doang). ──

private val ALIGN_TAG = Regex("^<(center|right)>(.*)</\\1>$")
private val CHECKLIST_MARKER = Regex("^-\\s\\[[ xX]]\\s")
private val NUMBERED_MARKER = Regex("^\\d+\\.\\s")

internal class LiveStylePalette(
    val accent: Int,
    val dim: Int,
    val muted: Int,
    val success: Int,
    val codeBg: Int
)

// ── Subclass PENANDA — StyleSpan/UnderlineSpan/dst dipakai DUA KONTEKS beda di file ini:
// gaya INLINE (dari data, harus bisa dibaca balik) DAN styling visual prefix baris (bullet/
// nomor/dst, murni tampilan, gak perlu dibaca balik — didapat ulang dari scan teks tiap kali).
// Kalau dua-duanya pakai kelas Android POLOS yang SAMA, gak ada cara bedain pas nge-scan
// balik span di Editable buat ekstrak [InlineStyleRange] (lihat extractInlineStyles di bawah)
// — StyleSpan(BOLD) bisa aja itu inline bold ATAU heading, ambigu. Subclass PENANDA ini gak
// nambah efek visual apa pun (Android tetep render sesuai span PARENT-nya), cuma nambah
// identitas TIPE yang bisa dicek lewat `is InlineBoldMarker` dkk. ──
private class InlineBoldMarker : StyleSpan(Typeface.BOLD)
private class InlineItalicMarker : StyleSpan(Typeface.ITALIC)
private class InlineUnderlineMarker : UnderlineSpan()
private class InlineStrikethroughMarker : StrikethroughSpan()
private class InlineCodeTypefaceMarker : TypefaceSpan("monospace")
private class InlineCodeBgMarker(color: Int) : BackgroundColorSpan(color)

/** [FIX15] PENANDA doang (BUKAN turunan span Android apa pun) — visual heading (bold/size/
 *  warna) dipasang TERPISAH lewat span asli biasa (lihat applyLiveSpans), ini cuma buat
 *  ngelacak POSISI baris heading-nya lewat getSpans(). [level] disimpen biar extractHeadingLines
 *  tau ini H1/H2/H3 tanpa perlu baca ulang teksnya. */
private class HeadingLineSpan(val level: Int)

/** Pasang span dari [inlineStyles]/[headingLines] (BENERAN bold/italic/heading/dst, bukan
 * disamarkan) PLUS styling visual ringan buat prefix baris (bullet/nomor/checklist/code-fence)
 * yang dibiarin KELIHATAN apa adanya. Dipanggil dari TextWatcher tiap teks berubah, dan sekali
 * manual pas blok pertama dirender (lihat NativeTextField.kt). */
internal fun applyLiveSpans(
    editable: Editable,
    inlineStyles: List<InlineStyleRange>,
    headingLines: List<HeadingLineMarker>,
    applied: MutableList<Any>,
    palette: LiveStylePalette,
    metrics: DisplayMetrics
) {
    applied.forEach { editable.removeSpan(it) }
    applied.clear()

    fun px(sp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics).toInt().coerceAtLeast(1)
    fun add(span: Any, start: Int, end: Int) {
        if (start >= end || start < 0 || end > editable.length) return
        editable.setSpan(span, start, end, Editable.SPAN_EXCLUSIVE_EXCLUSIVE)
        applied.add(span)
    }

    // ── Gaya INLINE — span PENANDA (bukan kelas Android polos), langsung dari data, gak
    // perlu scan ulang teks. Penanda ini yang nanti dibaca balik lewat extractInlineStyles(). ──
    for (r in inlineStyles) {
        when (r.style) {
            InlineStyle.Bold -> add(InlineBoldMarker(), r.start, r.end)
            InlineStyle.Italic -> add(InlineItalicMarker(), r.start, r.end)
            InlineStyle.Underline -> add(InlineUnderlineMarker(), r.start, r.end)
            InlineStyle.Strikethrough -> add(InlineStrikethroughMarker(), r.start, r.end)
            InlineStyle.Code -> {
                add(InlineCodeTypefaceMarker(), r.start, r.end)
                add(InlineCodeBgMarker(palette.codeBg), r.start, r.end)
            }
        }
    }

    // [FIX15] Heading — span PENANDA HeadingLineSpan dipasang SPAN_EXCLUSIVE_INCLUSIVE
    // (BEDA dari yang lain yang EXCLUSIVE_EXCLUSIVE): ujung akhirnya INCLUSIVE biar kalau user
    // lanjut ngetik PERSIS di ujung baris heading (kasus paling umum — nulis judul terus
    // lanjut ngetik isinya), teks baru itu ikut "ketarik" masuk cakupan heading, bukan
    // nongol sebagai teks polos ukuran normal di ujung. Efek sampingnya: kalau user pencet
    // Enter di ujung baris ini, span BISA kebablasan "nutup" sampai ke baris baru buat
    // sesaat — makanya extractHeadingLines() (di bawah) SELALU motong balik persis di
    // newline pertama pas dibaca ulang, apapun raw end mentah span-nya. Visual (bold/
    // ukuran/warna) dipasang TERPISAH lewat add() biasa (EXCLUSIVE_EXCLUSIVE gak masalah
    // buat ini, cuma soal tampilan, bukan pelacakan).
    for (h in headingLines) {
        if (h.start >= h.end || h.start < 0 || h.end > editable.length) continue
        val marker = HeadingLineSpan(h.level)
        editable.setSpan(marker, h.start, h.end, Editable.SPAN_EXCLUSIVE_INCLUSIVE)
        applied.add(marker)
        val size = when (h.level) { 1 -> 19f; 2 -> 17f; else -> 15f }
        add(StyleSpan(Typeface.BOLD), h.start, h.end); add(AbsoluteSizeSpan(px(size)), h.start, h.end); add(ForegroundColorSpan(palette.accent), h.start, h.end)
    }

    // ── Prefix baris lain (bullet/nomor/checklist/code-fence/image/align) — styling ringan,
    // TETAP KELIHATAN (gak disamarkan, gak jadi struktur terpisah). ──
    val text = editable.toString()
    val lines = text.split("\n")
    var offset = 0
    for (line in lines) {
        val lineStart = offset
        val lineEnd = offset + line.length
        when {
            CHECKLIST_MARKER.containsMatchIn(line) -> {
                val checked = line.trimStart().startsWith("- [x", ignoreCase = true)
                val markerLen = CHECKLIST_MARKER.find(line)!!.value.length
                add(ForegroundColorSpan(if (checked) palette.success else palette.muted), lineStart, (lineStart + markerLen).coerceAtMost(lineEnd))
                add(StyleSpan(Typeface.BOLD), lineStart, (lineStart + 2).coerceAtMost(lineEnd))
                if (checked) {
                    val contentStart = (lineStart + markerLen).coerceAtMost(lineEnd)
                    add(ForegroundColorSpan(palette.dim), contentStart, lineEnd)
                    add(StrikethroughSpan(), contentStart, lineEnd)
                }
            }
            line.startsWith("- ") || line.startsWith("* ") -> add(ForegroundColorSpan(palette.accent), lineStart, (lineStart + 2).coerceAtMost(lineEnd)).let { add(StyleSpan(Typeface.BOLD), lineStart, (lineStart + 2).coerceAtMost(lineEnd)) }
            NUMBERED_MARKER.containsMatchIn(line) -> {
                val markerLen = NUMBERED_MARKER.find(line)!!.value.length
                add(ForegroundColorSpan(palette.accent), lineStart, (lineStart + markerLen).coerceAtMost(lineEnd))
                add(StyleSpan(Typeface.BOLD), lineStart, (lineStart + markerLen).coerceAtMost(lineEnd))
            }
            line.trimStart().startsWith("```") -> { add(TypefaceSpan("monospace"), lineStart, lineEnd); add(ForegroundColorSpan(palette.muted), lineStart, lineEnd) }
            line.trimStart().startsWith("![") -> add(ForegroundColorSpan(palette.accent), lineStart, lineEnd)
            ALIGN_TAG.find(line) != null -> add(ForegroundColorSpan(palette.muted), lineStart, lineEnd)
            line.trimStart().startsWith("|") -> { add(TypefaceSpan("monospace"), lineStart, lineEnd); add(ForegroundColorSpan(palette.muted), lineStart, lineEnd); add(AbsoluteSizeSpan(px(12f)), lineStart, lineEnd) }
        }
        offset = lineEnd + 1
    }
}

/** Kebalikan dari applyLiveSpans buat bagian inline — baca span PENANDA yang ADA di
 * [editable] SEKARANG (posisinya udah otomatis digeser bener sama Android sendiri kalau ada
 * teks disisip/dihapus di sekitarnya — itu perilaku bawaan Spannable, gak perlu dihitung
 * manual) dan susun ulang jadi [InlineStyleRange]. Dipanggil dari TextWatcher tiap teks
 * berubah, SEBELUM applyLiveSpans motong-masang ulang semuanya. */
internal fun extractInlineStyles(editable: Editable): List<InlineStyleRange> {
    val result = mutableListOf<InlineStyleRange>()
    editable.getSpans(0, editable.length, InlineBoldMarker::class.java).forEach { result.add(InlineStyleRange(editable.getSpanStart(it), editable.getSpanEnd(it), InlineStyle.Bold)) }
    editable.getSpans(0, editable.length, InlineItalicMarker::class.java).forEach { result.add(InlineStyleRange(editable.getSpanStart(it), editable.getSpanEnd(it), InlineStyle.Italic)) }
    editable.getSpans(0, editable.length, InlineUnderlineMarker::class.java).forEach { result.add(InlineStyleRange(editable.getSpanStart(it), editable.getSpanEnd(it), InlineStyle.Underline)) }
    editable.getSpans(0, editable.length, InlineStrikethroughMarker::class.java).forEach { result.add(InlineStyleRange(editable.getSpanStart(it), editable.getSpanEnd(it), InlineStyle.Strikethrough)) }
    // Code ada 2 marker (typeface+background) tapi harusnya selalu berpasangan rentang sama
    // persis (dipasang bareng di applyLiveSpans) — cukup baca salah satu biar gak dobel.
    editable.getSpans(0, editable.length, InlineCodeTypefaceMarker::class.java).forEach { result.add(InlineStyleRange(editable.getSpanStart(it), editable.getSpanEnd(it), InlineStyle.Code)) }
    return result.filter { it.start in 0..editable.length && it.end in 0..editable.length && it.start < it.end }
}

/** [FIX15] Kebalikan dari bagian heading applyLiveSpans — baca balik posisi HeadingLineSpan
 * yang SEKARANG (udah otomatis digeser/dimolorin Android seiring user ngetik/ngedit). Ujung
 * mentahnya (getSpanEnd) BISA kebablasan lewat newline (lihat catatan SPAN_EXCLUSIVE_INCLUSIVE
 * di applyLiveSpans) — makanya SELALU dipotong ulang persis di karakter '\n' PERTAMA yang
 * ketemu mulai dari start, apa pun raw end-nya. Ini yang mastiin Enter di ujung baris heading
 * BENERAN motong heading-nya di situ, gak nular ke baris baru di bawahnya. */
internal fun extractHeadingLines(editable: Editable): List<HeadingLineMarker> {
    val text = editable
    val result = mutableListOf<HeadingLineMarker>()
    editable.getSpans(0, editable.length, HeadingLineSpan::class.java).forEach { span ->
        val rawStart = editable.getSpanStart(span).coerceIn(0, editable.length)
        val rawEnd = editable.getSpanEnd(span).coerceIn(0, editable.length)
        if (rawStart >= rawEnd) return@forEach
        var nl = rawStart
        while (nl < editable.length && text[nl] != '\n') nl++
        val clippedEnd = minOf(rawEnd, nl)
        if (rawStart < clippedEnd) result.add(HeadingLineMarker(rawStart, clippedEnd, span.level))
    }
    return result
}
