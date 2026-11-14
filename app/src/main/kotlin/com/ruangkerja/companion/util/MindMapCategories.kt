package com.ruangkerja.companion.util

/**
 * Kosakata kategori & hubungan mind map + pengelompokan warna.
 *
 * Sesuai spek Mind Map: kategori BUKAN template wajib dan tidak dikunci pada jumlah/jenis tertentu — daftar
 * [VOCABULARY] hanyalah kosakata yang disarankan (dipakai di prompt AI dan pemilih kategori). Teks bebas
 * tetap diterima apa adanya (lihat [normalize]); node boleh juga tanpa kategori.
 */
internal object MindMapCategories {

    val VOCABULARY: List<String> = listOf(
        "Konsep", "Definisi", "Tujuan", "Fungsi", "Manfaat", "Karakteristik", "Prinsip", "Unsur", "Komponen",
        "Jenis", "Klasifikasi", "Struktur", "Proses", "Tahapan", "Mekanisme", "Aturan", "Rumus", "Formula", "Variabel",
        "Indikator", "Syarat", "Kondisi", "Hubungan", "Sebab", "Akibat", "Dampak", "Perbedaan", "Persamaan",
        "Contoh", "Studi Kasus", "Penerapan", "Prosedur", "Metode", "Hasil", "Kelebihan", "Keterbatasan",
        "Risiko", "Kesalahan Umum", "Istilah", "Catatan Penting"
    )

    /**
     * Kosakata linking phrase (saran di UI & prompt; teks bebas tetap boleh). Sesuai spek: pakai frasa yang
     * MENJELASKAN hubungan — bukan label generik ("terkait", "berhubungan", "link"), lihat [cleanRelation].
     */
    val RELATIONS: List<String> = listOf(
        "terdiri dari", "merupakan bagian dari", "menghasilkan", "menyebabkan", "memengaruhi", "digunakan untuk",
        "dicatat melalui", "menjelaskan", "merupakan contoh dari", "berbeda dengan", "bergantung pada",
        "menjadi dasar bagi", "menghasilkan perubahan pada", "membutuhkan", "terjadi sebelum", "terjadi setelah"
    )

    private val GENERIC_RELATIONS: Set<String> = setOf(
        "terkait", "berhubungan", "berhubungan dengan", "berkaitan", "berkaitan dengan", "hubungan", "link", "relasi",
        "related", "related to"
    )

    /**
     * Rapikan label hubungan DARI AI/impor: label generik dikosongkan (peta menampilkan garis tanpa label, bukan label
     * yang tidak menjelaskan apa-apa). Label ketikan pengguna sendiri TIDAK disaring.
     */
    fun cleanRelation(raw: String?): String {
        val t = raw.orEmpty().replace(Regex("\\s+"), " ").trim().trim('"', '\'', '.', ':', '-', '>').trim()
        if (t.isEmpty() || t.lowercase() in GENERIC_RELATIONS) return ""
        return t.take(40)
    }

    /** Kelompok warna. LAIN = kategori teks bebas di luar kosakata; NETRAL = tanpa kategori. */
    enum class Family { NETRAL, INTI, STRUKTUR, PROSES, HUBUNGAN, PENERAPAN, PERINGATAN, CATATAN, LAIN }

    private val families: Map<Family, List<String>> = mapOf(
        Family.INTI to listOf("Konsep", "Definisi", "Tujuan", "Fungsi", "Manfaat"),
        Family.STRUKTUR to listOf("Karakteristik", "Prinsip", "Unsur", "Komponen", "Jenis", "Klasifikasi", "Struktur"),
        Family.PROSES to listOf("Proses", "Tahapan", "Mekanisme", "Aturan", "Rumus", "Formula", "Variabel", "Indikator", "Syarat", "Kondisi", "Prosedur", "Metode"),
        Family.HUBUNGAN to listOf("Hubungan", "Sebab", "Akibat", "Dampak", "Perbedaan", "Persamaan"),
        Family.PENERAPAN to listOf("Contoh", "Studi Kasus", "Penerapan", "Hasil", "Kelebihan"),
        Family.PERINGATAN to listOf("Keterbatasan", "Risiko", "Kesalahan Umum"),
        Family.CATATAN to listOf("Istilah", "Catatan Penting")
    )
    private val familyByName: Map<String, Family> =
        families.flatMap { (f, names) -> names.map { it.lowercase() to f } }.toMap()
    private val vocabByLower: Map<String, String> = VOCABULARY.associateBy { it.lowercase() }

