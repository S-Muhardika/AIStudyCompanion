package com.ruangkerja.companion.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BARU, 11 Nov — "PLANNING IMPLEMENTASI RKR: PERBAIKAN RAG, CHUNKING, DAN AUTOMATIC EMBEDDING"
 * §2] Unit test buat chunkText() — 10 skenario persis sesuai daftar di dokumen. Cuma nguji
 * chunkText() (satu-satunya fungsi yang eksplisit diminta diuji; topKSimilar()/cosineSimilarity()
 * tidak disentuh perbaikan ini jadi tidak ditambah test baru di sini). Tidak butuh mock/Context
 * apa pun — ChunkRetrieval.kt murni PURE FUNCTION (lihat komentar header file itu sendiri),
 * malah lebih sederhana dari LocalStorageTest.kt yang masih perlu mock Context.
 *
 * CATATAN JUJUR (sama kayak LocalStorageTest.kt): file ini ditulis & ditelusuri manual tanpa
 * compiler — sandbox tempat ini ditulis tidak punya Android SDK/Gradle cache sama sekali
 * (sdk.dir di local.properties nunjuk ke path di HP, bukan ke sandbox ini), jadi ./gradlew
 * sama sekali tidak bisa dijalankan dari sini. WAJIB dijalankan (`./gradlew :app:testDebugUnitTest`)
 * di AndroidIDE buat mastiin beneran lulus, bukan diasumsikan lulus dari sini.
 */
class ChunkRetrievalTest {

    private fun repeatWord(word: String, times: Int) = (1..times).joinToString(" ") { word }

    // 1. Input kosong menghasilkan list kosong.
    @Test
    fun `input kosong menghasilkan list kosong`() {
        assertEquals(emptyList<String>(), chunkText(""))
    }

    // 2. Input satu paragraf pendek menghasilkan satu chunk.
    @Test
    fun `satu paragraf pendek menghasilkan satu chunk`() {
        val result = chunkText("Ini paragraf pendek biasa.")
        assertEquals(1, result.size)
        assertEquals("Ini paragraf pendek biasa.", result[0])
    }

    // 3. Beberapa paragraf dapat digabungkan.
    @Test
    fun `beberapa paragraf pendek digabungkan jadi satu chunk kalau masih muat`() {
        val text = "Paragraf satu.\n\nParagraf dua.\n\nParagraf tiga."
        val result = chunkText(text)
        assertEquals(1, result.size)
        assertTrue(result[0].contains("Paragraf satu."))
        assertTrue(result[0].contains("Paragraf dua."))
        assertTrue(result[0].contains("Paragraf tiga."))
    }

    // 4. Paragraf tunggal yang panjang berhasil dipecah.
    @Test
    fun `paragraf tunggal yang panjang berhasil dipecah jadi beberapa chunk`() {
        // 1 "paragraf" (tanpa baris kosong di tengah) > 900 karakter.
        val longParagraph = repeatWord("kata", 300)
        assertTrue("data uji harus > 900 karakter dulu baru valid buat tes ini", longParagraph.length > 900)

        val result = chunkText(longParagraph)
        assertTrue("paragraf > 900 karakter harus kepecah jadi lebih dari 1 chunk", result.size > 1)
    }

    // 5. Tidak ada chunk yang melebihi 900 karakter.
    @Test
    fun `tidak ada chunk yang melebihi 900 karakter`() {
        val text = buildString {
            append(repeatWord("alpha", 300)) // paragraf super panjang, tanpa baris kosong
            append("\n\n")
            append("Paragraf pendek biasa yang harusnya ikut digabung kalau muat.")
            append("\n\n")
            append(repeatWord("beta", 250))
        }
        val result = chunkText(text)
        result.forEach { chunk ->
            assertTrue(
                "chunk sepanjang ${chunk.length} karakter melebihi batas 900: \"${chunk.take(50)}...\"",
                chunk.length <= 900
            )
        }
    }

    // 6. Tidak ada kata yang hilang akibat pemotongan.
    @Test
    fun `tidak ada kata yang hilang akibat pemotongan paragraf panjang`() {
        val words = (1..300).map { "kata$it" }
        val text = words.joinToString(" ")
        val result = chunkText(text)
        // Tokenisasi ulang hasilnya (bukan .contains() mentah — "kata1" adalah substring dari
        // "kata10"/"kata11"/dst, jadi .contains() doang bisa salah lolos walau katanya beneran
        // hilang). Cek keanggotaan token PERSIS sebagai gantinya.
        val rejoinedTokens = result.joinToString(" ").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        words.forEach { w ->
            assertTrue("kata \"$w\" harus tetap ada setelah chunking", w in rejoinedTokens)
        }
    }

    // 7. Urutan isi materi tetap dipertahankan.
    @Test
    fun `urutan isi materi tetap dipertahankan setelah chunking`() {
        val text = "Paragraf pertama di awal.\n\n" + repeatWord("tengah", 300) + "\n\nParagraf terakhir di akhir."
        val result = chunkText(text)
        val rejoined = result.joinToString(" | ")
        val posFirst = rejoined.indexOf("Paragraf pertama di awal.")
        val posMiddle = rejoined.indexOf("tengah")
        val posLast = rejoined.indexOf("Paragraf terakhir di akhir.")
        assertTrue("ketiganya harus ditemukan", posFirst >= 0 && posMiddle >= 0 && posLast >= 0)
        assertTrue("urutan harus: awal < tengah < akhir", posFirst < posMiddle && posMiddle < posLast)
    }

    // 8. Teks yang hanya berisi spasi tidak menghasilkan chunk.
    @Test
    fun `teks yang hanya berisi spasi tidak menghasilkan chunk`() {
        assertEquals(emptyList<String>(), chunkText("   \n\n   \n\t\n   "))
    }

    // 9. Teks dengan karakter Unicode dan bahasa Indonesia tetap diproses.
    @Test
    fun `teks unicode dan bahasa indonesia tetap diproses dengan benar`() {
        val text = "Akuntansi adalah seni mencatat, menggolongkan, dan mengikhtisarkan transaksi keuangan. " +
            "Contoh emoji: 📚✨🎓. Huruf aksen: café, naïve, Zürich. " +
            "Istilah bahasa Indonesia: pembelajaran, perusahaan, kewirausahaan."
        val result = chunkText(text)
        assertEquals(1, result.size)
        assertTrue(result[0].contains("Akuntansi"))
        assertTrue(result[0].contains("📚")) // emoji buku, cek surrogate pair utuh
        assertTrue(result[0].contains("café"))
        assertTrue(result[0].contains("kewirausahaan"))
    }

    // 10. Paragraf yang sangat panjang tanpa spasi tetap bisa diproses tanpa crash.
    @Test
    fun `paragraf sangat panjang tanpa spasi tetap diproses tanpa crash`() {
        // 1 "kata" raksasa — tidak ada spasi/baris baru/tanda baca sama sekali, jadi
        // findBreakPoint() pasti gagal nemu titik potong dan harus jatuh ke safeCharCut().
        val text = "a".repeat(5000)
        val result = chunkText(text)
        assertTrue("harus kepecah jadi beberapa chunk", result.size > 1)
        result.forEach { chunk -> assertTrue(chunk.length <= 900) }
        assertEquals("tidak boleh ada karakter yang hilang/dobel", 5000, result.sumOf { it.length })
    }
}
