package com.ruangkerja.companion.network

// [BARU, 14-15 Nov — permintaan William: streaming native Diskusi + checkpoint/fallback
// antar-provider, lihat project doc audit-rencana-streaming-checkpoint-14nov2026.md] Model
// Kotlin INTERNAL (bukan DTO mentah) buat event SSE yang dikirim ai-proxy — sengaja dipisah
// dari bentuk JSON mentahnya (lihat parseSseEvent di GeminiService.kt) biar pemanggil (AiEngine,
// AppViewModel) tidak perlu tahu bentuk wire SSE sama sekali, cuma tahu 6 kemungkinan ini.
//
// SCOPE (dikonfirmasi William 14 Nov): HANYA jalur default/shared-key (non-BYOK). Kalau BYOK
// aktif, AiEngine.chatStream membungkus hasil chat() LAMA (satu balasan utuh) jadi Delta+Done
// tunggal lewat tipe yang sama ini — jadi AppViewModel TIDAK PERNAH perlu tahu/cek apakah
// jawaban yang sedang diproses itu streaming asli atau BYOK yang dipalsukan jadi "streaming
// sekali pancar". Lihat AiEngine.kt.
sealed class ChatStreamEvent {
    /** Event "status" — info progres non-teks (mis. "AI sedang menulis..."). Boleh diabaikan
     *  UI kalau belum mau ditampilkan; tidak memengaruhi isi/":status" pesan ChatMsg. */
    data class Status(val phase: String, val message: String?, val provider: String? = null) : ChatStreamEvent()

    /** Event "delta" — potongan teks baru yang HARUS ditambahkan (append), bukan menggantikan,
     *  isi pesan yang sedang STREAMING. */
    data class Delta(val text: String, val provider: String, val model: String) : ChatStreamEvent()

    /** Event "reset" — provider yang sedang dicoba gagal di tengah jalan. `keepText == null`
     *  WAJIB diperlakukan sebagai "restart_from_beginning" APA PUN isi `strategy`, supaya aman
     *  kalau backend lama (belum punya checkpoint, lihat CHANGELOG.ts v46) kebetulan masih aktif
     *  — field `keepText` memang belum ada sama sekali di backend per audit 14 Nov. Saat backend
     *  checkpoint (TAHAP 3) sudah aktif, `keepText != null` berarti teks itu yang harus jadi isi
     *  BARU pesan yang sedang STREAMING (menggantikan akumulasi lama, BUKAN activity ditambah). */
    data class Reset(
        val provider: String,
        val discardedCharacters: Int,
        val reason: String,
        val strategy: String,
        val keepText: String? = null,
    ) : ChatStreamEvent()

    /** Event "done" — provider ini berhasil menyelesaikan jawaban SAMPAI TUNTAS. Hanya event ini
     *  yang boleh membuat ChatMsg berubah status jadi COMPLETED. */
    data class Done(val provider: String, val model: String, val finishReason: String, val outputCharacters: Int) : ChatStreamEvent()

    /** Event "error" — SEMUA provider di rantai fallback sudah gagal (atau kegagalan yang
     *  bukan soal provider, mis. kuota akun/device). Beda dari exception transport biasa
     *  (jaringan putus dst, yang keluar sbg Throwable lewat Flow, bukan lewat tipe ini). */
    data class ServerError(val code: String, val message: String) : ChatStreamEvent()

    /** Event "ping" (heartbeat ~10 detik). Tidak membawa informasi apa pun selain "koneksi
     *  masih hidup" — disediakan di sini kalau-kalau berguna buat UI/log, TIDAK WAJIB dipakai. */
    data object Heartbeat : ChatStreamEvent()
}
