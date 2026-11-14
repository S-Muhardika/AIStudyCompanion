package com.ruangkerja.companion.auth

// ⚠️ CATATAN JUJUR SEBELUM PAKAI FILE INI ⚠️
// Riwayat perbaikan file ini (biar jelas bagian mana yang udah teruji beneran vs baru):
// 1. Alur Credential Manager (androidx.credentials/googleid) — dari dokumentasi resmi Android,
//    SUDAH JALAN dikonfirmasi via testing beneran.
// 2. `provider = Google` (bukan string) — SUDAH JALAN, dikonfirmasi lewat compile+testing.
// 3. TYPE_NO_CREDENTIAL → ganti GetGoogleIdOption jadi GetSignInWithGoogleOption — SUDAH JALAN,
//    dikonfirmasi testing beneran (abis SHA-1 debug didaftarin ke Google Cloud Console).
// 4. "Account reauth failed" → daftarin SHA-1 debug sebagai OAuth client tipe Android di Google
//    Cloud Console (terpisah dari yang tipe Web) — SUDAH JALAN, dikonfirmasi testing beneran.
// 5. "Nonces mismatch" → nyalain "Skip nonce checks" di Supabase Dashboard — SUDAH JALAN.
// 6. Bug auto-logout tiap app ditutup → currentUser sekarang diturunkan dari
//    client.auth.sessionStatus (collect terus), bukan dicek sekali via currentUserOrNull() —
//    ini BARU, belum sempat dites ulang di device beneran. Kalau masih ke-logout juga abis ini,
//    kemungkinan penyebabnya soal PERSISTENCE session-nya sendiri (auth-kt butuh storage
//    implementation yang jalan bener di Android — default-nya seharusnya udah otomatis pakai
//    SharedPreferences, tapi ini belum diverifikasi eksplisit dari source code).
//
// Nama-nama field/class di bawah (SessionStatus.Authenticated.session.user, dst) dikonfirmasi
// LANGSUNG dari source code resmi supabase-kt, bukan tebakan lagi.

import android.content.Context
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.ruangkerja.companion.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.SecureRandom

/**
 * Login Google (opsional) + sesi Supabase. Dipakai lewat AppViewModel, JANGAN dipanggil
 * langsung dari UI — biar Supabase-kt tetap jadi detail implementasi yang terisolasi di sini,
 * konsisten sama pola AiEngine yang nyembunyiin OkHttp/Gemini/Groq dari ViewModel & UI.
 *
 * Login ini opsional untuk HAMPIR SEMUA fitur — RuangKerja Ringkas tetap 100% jalan tanpa login
 * untuk materi/tugas/kalender (local-first). Jangan gate fitur-fitur itu di belakang status
 * login ini.
 *
 * [UBAH, 27 Okt — keputusan eksplisit William, lihat CHANGELOG] SATU PENGECUALIAN: fitur AI
 * bawaan (jalur gratis lewat ai-proxy Supabase — BUKAN BYOK, lihat GeminiService.kt) SEKARANG
 * WAJIB login. Alasannya BUKAN soal fitur belajar inti vs tidak — ini soal siapa yang nanggung
 * biayanya: AI bawaan dibiayai key pribadi William yang dibagi ke SEMUA pengguna lewat satu
 * endpoint publik, sedangkan materi/tugas/kalender murni data milik user sendiri (nggak ada biaya
 * bersama yang bisa disalahgunakan). Tanpa identitas akun asli, siapa pun yang berhasil
 * nge-decompile APK dan nemu URL+anon key bisa nghabisin jatah AI SEMUA orang tanpa ketauan siapa
 * pelakunya (device_id lama gampang direset install-ulang). Login Supabase Auth ngasih identitas
 * yang DIVERIFIKASI SERVER (bukan tebakan), dipakai ai-proxy buat jaga kuota per akun — lihat
 * planning lengkapnya di percakapan 26-27 Okt. BYOK (API key sendiri di Pengaturan > Lanjutan)
 * TETAP tidak perlu login — itu kuota Google/provider milik user sendiri, bukan milik William.
 */
object SupabaseAuthManager {

