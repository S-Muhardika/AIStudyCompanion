package com.ruangkerja.companion.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object JinaExtractor {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private const val JINA_BASE = "https://r.jina.ai/"

    // [FIX] SEBELUMNYA request ini pakai header "X-Return-Format: text". Ternyata (dikonfirmasi
    // dari README resmi jina-ai/reader) itu artinya "kasih document.body.innerText MENTAH,
    // LEWATIN proses readability sama sekali" — sama kayak "X-Return-Format: markdown" yang
    // eksplisit, DUA-DUANYA sengaja MEMATIKAN filtering. Itu sebabnya hasilnya "semua teks
    // dimasukkan, gak rapi": nav bar, iklan, footer, cookie banner — SEMUA halaman ke-dump rata
    // tanpa struktur, karena bukan Jina yang gagal milah, tapi diminta buat JANGAN milah.
    //
    // Mode DEFAULT Jina (header X-Return-Format TIDAK DIKIRIM SAMA SEKALI, bukan cuma diisi
    // string kosong) justru yang lewatin Readability.js (algoritma "reader mode" browser) DULU
    // sebelum dikonversi ke Markdown — ini yang bikin Jina Reader terkenal bisa milah konten
    // inti & buang navigasi otomatis. Fix-nya: JANGAN set header ini sama sekali, biar Jina
    // pakai default-nya. Bonus: hasilnya jadi Markdown terstruktur (heading/bullet asli), bukan
    // teks rata — lebih cocok juga buat parser markdown lain di app ini (ReportPdfBuilder dkk).
    private val HEADER_MARKERS = listOf("Markdown Content:", "Text Content:", "HTML Content:")

    suspend fun extract(url: String): String = withContext(Dispatchers.IO) {
        val targetUrl = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        // [FIX, 29 Okt — audit RENDAH #2] Request.Builder().url() bisa lempar
        // IllegalArgumentException kalau targetUrl hasil input user gak valid jadi URL (karakter
        // aneh, dst) — SEBELUMNYA gak ketangkep di sini, cuma kegagalan HTTP (resp.isSuccessful,
        // di bawah) yang ditangani. Sekarang dibungkus biar pemanggil (AppViewModel.importFromUrl,
        // lewat UI) selalu nerima Exception dengan pesan konsisten kayak kegagalan lain di fungsi
        // ini, bukan exception platform mentah yang mungkin gak ketangkep di UI.
        val req = try {
            Request.Builder().url("$JINA_BASE$targetUrl").build()
        } catch (_: IllegalArgumentException) {
            throw Exception("Tautan tidak valid — periksa kembali URL yang dimasukkan.")
        }
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw Exception("Gagal mengambil konten (HTTP ${resp.code})")
        val body = resp.body?.string() ?: throw Exception("Konten kosong")
        val lines = body.lines()

        // Jina SELALU nutup blok header (Title/URL Source/Published Time, dst — apa pun
        // fieldnya, urutan apa pun) dengan baris penanda "<Format> Content:" sebelum body
        // asli mulai. Cari penanda itu dan ambil semua SETELAHNYA — lebih tahan banting
        // daripada nebak satu-satu field header yang mungkin muncul, karena kalau Jina nambah
        // field baru di masa depan pun penanda ini yang tetap jadi acuan.
        val markerIdx = lines.indexOfFirst { line -> HEADER_MARKERS.any { line.trimStart().startsWith(it) } }
        val cleaned = if (markerIdx >= 0) {
            val markerLine = lines[markerIdx].trimStart()
            val marker = HEADER_MARKERS.first { markerLine.startsWith(it) }
            val sameLineTail = markerLine.removePrefix(marker).trim()
            (listOfNotNull(sameLineTail.ifEmpty { null }) + lines.drop(markerIdx + 1)).joinToString("\n").trim()
        } else {
            // Fallback kalau suatu saat penanda di atas gak ketemu (format berubah total) —
            // heuristik lama, tapi udah dibenerin biar match header asli ("URL Source:").
            lines.dropWhile {
                it.startsWith("Title:") || it.startsWith("URL Source:") || it.startsWith("Published") || it.startsWith("Description:") || it.isBlank()
            }.joinToString("\n").trim()
        }
        if (cleaned.length < 50) throw Exception("Konten terlalu pendek — pastikan URL bisa diakses publik.")
        cleaned
    }
}
