package com.ruangkerja.companion.data.sync

import com.ruangkerja.companion.auth.SupabaseAuthManager
import com.ruangkerja.companion.data.AppRepository
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Orkestrasi sinkronisasi cloud: push perubahan lokal -> Supabase, lalu pull perubahan dari
 * Supabase -> lokal. Dipanggil dari AppViewModel.syncNow(), BUKAN langsung dari UI — pola sama
 * persis kayak SupabaseAuthManager (isolasi detail SDK di satu tempat).
 *
 * ⚠️ CATATAN JUJUR: kode di file ini dirancang & ditelusuri hati-hati (termasuk verifikasi
 * sintaks Postgrest-kt v3.x lewat dokumentasi resmi), TAPI belum pernah dites lawan project
 * Supabase beneran — nggak ada akses jaringan/Android SDK buat itu di lingkungan pembuatan kode
 * ini. Kemungkinan titik gesekan paling besar kalau ada error compile: import
 * `io.github.jan.supabase.postgrest.*` yang exact path-nya bisa beda dikit tergantung versi
 * supabase-kt yang ke-pin di build.gradle (nggak ketauan dari zip project, gak ada file gradle-
 * nya). Kalau AndroidIDE nunjukin "unresolved reference" di salah satu baris impor, itu tandanya
 * cuma perlu disesuaikan sama saran auto-complete-nya, BUKAN berarti logikanya salah.
 *
 * URUTAN push MENGIKUTI dependensi foreign key di supabase/schema.sql: workspace duluan, baru
 * chapter (butuh workspace-nya udah ada di server), baru material & flashcard_set (butuh
 * chapter-nya udah ada). Task & draft nggak gantung ke keduanya, aman ditaruh kapan aja. Urutan
 * pull ikut pola yang sama biar konsisten, walau LocalStorage sendiri nggak menegakkan foreign
 * key (jadi urutan pull sebenarnya nggak wajib, cuma buat rapi).
 */
class SyncManager(private val repo: AppRepository, private val syncMeta: SyncMetaStore) {

    sealed class SyncResult {
        data class Success(val syncedAt: Long) : SyncResult()
        data class Failure(val message: String) : SyncResult()
        // [BARU] Akun yang lagi login BEDA dari akun yang terakhir disinkron di HP ini —
        // syncNow() TIDAK jalanin push/pull APA PUN kalau ini kejadian (lihat pengecekan di
        // awal syncNow() di bawah). previousEmail cuma buat ditampilin ke user di UI.
        data class AccountMismatch(val previousEmail: String?) : SyncResult()
    }

    private val client get() = SupabaseAuthManager.client

    suspend fun syncNow(): SyncResult = withContext(Dispatchers.IO) {
        val user = SupabaseAuthManager.currentUser.value
            ?: return@withContext SyncResult.Failure("Belum login — sinkronisasi butuh akun Google.")
        val userId = user.id
        try {
            // [FIX startup-optimasi] repo.isReady() sekarang cuma nunggu data "inti" (biar
            // Beranda cepet muncul, lihat LocalStorage.kt) — sync butuh LEBIH dari itu (perlu
            // allFlashcardSetsOnce() dkk juga udah kebaca), makanya awaitFullyReady() di sini,
            // bukan isReady() biasa.
            repo.awaitFullyReady()

            val meta = syncMeta.snapshot()
            // [BARU] Perbandingan MURNI LOKAL, TANPA jaringan — kalau akun yang login sekarang
            // beda dari yang terakhir disinkron di HP ini, BERHENTI DI SINI, jangan push/pull
            // apa pun. Lihat SyncModels.kt (SyncMeta.syncedAccountId) & switchAccountAndPullFresh
            // di bawah buat alur resolusinya.
            if (meta.syncedAccountId != null && meta.syncedAccountId != userId) {
                return@withContext SyncResult.AccountMismatch(meta.syncedAccountEmail)
            }

            val watermark = meta.lastSyncedAt
            // [FIX] Jalan tiap sync, sebelum push mulai — murah (cuma filter in-memory kalau
            // gak ada yang perlu dibenerin) dan aman diulang. Nangkep task lama yang
            // workspaceId-nya udah dangling dari SEBELUM deleteWorkspace() punya fix unlink-nya
            // (lihat AppRepository.unlinkDanglingTaskWorkspaceRefs) — kalau dibiarkan, push
            // task ini bakal nabrak foreign key tasks_workspace_id_fkey persis kayak kasus
            // flashcard yatim kemarin.
            repo.unlinkDanglingTaskWorkspaceRefs()

            // Dicatat SEBELUM push/pull mulai (bukan sesudah) — standar buat watermark
            // sinkronisasi inkremental: kalau ada perubahan lokal LAIN yang somehow nyempil PAS
            // sync ini lagi jalan, dia bakal punya updatedAt >= syncStartedAt, jadi tetep kena
            // included di sync BERIKUTNYA (watermark berikutnya = syncStartedAt ini), bukan
            // ketinggalan.
            val syncStartedAt = System.currentTimeMillis()

            pushWorkspaces(userId, watermark)
            pushChapters(userId, watermark)
            pushMaterials(userId, watermark)
            pushFlashcardSets(userId, watermark, meta)
            pushTasks(userId, watermark)
            pushDrafts(userId, watermark)
            pushTombstones(userId, meta.tombstones)

            pullWorkspaces(watermark)
            pullChapters(watermark)
            pullMaterials(watermark)
            pullFlashcardSets(watermark)
            pullTasks(watermark)
            pullDrafts(watermark)

            // Watermark & tombstone CUMA di-advance/dikosongkan kalau SEMUA langkah di atas
            // sukses (nggak ada exception) — kalau ada yang gagal di tengah jalan, syncNow()
            // berikutnya otomatis mengulang dari watermark LAMA yang sama, aman di-retry karena
            // upsert & tombstone-push sama-sama idempotent (push ulang data yang identik = no-op
            // efektif, ditolak trigger LWW server kalau timestamp-nya sama).
            syncMeta.update {
                it.copy(
                    lastSyncedAt = syncStartedAt, tombstones = emptyList(),
                    syncedAccountId = userId, syncedAccountEmail = user.email
                )
            }
            SyncResult.Success(syncStartedAt)
        } catch (e: Exception) {
            SyncResult.Failure(e.message ?: "Sinkronisasi gagal karena kesalahan tidak diketahui.")
        }
    }

