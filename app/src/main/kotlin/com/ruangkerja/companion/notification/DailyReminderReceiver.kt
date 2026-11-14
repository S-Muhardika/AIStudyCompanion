package com.ruangkerja.companion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.ruangkerja.companion.util.DateUtils

/**
 * Dipanggil AlarmManager tiap ±jam 20:00 (lewat NotificationHelper.scheduleDailyCheck).
 * Cuma nampilin notifikasi kalau "Pengingat Harian" aktif DAN user belum buka bab
 * apapun hari ini (dicek dari "last_active_date" yang di-update AppViewModel.selectChapter).
 * Kalau user udah belajar hari ini, receiver ini diam aja — nggak spam.
 */
class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("daily_reminder_enabled", false)) return
        if (!NotificationHelper.hasNotificationPermission(context)) return

        val today = DateUtils.todayDateString()
        if (prefs.getString("last_active_date", null) == today) return // udah belajar hari ini

        NotificationHelper.createChannel(context)
        val notif = NotificationHelper.buildNotification(
            context, "Belum belajar hari ini",
            "Sempatkan membuka salah satu bab, walau hanya sebentar."
        )
        NotificationManagerCompat.from(context).notify(NotificationHelper.DAILY_NOTIF_ID, notif)
    }
}
