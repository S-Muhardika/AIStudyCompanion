package com.ruangkerja.companion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.ruangkerja.companion.data.db.Draft
import com.ruangkerja.companion.data.db.DraftSuggestion
import com.ruangkerja.companion.util.DRAFT_DORMANT_MS
import kotlinx.serialization.json.Json
import java.io.File

/**
 * [BARU, Fase 3 ekosistem draft] Dipanggil AlarmManager tiap ±jam 19:00 (lewat
 * NotificationHelper.scheduleSuggestionCheck). Baca suggestions.json LANGSUNG dari disk
 * (bukan lewat LocalStorage/AppRepository penuh) — sengaja ringan, sama kayak
 * DailyReminderReceiver yang baca SharedPreferences langsung, biar receiver background ini
 * nggak perlu nyalain seluruh graf data aplikasi cuma buat satu pengecekan singkat.
 *
 * Cuma nampilin notifikasi (SATU, terkelompok) kalau "Saran Draft" aktif DAN ada minimal
 * satu suggestion yang (a) statusnya masih PENDING/SEEN/SNOOZED (belum ditindaklanjuti) DAN
 * (b) draft sumbernya udah "didiemin" >=DRAFT_DORMANT_MS DAN (c) gak lagi di-snooze. Kalau
 * semua sudah ACCEPTED/DISMISSED, masih "anget" (baru disave, belum 7 hari), lagi di-snooze,
 * atau memang belum ada saran sama sekali, diam saja — nggak spam (spek: "RELEVANCE >
 * FREQUENCY").
 *
 * [FIX, 17 Okt — masukan William] Dulu SAMA SEKALI gak peduli umur draft maupun
 * snoozedMatchUntil — notif ini nagih dari HARI PERTAMA sebuah draft match, bahkan buat
 * MEDIUM yang pop-up-nya sendiri gak pernah muncul, dan pencet "Nanti" gak ngaruh apa-apa ke
 * notif ini (cuma ngecek status, bukan draft.snoozedMatchUntil). Sekarang ikut baca
 * drafts.json (masih langsung dari disk, bukan lewat LocalStorage — filosofi ringan di atas
 * tetap dipegang) buat cross-check umur & status snooze tiap draft, pakai aturan SAMA PERSIS
 * kayak AppViewModel.checkDormantMatches() (satu angka ambang, DRAFT_DORMANT_MS, dari
 * util/DraftMatcher.kt — biar dua sisi gak diam-diam beda nilai).
 */
class DraftSuggestionCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("draft_suggestion_notif_enabled", false)) return
        if (!NotificationHelper.hasNotificationPermission(context)) return

        val suggestionsFile = File(context.filesDir, "suggestions.json")
        if (!suggestionsFile.exists()) return
        val suggestions: List<DraftSuggestion> = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(suggestionsFile.readText())
        } catch (_: Exception) { return }
        val unresolved = suggestions.filter { it.status == "SEEN" || it.status == "SNOOZED" || it.status == "PENDING" }
        if (unresolved.isEmpty()) return

        val draftsFile = File(context.filesDir, "drafts.json")
        val drafts: List<Draft> = if (draftsFile.exists()) {
            try { Json { ignoreUnknownKeys = true }.decodeFromString(draftsFile.readText()) } catch (_: Exception) { emptyList() }
        } else emptyList()

        val now = System.currentTimeMillis()
        val eligibleCount = unresolved.count { s ->
            val draft = drafts.firstOrNull { it.id == s.draftId }
            draft != null && now - draft.updatedAt >= DRAFT_DORMANT_MS && now >= draft.snoozedMatchUntil
        }
        if (eligibleCount == 0) return

        NotificationHelper.createSuggestionChannel(context)
        val notif = NotificationHelper.buildSuggestionNotification(context, eligibleCount)
        NotificationManagerCompat.from(context).notify(NotificationHelper.SUGGESTION_NOTIF_ID, notif)
    }
}