    /**
     * [BARU] Resolusi buat kasus AccountMismatch — dipanggil user secara EKSPLISIT lewat tombol
     * "Pindah ke Akun Ini" di Settings (SETELAH konfirmasi dialog, TIDAK pernah otomatis).
     *
     * Urutan SENGAJA begini (jangan diubah urutannya):
     *  1. Backup dulu SEMUA data lokal ke file JSON internal (writeLocalBackup) — kalau langkah
     *     ini gagal (exception), berhenti DI SINI, jangan lanjut ke langkah manapun berikutnya.
     *     Data lokal lama masih 100% utuh kalau sampai sini gagal.
     *  2. BARU bersihin semua data lokal (wipeAllLocalDataForAccountSwitch) + reset TOTAL
     *     bookkeeping sync ke SyncMeta() kosong (termasuk syncedAccountId balik ke null).
     *  3. Jalanin syncNow() BIASA — sekarang aman: syncedAccountId null berarti checkAccount di
     *     atas otomatis lolos (dianggap "belum pernah sync, akun manapun boleh"), watermark 0
     *     + lokal kosong berarti PUSH nggak ngirim apa-apa (nggak ada yang "dirty") dan PULL
     *     narik SELURUH histori akun baru dari awal.
     *
     * RISIKO YANG SADAR DIAMBIL: kalau jaringan putus PAS ANTARA langkah 2 dan 3 selesai, lokal
     * bakal keliatan kosong sementara sampai user coba lagi (tinggal tap "Sinkron" ulang — aman
     * di-retry, lihat poin 3). Backup dari langkah 1 tetap ada di penyimpanan internal aplikasi
     * sebagai jaring pengaman kalau ternyata ada yang kelihatan janggal.
     */
    suspend fun switchAccountAndPullFresh(filesDir: java.io.File): SyncResult = withContext(Dispatchers.IO) {
        val user = SupabaseAuthManager.currentUser.value
            ?: return@withContext SyncResult.Failure("Belum login.")
        try {
            repo.awaitFullyReady() // sama alasannya kayak di syncNow() - lihat catatan di sana
            val previousEmail = syncMeta.snapshot().syncedAccountEmail
            repo.writeLocalBackup(filesDir, previousEmail) // gagal di sini -> exception -> lompat ke catch, TIDAK wipe apa pun
            repo.wipeAllLocalDataForAccountSwitch()
            syncMeta.update { SyncMeta() } // reset total: watermark 0, tombstone kosong, syncedAccountId null
            syncNow()
        } catch (e: Exception) {
            SyncResult.Failure("Gagal pindah akun: ${e.message ?: "kesalahan tidak diketahui"}. Data lokal TIDAK jadi dihapus.")
        }
    }

    // ═══════════════════════════════ PUSH ═══════════════════════════════
    // Pola sama di semua fungsi push (kecuali flashcard, lihat catatan di sana): ambil semua
    // baris lokal, saring yang updatedAt > watermark (berarti "berubah sejak sync terakhir"),
    // upsert ke Supabase. Kalau kosong, skip request sama sekali (hemat 1 round-trip).

