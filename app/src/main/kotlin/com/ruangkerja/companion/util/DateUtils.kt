package com.ruangkerja.companion.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * [DITULIS ULANG, 26 Sep — sesuai dokumen "RKR — Date & Time Standardization v1.0"] Satu
 * sumber kebenaran buat semua urusan tanggal/waktu di app ini, pakai java.time (aman dipakai
 * langsung — minSdk 26 udah native support, gak butuh desugaring). Versi SEBELUMNYA (SimpleDateFormat
 * + "pin ke UTC biar konsisten") DIBUANG karena justru melanggar aturan inti dokumen itu sendiri:
 * poin 9 — "Jangan menggunakan UTC untuk memaksa semua konsep tanggal menjadi UTC. UTC adalah
 * standar untuk TIMESTAMP, bukan untuk semua DATE."
 *
 * PEMETAAN KONSEP ke tipe data app ini (sesuai taksonomi dokumen):
 * - Task.deadline ("yyyy-MM-dd")     → LocalDate murni. TIDAK PERNAH disimpan/dianggap sebagai
 *                                       Instant/UTC — itu justru contoh larangan eksplisit di
 *                                       dokumen ("dueDate = 2026-09-27T00:00:00Z" = SALAH).
 * - Task.createdAt/updatedAt (Long)  → Instant (epoch millis). Field-nya TETAP Long buat sekarang
 *                                       (migrasi tipe data itu sendiri di luar cakupan audit ini —
 *                                       dokumen bilang "kode lama boleh tetap ada sementara migrasi"),
 *                                       tapi logic pemrosesannya di sini murni java.time.
 * - "Jam 09:00 WIB di tanggal deadline" (buat alarm & event kalender) → persis pola ZonedDateTime
 *   yang dicontohkan dokumen: LocalDate + LocalTime + ZoneId → ZonedDateTime → Instant.
 *
 * userZoneId di bawah ini PERSIS konsep "DateTimeConfig → userZoneId → DateUtils" di dokumen,
 * cuma digabung jadi satu file (app ini kecil, satu object cukup) — SATU-SATUNYA tempat
 * "Asia/Jakarta" ditulis. File lain (NotificationHelper, CalendarService, dst.) ambil dari sini,
 * BUKAN nulis ZoneId/TimeZone sendiri.
 */
object DateUtils {
    /** Timezone tunggal buat app ini — belum ada setting timezone per-user, jadi di-hardcode
     * sesuai target pengguna (Indonesia). SATU-SATUNYA tempat nilai ini ditulis. */
    val userZoneId: ZoneId = ZoneId.of("Asia/Jakarta")

    /** Seam buat testing (Clock.fixed(...) di unit test) — default clock sistem beneran.
     * Semua fungsi "current...()" di bawah lewat sini, bukan Instant.now()/System.currentTimeMillis()
     * langsung, sesuai anjuran dokumen bagian CLOCK/TESTING. */
    var clock: Clock = Clock.systemUTC()

    private val ID_LOCALE = Locale("id", "ID")
    private val DISPLAY_SHORT = DateTimeFormatter.ofPattern("d MMM yyyy", ID_LOCALE)   // "25 Sep 2026"
    private val DISPLAY_LONG = DateTimeFormatter.ofPattern("d MMMM yyyy", ID_LOCALE)   // "25 September 2026"

    fun currentInstant(): Instant = Instant.now(clock)
    fun currentZoned(): ZonedDateTime = ZonedDateTime.now(clock.withZone(userZoneId))
    fun currentDate(): LocalDate = currentZoned().toLocalDate()

    // ── LocalDate (deadline) — format penyimpanan SELALU ISO "yyyy-MM-dd" (LocalDate.parse/
    // .toString() bawaan Java sudah persis pola ini, gak perlu DateTimeFormatter kustom) ──

    /** "yyyy-MM-dd" → LocalDate. Null kalau kosong/format salah — TIDAK exception ke pemanggil. */
    fun parseDeadline(deadline: String): LocalDate? =
        if (deadline.isBlank()) null else try { LocalDate.parse(deadline) } catch (_: DateTimeParseException) { null }

    /** Validasi murni format+rentang tanggal (dipakai sebelum kirim ke Google Calendar API). */
    fun isValidDeadline(deadline: String): Boolean = parseDeadline(deadline) != null

    /** LocalDate → "yyyy-MM-dd" (ISO) buat disimpan. */
    fun formatDeadline(date: LocalDate): String = date.toString()

    /** "yyyy-MM-dd" → "25 Sep 2026" (Indonesia) buat ditampilin. String asli dibalikin apa
     * adanya kalau gagal parse (dari pada nampilin error/kosong ke user). */
    fun formatDeadlineDisplay(deadline: String): String = parseDeadline(deadline)?.format(DISPLAY_SHORT) ?: deadline

