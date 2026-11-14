package com.ruangkerja.companion.notification

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ruangkerja.companion.MainActivity
import com.ruangkerja.companion.data.db.Task
import com.ruangkerja.companion.util.DateUtils
import java.time.Instant

/**
 * Pengingat notifikasi lokal untuk deadline tugas — murni AlarmManager + BroadcastReceiver,
 * TANPA Firebase/push service, konsisten sama filosofi "self-hosted, tanpa dependensi luar".
 * Ini independen dari CalendarService: jalan walau HP nggak punya app kalender yang aktif.
 *
 * Asumsi waktu deadline: jam 09:00 WIB di tanggal deadline (sama kayak asumsi CalendarService),
 * karena Task.deadline cuma nyimpen tanggal (yyyy-MM-dd) tanpa jam.
 */
object NotificationHelper {
    const val CHANNEL_ID = "task_reminders"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TITLE = "task_title"
    const val EXTRA_DESC = "task_desc"
    private const val PREFS = "app_prefs"
    private const val DAILY_REQUEST_CODE = 999001
    const val DAILY_NOTIF_ID = 999002

    // [BARU, Fase 3 ekosistem draft] Channel TERPISAH dari "task_reminders" — ini saran, bukan
    // tenggat waktu, jadi importance-nya sengaja lebih rendah (DEFAULT, bukan HIGH) sesuai spek:
    // "notification Draft bukan notification darurat". Request code & notif ID beda dari yang
    // dipakai reminder harian, biar dua-duanya bisa aktif bareng tanpa saling timpa.
    const val SUGGESTION_CHANNEL_ID = "draft_suggestions"
    const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"
    private const val SUGGESTION_REQUEST_CODE = 999003
    const val SUGGESTION_NOTIF_ID = 999004

    // [DIGABUNG, audit duplikasi 2 Okt] Dulu createChannel & createSuggestionChannel dua salinan identik
    // yang beda cuma id/nama/deskripsi/importance.
    private fun ensureChannel(context: Context, id: String, name: String, importance: Int, desc: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(id) == null) {
                val channel = NotificationChannel(id, name, importance).apply { description = desc }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    fun createChannel(context: Context) = ensureChannel(
        context, CHANNEL_ID, "Pengingat Tugas", NotificationManager.IMPORTANCE_HIGH,
        "Notifikasi pengingat deadline tugas dari Ruang Kerja Ringkas"
    )

    fun createSuggestionChannel(context: Context) = ensureChannel(
        context, SUGGESTION_CHANNEL_ID, "Draft & Workspace Suggestions", NotificationManager.IMPORTANCE_DEFAULT,
        "Saran ketika draft cepat mungkin berkaitan dengan salah satu Ruang Kerja"
    )

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

    /** Bisa jadwalin alarm PERSIS di waktunya atau nggak (kebijakan Android 12+). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun requestCode(taskId: String) = taskId.hashCode()

    private fun pendingIntentFor(context: Context, task: Task): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TITLE, task.title)
            putExtra(EXTRA_DESC, task.description)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(task.id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Jadwalin pengingat. Kalau waktunya udah lewat atau reminder nonaktif, nggak ngapa-ngapain. */
    fun scheduleReminder(context: Context, task: Task) {
        if (!task.reminderEnabled || task.status == 2) return
        val due = DateUtils.deadlineMomentMillis(task.deadline) ?: return
        var triggerAt = due - task.reminderMinutesBefore * 60_000L
        if (triggerAt <= System.currentTimeMillis()) return
        triggerAt = applyQuietHours(context, triggerAt)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntentFor(context, task)
        try {
            if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Kalau "Jam Tenang" aktif dan waktunya jatuh 22:00–06:00, tunda ke jam 06:00 berikutnya. */
    private fun applyQuietHours(context: Context, triggerAt: Long): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("quiet_hours_enabled", false)) return triggerAt
        var zdt = Instant.ofEpochMilli(triggerAt).atZone(DateUtils.userZoneId)
        val hour = zdt.hour
        if (hour in 6 until 22) return triggerAt
        if (hour >= 22) zdt = zdt.plusDays(1)
        zdt = zdt.withHour(6).withMinute(0).withSecond(0).withNano(0)
        return zdt.toInstant().toEpochMilli()
    }

    fun cancelReminder(context: Context, taskId: String) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode(taskId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
        pi.cancel()
    }

    // [DIGABUNG, audit duplikasi 2 Okt] Dulu dailyPendingIntent & suggestionPendingIntent dua salinan yang beda
    // cuma kelas receiver dan request code.
    private fun receiverPendingIntent(context: Context, receiver: Class<*>, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, Intent(context, receiver),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun dailyPendingIntent(context: Context): PendingIntent =
        receiverPendingIntent(context, DailyReminderReceiver::class.java, DAILY_REQUEST_CODE)

    /** Cek tiap jam 20:00 — DailyReminderReceiver yang nentuin perlu notif atau nggak
     *  (baca "last_active_date", cuma notif kalau hari ini belum buka bab apapun). */
    fun scheduleDailyCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = DateUtils.nextOccurrenceMillis(20, 0)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_DAY, dailyPendingIntent(context))
    }

    fun cancelDailyCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(dailyPendingIntent(context))
    }

    private fun suggestionPendingIntent(context: Context): PendingIntent =
        receiverPendingIntent(context, DraftSuggestionCheckReceiver::class.java, SUGGESTION_REQUEST_CODE)

    /** Cek tiap ±jam 19:00 (beda sejam dari reminder harian jam 20:00, biar nggak nongol
     *  bareng) — DraftSuggestionCheckReceiver yang nentuin perlu notif atau nggak (baca
     *  suggestions.json, cuma notif kalau ada yang statusnya masih PENDING/SEEN/SNOOZED). */
    fun scheduleSuggestionCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = DateUtils.nextOccurrenceMillis(19, 0)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_DAY, suggestionPendingIntent(context))
    }

    fun cancelSuggestionCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(suggestionPendingIntent(context))
    }

    /** Beda dari buildNotification() biasa — tap notifikasi ini buka LANGSUNG ke halaman
     *  Notifikasi (lewat EXTRA_OPEN_NOTIFICATIONS), bukan cuma ke Beranda, karena isinya
     *  memang daftar saran yang perlu ditinjau di sana. Satu notifikasi TERKELOMPOK untuk
     *  semua saran yang belum ditindaklanjuti (bukan satu notifikasi per saran) — sesuai
     *  larangan spam di spek ekosistem draft. */
    internal fun buildSuggestionNotification(context: Context, count: Int): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
        }
        val contentPi = PendingIntent.getActivity(
            context, SUGGESTION_NOTIF_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (count == 1) "1 draft mungkin memiliki Ruang Kerja terkait"
                   else "$count draft mungkin memiliki Ruang Kerja terkait"
        return NotificationCompat.Builder(context, SUGGESTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Ruang Kerja Ringkas")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
    }

    internal fun buildNotification(context: Context, title: String, body: String): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context, title.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
    }
}