    /** Data sesi minimal yang dibutuhin UI — sengaja BUKAN tipe dari SDK Supabase langsung,
     * biar kalau ada perubahan API di SDK-nya, dampaknya cuma di file ini.
     *
     * [BARU, 27 Okt] `accessToken` — JWT sesi Supabase Auth user ini SAAT INI, dipakai
     * GeminiService buat header "Authorization: Bearer <accessToken>" ke ai-proxy (gantiin
     * publishable/anon key yang DULU kadang nyasar ke situ — lihat edgePost() di GeminiService.kt).
     * BUKAN token Google — ini token SUPABASE, diterbitkan Supabase Auth setelah proses login
     * Google di bawah selesai. auth-kt otomatis refresh token ini sebelum kedaluwarsa (bagian dari
     * plugin Auth yang di-install di client di bawah) — karena currentUser diturunkan ULANG tiap
     * sessionStatus berubah (bukan dibaca sekali lalu di-cache), accessToken di sini OTOMATIS ikut
     * ter-refresh juga, nggak perlu kode tambahan buat itu. */
    data class AuthUser(val id: String, val email: String?, val accessToken: String)

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
        }
    }

    // Scope umur-panjang milik object ini sendiri (bukan viewModelScope) — auth-kt perlu terus
    // "didengerin" statusnya SELAMA app hidup, independen dari ViewModel mana pun yang kebetulan
    // lagi collect dia.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // [FIX bug auto-logout] SEBELUMNYA ini MutableStateFlow yang aku update MANUAL (abis
    // sign-in sukses, abis sign-out, abis restoreSession() sekali di awal via
    // currentUserOrNull()). Ternyata auth-kt punya state SessionStatus.Initializing pas app
    // baru buka (dia lagi baca sesi tersimpan dari disk) — currentUserOrNull() yang dipanggil
    // SEKALI di awal itu race, kepanggil SEBELUM proses baca itu kelar, jadi keburu null dan
    // ke-anggep "belum login" padahal sesinya sebenernya ada tersimpan.
    //
    // Sekarang currentUser DITURUNKAN LANGSUNG dari client.auth.sessionStatus (di-collect
    // terus-terusan, bukan dicek sekali) — otomatis nunggu Initializing kelar dulu sebelum
    // nentuin status beneran, nggak ada lagi kemungkinan race semacam itu. Ini juga berarti
    // signInWithGoogle()/signOut() di bawah nggak perlu lagi update state ini secara manual —
    // begitu auth-kt sendiri pindah status, flow ini otomatis ikut kebarui.
    val currentUser: StateFlow<AuthUser?> = client.auth.sessionStatus
        .map { status ->
            (status as? SessionStatus.Authenticated)?.session?.let { session ->
                session.user?.let { AuthUser(id = it.id, email = it.email, accessToken = session.accessToken) }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // Nonce acak buat cegah replay attack — persis fungsi yang dicontohkan dokumentasi resmi
    // Android buat dipasangkan ke GetGoogleIdOption/GetSignInWithGoogleOption.setNonce().
    private fun generateSecureRandomNonce(byteLength: Int = 32): String {
        val randomBytes = ByteArray(byteLength)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(randomBytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    /**
     * Alur: Credential Manager minta ID token Google (native, nggak buka browser) -> token itu
     * dikirim ke Supabase Auth buat mulai sesi. `context` HARUS activity context (bukan
     * application context) karena Credential Manager perlu nampilin bottom sheet UI.
     *
     * CATATAN NONCE: kode di bawah kirim nonce MENTAH yang sama ke Google (setNonce) dan ke
     * Supabase (nonce = nonce) — ini sesuai contoh resmi Android buat Credential Manager. Kalau
     * pas testing muncul error nonce mismatch dari Supabase, itu tandanya auth-kt versi kamu
     * expect format lain (mis. di-hash SHA-256 dulu kayak alur web Supabase) — escape hatch-nya
     * ada di Supabase Dashboard: Authentication > Providers > Google > Skip Nonce Check
     * (matiin HANYA kalau memang macet di sini, jangan default dimatiin dari awal).
     */
    suspend fun signInWithGoogle(context: Context): Result<Unit> {
        return try {
            val nonce = generateSecureRandomNonce()
            // [PERCOBAAN] Ganti dari GetGoogleIdOption (bottom sheet otomatis, auto-filter akun)
            // ke GetSignInWithGoogleOption (tombol eksplisit, TANPA auto-filter) — beda jalur
            // pencarian kredensial di Google Play Services. Error TYPE_NO_CREDENTIAL yang kita
            // alamin kejadian persis di tahap filtering itu, jadi ini kandidat kuat buat behave
            // beda. Kalau ini JUGA gagal dengan error yang sama, baru itu bukti kuat masalahnya
            // di level Play Services/akun, bukan di pilihan API kita.
            val googleIdOption = GetSignInWithGoogleOption.Builder(serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setNonce(nonce)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(request = request, context = context)

            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return Result.failure(Exception("Jenis kredensial dari perangkat tidak dikenali."))
            }
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)

            client.auth.signInWith(IDToken) {
                idToken = googleIdTokenCredential.idToken
                provider = Google
                this.nonce = nonce
            }
            Result.success(Unit)
        } catch (e: GetCredentialException) {
            // [FIX] e.message doang sering cuma nunjukin ringkasan tipe error ("No credentials
            // available"), sementara detail SPESIFIK (kode status asli dari Google Play
            // Services, misal "16: [28433] Cannot find a matching credential") biasanya
            // ketimbun di e.cause (atau cause-nya-cause, bisa berlapis). Susurin seluruh rantai
            // cause-nya biar nggak ada detail yang kelewat.
            val chain = generateSequence(e as Throwable) { it.cause }
                .joinToString(" <- ") { it.message ?: it::class.simpleName ?: "?" }
            Result.failure(Exception("Login gagal (${e.type}): $chain"))
        } catch (e: GoogleIdTokenParsingException) {
            Result.failure(Exception("Gagal membaca token dari Google. Coba update Google Play Services lalu ulangi."))
        } catch (e: Exception) {
            Result.failure(Exception("Login gagal: ${e.message ?: "kesalahan tidak diketahui"}."))
        }
    }

    suspend fun signOut() {
        try {
            client.auth.signOut()
        } catch (_: Exception) {
            // Best-effort — auth-kt tetap transisi sessionStatus ke NotAuthenticated di sisi
            // lokal walau request signOut ke server gagal (mis. lagi offline).
        }
    }
}