    // ── Jembatan ke DatePicker Material3 — widget itu SPESIFIK minta/ngasih balik UTC-epoch
    // millis (dokumentasi resminya), ITU KEPUTUSAN WIDGET-NYA, bukan keputusan "deadline itu
    // UTC" dari app ini. Konversi cuma kejadian SESAAT persis di titik interop ini, langsung
    // balik ke LocalDate begitu keluar dari widget — TIDAK ada Instant/UTC yang nempel ke
    // deadline di luar 2 fungsi kecil ini. ──

    /** LocalDate (via string "yyyy-MM-dd") → UTC millis buat initialSelectedDateMillis DatePicker. */
    fun deadlineToUtcMillis(deadline: String): Long? =
        parseDeadline(deadline)?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli()

    /** UTC millis dari DatePicker.selectedDateMillis → "yyyy-MM-dd" buat disimpan. */
    fun utcMillisToDeadline(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()

    // ── Deadline → momen nyata WIB (buat alarm pengingat & mulai event kalender). Asumsi jam
    // 09:00 WIB di tanggal deadline ini udah ada dari awal (CalendarService/NotificationHelper),
    // di sini cuma disatuin. Persis pola dokumen: LocalDate + LocalTime + ZoneId → ZonedDateTime
    // → Instant. ──

    /** "yyyy-MM-dd" → ZonedDateTime jam [hour]:[minute] WIB di tanggal itu. Null kalau
     * kosong/format salah. Dipakai GoogleCalendarService buat format RFC3339 langsung. */
    fun deadlineZoned(deadline: String, hour: Int = 9, minute: Int = 0): ZonedDateTime? =
        parseDeadline(deadline)?.atTime(hour, minute)?.atZone(userZoneId)

    /** Sama kayak deadlineZoned tapi langsung epoch millis — buat AlarmManager/ContentValues
     * on-device Calendar Provider yang emang minta Long, bukan ZonedDateTime. */
    fun deadlineMomentMillis(deadline: String, hour: Int = 9, minute: Int = 0): Long? =
        deadlineZoned(deadline, hour, minute)?.toInstant()?.toEpochMilli()

    /** Apakah "sekarang" (real time, lewat clock di atas) udah lewat jam 09:00 WIB di tanggal
     * deadline. */
    fun isDeadlineOverdue(deadline: String): Boolean {
        val due = deadlineZoned(deadline)?.toInstant() ?: return false
        return due.isBefore(currentInstant())
    }

    /** "Hari ini" dalam kalender WIB, "yyyy-MM-dd" — dipakai nandain "udah belajar hari ini"
     * (AppViewModel.markStudiedToday ↔ DailyReminderReceiver baca nilai yang sama). */
    fun todayDateString(): String = currentDate().toString()

    /** Instant epoch millis kejadian BERIKUTNYA jam [hour]:[minute] WIB — hari ini kalau belum
     * lewat, besok kalau udah. Dipakai NotificationHelper buat jadwal alarm harian berulang
     * (pengingat 20:00, cek saran draft 19:00) — gantiin java.util.Calendar. */
    fun nextOccurrenceMillis(hour: Int, minute: Int): Long {
        var target = currentZoned().withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.toInstant().isAfter(currentInstant())) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    // ── Timestamp absolut (createdAt/updatedAt: Long millis, "kapan saya ngelakuin X") — timezone
    // DEVICE (bukan userZoneId/WIB), sengaja beda dari deadline: timestamp kejadian wajarnya
    // ditampilin di zona waktu si pengguna lagi liat, bukan dipaksa WIB. ──

    /** Millis → "25 Sep 2026" (Indonesia, timezone device). */
    fun formatDisplayDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DISPLAY_SHORT)

    /** Millis → "25 September 2026" (nama bulan lengkap) — buat header dokumen/laporan formal
     * (ReportPdfBuilder), beda konteks dari list UI biasa yang pakai bentuk singkat. */
    fun formatDisplayDateLong(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DISPLAY_LONG)

    // ── Waktu relatif ──

    /** Millis → "barusan" / "X menit lalu" / "X jam lalu" / "X hari lalu". Dipindah dari
     * relativeDraftTime (WorkspaceListScreen.kt). SettingsScreen.formatLastSynced() SENGAJA
     * TIDAK dialihin ke sini — kata-katanya beda ("Disinkron X lalu" + kasus null), bukan
     * cuma pembulatan menit yang sama; disatuin paksa berisiko ubah teks yang keliatan user
     * tanpa manfaat nyata. */
    fun relativeTime(millis: Long): String {
        val diffMin = (currentInstant().toEpochMilli() - millis) / 60000
        return when {
            diffMin < 1 -> "barusan"
            diffMin < 60 -> "$diffMin menit lalu"
            diffMin < 1440 -> "${diffMin / 60} jam lalu"
            else -> "${diffMin / 1440} hari lalu"
        }
    }
}
