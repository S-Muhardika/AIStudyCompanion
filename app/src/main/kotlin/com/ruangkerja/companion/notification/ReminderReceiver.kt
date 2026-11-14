package com.ruangkerja.companion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(NotificationHelper.EXTRA_TASK_ID) ?: return
        val title  = intent.getStringExtra(NotificationHelper.EXTRA_TITLE) ?: "Tugas"
        val desc   = intent.getStringExtra(NotificationHelper.EXTRA_DESC) ?: ""

        if (!NotificationHelper.hasNotificationPermission(context)) return
        NotificationHelper.createChannel(context)
        val body = desc.ifBlank { "Jangan lupa, tugas ini mau jatuh tempo." }
        val notif = NotificationHelper.buildNotification(context, "Deadline: $title", body)
        NotificationManagerCompat.from(context).notify(taskId.hashCode(), notif)
    }
}
