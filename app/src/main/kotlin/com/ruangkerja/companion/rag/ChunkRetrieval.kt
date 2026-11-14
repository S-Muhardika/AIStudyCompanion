package com.ruangkerja.companion.rag

import com.ruangkerja.companion.data.db.MaterialChunk
import kotlin.math.sqrt

// ── [BARU] Logic murni buat RAG (Diskusi grounded) — chunking teks materi jadi potongan yang
// cocok buat embedding, dan cosine similarity buat cari yang paling relevan pas user nanya.
// Sengaja dipisah dari network/ (yang manggil API) dan data/db/ (yang nyimpen) — file ini
// PURE FUNCTION, gak nyentuh network/storage sama sekali, jadi gampang diverifikasi lewat
// simulasi tanpa perlu device/API asli (sama kayak pola BlockManager.parseBlocks()). ──

private const val CHUNK_TARGET_SIZE = 900

// [KOREKSI, 11 Nov — "PLANNING IMPLEMENTASI RKR: PERBAIKAN RAG, CHUNKING, DAN AUTOMATIC
// EMBEDDING" §1] chunkText() di bawah DULU cuma mecah per-paragraf (baris kosong) lalu
// gabung-sampe-pas — tapi gak pernah ngecek apakah SATU paragraf sendirian udah lebih gede
// dari CHUNK_TARGET_SIZE (mis. 1 paragraf 5.000 karakter tanpa baris kosong di tengahnya bisa
// masuk JADI SATU chunk, lebih gede dari target). Helper di bawah (findBreakPoint +
// splitOversizedParagraph) nutup celah itu: paragraf yang sendirian > CHUNK_TARGET_SIZE
// dipecah DULU jadi beberapa potongan <= target, prioritas titik potong: baris
// kosong > baris baru > akhir kalimat > spasi (sesuai urutan di dokumen §1), baru-kalau-gak-ada
// satupun titik potong yang masuk akal (mis. 1 kata/URL panjang tanpa spasi) jatuh ke potongan
// per-karakter. Tidak mengubah cosineSimilarity()/topKSimilar() di bawah sama sekali.

// Titik potong "layak" minimal ada di separuh kedua window pencarian — kalau titik potong
// terbaik yang ketemu ada di awal banget (mis. baris baru di karakter ke-5 dari window 900),
// hasilnya jadi potongan mini yang nyisain hampir seluruh window gak kepake. Di bawah batas
// ini, mending jatuh ke potongan per-karakter (lebih merata) daripada dipaksa ikut titik potong
// yang kebetulan ada tapi jelek posisinya.
private const val MIN_SPLIT_RATIO = 0.5

/** Cari posisi potong terbaik di dalam `text` (cuma di window karakter 0 until [limit]),
 * prioritas: baris kosong (\n\n) > baris baru (\n) > akhir kalimat (. ! ? diikuti
 * spasi/akhir-window) > spasi mana pun. Null kalau gak ada satupun yang "layak" (lihat
 * MIN_SPLIT_RATIO) — caller lalu jatuh ke potongan per-karakter. Posisi yang dikembalikan
 * adalah index SETELAH karakter pemisahnya, jadi `text.substring(0, pos)` gak menyertakan
 * pemisah itu sendiri (aman buat langsung di-trim tanpa kehilangan kata). */
private fun findBreakPoint(text: String, limit: Int): Int? {
    val window = if (text.length <= limit) text else text.substring(0, limit)
    val minPos = (limit * MIN_SPLIT_RATIO).toInt()

    val blankLine = window.lastIndexOf("\n\n")
    if (blankLine >= minPos) return blankLine + 2

    val newline = window.lastIndexOf("\n")
    if (newline >= minPos) return newline + 1

    for (i in window.length - 1 downTo minPos) {
        val c = window[i]
        if ((c == '.' || c == '!' || c == '?') && (i + 1 >= window.length || window[i + 1].isWhitespace())) {
            return i + 1
        }
    }

    for (i in window.length - 1 downTo minPos) {
        if (window[i].isWhitespace()) return i + 1
    }

    return null
}

