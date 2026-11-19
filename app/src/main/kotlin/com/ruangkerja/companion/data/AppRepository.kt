package com.ruangkerja.companion.data

import com.ruangkerja.companion.data.db.*
import com.ruangkerja.companion.data.sync.SyncEntity
import com.ruangkerja.companion.data.sync.SyncMetaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// ─────────────────────────────────────────────────────────────────────────
// [BARU, V2.0.1] Sinkronisasi cloud nambah SATU prinsip penting di file ini:
//
// insertX/updateX/deleteX (dipanggil UI/ViewModel, "USER yang ubah sesuatu"):
//   - insert/update SELALU nyetempel updatedAt yang FRESH (System.currentTimeMillis()).
//   - delete SELALU nyatet tombstone ke SyncMetaStore, biar SyncManager tau nanti
//     ada yang perlu diberitahu ke server pas sync berikutnya.
//
// applyRemoteXxx (dipanggil SyncManager doang, "SERVER yang ubah sesuatu lewat pull"):
//   - TIDAK nyetempel updatedAt baru — data dari server udah bawa updatedAt-nya
//     sendiri, kalau ditimpa stempel fresh di sini mekanisme Last-Write-Wins bisa
//     korup (baris yang BARU DI-PULL keliatan "baru diubah barusan" padahal
//     cuma dipindahin apa adanya).
//   - TIDAK nyatet tombstone — kalau ikut nyatet, tombstone itu bakal ke-push BALIK
//     ke server tiap sync berikutnya buat sesuatu yang SERVER SENDIRI yang bilang
//     duluan. Nggak fatal (trigger LWW di server tetap nolak/no-op), tapi boros.
//
// Beberapa applyRemoteXxx juga sengaja manggil method LOKAL yang BEDA dari
// pasangan insertX/updateX-nya (mis. applyRemoteUpsertMaterial manggil
// db.updateMaterial, BUKAN db.insertMaterial) — soalnya insertX/updateX di
// LocalStorage.kt TERNYATA nggak seragam semantiknya (sebagian upsert-by-id,
// sebagian append polos, sebagian no-op kalau id belum ada). Tiap keputusan
// dijelasin di komentar masing-masing fungsi di bawah.
// ─────────────────────────────────────────────────────────────────────────

class AppRepository(private val db: LocalStorage, private val syncMeta: SyncMetaStore) {
    fun isReady(): Flow<Boolean> = db.isReady

    // [BARU, optimasi startup] Beda dari isReady() di atas (yang cuma nunggu data "inti" biar
    // Beranda cepet muncul) — ini nunggu SEMUA data lokal kebaca, termasuk flashcard & chunks.
    // Dipakai SyncManager SEBELUM push, karena sync perlu liat data lengkap, bukan cuma yang
    // "inti" (lihat LocalStorage.readyHeavy()/awaitFullyLoaded()).
    suspend fun awaitFullyReady() = db.awaitFullyLoaded()

    // Ekspor — snapshot semua data lintas workspace
    fun allWorkspacesOnce() = db.allWorkspacesOnce()
    fun allChaptersOnce() = db.allChaptersOnce()
    fun allMaterialsOnce() = db.allMaterialsOnce()
    fun allTasksOnce() = db.allTasksOnce()
    fun allFlashcardSetsOnce() = db.allFlashcardSetsOnce()

    // ── Workspace ───────────────────────────────────────────────────────
    fun workspaces(): Flow<List<Workspace>> = db.workspacesFlow()