    private suspend fun pushWorkspaces(userId: String, watermark: Long) {
        val dirty = repo.allWorkspacesOnce().filter { it.updatedAt > watermark }
        if (dirty.isNotEmpty()) client.from("workspaces").upsert(dirty.map { it.toRemote(userId) })
    }

    private suspend fun pushChapters(userId: String, watermark: Long) {
        val dirty = repo.allChaptersOnce().filter { it.updatedAt > watermark }
        if (dirty.isNotEmpty()) client.from("chapters").upsert(dirty.map { it.toRemote(userId) })
    }

    private suspend fun pushMaterials(userId: String, watermark: Long) {
        val dirty = repo.allMaterialsOnce().filter { it.updatedAt > watermark }
        if (dirty.isNotEmpty()) client.from("materials").upsert(dirty.map { it.toRemote(userId) })
    }

    private suspend fun pushTasks(userId: String, watermark: Long) {
        val dirty = repo.allTasksOnce().filter { it.updatedAt > watermark }
        if (dirty.isNotEmpty()) client.from("tasks").upsert(dirty.map { it.toRemote(userId) })
    }

    private suspend fun pushDrafts(userId: String, watermark: Long) {
        val dirty = repo.drafts().first().filter { it.updatedAt > watermark }
        if (dirty.isNotEmpty()) client.from("drafts").upsert(dirty.map { it.toRemote(userId) })
    }

    // Beda pola dari yang lain: FlashcardData/LocalStorage nggak punya field updatedAt sama
    // sekali, jadi "kapan terakhir berubah" dilacak MANUAL lewat SyncMeta.flashcardSetUpdatedAt
    // (lihat AppRepository.saveFlashcards). Chapter yang punya flashcard tapi BELUM PERNAH
    // tercatat di situ (data lama dari SEBELUM fitur sync ini ada) tetap ikut di-push pas
    // sinkronisasi PERTAMA — treated sebagai "pasti perlu di-push", bukan di-skip diam-diam.
    private suspend fun pushFlashcardSets(userId: String, watermark: Long, meta: SyncMeta) {
        val allSets = repo.allFlashcardSetsOnce()
        if (allSets.isEmpty()) return
        // [FIX] chapterId yang udah gak ada lagi di repo.allChaptersOnce() = flashcard "yatim" —
        // LocalStorage.deleteChapter()/deleteWorkspace() (versi SEBELUM diperbaiki) gak nyentuh
        // _flashcardMap sama sekali pas chapter-nya dihapus, jadi entrinya nyangkut nunjuk ke
        // chapter_id yang udah nggak eksis di mana pun. Kalau ini ikut dipush, foreign key
        // flashcard_sets_chapter_id_fkey NOLAK (chapter_id-nya emang beneran nggak ada, di lokal
        // maupun di server) — makanya di-skip di sini, bukan cuma dicek watermark-nya doang.
        val validChapterIds = repo.allChaptersOnce().map { it.id }.toSet()
        val toPush = mutableListOf<RemoteFlashcardSet>()
        for ((chapterId, cards) in allSets) {
            if (chapterId !in validChapterIds) continue // yatim, chapter-nya udah nggak ada -> percuma dipush
            val known = meta.flashcardSetUpdatedAt[chapterId]
            if (known != null && known <= watermark) continue // udah disinkron, gak ada perubahan baru
            val stamp = known ?: System.currentTimeMillis()
            toPush += RemoteFlashcardSet(chapterId = chapterId, userId = userId, cards = cards, updatedAt = stamp)
            if (known == null) syncMeta.touchFlashcardSet(chapterId, stamp) // mulai tracking dari sekarang
        }
        if (toPush.isNotEmpty()) client.from("flashcard_sets").upsert(toPush)
    }

    // UPDATE (bukan upsert) manual per tombstone — baris yang mau di-soft-delete ini kemungkinan
    // BESAR udah ada di server (kecuali dibuat lalu langsung dihapus SEBELUM sempat sync sama
    // sekali, dalam hal ini UPDATE yang gak nemu baris manapun cuma no-op standar REST/SQL,
    // BUKAN error). updated_at DIIKUT-sertakan sama nilai deletedAt-nya (bukan cuma deleted_at
    // doang) — trigger LWW server banding-in updated_at, jadi soft-delete ini WAJIB kebawa
    // updated_at yang fresh juga, atau bisa ke-tolak triggernya kalau baris itu kebetulan udah
    // pernah di-update sebelumnya dengan updated_at yang lebih baru dari deletedAt ini.
    // [FIX, 29 Okt — audit SEDANG #2] Nambahin filter eq("user_id", userId) di sini SEBAGAI
    // LAPISAN TAMBAHAN (defense-in-depth), BUKAN karena RLS dianggap salah — proteksi utama
    // lintas-akun TETAP RLS sisi server (lihat catatan PULL di bawah, dan CATATAN JUJUR di
    // header file ini soal belum pernah dites lawan project Supabase beneran). Ini jaga-jaga
    // murni: kalau suatu saat RLS policy-nya ada yang kurang pas, baris yang kena UPDATE dari
    // sini tetap gak bisa nyasar ke akun lain. Gak ngubah perilaku buat pemakaian normal (baris
    // yang di-update kan emang udah pasti milik userId yang lagi login).
    private suspend fun pushTombstones(userId: String, tombstones: List<Tombstone>) {
        for (t in tombstones) {
            val (table, idColumn) = tableAndIdColumnFor(t.entity) ?: continue
            client.from(table).update({
                set("deleted_at", t.deletedAt)
                set("updated_at", t.deletedAt)
            }) {
                filter {
                    eq(idColumn, t.id)
                    eq("user_id", userId)
                }
            }
        }
    }

