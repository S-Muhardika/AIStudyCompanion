package com.ruangkerja.companion.network

// Reminder bawaan Calendar Provider (lihat addReminder di bawah) itu BEST-EFFORT — app
// Kalender/OS yang beneran nampilin notifnya, bukan kode di sini. File ini buat VISIBILITAS di
// app Kalender HP, bukan jaminan pengingat. Reminder yang RKR beneran bisa jamin ada di
// NotificationHelper (independen total dari file ini). Hubungan lengkap CalendarService vs
// GoogleCalendarService vs NotificationHelper: lihat komentar di atas AppViewModel.addTask().
// JANGAN dihapus — ini fallback offline (login gagal/gak ada/gak reachable turun ke sini).

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.ruangkerja.companion.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CalendarService {
    suspend fun addTask(context: Context, title: String, description: String = "", deadlineDate: String): Long = withContext(Dispatchers.IO) {
        val startMillis = DateUtils.deadlineMomentMillis(deadlineDate, 9, 0) ?: throw Exception("Format tanggal tidak valid.")
        val endMillis = DateUtils.deadlineMomentMillis(deadlineDate, 10, 0)!!
        val calendarId = try {
            getDefaultCalendarId(context)
        } catch (e: SecurityException) {
            throw Exception("Izin akses kalender HP belum diberikan (Pengaturan > Aplikasi > Ruang Kerja Ringkas > Izin > Kalender).")
        } ?: throw Exception("Tidak ada kalender aktif ditemukan di HP ini (cek ada akun kalender yang \"Terlihat\"/visible di app Kalender bawaan HP).")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description.ifBlank { "Tugas dari Ruang Kerja Ringkas" })
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Jakarta")
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: throw Exception("Gagal menyimpan event.")
        val eventId = uri.lastPathSegment?.toLong() ?: throw Exception("ID event tidak ditemukan.")
        addReminder(context, eventId, 1440); addReminder(context, eventId, 60)
        eventId
    }

    suspend fun deleteTask(context: Context, eventId: Long) = withContext(Dispatchers.IO) {
        context.contentResolver.delete(Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString()), null, null)
    }

    private fun getDefaultCalendarId(context: Context): Long? =
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.VISIBLE),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1",
            null,
            // [FIX] Dulu cuma "_ID ASC" — asal ambil kalender ber-ID terkecil yang visible &
            // syncable, BUKAN kalender utama user. Di HP multi-akun/ada kalender langganan
            // (hari libur, dll), tugas bisa nyasar ke kalender yang salah atau bahkan read-only.
            // IS_PRIMARY DESC diprioritaskan dulu; kalau gak ada satupun yang primary (jarang),
            // otomatis jatuh ke _ID ASC seperti sebelumnya — jadi tetap ada fallback yang sama.
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun addReminder(context: Context, eventId: Long, minutesBefore: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
    }
}
