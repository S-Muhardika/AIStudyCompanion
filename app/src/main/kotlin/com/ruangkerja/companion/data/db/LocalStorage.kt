package com.ruangkerja.companion.data.db

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LocalStorage(context: Context) {

    private val dir  = context.filesDir
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // Scope milik LocalStorage sendiri (bukan GlobalScope) — supaya nggak "menggantung"
    // tanpa pemilik yang jelas dan konsisten dengan siklus hidup singleton ini.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    private val _chapters   = MutableStateFlow<List<Chapter>>(emptyList())
    private val _materials  = MutableStateFlow<List<Material>>(emptyList())
    private val _tasks      = MutableStateFlow<List<Task>>(emptyList())
    private val _drafts     = MutableStateFlow<List<Draft>>(emptyList())
    private val _flashcardMap = MutableStateFlow<Map<String, List<FlashcardData>>>(emptyMap())
    // [BARU] Chunk materi + embedding buat RAG (Diskusi grounded) — pola sama persis kayak
    // state lain di file ini (MutableStateFlow + JSON lokal), "lokal jadi primary" (keputusan
    // hybrid: Supabase kalau nanti dikonfigurasi cuma sync/backup opsional, app tetep jalan
    // penuh tanpa itu).
    private val _chunks = MutableStateFlow<List<MaterialChunk>>(emptyList())

    // [BARU, Fase 2 ekosistem draft] Riwayat saran draft->workspace buat Notification Center.
    // Bukan data inti (app tetep jalan normal walau ini kosong/belum kemuat), jadi ikut
    // heavyJob kayak flashcard/chunks, bukan coreJob.
    private val _suggestions = MutableStateFlow<List<DraftSuggestion>>(emptyList())

    // [BARU, 13 Nov — Diskusi: histori multi-thread] Sama alasan dengan _chunks/_suggestions di
    // atas: bukan data "inti" buat Beranda, jadi ikut heavyJob bukan coreJob. Lokal-only,
    // SENGAJA tidak ikut SyncManager (lihat catatan lengkap di Entities.kt ChatThread/ChatMsg).
    private val _chatThreads = MutableStateFlow<List<ChatThread>>(emptyList())
    private val _chatMessages = MutableStateFlow<List<ChatMsg>>(emptyList())

    // Dipakai UI (lewat AppRepository/AppViewModel) buat nampilin loading state
    // yang jujur, alih-alih "Belum ada Ruang Kerja" yang sekilas nongol duluan
    // padahal datanya masih dibaca dari disk.
    private val _isReady = MutableStateFlow(false)
    val isReady: Flow<Boolean> = _isReady

    // [OPTIMASI STARTUP] Sebelumnya SATU initJob nunggu SEMUA 7 file, termasuk chunks.json
    // (chunk + embedding RAG buat "Diskusi") yang bisa membengkak jauh lebih besar dari file
    // lain seiring pemakaian — bikin Beranda nunggu lama padahal dia cuma butuh 5 data "inti"
    // di bawah. Sekarang dipecah jadi 2 job PARALEL: coreJob (workspace/chapter/material/task/
    // draft — nge-gate isReady, dipakai Beranda) dan heavyJob (flashcard/chunks — jalan sendiri
    // di belakang layar, gak nge-block isReady sama sekali). Begitu tab Flashcard/Diskusi
    // beneran dibuka, heavyJob biasanya udah lama kelar duluan (biasanya cuma telat sepersekian
    // detik dari coreJob, jauh sebelum user sempet navigasi ke situ).
    private val coreJob = ioScope.async {
        val wsDef   = async { load<Workspace>("workspaces.json") }
        val chDef   = async { load<Chapter>("chapters.json") }
        val matDef  = async { load<Material>("materials.json") }
        val taskDef = async { load<Task>("tasks.json") }
        val draftDef = async { load<Draft>("drafts.json") }
        _workspaces.value = wsDef.await()
        _chapters.value   = chDef.await()
        _materials.value  = matDef.await()
        _tasks.value      = taskDef.await()
        // [FIX, 16 Okt — masukan William: migrasi otomatis, bukan dibiarin ilang] Draft.
        // dismissedMatchWorkspaceId (field tunggal lama) DIGABUNGIN ke dismissedMatchWorkspaceIds
        // (Set baru) di SINI, satu-satunya titik drafts.json dibaca dari disk — bukan cuma
        // dibiarin ada buat jaga-jaga. Field lama sekalian dikosongin (null) abis nilainya
        // kepindah, jadi gak nyisain data dobel di memori. Ini jalan tiap app dibuka, jadi
        // draft yang gak pernah disentuh lagi pun tetap ke-migrasi tiap kali ke-load — bukan
        // migrasi SEKALI doang terus berharap keburu kesave. Hasil migrasi baru beneran nempel
        // ke drafts.json pas draft itu sendiri disave lagi (autosave/dihapus/dipindah) — draft
        // yang didiemin aja di disk tetep nyimpen field lama di sana, tapi itu gak masalah:
        // in-memory (yang beneran dipakai DraftMatcher/UI) selalu benar sejak app baru kebuka,
        // field lama di disk cuma sisa gak kepake yang gak pernah lagi dibaca dari sini.
        _drafts.value = draftDef.await().map { d ->
            if (d.dismissedMatchWorkspaceId != null)
                d.copy(
                    dismissedMatchWorkspaceIds = d.dismissedMatchWorkspaceIds + d.dismissedMatchWorkspaceId,
                    dismissedMatchWorkspaceId = null
                )
            else d
        }
        _isReady.value = true
    }

    private val heavyJob = ioScope.async {
        val fcDef   = async { loadMap<Map<String, List<FlashcardData>>>("flashcards.json") }
        val chunkDef = async { load<MaterialChunk>("chunks.json") }
        val suggDef = async { load<DraftSuggestion>("suggestions.json") }
        // [BARU, 13 Nov] Diskusi: histori multi-thread.
        val threadDef = async { load<ChatThread>("chat_threads.json") }
        val msgDef    = async { load<ChatMsg>("chat_messages.json") }
        _flashcardMap.value = fcDef.await()
        _chunks.value     = chunkDef.await()
        _suggestions.value = suggDef.await()
        _chatThreads.value = threadDef.await()
        _chatMessages.value = msgDef.await()
    }

    /** Gerbang wajib di awal fungsi baca-ubah-tulis yang CUMA nyentuh data "inti" (workspace/
     * chapter/material/task/draft). Kalau load awal udah kelar (kasus normal — biasanya
     * berdurasi milidetik), `await()` langsung lolos tanpa delay berarti. */
    private suspend fun ready() = coreJob.await()

    /** Gerbang buat fungsi yang nyentuh _flashcardMap ATAU _chunks — WAJIB nunggu heavyJob juga,
     * bukan cuma coreJob, atau race condition yang sama kayak sebelum ada `ready()` bisa kejadian
     * lagi (baca-ubah-tulis nyempil sebelum load awal-nya sendiri kelar, hasil tulisnya ketiban
     * balik pas heavyJob akhirnya assign _xxx.value). Dipanggil juga oleh
     * AppRepository.awaitFullyReady() (dipakai SyncManager sebelum push — sync butuh SEMUA data
     * kebaca, bukan cuma yang "inti"). */
    private suspend fun readyHeavy() { coreJob.await(); heavyJob.await() }

    suspend fun awaitFullyLoaded() = readyHeavy()

    // [FIX, 29 Okt — audit TINGGI #1] writeAtomic() + quarantineCorruptFile() ditambahin buat
    // nutup risiko kehilangan data kalau proses app mati PERSIS di tengah nulis file (force-stop,
    // low-memory kill OS, dst). Detail lengkap ada di laporan audit project ini (temuan B1).
    //
    // writeAtomic: tulis dulu ke "<nama>.tmp" di direktori yang SAMA, baru rename() nimpa file
    // aslinya. rename() dalam satu direktori/filesystem (SELALU kasus di sini — satu-satunya
    // direktori yang dipakai LocalStorage adalah context.filesDir) atomik di level OS: proses
    // yang mati di tengah jalan nyisain file ASLI tetap utuh (belum kesentuh) ATAU file BARU yang
    // utuh — gak pernah ada kondisi "setengah ketulis" yang bikin JSON kepotong/korup. Kalau
    // rename() gagal (jarang — biasanya cuma masalah permission/storage), fallback tulis langsung
    // biar gak diam-diam gagal tanpa jejak sama sekali.
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    // File yang gagal di-parse (korup/terpotong) DIPINDAHIN dulu (bukan ditimpa/dihapus) sebelum
    // dianggap kosong oleh pemanggil load()/loadMap() — insert/save berikutnya sekarang nimpa
    // slot yang udah kosong secara SAH, BUKAN nimpa file korup yang masih mungkin bisa dipulihin
    // manual. Ini belum nyertain notifikasi ke user (itu perubahan UI terpisah) — minimal datanya
    // gak lenyap tanpa bekas sama sekali.
    private fun quarantineCorruptFile(file: File) {
        try { file.renameTo(File(dir, "${file.name}.corrupt-${System.currentTimeMillis()}")) } catch (_: Exception) {}
    }

    private inline fun <reified T> load(filename: String): List<T> {
        val file = File(dir, filename)
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(file.readText())
        } catch (_: Exception) {
            quarantineCorruptFile(file)
            emptyList()
        }
    }

    private inline fun <reified T> save(filename: String, list: List<T>) = try {
        writeAtomic(File(dir, filename), json.encodeToString(list))
    } catch (_: Exception) {}

    private inline fun <reified T> loadMap(filename: String): T {
        val file = File(dir, filename)
        if (!file.exists()) return json.decodeFromString("{}")
        return try {
            json.decodeFromString(file.readText())
        } catch (_: Exception) {
            quarantineCorruptFile(file)
            json.decodeFromString("{}")
        }
    }

    // Ekspor — snapshot SEMUA data lintas workspace, dipakai fitur Ekspor Data di Pengaturan
    fun allWorkspacesOnce(): List<Workspace> = _workspaces.value
    fun allChaptersOnce(): List<Chapter> = _chapters.value
    fun allMaterialsOnce(): List<Material> = _materials.value
    fun allTasksOnce(): List<Task> = _tasks.value
    // [BARU] Dipakai SyncManager buat push — perlu tau SEMUA chapter yang punya flashcard
    // (termasuk yang disimpan SEBELUM fitur sync ini ada), bukan cuma satu chapterId spesifik
    // kayak flashcardsOnce(chId).
    fun allFlashcardSetsOnce(): Map<String, List<FlashcardData>> = _flashcardMap.value

    // Workspace
    fun workspacesFlow(): Flow<List<Workspace>> = _workspaces.map { it.sortedWith(compareByDescending<Workspace> { w -> w.pinned }.thenBy { w -> w.createdAt }) }
    suspend fun insertWorkspace(w: Workspace) = withContext(Dispatchers.IO) {
        ready()
        val u = _workspaces.value.filter { it.id != w.id } + w
        save("workspaces.json", u); _workspaces.value = u
    }
    suspend fun deleteWorkspace(id: String) = withContext(Dispatchers.IO) {
        readyHeavy() // nyentuh _chunks & _flashcardMap juga, bukan cuma data "inti"
        val chIds = _chapters.value.filter { it.workspaceId == id }.map { it.id }.toSet()
        val mats = _materials.value.filter { it.chapterId !in chIds }
        val chs  = _chapters.value.filter { it.workspaceId != id }
        val wss  = _workspaces.value.filter { it.id != id }
        val chunks = _chunks.value.filter { it.chapterId !in chIds }
        // [FIX] Sama kayak deleteChapter — sebelumnya _flashcardMap TIDAK ikut dibersihin buat
        // SEMUA chapter yang ke-cascade-hapus bareng workspace ini, jadi flashcard-flashcard itu
        // jadi yatim (lihat catatan lengkap di deleteChapter di atas).
        val flashcards = _flashcardMap.value - chIds
        // [FIX] Task yang nempel ke workspace ini SEBELUMNYA dibiarkan nunjuk ke workspaceId yang
        // udah gak ada (dangling) — task-nya sendiri TETAP valid tanpa workspace (beda kasus sama
        // chapter/material/flashcard yang emang percuma tanpa induknya), jadi link-nya di-null-in
        // aja, BUKAN task-nya ikut kehapus. Ini nyamain perilaku lokal sama ON DELETE SET NULL
        // yang udah didefinisikan di tasks.workspace_id, supabase/schema.sql.
        val tasksFixed = _tasks.value.map { if (it.workspaceId == id) it.copy(workspaceId = null) else it }
        // [BARU, 13 Nov] Sama alasan dengan flashcards/chunks di atas — thread+pesan Diskusi
        // SEMUA bab yang ikut ke-cascade-hapus bareng workspace ini harus ikut dibersihin juga,
        // atau jadi yatim nunjuk ke chapterId yang udah gak ada di mana pun.
        val threadIdsGone = _chatThreads.value.filter { it.chapterId in chIds }.map { it.id }.toSet()
        val threads = _chatThreads.value.filter { it.chapterId !in chIds }
        val msgs = _chatMessages.value.filter { it.threadId !in threadIdsGone }
        save("materials.json", mats); _materials.value = mats
        save("chapters.json", chs); _chapters.value = chs
        save("workspaces.json", wss); _workspaces.value = wss
        save("chunks.json", chunks); _chunks.value = chunks
        save("tasks.json", tasksFixed); _tasks.value = tasksFixed
        save("chat_threads.json", threads); _chatThreads.value = threads
        save("chat_messages.json", msgs); _chatMessages.value = msgs
        writeAtomic(File(dir, "flashcards.json"), json.encodeToString(flashcards)); _flashcardMap.value = flashcards
    }

    // Chapter
    fun chaptersFlow(wsId: String): Flow<List<Chapter>> =
        _chapters.map { it.filter { c -> c.workspaceId == wsId }.sortedWith(compareBy({ it.orderIndex }, { it.createdAt })) }
    suspend fun chapter(id: String): Chapter? { ready(); return _chapters.value.find { it.id == id } }
    suspend fun insertChapter(c: Chapter) = withContext(Dispatchers.IO) {
        ready()
        val u = _chapters.value.filter { it.id != c.id } + c
        save("chapters.json", u); _chapters.value = u
    }
    suspend fun updateChapter(c: Chapter) = withContext(Dispatchers.IO) {
        ready()
        val u = _chapters.value.map { if (it.id == c.id) c else it }
        save("chapters.json", u); _chapters.value = u
    }
    suspend fun deleteChapter(id: String) = withContext(Dispatchers.IO) {
        readyHeavy() // nyentuh _chunks & _flashcardMap juga, bukan cuma data "inti"
        val mats = _materials.value.filter { it.chapterId != id }
        val chs  = _chapters.value.filter { it.id != id }
        val chunks = _chunks.value.filter { it.chapterId != id }
        // [FIX] Sebelumnya _flashcardMap TIDAK ikut dibersihin di sini — flashcard punya chapter
        // yang dihapus jadi "yatim", nunjuk ke chapter_id yang udah nggak eksis di mana pun.
        // Ketauan pas SyncManager.pushFlashcardSets() nabrak foreign key gara-gara ini.
        val flashcards = _flashcardMap.value - id
        // [BARU, 13 Nov] Thread+pesan Diskusi bab ini ikut dihapus — sama alasan persis kayak
        // _flashcardMap/_chunks di atas (lihat catatan panjang deleteWorkspace soal ini juga).
        val threadIdsGone = _chatThreads.value.filter { it.chapterId == id }.map { it.id }.toSet()
        val threads = _chatThreads.value.filter { it.chapterId != id }
        val msgs = _chatMessages.value.filter { it.threadId !in threadIdsGone }
        save("materials.json", mats); _materials.value = mats
        save("chapters.json", chs); _chapters.value = chs
        save("chunks.json", chunks); _chunks.value = chunks
        save("chat_threads.json", threads); _chatThreads.value = threads
        save("chat_messages.json", msgs); _chatMessages.value = msgs
        writeAtomic(File(dir, "flashcards.json"), json.encodeToString(flashcards)); _flashcardMap.value = flashcards
    }
    suspend fun chapterCount(wsId: String): Int { ready(); return _chapters.value.count { it.workspaceId == wsId } }

    // Material
    fun materialsFlow(chId: String): Flow<List<Material>> = _materials.map { it.filter { m -> m.chapterId == chId }.sortedWith(compareByDescending<Material> { m -> m.pinned }.thenBy { m -> m.createdAt }) }
    suspend fun materialsOnce(chId: String): List<Material> { ready(); return _materials.value.filter { it.chapterId == chId }.sortedBy { it.createdAt } }
    suspend fun insertMaterial(m: Material) = withContext(Dispatchers.IO) {
        ready()
        val u = _materials.value + m; save("materials.json", u); _materials.value = u
    }
    suspend fun updateMaterial(m: Material) = withContext(Dispatchers.IO) {
        ready()
        val exists = _materials.value.any { it.id == m.id }
        val u = if (exists) _materials.value.map { if (it.id == m.id) m else it } else _materials.value + m
        save("materials.json", u); _materials.value = u
    }
    suspend fun deleteMaterial(id: String) = withContext(Dispatchers.IO) {
        readyHeavy() // nyentuh _chunks juga
        val u = _materials.value.filter { it.id != id }; save("materials.json", u); _materials.value = u
        val ck = _chunks.value.filter { it.materialId != id }; save("chunks.json", ck); _chunks.value = ck
    }

    // Chunk — pola beda dikit dari entity lain: "replace" (bukan insert/update terpisah)
    // karena tiap kali materi di-embed ulang, SEMUA chunk lama punya materi itu diganti utuh
    // (bukan di-merge satu-satu) — lebih sederhana & gak ada chunk basi ketinggalan kalau
    // materinya diedit sampe potongan-potongannya berubah semua.
    fun chunksForChapter(chId: String): List<MaterialChunk> = _chunks.value.filter { it.chapterId == chId }
    suspend fun replaceChunksForMaterial(materialId: String, newChunks: List<MaterialChunk>) = withContext(Dispatchers.IO) {
        readyHeavy()
        val u = _chunks.value.filter { it.materialId != materialId } + newChunks
        save("chunks.json", u); _chunks.value = u
    }

    // Diskusi — thread + pesan percakapan. Lokal-only (lihat catatan Entities.kt), readyHeavy()
    // karena dimuat di heavyJob — pola sama persis seperti Chunk di atas (Flow + Once + insert).
    fun chatThreadsFlow(chId: String): Flow<List<ChatThread>> =
        _chatThreads.map { it.filter { t -> t.chapterId == chId }.sortedByDescending { t -> t.updatedAt } }
    suspend fun chatThreadsOnce(chId: String): List<ChatThread> {
        readyHeavy(); return _chatThreads.value.filter { it.chapterId == chId }.sortedByDescending { it.updatedAt }
    }
    suspend fun insertChatThread(t: ChatThread) = withContext(Dispatchers.IO) {
        readyHeavy()
        val u = _chatThreads.value.filter { it.id != t.id } + t
        save("chat_threads.json", u); _chatThreads.value = u
    }
    /** Dipanggil tiap kali ada pesan baru masuk thread ini — supaya daftar riwayat (diurutkan
     *  updatedAt desc di atas) nunjukin percakapan yang baru DIPAKAI, bukan cuma yang baru DIBUAT. */
    suspend fun touchChatThread(id: String, updatedAt: Long) = withContext(Dispatchers.IO) {
        readyHeavy()
        val u = _chatThreads.value.map { if (it.id == id) it.copy(updatedAt = updatedAt) else it }
        save("chat_threads.json", u); _chatThreads.value = u
    }
    suspend fun deleteChatThread(id: String) = withContext(Dispatchers.IO) {
        readyHeavy()
        val threads = _chatThreads.value.filter { it.id != id }
        val msgs = _chatMessages.value.filter { it.threadId != id }
        save("chat_threads.json", threads); _chatThreads.value = threads
        save("chat_messages.json", msgs); _chatMessages.value = msgs
    }
    fun chatMessagesFlow(threadId: String): Flow<List<ChatMsg>> =
        _chatMessages.map { it.filter { m -> m.threadId == threadId }.sortedBy { m -> m.createdAt } }
    suspend fun chatMessagesOnce(threadId: String): List<ChatMsg> {
        readyHeavy(); return _chatMessages.value.filter { it.threadId == threadId }.sortedBy { it.createdAt }
    }
    suspend fun insertChatMessage(m: ChatMsg) = withContext(Dispatchers.IO) {
        readyHeavy()
        val u = _chatMessages.value + m
        save("chat_messages.json", u); _chatMessages.value = u
    }
    // [BARU, 14-15 Nov — streaming native Diskusi] insertChatMessage() di atas PURE APPEND (tidak
    // pernah cek id sebelumnya) — sengaja TIDAK dipakai ulang buat update progres pesan yang
    // sedang STREAMING, karena motret ulang `m` yang sama (id sama, text makin panjang) lewat
    // insertChatMessage() bakal bikin BEBERAPA entri dengan id yang SAMA di chat_messages.json
    // (LazyColumn items(key=it.id) di TabDiskusi bakal crash "key sudah dipakai", dan histori
    // yang dikirim ke AI jadi dobel). Pola method ini PERSIS sama seperti updateMaterial() di
    // atas: replace-by-id kalau sudah ada, insert kalau somehow belum (harusnya tidak pernah
    // kejadian lewat jalur normal, tapi aman dibiarkan sbg fallback daripada diam-diam hilang).
    suspend fun updateChatMessage(m: ChatMsg) = withContext(Dispatchers.IO) {
        readyHeavy()
        val exists = _chatMessages.value.any { it.id == m.id }
        val u = if (exists) _chatMessages.value.map { if (it.id == m.id) m else it } else _chatMessages.value + m
        save("chat_messages.json", u); _chatMessages.value = u
    }

    // Task
    fun tasksFlow(): Flow<List<Task>> = _tasks
    suspend fun insertTask(t: Task) = withContext(Dispatchers.IO) {
        ready()
        val u = _tasks.value + t; save("tasks.json", u); _tasks.value = u
    }
    suspend fun updateTask(t: Task) = withContext(Dispatchers.IO) {
        ready()
        val u = _tasks.value.map { if (it.id == t.id) t else it }; save("tasks.json", u); _tasks.value = u
    }
    suspend fun deleteTask(id: String) = withContext(Dispatchers.IO) {
        ready()
        val u = _tasks.value.filter { it.id != id }; save("tasks.json", u); _tasks.value = u
    }

    // Draft — catatan cepat, gak terikat Workspace
    fun draftsFlow(): Flow<List<Draft>> = _drafts.map { it.sortedByDescending { d -> d.updatedAt } }
    suspend fun insertDraft(d: Draft) = withContext(Dispatchers.IO) {
        ready()
        val u = _drafts.value + d; save("drafts.json", u); _drafts.value = u
    }
    suspend fun updateDraft(d: Draft) = withContext(Dispatchers.IO) {
        ready()
        val u = _drafts.value.map { if (it.id == d.id) d else it }; save("drafts.json", u); _drafts.value = u
    }
    suspend fun deleteDraft(id: String) = withContext(Dispatchers.IO) {
        ready()
        val u = _drafts.value.filter { it.id != id }; save("drafts.json", u); _drafts.value = u
    }

    // [BARU, Fase 2 ekosistem draft] Notification Center — pola sama persis kayak Draft di
    // atas, cuma readyHeavy() (bukan ready()) karena ini dimuat di heavyJob.
    fun suggestionsFlow(): Flow<List<DraftSuggestion>> = _suggestions.map { it.sortedByDescending { s -> s.updatedAt } }
    suspend fun insertSuggestion(s: DraftSuggestion) = withContext(Dispatchers.IO) {
        readyHeavy()
        val u = _suggestions.value + s; save("suggestions.json", u); _suggestions.value = u
    }
    suspend fun updateSuggestion(s: DraftSuggestion) = withContext(Dispatchers.IO) {
        readyHeavy()
        val u = _suggestions.value.map { if (it.id == s.id) s else it }; save("suggestions.json", u); _suggestions.value = u
    }
    suspend fun deleteSuggestion(id: String) = withContext(Dispatchers.IO) {
        readyHeavy()
        val u = _suggestions.value.filter { it.id != id }; save("suggestions.json", u); _suggestions.value = u
    }

    // Flashcard
    fun flashcardsOnce(chId: String): List<FlashcardData> = _flashcardMap.value[chId] ?: emptyList()
    suspend fun saveFlashcards(chId: String, cards: List<FlashcardData>) = withContext(Dispatchers.IO) {
        readyHeavy()
        val u = _flashcardMap.value.toMutableMap().apply { put(chId, cards) }
        writeAtomic(File(dir, "flashcards.json"), json.encodeToString(u))
        _flashcardMap.value = u
    }

    // [BARU] Bersihin SEMUA data lokal sekaligus — SATU-SATUNYA pemanggil yang dimaksud adalah
    // AppRepository.wipeAllLocalDataForAccountSwitch(), dipakai pas user ganti akun Google di HP
    // yang sama (lihat catatan panjang soal ini di SyncModels.kt & SyncManager.kt). SENGAJA
    // TIDAK lewat deleteWorkspace/deleteChapter/dkk satu-satu — itu masing-masing nyatet
    // tombstone yang di sini justru gak relevan (kita LAGI meninggalkan sesi akun lama
    // sepenuhnya, bukan mau bilang ke akun lama "ini dihapus").
    suspend fun wipeAllLocalData() = withContext(Dispatchers.IO) {
        readyHeavy()
        _workspaces.value = emptyList(); save("workspaces.json", emptyList<Workspace>())
        _chapters.value = emptyList(); save("chapters.json", emptyList<Chapter>())
        _materials.value = emptyList(); save("materials.json", emptyList<Material>())
        _tasks.value = emptyList(); save("tasks.json", emptyList<Task>())
        _drafts.value = emptyList(); save("drafts.json", emptyList<Draft>())
        _flashcardMap.value = emptyMap(); writeAtomic(File(dir, "flashcards.json"), json.encodeToString(_flashcardMap.value))
        _chunks.value = emptyList(); save("chunks.json", emptyList<MaterialChunk>())
        // [BARU, 13 Nov] Thread+pesan Diskusi juga HARUS ikut bersih pas ganti akun — kalau
        // kelewat, user yang baru login bisa lihat riwayat chat akun SEBELUMNYA di HP yang sama
        // (persis kelas bug yang jadi alasan fungsi wipeAllLocalData() ini ada sama sekali).
        _chatThreads.value = emptyList(); save("chat_threads.json", emptyList<ChatThread>())
        _chatMessages.value = emptyList(); save("chat_messages.json", emptyList<ChatMsg>())
    }
}
