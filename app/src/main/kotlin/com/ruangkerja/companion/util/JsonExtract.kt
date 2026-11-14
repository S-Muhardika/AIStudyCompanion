package com.ruangkerja.companion.util

/**
 * Ambil potongan JSON dari balasan AI yang kadang dibungkus teks pembuka atau pagar ```json.
 * Mengambil dari [open] pertama sampai [close] terakhir; kalau penandanya tidak ada, kembalikan teks apa
 * adanya (sudah di-trim). [DIGABUNG, audit duplikasi 2 Okt] Dulu pola ini ditulis ulang 4 kali di
 * AppViewModel (mind map, perbaikan mind map, flashcard, bab).
 */
internal fun extractJsonSpan(raw: String, open: Char, close: Char): String =
    raw.trim().let { val s = it.indexOf(open); val e = it.lastIndexOf(close); if (s >= 0 && e > s) it.substring(s, e + 1) else it }