    /** Padanan istilah umum ke kosakata. Nilai kosong = sengaja "tanpa kategori" (mis. PENJELASAN dari format lama). */
    private val synonyms: Map<String, String> = mapOf(
        "penjelasan" to "", "uraian" to "", "detail" to "",
        "konsep utama" to "Konsep", "peringatan" to "Kesalahan Umum", "kesalahan" to "Kesalahan Umum",
        "pengertian" to "Definisi", "ciri" to "Karakteristik", "ciri-ciri" to "Karakteristik",
        "langkah" to "Tahapan", "tahap" to "Tahapan", "kekurangan" to "Keterbatasan", "keunggulan" to "Kelebihan",
        "penyebab" to "Sebab", "catatan" to "Catatan Penting", "kasus" to "Studi Kasus", "aplikasi" to "Penerapan"
    )

    /** Rapikan kategori mentah: cocokkan ke kosakata (tak peka huruf besar/kecil), selain itu teks bebas ≤ 24 huruf. */
    fun normalize(raw: String?): String {
        val cleaned = raw.orEmpty().replace(Regex("\\s+"), " ").trim().trim('"', '\'', '.', ':').trim()
        if (cleaned.isEmpty()) return ""
        val key = cleaned.lowercase().replace('_', ' ')
        synonyms[key]?.let { return it }
        vocabByLower[key]?.let { return it }
        return cleaned.take(24).trim().replaceFirstChar { it.uppercase() }
    }

    fun familyOf(category: String): Family =
        if (category.isBlank()) Family.NETRAL else familyByName[category.trim().lowercase()] ?: Family.LAIN

    /**
     * Tebakan kategori dari AWALAN label (jalur algoritma/offline): hanya kalau label DIMULAI dengan kata
     * kosakata, mis. "Contoh kasus" → Contoh, "Tahap 1" → Tahapan. Kalau tak ada yang cocok → "" (tanpa
     * kategori); tidak pernah memaksa kategori.
     */
    fun infer(label: String): String {
        val l = label.trim().lowercase()
        for ((prefix, cat) in PREFIX_RULES) {
            if (l.startsWith(prefix) && (l.length == prefix.length || !l[prefix.length].isLetter())) return cat
        }
        return ""
    }

    private val PREFIX_RULES: List<Pair<String, String>> = listOf(
        "studi kasus" to "Studi Kasus", "kesalahan umum" to "Kesalahan Umum", "catatan penting" to "Catatan Penting",
        "pengertian" to "Definisi", "definisi" to "Definisi", "tujuan" to "Tujuan", "fungsi" to "Fungsi", "manfaat" to "Manfaat",
        "karakteristik" to "Karakteristik", "ciri" to "Karakteristik", "prinsip" to "Prinsip", "unsur" to "Unsur",
        "komponen" to "Komponen", "jenis" to "Jenis", "macam" to "Jenis", "klasifikasi" to "Klasifikasi", "struktur" to "Struktur",
        "tahapan" to "Tahapan", "tahap" to "Tahapan", "langkah" to "Tahapan", "mekanisme" to "Mekanisme", "aturan" to "Aturan",
        "rumus" to "Rumus", "formula" to "Formula", "variabel" to "Variabel", "indikator" to "Indikator", "syarat" to "Syarat", "kondisi" to "Kondisi",
        "prosedur" to "Prosedur", "metode" to "Metode", "proses" to "Proses",
        "hubungan" to "Hubungan", "penyebab" to "Sebab", "sebab" to "Sebab", "akibat" to "Akibat", "dampak" to "Dampak",
        "perbedaan" to "Perbedaan", "persamaan" to "Persamaan",
        "contoh" to "Contoh", "penerapan" to "Penerapan", "aplikasi" to "Penerapan", "hasil" to "Hasil",
        "kelebihan" to "Kelebihan", "keunggulan" to "Kelebihan", "kekurangan" to "Keterbatasan",
        "keterbatasan" to "Keterbatasan", "risiko" to "Risiko", "kesalahan" to "Kesalahan Umum",
        "istilah" to "Istilah", "catatan" to "Catatan Penting"
    )
}
