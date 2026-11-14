package com.ruangkerja.companion.util

// ── Predikat baris markdown yang dipakai BERSAMA oleh parser editor (BlockManager.parseBlocks) dan pembuat
// laporan PDF (ReportPdfBuilder). [DIGABUNG, audit duplikasi 2 Okt] Dulu masing-masing menyalin sendiri
// (isTableRow/isTableRowMd, dst.) supaya report/ tidak bergantung ke editor/ — util/ adalah tempat netral untuk
// keduanya. Salinan PDF juga sempat sedikit beda aturan kutipan (trim vs trimStart, hanya terasa pada baris
// ">" yang diikuti tab), jadi catatan bisa dianggap kutipan di satu tempat tapi bukan di tempat lain; sekarang
// PDF mengikuti aturan editor. Draft memakai aturan tabel yang sengaja lebih longgar (lihat
// editor/ARSITEKTUR_EDITOR.md), makanya isDraftTableLine TIDAK ikut digabung. ──
internal fun isTableRow(line: String): Boolean = line.trim().let { it.startsWith("|") && it.length > 1 }

internal fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|")) return false
    val cells = t.trim('|').split("|").map { it.trim() }
    return cells.isNotEmpty() && cells.all { it.isBlank() || it.matches(Regex("^:?-{2,}:?$")) }
}

internal fun isQuoteLine(line: String): Boolean = line.trimStart().let { it == ">" || it.startsWith("> ") }
