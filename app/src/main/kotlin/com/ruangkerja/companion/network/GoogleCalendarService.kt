package com.ruangkerja.companion.network

// Reminder Google Calendar (lihat reminders.overrides di GCalEventReq) relatif LEBIH bisa
// diandalkan daripada CalendarService (infra Google, bukan tergantung app Kalender/kebijakan
// baterai OEM) — tapi tetap bukan jaminan dari RKR sendiri. Hubungan lengkap CalendarService vs
// GoogleCalendarService vs NotificationHelper: lihat komentar di atas AppViewModel.addTask().

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.ruangkerja.companion.util.DateUtils
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Kalender GOOGLE user sendiri (via REST Calendar API v3) — beda dari CalendarService.kt yang
 * nulis ke Calendar Provider ON-DEVICE (bisa kalender siapa aja yang kebetulan "primary" di HP).
 * Butuh access token dengan scope https://www.googleapis.com/auth/calendar.events, didapat dari
 * GoogleCalendarAuth (auth/GoogleCalendarAuth.kt) — file ini SENGAJA gak tau apa-apa soal
 * gimana token itu didapat, cuma modal token siap pakai, biar konsisten sama pola AiEngine/
 * GeminiService yang misahin "cara dapet kredensial" dari "cara manggil API"-nya.
 *
 * ⚠️ Belum pernah diuji di device beneran (gak ada compiler di sandbox ini) — bentuk request/
 * response di bawah based on dokumentasi resmi Calendar API v3, tapi kalau ada field yang
 * ternyata beda pas dites, paling gampang dicek dari body error yang dikembaliin (masuk ke
 * pesan Exception di bawah, bukan ketelen diam-diam).
 */
object GoogleCalendarService {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private const val BASE = "https://www.googleapis.com/calendar/v3/calendars/primary/events"

    @Serializable private data class GCalDateTime(val dateTime: String, val timeZone: String)
    @Serializable private data class GCalReminderOverride(val method: String, val minutes: Int)
    @Serializable private data class GCalReminders(val useDefault: Boolean, val overrides: List<GCalReminderOverride>)
    @Serializable private data class GCalEventReq(
        val summary: String,
        val description: String,
        val start: GCalDateTime,
        val end: GCalDateTime,
        val reminders: GCalReminders
    )
    @Serializable private data class GCalEventResp(val id: String)
    @Serializable private data class GCalErrorBody(val error: GCalErrorDetail? = null)
    @Serializable private data class GCalErrorDetail(val message: String? = null)

    private fun readableError(code: Int, body: String?): String {
        val serverMsg = body?.let { try { json.decodeFromString<GCalErrorBody>(it).error?.message } catch (_: Exception) { null } }
        return when (code) {
            401 -> "Sesi Google Calendar sudah kedaluwarsa, coba sinkron ulang."
            403 -> "Akun Google ini belum kasih izin akses Calendar (atau Calendar API belum aktif di Google Cloud Console)."
            else -> "HTTP $code" + if (serverMsg != null) ": $serverMsg" else ""
        }
    }

    /** deadlineDate format "yyyy-MM-dd" — sama persis kontrak yang dipakai CalendarService.kt.
     * Event dibikin jam 09.00–10.00 WIB, pengingat 1 hari & 1 jam sebelumnya — niru persis
     * konvensi CalendarService.addTask() biar perilakunya konsisten dari sisi user, terlepas
     * dari jalur mana yang kepake (device atau akun Google). */
    suspend fun addTask(accessToken: String, title: String, description: String = "", deadlineDate: String): String = withContext(Dispatchers.IO) {
        val start = DateUtils.deadlineZoned(deadlineDate, 9, 0) ?: throw Exception("Format tanggal tidak valid.")
        val end = DateUtils.deadlineZoned(deadlineDate, 10, 0)!!

        val req = GCalEventReq(
            summary = title,
            description = description.ifBlank { "Tugas dari Ruang Kerja Ringkas" },
            start = GCalDateTime(start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "Asia/Jakarta"),
            end = GCalDateTime(end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "Asia/Jakarta"),
            reminders = GCalReminders(useDefault = false, overrides = listOf(
                GCalReminderOverride("popup", 1440),
                GCalReminderOverride("popup", 60)
            ))
        )
        val body = json.encodeToString(req).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(BASE).addHeader("Authorization", "Bearer $accessToken").post(body).build()
        try {
            client.newCall(request).execute().use { r ->
                val text = r.body?.string()
                if (!r.isSuccessful) throw Exception(readableError(r.code, text))
                val parsed = try { json.decodeFromString<GCalEventResp>(text ?: "") } catch (e: Exception) {
                    throw Exception("Google Calendar merespons OK tapi format datanya tidak dikenali.")
                }
                parsed.id
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            throw Exception("Gagal terhubung ke Google Calendar: ${e.message ?: "periksa koneksi internet"}.")
        }
    }

    /** 404/410 dianggap sukses (event-nya emang udah gak ada) — sama semangatnya kayak
     * CalendarService.deleteTask yang juga fire-and-forget dari sisi pemanggil. */
    suspend fun deleteTask(accessToken: String, eventId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE/$eventId").addHeader("Authorization", "Bearer $accessToken").delete().build()
        try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful && r.code != 404 && r.code != 410) throw Exception(readableError(r.code, r.body?.string()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            throw Exception("Gagal terhubung ke Google Calendar: ${e.message ?: "periksa koneksi internet"}.")
        }
    }
}
