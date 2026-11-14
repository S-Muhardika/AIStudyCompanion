package com.ruangkerja.companion.auth

// ⚠️ CATATAN JUJUR, sama semangatnya kayak header SupabaseAuthManager.kt: file ini BARU, belum
// pernah dites di device beneran (gak ada compiler di sandbox ini). Bentuk API di bawah
// (AuthorizationRequest.builder(), .setRequestedScopes(), AuthorizationResult.hasResolution()/
// .pendingIntent/.accessToken, getAuthorizationResultFromIntent()) based on dokumentasi resmi
// "Authorization | Google Identity for Android" — SATU hal yang paling mungkin meleset:
// `AuthorizationRequest.builder()` (huruf kecil, gaya statis khas paket
// com.google.android.gms.auth.api.identity.*) VS `AuthorizationRequest.Builder()` (huruf besar,
// gaya constructor kayak GetCredentialRequest.Builder() di SupabaseAuthManager.kt). Kalau
// compile error "unresolved reference: builder", ganti ke .Builder() (baris ini juga).
//
// KENAPA FILE TERPISAH dari SupabaseAuthManager.kt: itu login (Credential Manager, dapet ID
// token doang buat identitas) — SAMA SEKALI GAK OTOMATIS dapet akses API Google apa pun,
// termasuk Calendar. Ini scope AUTHORIZATION terpisah (dapet access token buat manggil Calendar
// API v3), pakai library beda (play-services-auth, BUKAN androidx.credentials/googleid) —
// butuh serverClientId YANG SAMA (BuildConfig.GOOGLE_WEB_CLIENT_ID) di Google Cloud Console,
// tapi dua flow ini independen satu sama lain.
//
// DEPENDENSI BARU yang harus ditambahin manual ke app/build.gradle (file ini gak ke-upload,
// jadi gak bisa diedit langsung dari sini):
//   implementation("com.google.android.gms:play-services-auth:21.2.0")
// Dan di Google Cloud Console (project yang sama tempat GOOGLE_WEB_CLIENT_ID dibuat): aktifkan
// "Google Calendar API" di API Library, lalu pastikan OAuth consent screen-nya nyantumin scope
// https://www.googleapis.com/auth/calendar.events.

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Otorisasi scope Google Calendar (terpisah dari login, lihat catatan header). Dipakai lewat
 * fungsi "ensure..." di layar (TaskScreen.kt) — sama posisinya kayak ensureCalendarPermission/
 * ensureNotificationPermission yang udah ada, cuma yang diminta di sini bukan izin Android tapi
 * izin scope Google. GoogleCalendarService.kt (network) yang benar-benar manggil Calendar API
 * pakai access token hasil dari sini.
 */
object GoogleCalendarAuth {
    // calendar.events: cukup buat bikin/hapus event, TIDAK bisa baca/ubah pengaturan kalender
    // atau kalender lain milik user — sengaja scope paling sempit yang RKR butuh (bukan scope
    // "calendar" penuh), prinsip least-privilege sekalian bikin proses verifikasi Google (kalau
    // suatu saat app-nya dipublikasi ke Play Store) jauh lebih ringan dibanding scope penuh.
    private val CALENDAR_SCOPE = Scope("https://www.googleapis.com/auth/calendar.events")

    sealed class AuthOutcome {
        data class Authorized(val accessToken: String) : AuthOutcome()
        data class NeedsConsent(val intentSenderRequest: IntentSenderRequest) : AuthOutcome()
        data class Failed(val message: String) : AuthOutcome()
    }

    private fun buildRequest() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(CALENDAR_SCOPE))
        .build()

    /**
     * Minta akses scope Calendar. Kalau user udah pernah kasih izin ini sebelumnya, Google Play
     * Services BIASANYA langsung ngasih access token TANPA nampilin dialog apa pun (Authorized
     * langsung). Kalau belum pernah/dicabut, hasilnya NeedsConsent — UI (TaskScreen.kt) HARUS
     * launch intentSenderRequest-nya lewat launcher dari
     * rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()),
     * lalu panggil resultFromIntent() di callback launcher itu buat ambil token final.
     */
    suspend fun authorize(context: Context): AuthOutcome = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(buildRequest())
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    cont.resume(AuthOutcome.NeedsConsent(IntentSenderRequest.Builder(pendingIntent.intentSender).build()))
                } else {
                    val token = result.accessToken
                    cont.resume(if (token != null) AuthOutcome.Authorized(token) else AuthOutcome.Failed("Google tidak mengirim access token."))
                }
            }
            .addOnFailureListener { e -> cont.resume(AuthOutcome.Failed(e.message ?: "Otorisasi Google Calendar gagal.")) }
    }

    /** Dipanggil dari callback launcher (StartIntentSenderForResult), abis user selesai di layar
     * consent Google — `data` adalah ActivityResult.data apa adanya, boleh null kalau user
     * membatalkan (back/tutup dialog). */
    fun resultFromIntent(context: Context, data: Intent?): AuthOutcome {
        if (data == null) return AuthOutcome.Failed("Dibatalkan.")
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (token != null) AuthOutcome.Authorized(token) else AuthOutcome.Failed("Izin Google Calendar ditolak atau tidak lengkap.")
        } catch (e: Exception) {
            AuthOutcome.Failed(e.message ?: "Gagal membaca hasil izin Google.")
        }
    }

    /**
     * Best-effort TANPA UI sama sekali — dipakai pas hapus tugas (delete gak boleh keblokir
     * nunggu dialog consent). Kalau ternyata butuh consent (belum pernah diizinkan/udah dicabut),
     * langsung balikin null, TIDAK nyoba nampilin dialog apa pun.
     */
    suspend fun getAccessTokenSilently(context: Context): String? = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(buildRequest())
            .addOnSuccessListener { result -> cont.resume(if (result.hasResolution()) null else result.accessToken) }
            .addOnFailureListener { cont.resume(null) }
    }
}