    suspend fun insertWorkspace(w: Workspace) = db.insertWorkspace(w.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteWorkspace(id: String) {
        // Kumpulin dulu SEMUA yang bakal ke-cascade lokal (chapter, material, flashcard set-nya)
        // SEBELUM db.deleteWorkspace beneran mecahnya — biar semuanya kebagian tombstone
        // masing-masing, bukan cuma workspace-nya doang. ON DELETE CASCADE di schema.sql TIDAK
        // nolong di sini: itu cuma jalan buat hard DELETE beneran, sedangkan kita SELALU
        // soft-delete lewat UPDATE deleted_at (lihat schema.sql poin 2).
        val chapterIds = db.chaptersFlow(id).first().map { it.id }
        val materialIds = chapterIds.flatMap { chId -> db.materialsFlow(chId).first().map { it.id } }
        // [FIX, 29 Okt — audit SEDANG #1, pola sama kayak deleteDraft() di bawah/16 Okt]
        // DraftSuggestion (entri histori Notification Center) yang workspaceId-nya nunjuk ke
        // workspace ini SEBELUMNYA gak ikut dibersihin pas workspace-nya dihapus — entrinya
        // nyangkut nunjuk ke workspace yang udah gak ada selamanya (status PENDING/SEEN/SNOOZED
        // gak pernah ke-resolve dari UI, badge Notification Center nyala terus). DraftSuggestion
        // LOCAL-ONLY (gak disync ke server — lihat deleteSuggestion() di bawah, gak ada
        // recordTombstone di situ), jadi cukup bersihin lokal aja di sini, gak perlu tombstone.
        db.suggestionsFlow().first().filter { it.workspaceId == id }.forEach { db.deleteSuggestion(it.id) }
        db.deleteWorkspace(id)
        syncMeta.recordTombstone(SyncEntity.WORKSPACE, id)
        chapterIds.forEach {
            syncMeta.recordTombstone(SyncEntity.CHAPTER, it)
            syncMeta.recordTombstone(SyncEntity.FLASHCARD_SET, it) // chapter_id JUGA PK flashcard_sets
        }
        materialIds.forEach { syncMeta.recordTombstone(SyncEntity.MATERIAL, it) }
    }

    suspend fun applyRemoteUpsertWorkspace(w: Workspace) = db.insertWorkspace(w) // insertWorkspace lokal udah upsert-by-id
    suspend fun applyRemoteDeleteWorkspace(id: String) = db.deleteWorkspace(id)

    // ── Chapter ─────────────────────────────────────────────────────────
    fun chapters(wsId: String): Flow<List<Chapter>> = db.chaptersFlow(wsId)
    // [BARU, 18 Nov — fitur "Favorit"]
    fun allChaptersFlow(): Flow<List<Chapter>> = db.allChaptersFlow()
    suspend fun chapter(id: String): Chapter? = db.chapter(id)
    suspend fun chapterCount(wsId: String): Int = db.chapterCount(wsId)

    suspend fun insertChapter(c: Chapter) = db.insertChapter(c.copy(updatedAt = System.currentTimeMillis()))
    suspend fun updateChapter(c: Chapter) = db.updateChapter(c.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteChapter(id: String) {
        val materialIds = db.materialsFlow(id).first().map { it.id }
        db.deleteChapter(id)
        syncMeta.recordTombstone(SyncEntity.CHAPTER, id)
        syncMeta.recordTombstone(SyncEntity.FLASHCARD_SET, id)
        materialIds.forEach { syncMeta.recordTombstone(SyncEntity.MATERIAL, it) }
    }

    // insertChapter lokal upsert-by-id (filter-out-lalu-tambah), aman dipakai buat kasus INSERT
    // maupun UPDATE hasil pull — beda sama updateChapter lokal yang cuma .map{} (no-op diam-diam
    // kalau id-nya belum ada sama sekali di list, yang justru kasus paling umum pas nge-pull
    // chapter BARU dari device lain).
    suspend fun applyRemoteUpsertChapter(c: Chapter) = db.insertChapter(c)
    suspend fun applyRemoteDeleteChapter(id: String) = db.deleteChapter(id)

    // ── Material ────────────────────────────────────────────────────────
    fun materials(chId: String): Flow<List<Material>> = db.materialsFlow(chId)
    suspend fun materialsOnce(chId: String): List<Material> = db.materialsOnce(chId)
    // [BARU, 18 Nov — "Materi Terbaru" di Beranda]
    fun allMaterialsFlow(): Flow<List<Material>> = db.allMaterialsFlow()

    suspend fun insertMaterial(m: Material) = db.insertMaterial(m.copy(updatedAt = System.currentTimeMillis()))
    suspend fun updateMaterial(m: Material) = db.updateMaterial(m.copy(updatedAt = System.currentTimeMillis()))
    suspend fun deleteMaterial(id: String) {
        db.deleteMaterial(id)
        syncMeta.recordTombstone(SyncEntity.MATERIAL, id)
    }

    // PENTING: pakai db.updateMaterial (BUKAN db.insertMaterial!) — insertMaterial lokal cuma
    // append POLOS tanpa cek id (lihat LocalStorage.kt), bakal bikin baris DOBEL kalau materialnya
    // udah ada lokal dan ke-pull ulang lagi. updateMaterial lokal yang justru upsert-by-id benar.
    suspend fun applyRemoteUpsertMaterial(m: Material) = db.updateMaterial(m)
    suspend fun applyRemoteDeleteMaterial(id: String) = db.deleteMaterial(id)

    // [BARU] Chunk materi buat RAG (Diskusi grounded) — TIDAK disinkronkan, lihat schema.sql
    // poin 6 (data turunan, bisa di-generate ulang dari Material kapan aja).
    fun chunksForChapter(chId: String): List<MaterialChunk> = db.chunksForChapter(chId)
    suspend fun replaceChunksForMaterial(materialId: String, chunks: List<MaterialChunk>) = db.replaceChunksForMaterial(materialId, chunks)

    // ── Diskusi — histori multi-thread (BARU, 13 Nov) ─────────────────────
    // Sama seperti Chunk di atas: TIDAK disinkronkan (tidak ada syncMeta.touchXxx di sini sama
    // sekali) — murni lokal per device, lihat catatan lengkap di Entities.kt ChatThread/ChatMsg.
    fun chatThreads(chId: String): Flow<List<ChatThread>> = db.chatThreadsFlow(chId)
    suspend fun chatThreadsOnce(chId: String): List<ChatThread> = db.chatThreadsOnce(chId)
    suspend fun insertChatThread(t: ChatThread) = db.insertChatThread(t)
    suspend fun touchChatThread(id: String, updatedAt: Long) = db.touchChatThread(id, updatedAt)
    suspend fun deleteChatThread(id: String) = db.deleteChatThread(id)
    fun chatMessages(threadId: String): Flow<List<ChatMsg>> = db.chatMessagesFlow(threadId)
    suspend fun chatMessagesOnce(threadId: String): List<ChatMsg> = db.chatMessagesOnce(threadId)
    suspend fun insertChatMessage(m: ChatMsg) = db.insertChatMessage(m)
    // [BARU, 14-15 Nov — streaming native Diskusi] lihat catatan lengkap di LocalStorage.updateChatMessage.
    suspend fun updateChatMessage(m: ChatMsg) = db.updateChatMessage(m)

    // ── ScheduleEntry — "Jadwal Hari Ini" (BARU, 16 Nov — redesain Beranda) ───────────────
    // Sama seperti ChatThread di atas: TIDAK disinkronkan (tidak ada syncMeta.recordTombstone
    // di sini sama sekali) — murni lokal per device, lihat catatan lengkap di Entities.kt.
    fun scheduleEntries(): Flow<List<ScheduleEntry>> = db.scheduleEntriesFlow()
    suspend fun insertScheduleEntry(s: ScheduleEntry) = db.insertScheduleEntry(s.copy(updatedAt = System.currentTimeMillis()))
    suspend fun updateScheduleEntry(s: ScheduleEntry) = db.updateScheduleEntry(s.copy(updatedAt = System.currentTimeMillis()))
    suspend fun deleteScheduleEntry(id: String) = db.deleteScheduleEntry(id)

    // ── Task ────────────────────────────────────────────────────────────
    fun tasks(): Flow<List<Task>> = db.tasksFlow()

    suspend fun insertTask(t: Task) = db.insertTask(t.copy(updatedAt = System.currentTimeMillis()))
    suspend fun updateTask(t: Task) = db.updateTask(t.copy(updatedAt = System.currentTimeMillis()))
    suspend fun deleteTask(id: String) {
        db.deleteTask(id)
        syncMeta.recordTombstone(SyncEntity.TASK, id)
    }

    // insertTask MAUPUN updateTask lokal, SENDIRI-SENDIRI, bukan upsert-by-id yang aman (insert
    // = append polos bisa dobel; update = no-op diam-diam kalau id belum ada) — jadi keberadaannya
    // dicek manual di sini dulu. Di sinilah JUGA calendarEventId LOKAL device ini dipertahankan
    // (RemoteTask di SyncModels.kt memang sengaja nggak pernah bawa field ini).
    suspend fun applyRemoteUpsertTask(remote: Task) {
        val existing = db.tasksFlow().first().find { it.id == remote.id }
        val merged = remote.copy(calendarEventId = existing?.calendarEventId)
        if (existing != null) db.updateTask(merged) else db.insertTask(merged)
    }
    suspend fun applyRemoteDeleteTask(id: String) = db.deleteTask(id)

    // [FIX] Perbaikan data lokal buat task yang workspaceId-nya nunjuk ke workspace yang UDAH
    // GAK ADA (dihapus lokal SEBELUM deleteWorkspace() punya fix unlink-nya, ATAU dari sebelum
    // fitur sync ini ada sama sekali) — akar masalah sama persis kayak flashcard yatim, cuma di
    // sini task-nya sendiri TETAP valid, jadi diperbaiki (link basinya di-null-in), BUKAN dibuang.
    // SENGAJA pakai db.updateTask() LANGSUNG (bukan repo.updateTask()) — ini perbaikan data yang
    // udah salah dari awal, BUKAN "user baru saja mengedit sesuatu", jadi updatedAt ASLI-nya
    // dipertahankan apa adanya (gak di-stempel ulang), biar gak keliru menang di perbandingan LWW
    // padahal isinya cuma dibenerin link-nya doang.
    suspend fun unlinkDanglingTaskWorkspaceRefs() {
        val validWorkspaceIds = db.allWorkspacesOnce().map { it.id }.toSet()
        val dangling = db.allTasksOnce().filter { it.workspaceId != null && it.workspaceId !in validWorkspaceIds }
        for (t in dangling) db.updateTask(t.copy(workspaceId = null))
    }

    // ── Draft ───────────────────────────────────────────────────────────
    fun drafts(): Flow<List<Draft>> = db.draftsFlow()

    suspend fun insertDraft(d: Draft) = db.insertDraft(d.copy(updatedAt = System.currentTimeMillis()))
    suspend fun updateDraft(d: Draft) = db.updateDraft(d.copy(updatedAt = System.currentTimeMillis()))
    // [FIX, 16 Okt — temuan audit notifikasi #2] Dulu DraftSuggestion (entri histori Notification
    // Center + pemicu notifikasi harian ~19:00, lihat notification/DraftSuggestionCheckReceiver.kt)
    // gak ikut kehapus pas draft sumbernya kehapus — baik lewat hapus manual (deleteDraft ini) MAUPUN
    // abis "Pindahkan" (AppViewModel.finishMoveDraft, yang manggil db.deleteDraft lewat sini juga).
    // deleteSuggestion() FUNGSINYA UDAH ADA dari awal tapi gak pernah kepanggil dari manapun — kebuat,
    // lupa disambungin. Akibatnya entri basi nyangkut status SEEN/PENDING/SNOOZED SELAMANYA (badge
    // Notification Center & notifikasi harian cuma ngecek status, gak ngecek draft/workspace-nya
    // masih ada atau nggak), dan gak ada cara ngilanginnya dari UI (reopenSuggestion() diam-diam
    // no-op kalau draftId-nya udah gak ketemu). Satu draft bisa punya LEBIH dari satu entri suggestion
    // (kalau pernah cocok ke beberapa workspace beda di waktu beda — lihat suggestionId di
    // AppViewModel, kuncinya draftId+workspaceId) makanya di-filter semua yang draftId-nya cocok,
    // bukan cuma hapus satu id.
    suspend fun deleteDraft(id: String) {
        db.suggestionsFlow().first().filter { it.draftId == id }.forEach { db.deleteSuggestion(it.id) }
        db.deleteDraft(id)
        syncMeta.recordTombstone(SyncEntity.DRAFT, id)
    }

    // Sama kasusnya kayak Task barusan — insertDraft/updateDraft lokal sendiri-sendiri bukan
    // upsert yang aman, jadi cek keberadaannya dulu.
    suspend fun applyRemoteUpsertDraft(remote: Draft) {
        val exists = db.draftsFlow().first().any { it.id == remote.id }
        if (exists) db.updateDraft(remote) else db.insertDraft(remote)
    }
    suspend fun applyRemoteDeleteDraft(id: String) = db.deleteDraft(id)

    // [BARU] Upsert draft yang aman dipakai UI autosave (lihat DraftEditorOverlay di
    // WorkspaceListScreen.kt) — beda dari insertDraft/updateDraft biasa di atas (yang
    // masing-masing SENDIRIAN bukan upsert-by-id yang aman, sama kasusnya kayak
    // applyRemoteUpsertDraft di atas), fungsi ini cek keberadaannya dulu: insert kalau baru,
    // update kalau udah ada — SATU pintu buat autosave yang gak perlu tau bedanya "draft ini
    // baru pertama kali disimpan atau enggak". TETAP stempel updatedAt fresh (beda dari
    // applyRemoteUpsertDraft yang buat SYNC, preserve timestamp remote apa adanya) karena ini
    // genuinely user yang lagi nulis/ngedit.
    suspend fun upsertDraft(d: Draft) {
        val stamped = d.copy(updatedAt = System.currentTimeMillis())
        val exists = db.draftsFlow().first().any { it.id == d.id }
        if (exists) db.updateDraft(stamped) else db.insertDraft(stamped)
    }

    // [BARU, Fase 2 ekosistem draft] Notification Center — upsertSuggestion niru pola
    // upsertDraft di atas: exists-check by id (stabil, draftId+"_"+workspaceId) biar entri
    // LAMA di-update (bukan bikin baris baru tiap kali status suggestion yang sama berubah).
    fun suggestions(): Flow<List<DraftSuggestion>> = db.suggestionsFlow()
    suspend fun upsertSuggestion(s: DraftSuggestion) {
        val stamped = s.copy(updatedAt = System.currentTimeMillis())
        val exists = db.suggestionsFlow().first().any { it.id == s.id }
        if (exists) db.updateSuggestion(stamped) else db.insertSuggestion(stamped)
    }
    suspend fun deleteSuggestion(id: String) = db.deleteSuggestion(id)

    // ── Flashcard ───────────────────────────────────────────────────────
    fun flashcards(chId: String): List<FlashcardData> = db.flashcardsOnce(chId)

    // Dipanggil pas flashcard di-generate/diregenerasi user — stempel timestamp FRESH ke
    // bookkeeping sync (FlashcardData/LocalStorage nggak punya field updatedAt sendiri sama
    // sekali — lihat penjelasan lengkap soal ini di SyncModels.kt).
    suspend fun saveFlashcards(chId: String, cards: List<FlashcardData>) {
        db.saveFlashcards(chId, cards)
        syncMeta.touchFlashcardSet(chId)
    }

    // Dipakai SyncManager pas nerapin hasil pull — preserve timestamp DARI SERVER apa adanya
    // (remoteUpdatedAt), BUKAN stempel fresh baru (lihat catatan header file ini).
    suspend fun applyRemoteUpsertFlashcardSet(chId: String, cards: List<FlashcardData>, remoteUpdatedAt: Long) {
        db.saveFlashcards(chId, cards)
        syncMeta.touchFlashcardSet(chId, remoteUpdatedAt)
    }

    // ── Ganti akun Google di HP yang sama (lihat SyncModels.kt & SyncManager.kt) ──────────
    // Data ekspor manual ("Ekspor Data" di Pengaturan) sengaja TERPISAH dari ini — itu buat
    // dibagikan/disimpan user sendiri, formatnya lebih ringkas (gak nyertain draft/flashcard).
    // Backup di sini otomatis, internal, buat SATU tujuan doang: jaring pengaman sebelum
    // wipeAllLocalDataForAccountSwitch() — makanya nyertain SEMUA entity yang bakal kehapus.
    @Serializable
    private data class LocalBackupBundle(
        val backedUpAt: Long = System.currentTimeMillis(),
        val previousAccountEmail: String? = null,
        val workspaces: List<Workspace>,
        val chapters: List<Chapter>,
        val materials: List<Material>,
        val tasks: List<Task>,
        val drafts: List<Draft>,
        val flashcardSets: Map<String, List<FlashcardData>>
    )

    /** Tulis SEMUA data lokal saat ini ke satu file JSON di penyimpanan internal aplikasi
     * (BUKAN cache — cache bisa dibersihin sistem kapan aja, ini harus tahan lama). Dipanggil
     * SEBELUM wipeAllLocalDataForAccountSwitch(); kalau ini gagal (lempar exception), pemanggil
     * (SyncManager.switchAccountAndPullFresh) WAJIB berhenti di situ, JANGAN lanjut wipe. */
    suspend fun writeLocalBackup(filesDir: File, previousAccountEmail: String?): File = withContext(Dispatchers.IO) {
        val bundle = LocalBackupBundle(
            previousAccountEmail = previousAccountEmail,
            workspaces = db.allWorkspacesOnce(),
            chapters = db.allChaptersOnce(),
            materials = db.allMaterialsOnce(),
            tasks = db.allTasksOnce(),
            drafts = drafts().first(),
            flashcardSets = db.allFlashcardSetsOnce()
        )
        val dir = File(filesDir, "account_switch_backups").apply { mkdirs() }
        val file = File(dir, "backup_sebelum_ganti_akun_${System.currentTimeMillis()}.json")
        file.writeText(Json { prettyPrint = true }.encodeToString(bundle))
        file
    }

    /** Bersihin SEMUA data lokal — SENGAJA cuma dipanggil dari alur ganti-akun (lihat
     * SyncManager.switchAccountAndPullFresh), yang SELALU manggil writeLocalBackup() dulu
     * sebelum ini. TIDAK menyentuh syncMeta sama sekali — itu direset terpisah oleh pemanggil,
     * biar tanggung jawab "hapus data" vs "reset bookkeeping sync" tetap kelihatan sebagai dua
     * langkah yang jelas, bukan nyampur di satu fungsi. */
    suspend fun wipeAllLocalDataForAccountSwitch() = db.wipeAllLocalData()
}
