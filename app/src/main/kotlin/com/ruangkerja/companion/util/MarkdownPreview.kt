package com.ruangkerja.companion.util

// [BARU, 12 Nov — Tahap 1 "REKOMENDASI FINAL ARSITEKTUR": audit nemu beberapa preview/judul
// satu-baris (kartu Materi, dkk.) yang nampilin syntax Markdown mentah ke user — lihat
// rag-chunking-android/working-agreement buat konteks audit lengkapnya.
//
// Fungsi di sini BEDA tujuan dari MarkdownText (ui/components/MarkdownRenderer.kt): itu
// renderer PENUH (AnnotatedString berstruktur, buat ditampilin multi-baris apa adanya) — ini
// cuma buat preview/judul SATU BARIS yang di-maxLines/ellipsis, jadi yang dibutuhin String
// POLOS bersih, bukan styling. Sengaja LOSSY: boleh sedikit kurang presisi (mis. italic
// `*kata*`/`_kata_` tunggal SENGAJA tidak di-strip di sini — regex yang aman buat bedain itu
// dari perkalian "2 * 3" atau nama_variabel butuh logic sama ribetnya kayak mdInline() yang gak
// cocok buat konteks lossy-preview ini) — yang penting dijamin BERSIH dari heading/bold/link/
// gambar/list/quote/code-fence/tabel/horizontal-rule, yang jauh lebih sering & lebih mencolok
// munculnya di materi/konten AI daripada italic tunggal.
private val PREVIEW_HEADING = Regex("^#{1,6}\\s+")
private val PREVIEW_QUOTE = Regex("^>\\s?")
private val PREVIEW_CHECKLIST = Regex("^[-*]\\s\\[[ xX]]\\s")
private val PREVIEW_BULLET = Regex("^[-*]\\s+")
private val PREVIEW_NUMBERED = Regex("^\\d+\\.\\s+")
private val PREVIEW_HR = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val PREVIEW_IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
private val PREVIEW_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val PREVIEW_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
private val PREVIEW_STRIKE = Regex("~~([^~]+)~~")
private val PREVIEW_UNDERLINE = Regex("<u>([^<]*)</u>")
private val PREVIEW_INLINE_CODE = Regex("`([^`]*)`")
private val PREVIEW_WHITESPACE = Regex("\\s+")

/** Ubah teks Markdown (bisa multi-baris) jadi satu baris String polos buat preview/judul
 *  pendek — strip heading/quote/checklist/bullet/numbered/hr/code-fence/tabel per baris, lalu
 *  gabung spasi dan strip image/link/bold/strike/underline/inline-code. Lihat catatan file di
 *  atas soal kenapa italic tunggal (`*`/`_`) SENGAJA tidak ikut di-strip. */
internal fun markdownToPlainPreview(text: String): String {
    val cleanedLines = text.lines().mapNotNull { rawLine ->
        val trimmed = rawLine.trim()
        when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("```") -> null
            PREVIEW_HR.matches(trimmed) -> null
            isTableRow(trimmed) -> null
            else -> trimmed
                .replace(PREVIEW_CHECKLIST, "")
                .replace(PREVIEW_HEADING, "")
                .replace(PREVIEW_QUOTE, "")
                .replace(PREVIEW_BULLET, "")
                .replace(PREVIEW_NUMBERED, "")
        }
    }
    val inlineStripped = cleanedLines.joinToString(" ")
        .replace(PREVIEW_IMAGE, "")
        .replace(PREVIEW_LINK, "$1")
        .replace(PREVIEW_BOLD, "$1")
        .replace(PREVIEW_STRIKE, "$1")
        .replace(PREVIEW_UNDERLINE, "$1")
        .replace(PREVIEW_INLINE_CODE, "$1")
    return inlineStripped.replace(PREVIEW_WHITESPACE, " ").trim()
}
