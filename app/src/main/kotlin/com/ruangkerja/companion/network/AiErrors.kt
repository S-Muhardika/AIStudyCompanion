package com.ruangkerja.companion.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Lapisan mana yang gagal saat memanggil AI. Dulu hampir semua kegagalan berujung pesan generik
 * ("AI tidak merespons") atau exception mentah dari parser JSON, sehingga mustahil membedakan
 * "koneksi internet bermasalah" dari "server/API-nya yang menolak". Sekarang setiap kegagalan
 * membawa penyebabnya (lihat [AiException.failure]) dan pesannya langsung menyebut lapisan yang gagal.
 */
enum class AiFailure {
    NETWORK,   // belum ada respons HTTP sama sekali (DNS gagal, timeout, TLS, offline) atau jaringan mengalihkan permintaan
    CONFIG,    // aplikasi sendiri belum punya alamat/kunci yang dibutuhkan
    AUTH,      // kunci/kredensial ditolak (HTTP 401/403)
    // [BARU, 27 Okt] Dulu "belum login" ikut numpang AUTH di atas (sama-sama 401). Sekarang
    // dipisah: ini KHUSUS kasus jalur ai-proxy (bukan BYOK) dicoba padahal belum ada sesi
    // Supabase Auth aktif sama sekali — dicek di KLIEN (GeminiService.edgePost) SEBELUM request
    // dikirim, jadi gagalnya cepat & pesannya bisa langsung nyebut solusinya (login / pakai BYOK),
    // beda dari AUTH yang baru ketahuan SETELAH server menolak (kredensial ada tapi ditolak).
    LOGIN_REQUIRED,
    MODEL,     // model tidak ditemukan / sudah dihentikan (HTTP 404)
    QUOTA,     // rate limit / kuota habis (HTTP 429) — termasuk kuota harian akun/device/provider yang RKR tetapkan sendiri
    SERVER,    // penyedia AI atau function proxy membalas error (4xx lain / 5xx / pesan error dari function)
    RESPONSE   // server membalas sukses, tapi isinya tidak bisa dipakai
}

/**
 * Exception AI yang membawa [failure]. Turunan Exception biasa, jadi `catch (e: Exception)` di
 * ViewModel tetap jalan dan `e.message` tetap berupa pesan siap-tampil.
 */
class AiException(val failure: AiFailure, message: String) : Exception(message)

internal object AiErrors {

    /** Kegagalan level jaringan (belum ada respons HTTP). [target] = nama yang dilihat pengguna, mis. "Groq" atau "AI". */
    fun network(e: IOException, target: String): AiException = AiException(
        AiFailure.NETWORK,
        when (e) {
            is UnknownHostException -> "Server $target tidak ditemukan. Periksa koneksi internet atau pengaturan DNS Anda."
            is SocketTimeoutException -> "Server $target tidak merespons tepat waktu. Periksa sinyal internet, lalu coba lagi."
            is SSLException -> "Koneksi aman ke server $target gagal. Periksa tanggal dan jam perangkat, atau coba jaringan lain."
            is ConnectException -> "Tidak dapat tersambung ke server $target. Periksa koneksi internet Anda."
            else -> "Tidak dapat terhubung ke server $target. Periksa koneksi internet Anda."
        }
    )

    /** Status HTTP non-sukses dari [target] yang tidak punya penanganan khusus. */
    fun http(code: Int, target: String, detail: String? = null): AiException {
        val d = detail?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
        val tag = if (d != null) "HTTP $code: $d" else "HTTP $code"
        return when {
            code == 401 || code == 403 -> AiException(AiFailure.AUTH, "Akses ke $target ditolak ($tag).")
            code == 404 -> AiException(AiFailure.MODEL, "Layanan atau model $target tidak ditemukan ($tag).")
            code == 429 -> AiException(AiFailure.QUOTA, "Kuota $target sedang habis atau dibatasi ($tag). Coba lagi dalam beberapa menit.")
            code in 500..599 -> AiException(AiFailure.SERVER, "Server $target sedang mengalami gangguan ($tag).")
            else -> AiException(AiFailure.SERVER, "$target menolak permintaan ($tag).")
        }
    }
}
