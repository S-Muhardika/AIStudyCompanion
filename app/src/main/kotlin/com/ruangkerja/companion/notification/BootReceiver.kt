package com.ruangkerja.companion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.ruangkerja.companion.data.db.Task
import java.io.File

/**
 * AlarmManager di-clear tiap reboot HP — receiver ini nyalain ulang semua pengingat
 * tugas yang masih aktif & belum selesai begitu HP nyala lagi. Baca tasks.json
 * langsung (bukan lewat LocalStorage/ViewModel) biar cepat dan nggak gantung ke
 * lifecycle Activity yang belum tentu ada saat boot.
 */
class BootReceiver : BroadcastReceiver() {
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val file = File(appContext.filesDir, "tasks.json")
                if (file.exists()) {
                    val json = Json { ignoreUnknownKeys = true }
                    val tasks: List<Task> = json.decodeFromString(file.readText())
                    tasks.filter { it.reminderEnabled && it.status != 2 }
                        .forEach { NotificationHelper.scheduleReminder(appContext, it) }
                }
                val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("daily_reminder_enabled", false)) {
                    NotificationHelper.scheduleDailyCheck(appContext)
                }
                // [FIX, 16 Okt — temuan audit notifikasi] Re-arm scheduleSuggestionCheck KELEWAT
                // di sini dari awal — pola persis sama kayak daily_reminder_enabled di atas
                // ("pola sama persis" malah udah ditulis sendiri di komentar AppViewModel.
                // setDraftSuggestionNotifEnabled), tapi boot receiver ini cuma nyalain ulang yang
                // daily doang. Akibatnya: kalau toggle "Saran Draft" aktif, notifikasi Android buat
                // saran draft→workspace berhenti PERMANEN abis HP reboot (semua alarm AlarmManager
                // kehapus pas reboot, termasuk yang setInexactRepeating kayak punya fitur ini) —
                // gak nongol lagi sampai togglenya dimatiin-nyalain manual. Sekarang disamain.
                if (prefs.getBoolean("draft_suggestion_notif_enabled", false)) {
                    NotificationHelper.scheduleSuggestionCheck(appContext)
                }
            } catch (_: Exception) {
                // Gagal baca/parsing tasks.json — nggak fatal, cuma reminder nggak ke-reschedule.
            } finally {
                pending.finish()
            }
        }
    }
}