    // ═══════════════════════════════ PULL ═══════════════════════════════
    // Pola sama di semua fungsi pull: ambil baris remote dengan updated_at > watermark (RLS
    // server otomatis membatasi ke baris milik user yang login, TANPA perlu filter user_id
    // manual di sini). Baris ber-deleted_at -> hapus BENERAN di lokal (applyRemoteDeleteX).
    // Baris normal -> terapkan ke lokal (applyRemoteUpsertX). SEMUA lewat applyRemoteXxx, BUKAN
    // insertX/updateX/deleteX biasa — lihat catatan panjang soal ini di AppRepository.kt.

    private suspend fun pullWorkspaces(watermark: Long) {
        val remote = client.from("workspaces").select { filter { gt("updated_at", watermark) } }.decodeList<RemoteWorkspace>()
        for (r in remote) {
            if (r.deletedAt != null) repo.applyRemoteDeleteWorkspace(r.id) else repo.applyRemoteUpsertWorkspace(r.toLocal())
        }
    }

    private suspend fun pullChapters(watermark: Long) {
        val remote = client.from("chapters").select { filter { gt("updated_at", watermark) } }.decodeList<RemoteChapter>()
        for (r in remote) {
            if (r.deletedAt != null) repo.applyRemoteDeleteChapter(r.id) else repo.applyRemoteUpsertChapter(r.toLocal())
        }
    }

    private suspend fun pullMaterials(watermark: Long) {
        val remote = client.from("materials").select { filter { gt("updated_at", watermark) } }.decodeList<RemoteMaterial>()
        for (r in remote) {
            if (r.deletedAt != null) repo.applyRemoteDeleteMaterial(r.id) else repo.applyRemoteUpsertMaterial(r.toLocal())
        }
    }

    private suspend fun pullTasks(watermark: Long) {
        val remote = client.from("tasks").select { filter { gt("updated_at", watermark) } }.decodeList<RemoteTask>()
        for (r in remote) {
            if (r.deletedAt != null) repo.applyRemoteDeleteTask(r.id) else repo.applyRemoteUpsertTask(r.toLocal())
        }
    }

    private suspend fun pullDrafts(watermark: Long) {
        val remote = client.from("drafts").select { filter { gt("updated_at", watermark) } }.decodeList<RemoteDraft>()
        for (r in remote) {
            if (r.deletedAt != null) repo.applyRemoteDeleteDraft(r.id) else repo.applyRemoteUpsertDraft(r.toLocal())
        }
    }

    private suspend fun pullFlashcardSets(watermark: Long) {
        val remote = client.from("flashcard_sets").select { filter { gt("updated_at", watermark) } }.decodeList<RemoteFlashcardSet>()
        for (r in remote) {
            // Nggak ada "hapus satu flashcard set" tersendiri di LocalStorage — chapter yang jadi
            // induknya juga bakal ke-pull sebagai deleted lewat pullChapters di atas (device asal
            // yang hapus SUDAH nyatet tombstone buat KEDUANYA, lihat AppRepository.deleteChapter).
            // Di sini cukup kosongkan isinya biar konsisten, bukan ninggalin data basi.
            val cards = if (r.deletedAt != null) emptyList() else r.cards
            repo.applyRemoteUpsertFlashcardSet(r.chapterId, cards, r.updatedAt)
        }
    }

    private fun tableAndIdColumnFor(entity: String): Pair<String, String>? = when (entity) {
        SyncEntity.WORKSPACE -> "workspaces" to "id"
        SyncEntity.CHAPTER -> "chapters" to "id"
        SyncEntity.MATERIAL -> "materials" to "id"
        SyncEntity.TASK -> "tasks" to "id"
        SyncEntity.DRAFT -> "drafts" to "id"
        SyncEntity.FLASHCARD_SET -> "flashcard_sets" to "chapter_id" // PK-nya chapter_id, bukan id
        else -> null
    }
}