/** Posisi potong darurat per-karakter, dipakai cuma kalau findBreakPoint() gak nemu titik
 * potong layak sama sekali. Mundur 1 posisi kalau pas jatuh di tengah surrogate pair (emoji/
 * karakter astral Unicode) biar karakternya gak korup jadi dua tanda tanya lepas. */
private fun safeCharCut(text: String, limit: Int): Int {
    val pos = limit.coerceAtMost(text.length)
    return if (pos in 1 until text.length &&
        Character.isHighSurrogate(text[pos - 1]) && Character.isLowSurrogate(text[pos])
    ) pos - 1 else pos
}

/** Pecah SATU paragraf yang sendirian udah > [maxSize] jadi beberapa potongan, masing-masing
 * dijamin <= [maxSize] (lihat bukti invariant di catatan chunkText()). Paragraf yang sudah
 * <= [maxSize] dikembalikan apa adanya (satu elemen, no-op) — jalur normal (bukan-kepanjangan)
 * sama sekali tidak kena perubahan ini. */
private fun splitOversizedParagraph(paragraph: String, maxSize: Int): List<String> {
    if (paragraph.length <= maxSize) return listOf(paragraph)

    val pieces = mutableListOf<String>()
    var remaining = paragraph
    while (remaining.length > maxSize) {
        val breakPos = findBreakPoint(remaining, maxSize) ?: safeCharCut(remaining, maxSize)
        val piece = remaining.substring(0, breakPos).trim()
        if (piece.isNotEmpty()) pieces.add(piece)
        remaining = remaining.substring(breakPos).trimStart()
    }
    if (remaining.isNotEmpty()) pieces.add(remaining)
    return pieces
}

/** Pecah teks materi (markdown mentah) jadi beberapa chunk buat di-embed satu-satu. Dipisah
 * per PARAGRAF (baris kosong, niru pola parseBlocks() di editor) lalu digabung sampe deket
 * CHUNK_TARGET_SIZE karakter — chunk yang terlalu kecil (1 kalimat doang) kurang informatif
 * buat di-embed, kepotong kegedean (1 chunk = seluruh dokumen) bikin similarity search gak
 * presisi (embedding-nya jadi "rata-rata" terlalu banyak topik sekaligus). Target 900 karakter
 * itu kira-kira 1-2 paragraf materi kuliah/sekolah — bukan angka baku, keputusan awal yang
 * gampang di-tuning kalau hasil pencarian kerasa kurang pas.
 *
 * [KOREKSI, 11 Nov] Paragraf yang SENDIRIAN udah > CHUNK_TARGET_SIZE sekarang dipecah dulu
 * lewat splitOversizedParagraph() sebelum ikut logika gabung-sampe-pas di bawah — jadi INVARIANT
 * "setiap chunk hasil akhir <= CHUNK_TARGET_SIZE" selalu terjaga, baik paragrafnya pendek
 * (digabung kayak biasa) maupun kepanjangan (dipecah dulu, baru masing-masing potongan ikut
 * digabung/dipisah kayak paragraf biasa). */
internal fun chunkText(text: String): List<String> {
    val paragraphs = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
    if (paragraphs.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (p in paragraphs) {
        for (piece in splitOversizedParagraph(p, CHUNK_TARGET_SIZE)) {
            if (current.isNotEmpty() && current.length + piece.length > CHUNK_TARGET_SIZE) {
                chunks.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(piece)
        }
    }
    if (current.isNotEmpty()) chunks.add(current.toString())
    return chunks
}

private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom == 0f) 0f else dot / denom
}

/** Cari [k] chunk paling mirip sama [queryEmbedding] dari [chunks] — brute-force (hitung
 * cosine similarity ke SEMUA chunk, urutin, ambil teratas). Buat jumlah chunk materi personal
 * (ratusan sampe rendah-ribuan), ini cepet — gak butuh index/database vektor beneran. Kalau
 * suatu saat jumlah chunk-nya udah jutaan, baru brute-force ini jadi bottleneck — jauh di
 * luar skala pemakaian app catatan personal. */
internal fun topKSimilar(queryEmbedding: List<Float>, chunks: List<MaterialChunk>, k: Int): List<MaterialChunk> =
    chunks
        .map { it to cosineSimilarity(queryEmbedding, it.embedding) }
        .sortedByDescending { it.second }
        .take(k)
        .map { it.first }
