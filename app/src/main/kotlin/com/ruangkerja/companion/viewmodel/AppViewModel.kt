package com.ruangkerja.companion.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ruangkerja.companion.auth.SupabaseAuthManager
import com.ruangkerja.companion.data.AppRepository
import com.ruangkerja.companion.data.db.*
import com.ruangkerja.companion.data.sync.SyncManager
import com.ruangkerja.companion.data.sync.SyncMetaStore
import com.ruangkerja.companion.network.CalendarService
import com.ruangkerja.companion.network.GoogleCalendarService
import com.ruangkerja.companion.network.AiEngine
import com.ruangkerja.companion.network.AiException
// [BARU, 14-15 Nov — streaming native Diskusi] dipakai sendChat() versi streaming di bawah.
import com.ruangkerja.companion.network.ChatStreamEvent
import com.ruangkerja.companion.notification.NotificationHelper
import com.ruangkerja.companion.rag.chunkText
import com.ruangkerja.companion.rag.topKSimilar
import com.ruangkerja.companion.report.ChapterReportData
import com.ruangkerja.companion.report.ReportPdfBuilder
import com.ruangkerja.companion.report.WorkspaceReportData
import com.ruangkerja.companion.ui.theme.AccentChoice
import com.ruangkerja.companion.ui.theme.ThemeMode
import com.ruangkerja.companion.util.MindMapCodec
import com.ruangkerja.companion.util.MindMapOps
import com.ruangkerja.companion.util.MindMapParser
import com.ruangkerja.companion.util.MindMapSource
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.util.DraftMatch
import com.ruangkerja.companion.util.DRAFT_DORMANT_MS
import com.ruangkerja.companion.util.MatchConfidence
import com.ruangkerja.companion.util.generateChapterName
import com.ruangkerja.companion.util.extractJsonSpan
import com.ruangkerja.companion.util.findMatchingWorkspace
import com.ruangkerja.companion.util.markdownToPlainPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AiState(val loading: Boolean = false, val error: String? = null)

@Serializable private data class FlashcardJson(val front: String, val back: String)
@Serializable private data class ChapterJson(val title: String, val description: String = "")

private val AppJson = Json { ignoreUnknownKeys = true }

// [BARU, 18 Okt — ekosistem draft ronde 3] Top-level (BUKAN nested di dalam class AppViewModel)
// supaya bisa diimport & dipakai langsung dari WorkspaceListScreen.kt buat render dialog
// konfirmasi nama Bab baru — lihat AppViewModel.newChapterPrompt/confirmNewChapterName().
data class NewChapterPrompt(
    val draft: Draft, val workspace: Workspace, val confidence: MatchConfidence,
    val orderIndex: Int, val suggestedName: String
)

// [BARU, 11 Nov — "PLANNING IMPLEMENTASI RKR: PERBAIKAN RAG, CHUNKING, DAN AUTOMATIC EMBEDDING"
// §3B] Dipakai reindexMaterial() buat validasi embedding sebelum index lama diganti. 768 sama
// persis kayak outputDimensionality yang diminta GeminiService.embed() (jalur BYOK) dan ai-proxy
// embedGemini() (jalur shared-key) — lihat network/GeminiService.kt — BUKAN angka tebakan.
private const val EXPECTED_EMBEDDING_DIMENSIONS = 768

// [BARU, 11 Nov — dokumen sama §4] Pola percakapan sederhana yang TIDAK butuh pencarian materi
// (salam/terima kasih/konfirmasi/perpisahan/tanya identitas asisten) — dicocokkan PERSIS (bukan
// "contains"/substring) sesudah dinormalisasi lewat isSimpleChatTurn() di bawah, SENGAJA supaya
// pesan yang KEBETULAN memuat salah satu kata ini di tengah pertanyaan akademik (mis. "Oke, tapi
// kenapa transaksi ini dicatat debit?") tetap lanjut ke buildRagContext(). Dokumen eksplisit
// melarang aturan "semua pertanyaan pendek dilewati" — daftar di bawah SENGAJA tidak memuat
// pertanyaan pendek yang masih bisa menyinggung materi ("Apa itu akuntansi?", "Apa maksudnya?",
// "Jelaskan perbedaannya.", dst — semua itu HARUS tetap lolos retrieval).
private val SIMPLE_CHAT_PATTERNS = setOf(
    // Salam
    "hai", "halo", "hallo", "hi", "hey", "pagi", "siang", "sore", "malam",
    "selamat pagi", "selamat siang", "selamat sore", "selamat malam",
    "assalamualaikum", "assalamu'alaikum", "waalaikumsalam",
    // Ucapan terima kasih
    "terima kasih", "terimakasih", "makasih", "makasih ya", "makasih banyak",
    "thanks", "thank you", "trims",
    // Konfirmasi sederhana
    "oke", "ok", "oke deh", "oke sip", "baik", "baiklah", "siap", "ya", "iya", "yap", "yoi",
    "paham", "ngerti", "mengerti", "oh paham", "oh ngerti", "noted", "sip", "mantap",
    // Perpisahan
    "dadah", "bye", "sampai jumpa", "sampai nanti", "selesai", "cukup", "segini dulu", "udahan",
    // Identitas asisten (tanya LANGSUNG ke asisten, bukan soal materi)
    "siapa kamu", "siapa kau", "kamu siapa", "kau siapa", "siapa namamu",
    "kamu ini apa", "kamu ini siapa", "kamu ai apa", "kamu apa",
)

/** True kalau [text] SECARA UTUH (bukan sebagian/mengandung) cuma basa-basi yang gak butuh
 * pencarian materi — lihat SIMPLE_CHAT_PATTERNS. Normalisasi: lowercase, trim, buang tanda baca
 * akhir (.!?,) doang — SENGAJA tidak mengubah/membuang isi TENGAH kalimat, jadi sebuah pesan
 * panjang yang kebetulan DIAWALI kata sapaan ("Oke, tapi kenapa X?") tidak akan match (match
 * harus sama persis utuh dengan salah satu pola, bukan prefix/substring). Konservatif sesuai
 * instruksi dokumen — kalau ragu, function ini mengembalikan false (tetap jalankan retrieval). */
internal fun isSimpleChatTurn(text: String): Boolean {
    val normalized = text.trim().lowercase().trimEnd('.', '!', '?', ',', ' ')
    if (normalized.isEmpty()) return false
    return normalized in SIMPLE_CHAT_PATTERNS
}

// [FIX, 18 Okt — self-audit] @OptIn ini HARUS nempel langsung di atas class AppViewModel (dipakai
// flatMapLatest di 2 tempat jauh di bawah, baris chapters/materials). Sempat ke-geser nangkring
// di atas NewChapterPrompt pas data class itu disisipkan di sini — udah dibenerin sebelum sempat
// ke-build, bukan ketauan dari error compile.
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val syncMeta = SyncMetaStore(app)
    private val repo  = AppRepository(LocalStorage(app), syncMeta)
    private val syncManager = SyncManager(repo, syncMeta)
    val ai            = AiEngine(app)
    private val prefs = app.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)

    // ── Akun & Sinkronisasi (opsional, V2.0.1) ─────────────
    // Login opsional buat sinkronisasi/materi/tugas — jangan gate fitur-fitur itu di belakang
    // ini. [UBAH, 27 Okt] PENGECUALIAN: fitur AI bawaan (jalur ai-proxy, bukan BYOK) sekarang
    // WAJIB login — lihat komentar lengkap di SupabaseAuthManager.kt & CHANGELOG 27 Okt.
    val currentUser: StateFlow<SupabaseAuthManager.AuthUser?> = SupabaseAuthManager.currentUser

    private val _authLoading = MutableStateFlow(false)
    val authLoading: StateFlow<Boolean> = _authLoading
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError

    // [FIX] init block yang manggil restoreSession() udah nggak perlu lagi — currentUser di
    // SupabaseAuthManager sekarang otomatis "hidup" & up-to-date begitu object-nya kesentuh
    // (val currentUser di atas), nggak butuh trigger manual lagi.

    fun signInWithGoogle(context: android.content.Context) = viewModelScope.launch {
        _authLoading.value = true
        _authError.value = null
        SupabaseAuthManager.signInWithGoogle(context)
            .onSuccess {
                // [FIX race condition, lihat laporan William 18 Sep] `currentUser` di
                // SupabaseAuthManager diturunkan dari sessionStatus lewat .map{}.stateIn(scope
                // MILIK OBJECT ITU SENDIRI, bukan viewModelScope) — begitu signInWithGoogle() di
                // atas selesai (network call-nya udah sukses), butuh SATU siklus dispatch lagi di
                // scope terpisah itu sebelum currentUser.value beneran pindah dari null ke
                // AuthUser. Kalau syncNow() dipanggil di sini TANPA nunggu itu, dia langsung liat
                // currentUser.value == null (masih versi lama) dan diam-diam return@launch, TANPA
                // sync ATAU error apa pun. Ini PERSIS gejala yang dilaporkan: auto-sync abis login
                // nggak mindahin data & Settings bersih tanpa error, padahal tombol "Sinkron"
                // manual (dipencet belakangan, pas currentUser udah pasti ke-update) selalu jalan
                // normal. Timeout 3 detik cuma jaring pengaman kalau emisinya lebih lama dari
                // dugaan — syncNow() sendiri tetap aman dipanggil sesudahnya baik currentUser
                // udah update atau belum (null check di dalamnya masih berlaku sebagai fallback).
                withTimeoutOrNull(3000) { currentUser.filterNotNull().first() }
                syncNow()
            }
            .onFailure { _authError.value = it.message ?: "Login gagal." }
        _authLoading.value = false
    }
    fun signOut() = viewModelScope.launch { SupabaseAuthManager.signOut() }

    // [BARU, V2.0.1] Sinkronisasi cloud — SEPENUHNYA opsional, cuma aktif kalau user login.
    // Trigger: (1) otomatis sekali abis login sukses di atas, (2) manual lewat tombol "Sinkron
    // Sekarang" di SettingsScreen (AkunGroup). SENGAJA TIDAK auto-sync tiap app dibuka/kembali
    // ke foreground buat V1 ini — biar perilakunya predictable & gampang di-debug on-device dulu
    // (tinggal satu baris tambahan di init kalau nanti mau nambah trigger itu juga).
    private val _syncLoading = MutableStateFlow(false)
    val syncLoading: StateFlow<Boolean> = _syncLoading
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError
    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt
    // [BARU] Non-null berarti: akun yang lagi login BEDA dari akun yang terakhir disinkron di
    // HP ini (lihat SyncMeta.syncedAccountId). Nilainya email akun LAMA (buat ditampilin), atau
    // null kalau nggak tercatat sebelumnya. SettingsScreen nampilin peringatan + tombol "Pindah
    // ke Akun Ini" kalau ini non-null — TIDAK ADA push/pull yang jalan otomatis selama ini
    // belum diselesaikan (baik lewat auto-sync abis login maupun tombol "Sinkron" manual).
    private val _accountMismatchEmail = MutableStateFlow<String?>(null)
    val accountMismatchEmail: StateFlow<String?> = _accountMismatchEmail

    private fun applySyncResult(result: SyncManager.SyncResult) {
        when (result) {
            is SyncManager.SyncResult.Success -> {
                _lastSyncedAt.value = result.syncedAt
                _accountMismatchEmail.value = null
            }
            is SyncManager.SyncResult.Failure -> _syncError.value = result.message
            is SyncManager.SyncResult.AccountMismatch -> _accountMismatchEmail.value = result.previousEmail ?: "akun lain"
        }
    }

    fun syncNow() = viewModelScope.launch {
        if (currentUser.value == null || _syncLoading.value) return@launch
        _syncLoading.value = true
        _syncError.value = null
        applySyncResult(syncManager.syncNow())
        _syncLoading.value = false
    }

    /** Dipanggil user EKSPLISIT lewat tombol "Pindah ke Akun Ini" (setelah konfirmasi dialog di
     * Settings) — lihat SyncManager.switchAccountAndPullFresh buat urutan lengkap (backup dulu,
     * baru wipe, baru pull fresh). */
    fun switchAccount() = viewModelScope.launch {
        if (currentUser.value == null || _syncLoading.value) return@launch
        _syncLoading.value = true
        _syncError.value = null
        applySyncResult(syncManager.switchAccountAndPullFresh(getApplication<Application>().filesDir))
        _syncLoading.value = false
    }

    // ── Theme & Accent ────────────────────────────────────
    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.find { it.name == prefs.getString("theme_mode", "DARK") } ?: ThemeMode.DARK
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _accent = MutableStateFlow(
        AccentChoice.entries.find { it.name == prefs.getString("accent_choice", "INDIGO") } ?: AccentChoice.INDIGO
    )
    val accent: StateFlow<AccentChoice> = _accent

    private val _language = MutableStateFlow(
        com.ruangkerja.companion.ui.theme.AppLanguage.entries.find { it.name == prefs.getString("app_language", "ID") }
            ?: com.ruangkerja.companion.ui.theme.AppLanguage.ID
    )
    val language: StateFlow<com.ruangkerja.companion.ui.theme.AppLanguage> = _language
    fun setLanguage(lang: com.ruangkerja.companion.ui.theme.AppLanguage) {
        _language.value = lang
        prefs.edit().putString("app_language", lang.name).apply()
    }

    fun toggleDarkMode() {
        val next = if (_themeMode.value == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        _themeMode.value = next
        prefs.edit().putString("theme_mode", next.name).apply()
    }
    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }
    fun setAccent(choice: AccentChoice) {
        _accent.value = choice
        prefs.edit().putString("accent_choice", choice.name).apply()
    }

    // ── Layout: Grid atau Daftar (persisten per-preferensi, dipakai Beranda & halaman bab) ──
    private val _layoutGrid = MutableStateFlow(prefs.getBoolean("layout_grid", false))
    val layoutGrid: StateFlow<Boolean> = _layoutGrid
    fun toggleLayoutGrid() {
        _layoutGrid.value = !_layoutGrid.value
        prefs.edit().putBoolean("layout_grid", _layoutGrid.value).apply()
    }

    // ── Search universal: workspace, bab, materi sekaligus ──────
    data class SearchResult(val id: String, val type: String, val title: String, val subtitle: String)

    fun search(query: String): List<SearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val ws = repo.allWorkspacesOnce().filter { it.name.contains(q, true) || it.description.contains(q, true) }
            .map { SearchResult(it.id, "workspace", it.name, it.description.ifBlank { "Ruang Kerja" }) }
        val chs = repo.allChaptersOnce().filter { it.name.contains(q, true) || it.description.contains(q, true) }
            .map { SearchResult(it.id, "chapter", it.name, it.description.ifBlank { "Bab" }) }
        // Hasil materi diarahkan ke ID BAB-nya (bukan ID materi), soalnya materi nggak punya
        // layar sendiri — bukanya lewat tab Materi di ChapterDetailScreen.
        val mats = repo.allMaterialsOnce().filter { it.title.contains(q, true) || it.content.contains(q, true) }
            .map { SearchResult(it.chapterId, "material", it.title.ifBlank { "Materi tanpa judul" }, "Materi di sebuah bab") }
        return ws + chs + mats
    }

    // ── Notifikasi: Jam Tenang & Pengingat Harian ──────────
    private val _quietHoursEnabled = MutableStateFlow(prefs.getBoolean("quiet_hours_enabled", false))
    val quietHoursEnabled: StateFlow<Boolean> = _quietHoursEnabled
    fun setQuietHoursEnabled(on: Boolean) {
        _quietHoursEnabled.value = on
        prefs.edit().putBoolean("quiet_hours_enabled", on).apply()
    }

    private val _dailyReminderEnabled = MutableStateFlow(prefs.getBoolean("daily_reminder_enabled", false))
    val dailyReminderEnabled: StateFlow<Boolean> = _dailyReminderEnabled
    fun setDailyReminderEnabled(on: Boolean) {
        _dailyReminderEnabled.value = on
        prefs.edit().putBoolean("daily_reminder_enabled", on).apply()
        val app = getApplication<Application>()
        if (on) NotificationHelper.scheduleDailyCheck(app) else NotificationHelper.cancelDailyCheck(app)
    }

    // [BARU, Fase 3 ekosistem draft] Toggle notifikasi Android buat saran draft-workspace —
    // pola sama persis kayak dailyReminderEnabled di atas, cuma jadwal & receiver-nya beda
    // (lihat NotificationHelper.scheduleSuggestionCheck / DraftSuggestionCheckReceiver).
    private val _draftSuggestionNotifEnabled = MutableStateFlow(prefs.getBoolean("draft_suggestion_notif_enabled", false))
    val draftSuggestionNotifEnabled: StateFlow<Boolean> = _draftSuggestionNotifEnabled
    fun setDraftSuggestionNotifEnabled(on: Boolean) {
        _draftSuggestionNotifEnabled.value = on
        prefs.edit().putBoolean("draft_suggestion_notif_enabled", on).apply()
        val app = getApplication<Application>()
        if (on) NotificationHelper.scheduleSuggestionCheck(app) else NotificationHelper.cancelSuggestionCheck(app)
    }

    /** Ditandai tiap kali user buka sebuah bab — dipakai DailyReminderReceiver buat tau
     *  apakah user "udah belajar hari ini" (biar nggak notif kalau udah aktif). */
    private fun markStudiedToday() {
        prefs.edit().putString("last_active_date", DateUtils.todayDateString()).apply()
    }

    // ── Ekspor Data ─────────────────────────────────────────
    @Serializable
    private data class ExportBundle(
        val exportedAt: Long = System.currentTimeMillis(),
        val workspaces: List<Workspace>,
        val chapters: List<Chapter>,
        val materials: List<Material>,
        val tasks: List<Task>
    )

    /** Kumpulin SEMUA data (lintas workspace) jadi satu file JSON, dibungkus jadi .zip (satu
     *  entry: data.json) — biar konsisten sama restoreFromFile & lebih kecil ukurannya. */
    suspend fun buildExportFile(): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        val bundle = ExportBundle(
            workspaces = repo.allWorkspacesOnce(),
            chapters = repo.allChaptersOnce(),
            materials = repo.allMaterialsOnce(),
            tasks = repo.allTasksOnce()
        )
        val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, "ruangkerja_export_${System.currentTimeMillis()}.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("data.json"))
            zos.write(AppJson.encodeToString(bundle).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        file
    }

    /** Kebalikan dari buildExportFile — baca file .zip (lewat SAF, jadi terima Uri bukan path
     *  langsung) hasil "Ekspor Data", buka entry data.json di dalamnya, baru tulis ulang tiap
     *  item lewat insertXxx yang SAMA dipakai alur normal (upsert by id — lihat catatan di
     *  AppRepository.insertWorkspace). Artinya kalau ID-nya udah ada di HP ini, datanya KETIMPA
     *  sama isi file backup, bukan dobel — konfirmasi soal ini ditampilkan di UI (SettingsScreen)
     *  sebelum fungsi ini dipanggil. */
    suspend fun restoreFromFile(uri: android.net.Uri): Result<Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val input = app.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(java.io.IOException("File tidak bisa dibaca"))
            var jsonText: String? = null
            java.util.zip.ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "data.json") { jsonText = zis.bufferedReader(Charsets.UTF_8).readText(); break }
                    entry = zis.nextEntry
                }
            }
            val text = jsonText ?: return@withContext Result.failure(java.io.IOException("File zip tidak berisi data.json — pastikan ini hasil dari 'Ekspor Data'"))
            val bundle = AppJson.decodeFromString<ExportBundle>(text)
            bundle.workspaces.forEach { repo.insertWorkspace(it) }
            bundle.chapters.forEach { repo.insertChapter(it) }
            bundle.materials.forEach { repo.insertMaterial(it) }
            bundle.tasks.forEach { repo.insertTask(it) }
            Result.success(bundle.workspaces.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // [FIX, 22 Sep — laporan William] Dulu ada isDrawerOpen di sini, dipakai RuangKerjaApp
    // buat nyembunyiin bottom nav bar Beranda selama drawer side-nya kebuka — soalnya dulu
    // drawer cuma ngebungkus konten NavHost, bukan Scaffold paling luar yang punya bottom
    // bar itu sendiri. Nyembunyiin/nampilin bottomBar ternyata JUSTRU sumber bug "digeser ke
    // bawah, animasinya berantakan" yang dilaporin William: toggle bottomBar bikin Scaffold
    // paling luar ngitung ulang padding-nya SAAT ITU JUGA (bertabrakan sama animasi slide
    // drawer). Sekarang ModalNavigationDrawer-nya dipindah ke RuangKerjaApp, ngebungkus
    // Scaffold paling luar (bottomBar ikut ke-scrim, gak pernah di-toggle lagi) — isDrawerOpen
    // gak kepake lagi, dihapus total.
    //
    // showCreateDraft & draftEditTarget pindah ke sini (dari local state WorkspaceListScreen)
    // karena sekarang dipicu dari 2 tempat beda file: FAB + popup saran ekosistem draft (masih
    // di WorkspaceListScreen.kt) DAN side drawer (sekarang di RuangKerjaApp/MainActivity.kt) —
    // keduanya harus nyetel state yang sama biar DraftEditorOverlay-nya (tetap di-render di
    // WorkspaceListScreen) kebuka.
    private val _showCreateDraft = MutableStateFlow(false)
    val showCreateDraft: StateFlow<Boolean> = _showCreateDraft
    fun setShowCreateDraft(show: Boolean) { _showCreateDraft.value = show }

    private val _draftEditTarget = MutableStateFlow<Draft?>(null)
    val draftEditTarget: StateFlow<Draft?> = _draftEditTarget
    fun setDraftEditTarget(draft: Draft?) { _draftEditTarget.value = draft }

    // ── Workspaces ────────────────────────────────────────
    val isReady = repo.isReady().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val workspaces = repo.workspaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val drafts = repo.drafts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // [BARU, Fase 2 ekosistem draft] Riwayat buat layar Notification Center.
    val suggestionHistory = repo.suggestions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addWorkspace(name: String, desc: String) = viewModelScope.launch {
        repo.insertWorkspace(Workspace(name = name, description = desc))
    }
    fun deleteDraft(id: String) = viewModelScope.launch { repo.deleteDraft(id) }
    // [BARU] Dipakai DraftEditorOverlay buat autosave — satu pintu, gak peduli draft baru atau
    // lama (lihat catatan lengkap di AppRepository.upsertDraft()). Menggantikan addDraft() &
    // updateDraft() lama yang sekarang gak kepake lagi dari UI manapun.
    fun upsertDraft(d: Draft) = viewModelScope.launch { repo.upsertDraft(d) }

    // ── [BARU, Fase 1+2] Ekosistem draft — draft yang kata kuncinya cocok sama nama salah satu
    // workspace disaranin buat dipindahin (lihat util/DraftMatcher.kt). draftMatch null = gak
    // ada saran ditampilin; non-null = pop-up 4-aksi (Lihat/Pindahkan/Nanti/Abaikan) lagi
    // ditampilin di WorkspaceListScreen. chapterPicker dipakai kalau workspace tujuan punya
    // >1 bab. Tiap transisi status (SEEN/SNOOZED/DISMISSED/ACCEPTED) direkam ke DraftSuggestion
    // (lihat suggestionHistory di atas) buat layar Notification Center — id-nya STABIL
    // (suggestionId) biar draft yang diedit dikit UPDATE entri lama, bukan bikin baru
    // (spek "duplicate prevention"). ──
    private val _draftMatch = MutableStateFlow<Pair<Draft, DraftMatch>?>(null)
    val draftMatch: StateFlow<Pair<Draft, DraftMatch>?> = _draftMatch

    private val _chapterPicker = MutableStateFlow<Triple<Draft, Workspace, List<Chapter>>?>(null)
    val chapterPicker: StateFlow<Triple<Draft, Workspace, List<Chapter>>?> = _chapterPicker

    // [BARU, 18 Okt — ekosistem draft ronde 3, masukan William: "pemindahan custom... pindahin
    // sesuka hati"] Pintu masuk MANUAL — beda dari _draftMatch/_chapterPicker di atas yang
    // SELALU dipicu dari hasil auto-match (DraftMatcher), ini bisa dipicu dari draft APAPUN
    // kapan aja (tombol "Pindahkan" di drawer — lihat HomeDrawer.kt), mulai dari user milih
    // SENDIRI Ruang Kerja tujuannya. Begitu dipilih, funnel ke _chapterPicker yang SAMA persis
    // kayak alur auto-match (reuse penuh — termasuk "Bab Baru" + prompt nama di bawah), gak ada
    // dialog/logic baru buat tahap pilih-Bab-nya.
    private val _manualMoveWorkspacePicker = MutableStateFlow<Draft?>(null)
    val manualMoveWorkspacePicker: StateFlow<Draft?> = _manualMoveWorkspacePicker

    fun startManualMove(draft: Draft) { _manualMoveWorkspacePicker.value = draft }
    fun cancelManualMove() { _manualMoveWorkspacePicker.value = null }

    fun pickWorkspaceForManualMove(workspace: Workspace) {
        val draft = _manualMoveWorkspacePicker.value ?: return
        _manualMoveWorkspacePicker.value = null
        viewModelScope.launch {
            val chapters = repo.allChaptersOnce().filter { it.workspaceId == workspace.id }
            _chapterPicker.value = Triple(draft, workspace, chapters)
        }
    }

    // [BARU, 18 Okt — ekosistem draft ronde 3, masukan William: "ditanya... nama buatan sendiri
    // atau dari sistem"] Dulu moveDraftToWorkspace()/moveDraftToNewChapter() LANGSUNG bikin Bab
    // baru nama hardcode "Catatan Cepat" tanpa nanya. Sekarang dua-duanya mampir ke sini dulu —
    // nyodorin nama HEURISTIK (generateChapterName, non-AI — lihat util/ChapterNamer.kt) buat
    // dikonfirmasi/diedit user lewat dialog (confirmNewChapterName), BUKAN langsung dieksekusi.
    // (NewChapterPrompt deklarasinya top-level, bukan di sini — lihat atas class ini — soalnya
    // perlu diakses dari WorkspaceListScreen.kt buat render dialognya, StateFlow publik gak
    // boleh "bocorin" tipe private.)
    private val _newChapterPrompt = MutableStateFlow<NewChapterPrompt?>(null)
    val newChapterPrompt: StateFlow<NewChapterPrompt?> = _newChapterPrompt

    private fun promptNewChapterName(draft: Draft, workspace: Workspace, confidence: MatchConfidence, orderIndex: Int) {
        _newChapterPrompt.value = NewChapterPrompt(draft, workspace, confidence, orderIndex, generateChapterName(draft))
    }

    /** User konfirmasi (atau edit) nama Bab baru di dialog — baru di sini Bab-nya BENERAN
     *  dibikin & draft-nya dipindah. Nama kosong (user ngehapus semuanya) jatuh balik ke saran
     *  heuristik, bukan dibiarin jadi Bab tanpa nama. */
    fun confirmNewChapterName(name: String) {
        val p = _newChapterPrompt.value ?: return
        _newChapterPrompt.value = null
        viewModelScope.launch {
            val chapter = Chapter(workspaceId = p.workspace.id, name = name.trim().ifBlank { p.suggestedName }, orderIndex = p.orderIndex)
            repo.insertChapter(chapter)
            finishMoveDraft(p.draft, p.workspace, p.confidence, chapter)
        }
    }

    /** Dialog nama Bab ditutup TANPA konfirmasi (back/Batal) — draft tetap draft, gak ada Bab
     *  yang kebikin, gak ada yang dipindah. */
    fun cancelNewChapterPrompt() { _newChapterPrompt.value = null }

    // [BARU, 17 Okt — masukan William: pop-up/notifikasi jangan instan] Sekali per app dibuka
    // (ViewModel baru, bukan tiap balik ke Beranda — ViewModel-nya nempel sepanjang hidup
    // Activity), nunggu data inti kebaca (repo.isReady()) terus cek draft mana yang udah
    // "didiemin" cukup lama buat akhirnya ditunjukin — lihat checkDormantMatches() di bawah.
    init {
        viewModelScope.launch {
            repo.isReady().first { it }
            checkDormantMatches()
            // [BARU, 16 Nov — kartu "Lanjutkan"/akses-cepat "AI" di Beranda] Pulihin bab terakhir
            // dibuka dari SharedPreferences pas app baru nyala (lihat resolveLastChapterCard &
            // catatan "last_chapter_id" di dekat selectChapter()).
            // [HOTFIX, 17 Nov] try/catch tambahan di titik panggil ini juga (bukan cuma di DALAM
            // resolveLastChapterCard) — jaga-jaga kalau prefs.getString() sendiri yang bermasalah,
            // bukan isi fungsinya.
            try {
                resolveLastChapterCard(prefs.getString("last_chapter_id", null))
            } catch (e: Exception) { /* non-fatal, kartu "Lanjutkan" sekadar gak ke-isi */ }
        }
    }

    private fun suggestionId(draftId: String, workspaceId: String) = "${draftId}_$workspaceId"

    /** Update-in-place kalau entrinya udah ada (createdAt & field lain yang gak disebut tetep
     *  kebawa), bikin baru kalau belum. Dipanggil dari semua titik transisi status di bawah. */
    private suspend fun upsertSuggestionStatus(
        draft: Draft, workspace: Workspace, confidence: MatchConfidence, status: String,
        resolvedChapterName: String? = null
    ) {
        val id = suggestionId(draft.id, workspace.id)
        val existing = repo.suggestions().first().firstOrNull { it.id == id }
        repo.upsertSuggestion(
            (existing ?: DraftSuggestion(
                id = id, draftId = draft.id, draftTitleSnapshot = draft.title,
                draftContentSnapshot = draft.content,
                workspaceId = workspace.id, workspaceNameSnapshot = workspace.name,
                confidence = confidence.name, status = status
            )).copy(
                draftTitleSnapshot = draft.title, draftContentSnapshot = draft.content,
                workspaceNameSnapshot = workspace.name,
                confidence = confidence.name, status = status,
                resolvedChapterNameSnapshot = resolvedChapterName ?: existing?.resolvedChapterNameSnapshot
            )
        )
    }

    /** Dipanggil abis draft ditutup (commitAndClose di DraftEditorOverlay) — BUKAN tiap
     *  autosave, biar gak nyeletuk pop-up di tengah user masih ngetik. Skip total kalau
     *  draft ini lagi di-snooze ("Nanti" — lihat snoozeDraftMatch()). */
    // [UBAH] Dulu `fun` biasa yang nge-launch coroutine sendiri buat nulis suggestion (fire-
    // and-forget) — penulisannya bisa aja belum kelar pas draft editor udah kebuka ketutup
    // dan user langsung buka halaman Notifikasi, jadi kelihatan "delay" padahal cuma balapan
    // sama coroutine yang belum selesai. Sekarang `suspend fun`, DITUNGGU sampai selesai
    // nulis sebelum draft editor beneran ketutup — lihat saveDraftAndCheckMatch() di bawah,
    // dipanggil dari commitAndClose() yang sekarang juga nunggu (lewat rememberCoroutineScope).
    // [UBAH, 17 Okt — masukan William: "kalau udah ketemu langsung diminta pindah, harusnya
    // tunggu sampai draft didiemin dulu"] Dulu HIGH confidence langsung munculin pop-up di
    // sini juga (lihat histori git/CHANGELOG kalau perlu versi lama). Sekarang checkDraftMatch
    // CUMA nyatet match-nya (status PENDING) — gak peduli HIGH/MEDIUM, dua-duanya DIAM dulu.
    // Pop-up/notifikasi beneran baru nongol belakangan lewat checkDormantMatches() (dalam-app)
    // & DraftSuggestionCheckReceiver (notif Android), begitu draft ini "didiemin" >=7 hari
    // (DRAFT_DORMANT_MS) — lihat komentar di util/DraftMatcher.kt.
    suspend fun checkDraftMatch(draft: Draft) {
        if (System.currentTimeMillis() < draft.snoozedMatchUntil) return
        val match = findMatchingWorkspace(draft, repo.allWorkspacesOnce()) ?: return
        upsertSuggestionStatus(draft, match.workspace, match.confidence, "PENDING")
    }

    /** [BARU, 17 Okt — ekosistem draft timing] Dipanggil SEKALI tiap app dibuka (lihat init{}
     *  di atas kelas ini). Saran yang masih PENDING (match ketemu, tapi belum PERNAH
     *  ditunjukin ke user) baru dimunculin pop-up-nya begitu draft sumbernya "didiemin"
     *  (Draft.updatedAt) >=DRAFT_DORMANT_MS DAN gak lagi di-snooze. Cuma SATU yang ditunjukin
     *  per app-open (pola sama kayak pop-up modal lainnya — satu dalam satu waktu); yang lain
     *  tetap PENDING, actionable dari Notification Center, nunggu giliran app-open berikutnya.
     *  Transisi PENDING→SEEN di sini PERSIS sama maknanya kayak transisi HIGH-instan yang lama
     *  (SEEN = "udah pernah ditunjukin ke user"), cuma sekarang dipicu oleh WAKTU, bukan INSTAN. */
    private suspend fun checkDormantMatches() {
        if (_draftMatch.value != null) return
        val now = System.currentTimeMillis()
        val drafts = repo.drafts().first()
        val workspaces = repo.allWorkspacesOnce()
        val pending = repo.suggestions().first().filter { it.status == "PENDING" }
        for (s in pending) {
            val draft = drafts.firstOrNull { it.id == s.draftId } ?: continue
            if (now - draft.updatedAt < DRAFT_DORMANT_MS) continue
            if (now < draft.snoozedMatchUntil) continue
            val ws = workspaces.firstOrNull { it.id == s.workspaceId } ?: continue
            val confidence = if (s.confidence == "HIGH") MatchConfidence.HIGH else MatchConfidence.MEDIUM
            _draftMatch.value = draft to DraftMatch(ws, confidence)
            upsertSuggestionStatus(draft, ws, confidence, "SEEN")
            break
        }
    }

    /** Dipanggil dari commitAndClose() — nyimpen draft (kalau isinya beneran berubah) DAN
     *  ngecek kecocokannya, baru manggil [onDone] (biasanya onDismiss() si Composable) SETELAH
     *  keduanya kelar. Ini mastiin saran yang muncul (kalau ada) udah bener-bener kesimpen di
     *  penyimpanan & langsung kebaca di Notification Center begitu user balik ke Beranda.
     *  [FIX regresi] Sengaja pakai viewModelScope di sini, BUKAN rememberCoroutineScope() milik
     *  DraftEditorOverlay — scope Composable itu ke-cancel begitu Dialog-nya di-dismiss sistem
     *  (misal back button Android), yang bisa kejadian SEBELUM coroutine-nya sempat kelar,
     *  motong checkDraftMatch di tengah jalan (persis balik ke bug "trigger kadang ilang" yang
     *  udah pernah difix). viewModelScope umurnya ikut ViewModel, bukan ikut hidup-matinya
     *  Dialog, jadi nggak keputus walau dialog-nya udah keburu ilang dari layar. */
    fun saveDraftAndCheckMatch(draft: Draft, shouldSave: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            if (shouldSave) repo.upsertDraft(draft.copy(updatedAt = System.currentTimeMillis()))
            checkDraftMatch(draft)
            onDone()
        }
    }

    /** Notification Center — tap riwayat yang statusnya masih bisa ditindaklanjuti
     *  (SEEN/SNOOZED) buat munculin lagi pop-up 4-aksi yang sama. Kalau draft atau workspace
     *  aslinya udah kehapus, diam-diam gak ngapa-ngapain — histori tetap kebaca apa adanya,
     *  cuma gak bisa ditindaklanjuti lagi. */
    fun reopenSuggestion(s: DraftSuggestion) {
        if (s.status != "SEEN" && s.status != "SNOOZED" && s.status != "PENDING") return
        viewModelScope.launch {
            val draft = repo.drafts().first().firstOrNull { it.id == s.draftId } ?: return@launch
            val ws = repo.allWorkspacesOnce().firstOrNull { it.id == s.workspaceId } ?: return@launch
            val confidence = if (s.confidence == "HIGH") MatchConfidence.HIGH else MatchConfidence.MEDIUM
            _draftMatch.value = draft to DraftMatch(ws, confidence)
        }
    }

    /** "Lihat" — cuma nutup pop-up-nya, BUKAN nge-dismiss/snooze apapun: kalau draft-nya
     *  ditutup lagi tanpa diedit, commitAndClose skip cek (isinya sama persis kayak
     *  terakhir disimpan) jadi saran yang sama gak nongol ulang — tapi kalau diedit ke arah
     *  yang beda, tetep bisa disaranin lagi. UI yang manggil ini JUGA yang buka draft-nya. */
    fun clearDraftMatchPopup() { _draftMatch.value = null }

    /** "Nanti" (snooze) — BEDA dari Abaikan: ini nunda SEMUA saran buat draft ini (bukan cuma
     *  pasangan sama workspace ini) selama beberapa jam, karena user lagi gak mau mikirin
     *  soal ini dulu — bukan berarti sarannya salah (lihat Draft.snoozedMatchUntil). */
    fun snoozeDraftMatch() {
        val (draft, match) = _draftMatch.value ?: return
        _draftMatch.value = null
        val sixHours = 6 * 60 * 60 * 1000L
        viewModelScope.launch {
            repo.upsertDraft(draft.copy(snoozedMatchUntil = System.currentTimeMillis() + sixHours))
            upsertSuggestionStatus(draft, match.workspace, match.confidence, "SNOOZED")
        }
    }

    /** "Abaikan" — catet id workspace-nya di draft biar saran ke PASANGAN draft+workspace
     *  ini spesifik gak nongol lagi (draft cocok ke workspace LAIN tetap bisa disaranin).
     *  [FIX, 16 Okt — temuan audit notifikasi #2] Dulu `dismissedMatchWorkspaceId = ...` (field
     *  tunggal) — NIMPA dismissal sebelumnya kalau draft ini pernah di-"Abaikan" ke workspace lain
     *  duluan. Sekarang nambah ke Set (dismissedMatchWorkspaceIds), jadi semua yang pernah
     *  di-"Abaikan" tetap diinget, bukan cuma yang paling baru. */
    fun dismissDraftMatch() {
        val (draft, match) = _draftMatch.value ?: return
        _draftMatch.value = null
        viewModelScope.launch {
            repo.upsertDraft(draft.copy(dismissedMatchWorkspaceIds = draft.dismissedMatchWorkspaceIds + match.workspace.id))
            upsertSuggestionStatus(draft, match.workspace, match.confidence, "DISMISSED")
        }
    }

    /** "Pindahkan" — kalau workspace tujuan cuma punya 1 bab, langsung pindah ke situ. Kalau
     *  punya >1 bab, munculin chapterPicker dulu biar user milih bab yang relevan, bukan asal
     *  dump ke satu bab generik padahal ada bab lain yang lebih pas. Kalau workspace-nya masih
     *  KOSONG sama sekali (0 bab) — [UBAH, 18 Okt, lihat promptNewChapterName()] dulu LANGSUNG
     *  bikin Bab "Catatan Cepat" hardcode, sekarang mampir nanya nama dulu. */
    fun moveDraftToWorkspace() {
        val (draft, match) = _draftMatch.value ?: return
        _draftMatch.value = null
        viewModelScope.launch {
            val siblingChapters = repo.allChaptersOnce().filter { it.workspaceId == match.workspace.id }
            if (siblingChapters.size > 1) {
                _chapterPicker.value = Triple(draft, match.workspace, siblingChapters)
            } else {
                val chapter = siblingChapters.firstOrNull()
                if (chapter != null) {
                    finishMoveDraft(draft, match.workspace, match.confidence, chapter)
                } else {
                    promptNewChapterName(draft, match.workspace, match.confidence, 0)
                }
            }
        }
    }

    /** Chapter picker ditutup TANPA milih bab (back/tap luar) — draft tetep draft, gak jadi dipindah. */
    fun cancelChapterPicker() { _chapterPicker.value = null }

    // [DIGABUNG, audit duplikasi 2 Okt] Dulu ekspresi ini disalin di moveDraftToChapter & moveDraftToNewChapter.
    private suspend fun suggestionConfidence(draftId: String, workspaceId: String): MatchConfidence =
        repo.suggestions().first().firstOrNull { it.id == suggestionId(draftId, workspaceId) }
            ?.let { if (it.confidence == "HIGH") MatchConfidence.HIGH else MatchConfidence.MEDIUM } ?: MatchConfidence.MEDIUM

    /** User milih bab spesifik di chapterPicker. */
    fun moveDraftToChapter(chapter: Chapter) {
        val (draft, ws, _) = _chapterPicker.value ?: return
        _chapterPicker.value = null
        viewModelScope.launch {
            val confidence = suggestionConfidence(draft.id, ws.id)
            finishMoveDraft(draft, ws, confidence, chapter)
        }
    }

    /** User milih "Bab baru" di chapterPicker — workspace-nya udah punya bab lain (atau user
     *  masuk sini lewat pindah manual, lihat pickWorkspaceForManualMove), tapi mau taruh draft
     *  ini di bab baru daripada nyampur ke bab yang udah ada. [UBAH, 18 Okt] Dulu LANGSUNG bikin
     *  Bab "Catatan Cepat" hardcode di sini, sekarang mampir nanya nama dulu lewat
     *  promptNewChapterName() — Bab-nya baru BENERAN dibikin pas user konfirmasi. */
    fun moveDraftToNewChapter() {
        val (draft, ws, existing) = _chapterPicker.value ?: return
        _chapterPicker.value = null
        viewModelScope.launch {
            val confidence = suggestionConfidence(draft.id, ws.id)
            promptNewChapterName(draft, ws, confidence, existing.size)
        }
    }

    /** Draft jadi Material baru di bawah [chapter], draft ASLINYA dihapus (pindah, bukan
     *  disalin/digandakan), suggestion-nya direkam ACCEPTED + nama bab tujuan buat histori. */
    private suspend fun finishMoveDraft(draft: Draft, workspace: Workspace, confidence: MatchConfidence, chapter: Chapter) {
        repo.insertMaterial(Material(chapterId = chapter.id, title = draft.title, content = draft.content))
        repo.deleteDraft(draft.id)
        upsertSuggestionStatus(draft, workspace, confidence, "ACCEPTED", resolvedChapterName = chapter.name)
    }
    fun deleteWorkspace(id: String) = viewModelScope.launch { repo.deleteWorkspace(id) }
    /** `insertWorkspace` di LocalStorage sifatnya upsert (replace by id), jadi update generik
     *  (ubah nama/warna) cukup lewat ini — nggak perlu method updateWorkspace terpisah. */
    fun updateWorkspace(w: Workspace) = viewModelScope.launch { repo.insertWorkspace(w) }
    fun toggleWorkspacePin(w: Workspace) = viewModelScope.launch { repo.insertWorkspace(w.copy(pinned = !w.pinned)) }

    fun duplicateWorkspace(ws: Workspace) = viewModelScope.launch {
        val newWsId = java.util.UUID.randomUUID().toString()
        repo.insertWorkspace(ws.copy(id = newWsId, name = "${ws.name} (Salinan)", pinned = false, createdAt = System.currentTimeMillis()))
        val srcChapters = repo.allChaptersOnce().filter { it.workspaceId == ws.id }.sortedBy { it.orderIndex }
        srcChapters.forEachIndexed { idx, ch -> copyChapterDeep(ch, newWsId, idx, renameSuffix = false) }
    }

    @Serializable
    private data class WorkspaceExportBundle(
        val exportedAt: Long = System.currentTimeMillis(),
        val workspace: Workspace,
        val chapters: List<Chapter>,
        val materials: List<Material>,
        val flashcards: Map<String, List<FlashcardData>>,
        val tasks: List<Task>
    )

    /** Sama kayak buildExportFile tapi diperkecil ke satu workspace aja, plus ikut nyertain
     *  flashcard (yang nggak ada di ExportBundle global) biar backup satu ruang kerja lengkap. */
    suspend fun buildWorkspaceExportFile(wsId: String): java.io.File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ws = repo.allWorkspacesOnce().find { it.id == wsId } ?: return@withContext null
        val chs = repo.allChaptersOnce().filter { it.workspaceId == wsId }
        val chIds = chs.map { it.id }.toSet()
        val mats = repo.allMaterialsOnce().filter { it.chapterId in chIds }
        val cards = chIds.associateWith { repo.flashcards(it) }.filterValues { it.isNotEmpty() }
        val relatedTasks = repo.allTasksOnce().filter { it.workspaceId == wsId }
        val bundle = WorkspaceExportBundle(workspace = ws, chapters = chs, materials = mats, flashcards = cards, tasks = relatedTasks)
        val app = getApplication<Application>()
        val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
        val safe = ws.name.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).ifBlank { "workspace" }
        val file = java.io.File(dir, "ruangkerja_${safe}_${System.currentTimeMillis()}.json")
        file.writeText(AppJson.encodeToString(bundle))
        file
    }

    // ── Laporan Belajar (PDF) ───────────────────────────────
    /** Kumpulin data satu workspace (bab + MATERI LENGKAP per bab, bukan cuma hitungannya lagi
     *  — permintaan user biar laporan isinya konten belajar beneran, bukan cuplikan ringkasan
     *  AI) lalu render jadi PDF, simpen LANGSUNG ke Documents/Ruang Kerja/ publik lewat
     *  MediaStore. Pola sama kayak buildWorkspaceExportFile di atas, bedanya hasil akhirnya
     *  dokumen rapi bukan JSON mentah. Balikin null di Android <10 (lihat catatan di
     *  ReportPdfBuilder.buildToDocuments). */
    suspend fun buildWorkspaceReportPdf(wsId: String): android.net.Uri? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ws = repo.allWorkspacesOnce().find { it.id == wsId } ?: return@withContext null
        val chs = repo.allChaptersOnce().filter { it.workspaceId == wsId }.sortedBy { it.orderIndex }
        val mats = repo.allMaterialsOnce()
        val chapterData = chs.map { ch ->
            ChapterReportData(
                chapter = ch,
                materials = mats.filter { it.chapterId == ch.id }.sortedBy { it.createdAt },
                flashcardCount = repo.flashcards(ch.id).size
            )
        }
        val relatedTasks = repo.allTasksOnce().filter { it.workspaceId == wsId }
        val data = WorkspaceReportData(workspace = ws, chapters = chapterData, tasks = relatedTasks)
        ReportPdfBuilder.buildToDocuments(getApplication<Application>(), data)
    }

    // ── Chapters ──────────────────────────────────────────
    private val _wsId = MutableStateFlow<String?>(null)
    fun selectWorkspace(id: String) { _wsId.value = id }

    val chapters = _wsId.flatMapLatest { if (it != null) repo.chapters(it) else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addChapter(wsId: String, name: String, desc: String = "") = viewModelScope.launch {
        val idx = repo.chapterCount(wsId)
        repo.insertChapter(Chapter(workspaceId = wsId, name = name, description = desc, orderIndex = idx))
    }
    fun deleteChapter(id: String) = viewModelScope.launch { repo.deleteChapter(id) }

    /** Inti insert banyak bab sekaligus, orderIndex dihitung SEKALI di awal lalu naik manual
     *  per item — bukan query ulang tiap insert. `suspend` biasa (bukan viewModelScope.launch)
     *  supaya bisa langsung di-`await` dari dalam coroutine lain (generateChaptersFromTopic/Text)
     *  tanpa jadi child coroutine terpisah yang nggak ke-tunggu. */
    private suspend fun insertChaptersBulk(wsId: String, items: List<Pair<String, String>>) {
        var idx = repo.chapterCount(wsId)
        items.forEach { (name, desc) ->
            if (name.isNotBlank()) {
                repo.insertChapter(Chapter(workspaceId = wsId, name = name.trim(), description = desc.trim(), orderIndex = idx))
                idx++
            }
        }
    }
    /** Entry point buat UI — dipakai jalur manual (tempel daftar / nomor otomatis) yang
     *  nggak lewat AI, jadi nggak butuh loading state khusus kayak dua fungsi generate di bawah. */
    fun addChaptersBulk(wsId: String, items: List<Pair<String, String>>) = viewModelScope.launch {
        insertChaptersBulk(wsId, items)
    }

    /** Update generik buat Edit (nama/deskripsi) — pemanggil kirim Chapter hasil `.copy()` biar
     *  field lain (summary/notes/mindMapJson/orderIndex) nggak ikut ketiban default kosong. */
    fun updateChapterInfo(c: Chapter) = viewModelScope.launch { repo.updateChapter(c) }

    fun moveChapter(ch: Chapter, targetWsId: String) = viewModelScope.launch {
        repo.updateChapter(ch.copy(workspaceId = targetWsId, orderIndex = repo.chapterCount(targetWsId)))
    }

    /** Deep-copy satu bab (bab + semua materi + flashcard) ke workspace tujuan — dipakai baik
     *  buat Duplikat bab tunggal (target = workspace yang sama) maupun buat duplicateWorkspace
     *  (dipanggil berkali-kali, sekali per bab, ke workspace baru). */
    private suspend fun copyChapterDeep(src: Chapter, targetWsId: String, targetOrderIndex: Int, renameSuffix: Boolean): Chapter {
        val newId = java.util.UUID.randomUUID().toString()
        val copy = src.copy(
            id = newId, workspaceId = targetWsId, orderIndex = targetOrderIndex,
            name = if (renameSuffix) "${src.name} (Salinan)" else src.name,
            createdAt = System.currentTimeMillis()
        )
        repo.insertChapter(copy)
        repo.materialsOnce(src.id).forEach { m ->
            repo.insertMaterial(m.copy(id = java.util.UUID.randomUUID().toString(), chapterId = newId, createdAt = System.currentTimeMillis()))
        }
        val cards = repo.flashcards(src.id)
        if (cards.isNotEmpty()) repo.saveFlashcards(newId, cards.map { it.copy(id = java.util.UUID.randomUUID().toString()) })
        return copy
    }

    fun duplicateChapter(ch: Chapter) = viewModelScope.launch {
        copyChapterDeep(ch, ch.workspaceId, repo.chapterCount(ch.workspaceId), renameSuffix = true)
    }

    @Serializable
    private data class ChapterExportBundle(
        val exportedAt: Long = System.currentTimeMillis(),
        val chapter: Chapter,
        val materials: List<Material>,
        val flashcards: List<FlashcardData>
    )

    suspend fun buildChapterExportFile(chId: String): java.io.File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ch = repo.chapter(chId) ?: return@withContext null
        val bundle = ChapterExportBundle(chapter = ch, materials = repo.materialsOnce(chId), flashcards = repo.flashcards(chId))
        val app = getApplication<Application>()
        val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
        val safe = ch.name.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).ifBlank { "bab" }
        val file = java.io.File(dir, "ruangkerja_bab_${safe}_${System.currentTimeMillis()}.json")
        file.writeText(AppJson.encodeToString(bundle))
        file
    }

    // ── Chapter detail ────────────────────────────────────
    private val _chId    = MutableStateFlow<String?>(null)
    private val _chapter = MutableStateFlow<Chapter?>(null)
    val chapter: StateFlow<Chapter?> = _chapter

    fun selectChapter(id: String) {
        // [FIX] Laporan William: kerasa delay/gak stabil pas pindah halaman ke Bab. Akar
        // masalahnya BUKAN animasi NavHost — _chapter cuma ke-update DI DALAM
        // viewModelScope.launch di bawah, sementara ChapterDetailScreen mulai render DULUAN
        // begitu transisi NavHost kelar (baca _chapter.value lewat collectAsStateWithLifecycle
        // apa adanya). Kalau sebelumnya lagi buka Bab lain, _chapter.value MASIH nyimpen Bab
        // SEBELUMNYA sampe coroutine ini kelar — bukan cuma lambat, tapi SEMPET nampilin konten
        // Bab yang SALAH sekilas sebelum lompat ke Bab yang benar. Dikosongin SINKRON di sini
        // (sebelum launch), HANYA kalau emang ganti ke Bab lain (bukan re-select Bab yang
        // sama) — ChapterDetailScreen jatuh ke jalur spinner yang udah ada (ch == null), bukan
        // nampilin data basi.
        if (_chapter.value?.id != id) _chapter.value = null
        _chId.value = id
        _sumState.value = AiState()
        _mindMapState.value = AiState()
        _cardsState.value = AiState()
        markStudiedToday()
        // [BARU, 16 Nov — kartu "Lanjutkan"/akses-cepat "AI" di Beranda] selectChapter() ini
        // SATU-SATUNYA titik "user membuka sebuah bab" di seluruh app — sama alasannya kayak
        // markStudiedToday() di atas (pola persisted-pref sama persis), jadi dicatat di sini
        // juga. _lastChapterCard.value di-update di bawah, DI DALAM launch yang sama yang udah
        // resolve `ch` — sengaja TIDAK manggil resolveLastChapterCard(id) terpisah di sini biar
        // gak ada dua pemanggilan repo.chapter(id) yang redundan buat kejadian yang sama.
        prefs.edit().putString("last_chapter_id", id).apply()
        viewModelScope.launch {
            val ch = repo.chapter(id)
            _chapter.value = ch
            _mindMap.value = ch?.mindMapJson?.let { MindMapCodec.decode(it) }
            _cards.value = repo.flashcards(id).map { Flashcard(it.id, it.front, it.back) }
            // [BARU, 13 Nov — permintaan William: histori Diskusi "kayak ai chat pada umumnya"]
            // Buka ulang percakapan TERAKHIR bab ini (kalau ada), bukan selalu kosong — biar
            // user lanjut dari mana berhenti. null (belum ada thread sama sekali di bab ini)
            // dibiarkan null — sendChat() yang nanti bikin thread baru begitu pesan pertama
            // dikirim (lazy-create, lihat catatan di sana).
            _activeThreadId.value = repo.chatThreadsOnce(id).firstOrNull()?.id
            // [HOTFIX, 17 Nov] Dibungkus try/catch — kegagalan di sini (kartu "Lanjutkan") TIDAK
            // boleh ikut gagalin pengisian _chapter/_mindMap/_cards/_activeThreadId di atas, yang
            // SEMUANYA sudah berhasil dihitung duluan di coroutine yang sama sebelum baris ini.
            try {
                val ws = ch?.workspaceId?.let { wsId -> repo.allWorkspacesOnce().find { it.id == wsId } }
                _lastChapterCard.value = if (ch != null && ws != null) ch to ws else null
            } catch (e: Exception) { /* non-fatal, kartu "Lanjutkan" sekadar gak ke-update */ }
        }
    }

    fun clearTransientState() {
        // [UBAH, 13 Nov] _chat (flat in-memory list) diganti _activeThreadId — lihat bagian
        // "AI: Diskusi" di bawah. Dikosongin SINKRON di sini (sebelum selectChapter() yang
        // menyusul resolve thread TERAKHIR bab baru secara async) — alasan SAMA PERSIS dengan
        // _chapter di atas: biar nggak sempat nampilin sekilas riwayat chat bab SEBELUMNYA.
        _activeThreadId.value = null
        _mindMap.value = null
        _cards.value = emptyList()
        _sumState.value = AiState()
    }

    private fun refreshChapter() = viewModelScope.launch { _chId.value?.let { _chapter.value = repo.chapter(it) } }

    val materials = _chId.flatMapLatest { if (it != null) repo.materials(it) else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Dipakai NotebookEditor buat aksi AI di teks terpilih. Suspend biasa (bukan viewModelScope.launch)
     *  karena pemanggilnya perlu menunggu hasilnya buat langsung diselipkan ke teks. */
    // [FIX] Dulu exception ditelan jadi null, dan NotebookEditor cuma diam kalau hasilnya null —
    // aksi AI yang gagal tampak "tidak terjadi apa-apa", mustahil dibedakan koneksi vs API. Sekarang
    // kegagalan dilempar apa adanya (AiException dengan pesan siap tampil); NotebookEditor yang
    // menangkap dan menampilkannya lewat Toast.
    suspend fun runTextAction(action: String, text: String): String? = ai.textAction(action, text).trim()

    // [FIX] Dulu materi baru CUMA di-insert, gak pernah manggil reindexMaterial() — beda dari
    // updateMaterial() di bawah yang sudah benar. Efeknya: materi yang baru dibuat gak pernah
    // punya chunk/embedding buat RAG (Diskusi grounded) sampai materinya di-edit ulang minimal
    // sekali. Sekarang disamain: insert dulu, baru reindex pakai id yang baru di-generate.
    fun addMaterial(chId: String, content: String, title: String = "") = viewModelScope.launch {
        val material = Material(chapterId = chId, title = title, content = content)
        repo.insertMaterial(material)
        reindexMaterial(material.id, chId, content)
    }

    /**
     * [DIUBAH] Impor materi dari URL: ekstrak via Jina Reader, lalu rapikan struktur pakai
     * MarkdownStructureCleaner — MURNI algoritma teks (regex/heuristik), NOL panggilan AI, NOL
     * token, instan. Ini gantiin langkah AI "structureCleanup" yang sebelumnya otomatis jalan
     * tiap impor — untuk KASUS UMUM (rapiin nomor/sub-nomor, buang blok navigasi/link-list yang
     * ikut ke-scrape) heuristik ini udah cukup, dan jauh lebih murah + gak beresiko kepotong di
     * dokumen panjang kayak yang sempat kejadian.
     *
     * Yang TIDAK dicakup heuristik ini: mengubah teks yang kehilangan struktur tabelnya balik
     * jadi tabel Markdown (butuh pemahaman semantik). Action AI "structureCleanup" (AiPrompts.kt)
     * masih ada di kode buat itu — TINGGAL dipanggil manual lewat ai.textAction("structureCleanup",
     * text) kalau nanti mau disediakan sebagai tombol opsional di editor, tapi SENGAJA TIDAK
     * otomatis lagi di sini.
     */
    suspend fun importFromUrl(chId: String, url: String) {
        val raw = com.ruangkerja.companion.network.JinaExtractor.extract(url)
        val cleaned = com.ruangkerja.companion.util.MarkdownStructureCleaner.clean(raw, sourceUrl = url)
        addMaterial(chId, cleaned)
    }
    fun updateMaterial(id: String, chId: String, content: String, createdAt: Long, title: String = "") = viewModelScope.launch {
        val pinned = repo.materialsOnce(chId).find { it.id == id }?.pinned ?: false
        repo.updateMaterial(Material(id = id, chapterId = chId, title = title, content = content, pinned = pinned, createdAt = createdAt))
        reindexMaterial(id, chId, content)
    }

    // [BARU] Bikin ulang embedding materi buat RAG (Diskusi grounded) — dipanggil abis materi
    // disimpan, TAPI di coroutine TERPISAH (bukan nunggu di dalam updateMaterial di atas) biar
    // simpen catatan gak pernah nunggu panggilan jaringan/AI. Best-effort SEPENUHNYA: gagal
    // (offline/rate-limit/dst) ya didiamkan — chunk lama (kalau ada) TETAP ada, RAG buat materi
    // ini cuma belum ke-update sampe kesempatan berikutnya (materi ini diedit lagi). Ini bagian
    // dari keputusan "hybrid": lokal PRIMARY & app fungsi penuh tanpa RAG, RAG murni enhancement.
    private fun reindexMaterial(materialId: String, chapterId: String, content: String) = viewModelScope.launch {
        try {
            val pieces = chunkText(content)
            if (pieces.isEmpty()) { repo.replaceChunksForMaterial(materialId, emptyList()); return@launch }
            val vectors = ai.embed(pieces, "RETRIEVAL_DOCUMENT")
            // [KOREKSI, 11 Nov — "PLANNING IMPLEMENTASI RKR: PERBAIKAN RAG, CHUNKING, DAN
            // AUTOMATIC EMBEDDING" §3B] DULU embedding yang hilang/rusak cuma DIFILTER
            // (vectors.getOrElse(idx){emptyList()} lalu .filter{isNotEmpty()}) — jadi kegagalan
            // SEBAGIAN (mis. 8 dari 10 potongan berhasil di-embed, GeminiService.embedViaEdgeProxy
            // cuma ngecek embeddings.isNotEmpty(), BUKAN jumlahnya cocok vs texts.size — lihat
            // network/GeminiService.kt) tetap lolos dan MENGGANTI index lama yang lengkap dengan
            // yang baru tapi bolong. Index RAG materi ini jadi "setengah jadi" permanen sampai
            // materi diedit ulang & kebetulan embed-nya sukses penuh. Sekarang: index lama SAMA
            // SEKALI TIDAK DISENTUH (repo.replaceChunksForMaterial TIDAK dipanggil) kalau validasi
            // di bawah gagal di titik mana pun — exception yang sama (catch di bawah) atau
            // return@launch diam-diam PERSIS kayak kegagalan jaringan biasa, konsisten sama
            // prinsip "RAG murni enhancement, kegagalannya gak pernah bikin materi/Diskusi rusak".
            if (vectors.size != pieces.size) return@launch
            val chunks = pieces.mapIndexed { idx, txt ->
                MaterialChunk(materialId = materialId, chapterId = chapterId, chunkIndex = idx, text = txt, embedding = vectors[idx])
            }
            val allValid = chunks.all { c ->
                c.embedding.size == EXPECTED_EMBEDDING_DIMENSIONS && c.embedding.all { it.isFinite() }
            }
            if (!allValid) return@launch
            repo.replaceChunksForMaterial(materialId, chunks)
        } catch (_: Exception) { /* offline/rate-limit/dst — didiamkan, lihat komentar di atas */ }
    }
    fun deleteMaterial(id: String) = viewModelScope.launch { repo.deleteMaterial(id) }
    fun togglePinMaterial(m: Material) = viewModelScope.launch { repo.updateMaterial(m.copy(pinned = !m.pinned)) }

    // ── Editor Materi — dulu Dialog (window mengambang terpisah, `windowIsFloating=true`),
    // yang ternyata nanganin inset keyboard beda dari window biasa dan susah dibikin bener
    // (dua kali coba tambal window Dialog-nya masih gagal). Dipindah jadi destinasi navigasi
    // biasa di MainActivity — otomatis kewarisin setup edge-to-edge yang sama kayak Diskusi
    // (yang emang udah kebukti jalan), gantinya nyoba nge-replikasi manual di window terpisah. ──
    data class EditorTarget(val materialId: String, val chapterId: String, val title: String, val content: String, val createdAt: Long)
    private val _editorTarget = MutableStateFlow<EditorTarget?>(null)
    val editorTarget: StateFlow<EditorTarget?> = _editorTarget
    fun openEditor(materialId: String, chapterId: String, title: String, content: String, createdAt: Long) {
        _editorTarget.value = EditorTarget(materialId, chapterId, title, content, createdAt)
    }
    fun closeEditor() { _editorTarget.value = null }

    fun saveNotes(ch: Chapter, notes: String) = viewModelScope.launch { repo.updateChapter(ch.copy(notes = notes)); refreshChapter() }
    fun saveSummary(ch: Chapter, summary: String) = viewModelScope.launch { repo.updateChapter(ch.copy(summary = summary)); refreshChapter() }

    // ── AI: Ringkasan ─────────────────────────────────────
    private val _sumState = MutableStateFlow(AiState())
    val sumState: StateFlow<AiState> = _sumState

    fun summarize(ch: Chapter, extra: String = "") = viewModelScope.launch {
        _sumState.value = AiState(loading = true)
        try {
            val mats = repo.materialsOnce(ch.id)
            val text = (mats.map { it.content } + listOfNotNull(extra.ifBlank { null })).joinToString("\n\n")
            if (text.isBlank()) { _sumState.value = AiState(error = "Tambahkan materi dulu."); return@launch }
            saveSummary(ch, ai.summarize(text))
            _sumState.value = AiState()
        } catch (e: Exception) { _sumState.value = AiState(error = e.message) }
    }
    fun clearSumError() { _sumState.value = AiState() }

    // ── AI: Diskusi ───────────────────────────────────────
    // [UBAH, 13 Nov — permintaan William: "ubah sistem sama UI diskusi, bisa pilih level
    // thinking, ada history message, sama kayak ai chat pada umumnya"] _chat (flat list,
    // murni in-memory, direset tiap ganti bab) diganti jadi thread-aware + persisten:
    //  - chatThreads  = daftar percakapan bab AKTIF, flatMapLatest dari _chId (pola PERSIS
    //    sama dengan `materials` di atas), diurutkan terbaru-dipakai-duluan.
    //  - _activeThreadId = thread yang lagi dibuka user (null = belum ada/"percakapan baru").
    //  - chat = isi thread AKTIF, flatMapLatest dari _activeThreadId.
    // Thread baru TIDAK langsung di-insert ke repo saat user tap "Percakapan baru" — baru
    // beneran dibuat pas pesan PERTAMA dikirim (lihat sendChat di bawah), biar nggak numpuk
    // thread kosong di riwayat kalau user buka lalu batal/pindah tanpa ngetik apa-apa.
    val chatThreads: StateFlow<List<ChatThread>> =
        _chId.flatMapLatest { if (it != null) repo.chatThreads(it) else flowOf(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _activeThreadId = MutableStateFlow<String?>(null)
    val activeThreadId: StateFlow<String?> = _activeThreadId
    val chat: StateFlow<List<ChatMsg>> =
        _activeThreadId.flatMapLatest { if (it != null) repo.chatMessages(it) else flowOf(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _chatLoading = MutableStateFlow(false)
    val chatLoading: StateFlow<Boolean> = _chatLoading

    // [BARU] Level thinking Gemini yang dipilih user ("low"/"medium"/"high", null = Default) —
    // dibungkus StateFlow di sini biar Compose bisa collectAsStateWithLifecycle, tapi disimpan
    // di AiEngine (SharedPreferences) supaya tetap kepilih tiap buka app. HANYA berlaku ke
    // Gemini (lihat GeminiService.thinkingConfigFor) — provider lain (Groq/Cerebras/Custom di
    // Pengaturan > Lanjutan) mengabaikannya sepenuhnya, sesuai keputusan eksplisit William.
    private val _chatThinkingLevel = MutableStateFlow(ai.chatThinkingLevel)
    val chatThinkingLevel: StateFlow<String?> = _chatThinkingLevel
    fun setChatThinkingLevel(level: String?) { ai.chatThinkingLevel = level; _chatThinkingLevel.value = level }

    /** Buka thread yang sudah ada (dipilih dari daftar riwayat). */
    fun selectChatThread(threadId: String) { _activeThreadId.value = threadId }

    /** "+ Percakapan baru" — TIDAK langsung bikin ChatThread (lihat catatan di atas), cuma
     *  mengosongkan tampilan; thread beneran baru tercipta pas sendChat() pertama kali dipanggil. */
    fun newChatThread() { _activeThreadId.value = null }

    fun deleteChatThread(threadId: String) = viewModelScope.launch {
        repo.deleteChatThread(threadId)
        if (_activeThreadId.value == threadId) _activeThreadId.value = null
    }

    // [BARU, 14-15 Nov — streaming native Diskusi, lihat project doc
    // audit-rencana-streaming-checkpoint-14nov2026.md] Job dari sendChat() SEKARANG disimpan di
    // sini (dulu TIDAK PERNAH disimpan sama sekali, dikonfirmasi lewat audit 14 Nov — tidak ada
    // cara apa pun membatalkan sendChat() yang sedang jalan sebelum perubahan ini). cancelChat()
    // di bawah SATU-SATUNYA pemanggil .cancel() ini.
    private var chatJob: Job? = null

    /** Batalkan sendChat() yang sedang berjalan (kalau ada). Lihat catatan panjang di dalam
     *  sendChat() soal bagaimana ini benar-benar menghentikan koneksi jaringan OkHttp, bukan
     *  cuma berhenti menunggu hasilnya di sisi Kotlin. Aman dipanggil kapan pun, termasuk kalau
     *  tidak ada yang sedang berjalan (chatJob null / sudah selesai → tidak melakukan apa-apa). */
    fun cancelChat() { chatJob?.cancel() }

    fun sendChat(text: String, chapterId: String, fallbackCtx: String?) {
        chatJob = viewModelScope.launch {
            _chatLoading.value = true
            // [BARU, 14-15 Nov] nullable, bukan lateinit — dipakai kedua catch block di bawah
            // utk tahu APAKAH pesan asisten sempat dibuat sebelum kegagalan terjadi (lihat
            // catatan di catch(Exception) soal kenapa ini penting).
            var assistantMsg: ChatMsg? = null
            val acc = StringBuilder()
            try {
                // Lazy-create thread: belum ada thread aktif (baru tap "Percakapan baru" ATAU
                // memang belum pernah chat sama sekali di bab ini) → bikin ChatThread baru SEKARANG,
                // judul otomatis dari pesan pertama (markdownToPlainPreview sama seperti preview
                // kartu Materi di TabMateri, biar konsisten kalau pesannya kebetulan ada markdown-nya).
                val existingId = _activeThreadId.value
                val threadId: String
                val priorMsgs: List<ChatMsg>
                if (existingId == null) {
                    val title = markdownToPlainPreview(text).take(60).ifBlank { "Percakapan baru" }
                    val thread = ChatThread(chapterId = chapterId, title = title)
                    repo.insertChatThread(thread)
                    threadId = thread.id
                    _activeThreadId.value = threadId
                    priorMsgs = emptyList()
                } else {
                    threadId = existingId
                    priorMsgs = repo.chatMessagesOnce(threadId)
                }
                val userMsg = ChatMsg(threadId = threadId, role = "user", text = text)
                repo.insertChatMessage(userMsg)
                // [UBAH, 14-15 Nov — streaming native Diskusi] priorMsgs DIFILTER ke
                // role=="user" || status=="COMPLETED" sebelum dikirim ke AI — dulu TIDAK
                // difilter sama sekali (field status belum ada). Bug laten yang ketemu lewat
                // audit 14 Nov: pesan "Error: ..." dari giliran yang gagal ikut kebaca AI sbg
                // jawabannya SENDIRI di giliran berikutnya (lihat project doc §5.4). Pesan
                // STREAMING/FAILED/CANCELLED sekarang konsisten tidak pernah ikut histori ini.
                val history = (priorMsgs.filter { it.role == "user" || it.status == "COMPLETED" } + userMsg)
                    .map { it.role to it.text }
                // [KOREKSI, 11 Nov — "PLANNING IMPLEMENTASI RKR: PERBAIKAN RAG, CHUNKING, DAN
                // AUTOMATIC EMBEDDING" §4] DULU buildRagContext() (= 1 panggilan Gemini Embedding)
                // dipanggil buat SETIAP pesan, termasuk basa-basi kayak "Hai."/"Oke."/"Terima kasih."
                // yang jelas gak butuh pencarian materi. isSimpleChatTurn() di atas cuma match PERSIS
                // (bukan substring) ke daftar basa-basi tertutup — pertanyaan akademik pendek apa pun
                // ("Apa itu akuntansi?" dst) TETAP lewat buildRagContext() seperti biasa, sesuai
                // larangan dokumen soal "semua pertanyaan pendek dilewati". Fallback konteks bab juga
                // sengaja ikut di-skip utk giliran basa-basi ini (dokumen: "jangan sertakan fallback
                // konteks bab jika memang tidak diperlukan") — ctx jadi null sepenuhnya, ai.chat()
                // jalan modal riwayat percakapan doang, PERSIS kelakuan sebelum RAG ada sama sekali.
                val isSimple = isSimpleChatTurn(text)
                val rag = if (isSimple) null else buildRagContext(text, chapterId)
                val fallback = if (isSimple) null else fallbackCtx
                val ctx = listOfNotNull(rag, fallback).joinToString("\n\n").ifBlank { null }

                // [BARU, 14-15 Nov] Pesan asisten dibuat SEKARANG, kosong, status STREAMING — beda
                // dari dulu (ChatMsg assistant baru "ada" setelah ai.chat() balik lengkap SEKALI
                // pancar). id-nya TETAP SAMA sepanjang satu jawaban; repo.updateChatMessage (BUKAN
                // insertChatMessage lagi) dipakai tiap kali isinya berubah — lihat catatan panjang
                // di LocalStorage.updateChatMessage kenapa insertChatMessage() lama tidak boleh
                // dipanggil ulang utk ini (bakal bikin id dobel di chat_messages.json & bikin
                // LazyColumn items(key=id) di TabDiskusi crash).
                var current = ChatMsg(threadId = threadId, role = "assistant", text = "", status = "STREAMING")
                assistantMsg = current
                repo.insertChatMessage(current)

                ai.chatStream(history, ctx).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            acc.append(event.text)
                            current = current.copy(text = acc.toString())
                        }
                        is ChatStreamEvent.Reset -> {
                            // keepText null = restart total (backend SAAT INI belum punya
                            // checkpoint sama sekali, dikonfirmasi audit 14 Nov — lihat project
                            // doc §7.1) ATAU memang sengaja restart. keepText terisi =
                            // MENGGANTIKAN akumulasi lama sepenuhnya (bukan ditambahkan) —
                            // discardedCharacters di event ini artinya sebagian teks lama memang
                            // harus dibuang, itulah inti checkpoint.
                            acc.setLength(0)
                            acc.append(event.keepText ?: "")
                            current = current.copy(text = acc.toString())
                        }
                        is ChatStreamEvent.Done -> {
                            current = current.copy(text = acc.toString(), status = "COMPLETED")
                        }
                        is ChatStreamEvent.ServerError -> {
                            current = current.copy(
                                text = acc.toString().ifBlank { "Error: ${event.message}" },
                                status = "FAILED",
                            )
                        }
                        // Belum ditampilkan di UI versi ini (lihat TabDiskusi) — sengaja
                        // dilewati di sini, bukan lupa ditangani.
                        is ChatStreamEvent.Status, is ChatStreamEvent.Heartbeat -> return@collect
                    }
                    assistantMsg = current
                    repo.updateChatMessage(current)
                    if (event is ChatStreamEvent.Done) repo.touchChatThread(threadId, System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                // [BARU, 14-15 Nov] WAJIB ditangani TERPISAH dari catch(Exception) generik di
                // bawah — kalau tidak, pembatalan user lewat cancelChat() bakal kesimpen sbg
                // pesan "Error: ..." seolah kegagalan asli, padahal user sendiri yang membatalkan.
                // withContext(NonCancellable) WAJIB membungkus tulisan terakhir ini: begitu Job
                // ini berstatus cancelling, titik suspend APA PUN (termasuk repo.updateChatMessage
                // yang menulis ke disk) langsung melempar CancellationException lagi kalau TIDAK
                // dibungkus ini — status CANCELLED & checkpoint terakhir berisiko TIDAK PERNAH
                // benar-benar tersimpan. Dilempar ulang (bukan ditelan) di baris paling akhir —
                // begitu cara structured concurrency Kotlin diberi tahu coroutine ini memang
                // selesai DIBATALKAN, bukan selesai normal.
                val base = assistantMsg
                if (base != null) withContext(NonCancellable) {
                    repo.updateChatMessage(base.copy(text = acc.toString(), status = "CANCELLED"))
                }
                throw e
            } catch (e: Exception) {
                // [CATATAN] threadId/userMsg di atas sudah pasti ke-set begitu kita sampai sini
                // lewat jalur normal — tapi assistantMsg bisa saja MASIH null kalau gagalnya
                // justru SEBELUM pesan asisten sempat dibuat (mis. gagal simpan thread/pesan
                // user, jarang terjadi tapi mungkin). Cabang null di bawah mempertahankan PERSIS
                // kelakuan lama utk kasus itu: sisipkan pesan error baru (tidak ada apa pun yang
                // bisa diupdate). Cabang non-null menyimpan checkpoint terakhir (acc) kalau ada,
                // bukan cuma teks error polos — supaya sebagian jawaban yang sempat diterima
                // tidak hilang begitu saja dari layar.
                val base = assistantMsg
                val errText = "Error: ${e.message}"
                if (base != null) {
                    repo.updateChatMessage(base.copy(text = acc.toString().ifBlank { errText }, status = "FAILED"))
                } else {
                    // [KETEMU SAAT VERIFIKASI MANUAL, 14-15 Nov] status="FAILED" di sini WAJIB
                    // eksplisit, BUKAN dibiarkan default ("COMPLETED") — kalau dibiarkan default,
                    // pesan error ini bakal ikut lolos filter role=="user"||status=="COMPLETED"
                    // di giliran chat berikutnya dan kebaca AI sbg jawabannya sendiri, PERSIS bug
                    // lama yang coba diperbaiki field status ini (lihat project doc §5.4).
                    _activeThreadId.value?.let { tid ->
                        repo.insertChatMessage(ChatMsg(threadId = tid, role = "assistant", text = errText, status = "FAILED"))
                    }
                }
            } finally {
                _chatLoading.value = false
            }
        }
    }

    // [BARU] Retrieval buat RAG — embed PERTANYAAN user (taskType beda dari embed DOKUMEN pas
    // reindexMaterial, confirmed emang harus beda dari dokumentasi resmi Gemini), cari 5 chunk
    // paling mirip di bab ini, gabung jadi teks konteks. GAGAL DIAM-DIAM (return null) kalau
    // apa pun meleset (offline, belum ada chunk buat bab ini, dst) — sendChat() di atas tetep
    // jalan pakai fallbackCtx (ringkasan bab) doang, PERSIS kelakuan SEBELUM RAG ada. RAG murni
    // nambah, gak pernah bikin Diskusi berhenti kerja kalau lagi gak tersedia.
    private suspend fun buildRagContext(question: String, chapterId: String): String? {
        val chunks = repo.chunksForChapter(chapterId)
        if (chunks.isEmpty()) return null
        val queryEmbedding = try { ai.embed(listOf(question), "RETRIEVAL_QUERY").firstOrNull() } catch (_: Exception) { null }
        if (queryEmbedding.isNullOrEmpty()) return null
        val top = topKSimilar(queryEmbedding, chunks, k = 5)
        if (top.isEmpty()) return null
        return "Kutipan relevan dari materi:\n" + top.joinToString("\n---\n") { it.text }
    }

    // ── Mind Map ──────────────────────────────────────────
    // [DITULIS ULANG sesuai spek Mind Map] Pohon BEBAS kedalaman dengan kategori bebas, urutan (ordered), label hubungan,
    // dan cross-link; edit berbasis ID node (bukan indeks cabang/anak). Logika pohon — edit, validasi, penggabungan
    // hasil AI — ada di util/MindMapOps & MindMapCodec; ViewModel hanya memegang state, memanggil AI, dan menyimpan.
    private val _mindMap = MutableStateFlow<MindMapData?>(null)
    val mindMap: StateFlow<MindMapData?> = _mindMap
    private val _mindMapLoading = MutableStateFlow(false)
    val mindMapLoading: StateFlow<Boolean> = _mindMapLoading
    private val _mindMapState = MutableStateFlow(AiState())
    val mindMapState: StateFlow<AiState> = _mindMapState

    private fun persistMindMap(data: MindMapData) {
        val chId = _chId.value ?: return
        val json = MindMapCodec.encode(data)
        viewModelScope.launch { repo.chapter(chId)?.let { repo.updateChapter(it.copy(mindMapJson = json)) } }
    }
    private fun updateMindMap(data: MindMapData) { _mindMap.value = data; persistMindMap(data) }

    /** Ubah peta yang sedang terbuka lewat fungsi murni MindMapOps; tidak melakukan apa-apa kalau belum ada peta. */
    private fun editMindMap(block: (MindMapData) -> MindMapData) { _mindMap.value?.let { updateMindMap(block(it)) } }

    /**
     * Jalur AI — menyusun struktur ADAPTIF (kedalaman & jumlah cabang mengikuti materi) sekaligus kategori, urutan,
     * label hubungan, dan cross-link. Balasan yang terpotong batas token tetap diselamatkan sebisanya
     * (MindMapCodec.repairTruncated). Butuh internet.
     */
    fun generateMindMap(ch: Chapter) = viewModelScope.launch {
        val summary = ch.summary ?: return@launch
        _mindMapLoading.value = true
        _mindMapState.value = AiState()
        try {
            // Ringkasan (kerangka) + materi sumber (rincian): peta bisa lebih rinci daripada ringkasannya sendiri.
            val source = MindMapSource.build(summary, repo.materialsOnce(ch.id).map { it.title to it.content })
            val parsed = MindMapCodec.decodeAi(ai.mindMap(source))
            if (parsed != null) updateMindMap(parsed) else _mindMapState.value = AiState(error = "Format respons tidak valid. Coba lagi.")
        } catch (e: Exception) { _mindMapState.value = AiState(error = e.message) }
        finally { _mindMapLoading.value = false }
    }

    /**
     * Tombol "Lengkapi dengan AI" — dipakai kalau peta sudah ada (biasanya dari jalur algoritma atau hasil edit).
     * AI hanya melengkapi METADATA: kategori, urutan, hubungan, dan cross-link. Label & hierarki TETAP dari data lokal;
     * hasil digabung per id (MindMapOps.enrich) dan hanya mengisi yang masih kosong — hasil edit pengguna tidak ditimpa,
     * dan walau AI kebablasan mengubah label, petanya tidak akan rusak.
     */
    fun enrichMindMapWithAI(ch: Chapter) = viewModelScope.launch {
        val current = _mindMap.value ?: return@launch
        _mindMapLoading.value = true
        _mindMapState.value = AiState()
        try {
            val (tree, idByShort) = MindMapOps.toPromptTree(current)
            val meta = MindMapCodec.decodeEnrichment(ai.enrichMindMap(tree, maxOf(3, idByShort.size / 5)))
            if (meta != null) {
                // Gabungkan ke peta TERBARU (pengguna mungkin mengedit selama AI bekerja); id yang sudah hilang diabaikan.
                updateMindMap(MindMapOps.enrich(_mindMap.value ?: current, meta, idByShort))
            } else {
                _mindMapState.value = AiState(error = "Hasil AI tidak dapat dibaca. Coba lagi.")
            }
        } catch (e: Exception) { _mindMapState.value = AiState(error = e.message ?: "Gagal melengkapi mind map.") }
        finally { _mindMapLoading.value = false }
    }

    /**
     * Jalur ALGORITMA — murni parsing struktural dari ringkasan bab (heading/bullet Markdown yang sudah ada di
     * ch.summary), TANPA AI dan TANPA internet. Hierarki dipertahankan sedalam teksnya; kategori hanya ditebak dari
     * awalan label dan tidak pernah dipaksa — pengguna bisa mengubahnya di mode edit atau memakai "Lengkapi dengan AI".
     */
    fun generateMindMapFromSummary(ch: Chapter) {
        val text = ch.summary
        if (text.isNullOrBlank()) { _mindMapState.value = AiState(error = "Belum ada ringkasan untuk diparsing."); return }
        val parsed = MindMapParser.parseOutline(text, fallbackRoot = ch.name, forceRoot = true)
        if (parsed != null) { _mindMapState.value = AiState(); updateMindMap(parsed) }
        else _mindMapState.value = AiState(error = "Ringkasan belum berstruktur (heading/bullet). Coba susun secara manual atau gunakan AI.")
    }

    /** Jalur ALGORITMA dari teks outline bebas yang diketik/ditempel user sendiri. */
    fun generateMindMapFromOutline(ch: Chapter, outline: String) {
        val parsed = MindMapParser.parseOutline(outline, fallbackRoot = ch.name)
        if (parsed != null) { _mindMapState.value = AiState(); updateMindMap(parsed) }
        else _mindMapState.value = AiState(error = "Teks tidak bisa diparsing jadi struktur. Periksa format outline-nya.")
    }

    /** Mulai mind map kosong untuk disusun manual sepenuhnya lewat mode edit. */
    fun startManualMindMap(ch: Chapter) {
        _mindMapState.value = AiState()
        updateMindMap(MindMapData(MindMapNode(label = ch.name)))
    }

    fun clearMindMap() {
        _mindMap.value = null
        _mindMapState.value = AiState()
        val chId = _chId.value ?: return
        viewModelScope.launch { repo.chapter(chId)?.let { repo.updateChapter(it.copy(mindMapJson = null)) } }
    }

    // ── Edit manual (berbasis id; root = node berinduk-kosong, tidak bisa dihapus) ──
    fun addMindMapNode(parentId: String, label: String, category: String = "") =
        editMindMap { MindMapOps.addChild(it, parentId, MindMapNode(label = label.trim(), category = category)) }

    fun updateMindMapNode(id: String, label: String, category: String, relation: String, ordered: Boolean, note: String) =
        editMindMap { MindMapOps.update(it, id, label.trim(), category, relation, ordered, note) }

    fun deleteMindMapNode(id: String) = editMindMap { MindMapOps.remove(it, id) }

    /** Geser node di antara saudaranya (−1 = naik, +1 = turun) — penting untuk urutan langkah. */
    fun moveMindMapNode(id: String, delta: Int) = editMindMap { MindMapOps.move(it, id, delta) }

    fun addMindMapLink(from: String, to: String, relation: String) = editMindMap { MindMapOps.addLink(it, from, to, relation) }

    fun deleteMindMapLink(from: String, to: String) = editMindMap { MindMapOps.removeLink(it, from, to) }

    // ── Flashcard ─────────────────────────────────────────
    private val _cards = MutableStateFlow<List<Flashcard>>(emptyList())
    val flashcards: StateFlow<List<Flashcard>> = _cards
    private val _cardsLoading = MutableStateFlow(false)
    val flashcardsLoading: StateFlow<Boolean> = _cardsLoading
    private val _cardsState = MutableStateFlow(AiState())
    val flashcardsState: StateFlow<AiState> = _cardsState

    private fun persistFlashcards() {
        val chId = _chId.value ?: return
        val data = _cards.value.map { FlashcardData(it.id, it.front, it.back) }
        viewModelScope.launch { repo.saveFlashcards(chId, data) }
    }
    fun addFlashcard(front: String, back: String) { _cards.value = _cards.value + Flashcard(front = front, back = back); persistFlashcards() }
    fun deleteFlashcard(id: String) { _cards.value = _cards.value.filter { it.id != id }; persistFlashcards() }

    fun generateFlashcards(ch: Chapter) = viewModelScope.launch {
        val ctx = ch.summary ?: return@launch
        _cardsLoading.value = true
        _cardsState.value = AiState()
        try {
            val raw = ai.flashcards(ctx)
            val clean = extractJsonSpan(raw, '[', ']')
            val arr = AppJson.decodeFromString<List<FlashcardJson>>(clean)
            if (arr.isEmpty()) {
                _cardsState.value = AiState(error = "AI tidak menghasilkan flashcard apa pun. Coba lagi.")
            } else {
                _cards.value = _cards.value + arr.map { Flashcard(front = it.front, back = it.back) }
                persistFlashcards()
            }
        } catch (e: Exception) {
            // [FIX] Dulu error ini disimpan ke _cardsState tapi TabFlashcard tidak pernah membacanya,
            // jadi kegagalan AI di tab ini tidak tampil sama sekali. Pesan mentah parser JSON juga
            // tidak layak tampil ke pengguna — diganti kalimat baku; AiException sudah siap tampil.
            _cardsState.value = AiState(error = if (e is AiException) e.message else "Respons AI tidak dapat dibaca. Coba lagi.")
        }
        finally { _cardsLoading.value = false }
    }
    fun clearFlashcardsError() { _cardsState.value = AiState() }

    // ── AI: Pembuatan Bab (topik atau scan) ────────────────
    private val _chapterGenLoading = MutableStateFlow(false)
    val chapterGenLoading: StateFlow<Boolean> = _chapterGenLoading
    private val _chapterGenState = MutableStateFlow(AiState())
    val chapterGenState: StateFlow<AiState> = _chapterGenState

    private fun parseChapterJsonArray(raw: String): List<ChapterJson> {
        val clean = extractJsonSpan(raw, '[', ']')
        return AppJson.decodeFromString<List<ChapterJson>>(clean)
    }

    // [DIGABUNG, audit duplikasi 2 Okt] Dulu dua fungsi ini kerangkanya identik (loading, parse JSON, cek kosong,
    // sisipkan bab, tangani error); beda cuma panggilan AI-nya dan pesan "kosong". Pesan "kosong" untuk hasil
    // pindai juga diganti kalimat baku (aturan: teks aplikasi tidak boleh informal).
    private fun generateChapters(wsId: String, emptyMessage: String, onDone: () -> Unit, request: suspend () -> String) = viewModelScope.launch {
        _chapterGenLoading.value = true
        _chapterGenState.value = AiState()
        try {
            val arr = parseChapterJsonArray(request())
            if (arr.isEmpty()) _chapterGenState.value = AiState(error = emptyMessage)
            else insertChaptersBulk(wsId, arr.map { it.title to it.description })
        } catch (e: Exception) { _chapterGenState.value = AiState(error = e.message ?: "Gagal membuat bab.") }
        finally { _chapterGenLoading.value = false; onDone() }
    }

    fun generateChaptersFromTopic(wsId: String, topic: String, count: Int?, onDone: () -> Unit = {}) =
        generateChapters(wsId, "AI tidak menghasilkan bab apa pun. Coba lagi.", onDone) { ai.chaptersFromTopic(topic, count) }

    fun generateChaptersFromText(wsId: String, text: String, onDone: () -> Unit = {}) =
        generateChapters(wsId, "Tidak ada bab yang terdeteksi dari hasil pemindaian ini.", onDone) { ai.chaptersFromText(text) }

    // ── Tasks ─────────────────────────────────────────────
    val tasks = repo.tasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _taskError = MutableStateFlow<String?>(null)
    val taskError: StateFlow<String?> = _taskError
    fun clearTaskError() { _taskError.value = null }

    // ═══ HUBUNGAN CalendarService / GoogleCalendarService / NotificationHelper (audit item 3,
    // 28 Sep — ditulis biar keputusan ini eksplisit, bukan cuma kesimpulan diam-diam dari kode
    // yang kebetulan begini) ═══
    // Tiga hal ini SENGAJA independen satu sama lain, bukan kebetulan/belum sempat disatuin:
    // - CalendarService (on-device) & GoogleCalendarService (akun Google) = buat VISIBILITAS —
    //   biar tugas keliatan di app Kalender HP/Google Calendar user, di antara jadwal lain
    //   mereka. Reminder BAWAAN dua-duanya (CalendarContract.Reminders / reminders.overrides)
    //   ini best-effort: yang beneran nampilin notifnya itu app Kalender/OS (on-device) atau
    //   infra Google (akun Google) — BUKAN kode RKR. RKR nulis permintaannya, tapi nggak bisa
    //   jamin kapan/apakah itu beneran muncul (bisa ketunda kebijakan baterai OEM, dll — laporan
    //   William soal "pengingat kalender nggak muncul" ini asalnya dari sini, bukan bug nulis
    //   data). GoogleCalendarService relatif LEBIH bisa diandalkan (infra Google), CalendarService
    //   PALING nggak pasti (tergantung app Kalender & pengaturan baterai HP masing-masing).
    // - NotificationHelper = satu-satunya yang RKR BENERAN bisa jamin: AlarmManager dijadwalin
    //   & dieksekusi oleh kode RKR sendiri, independen total dari ada/nggaknya sinkron kalender.
    // Makanya toggle "Sinkron ke Kalender" & "Ingatkan Aku" di TaskScreen.kt SENGAJA terpisah
    // (bukan satu jadi otomatis nyalain yang lain) — orang yang cuma mau tugasnya keliatan di
    // kalender tanpa notif RKU tambahan tetap bisa milih itu doang. TaskScreen.kt kasih hint
    // ke user pas Sinkron ke Kalender aktif tapi Ingatkan Aku belum (lihat SyncToggleRow kedua),
    // ngarahin ke jalur yang beneran bisa dijamin RKR.
    //
    // Login = PREFERENSI pakai Google Calendar kalau bisa, BUKAN komitmen mutlak — kalau
    // googleAccessToken gagal didapat (offline dll, lihat ensureGoogleCalendarAccess di
    // TaskScreen.kt), jalur turun ke CalendarService on-device, SAMA kayak user yang logout.
    // CalendarService TIDAK boleh dihapus selama masih ada kemungkinan ini (fitur offline
    // bergantung penuh ke sana).
    // [FIX] wsId dulu gak ada di parameter sama sekali — Task.workspaceId jadi SELALU null,
    // padahal buildWorkspaceExportFile()/buildWorkspaceReportPdf() sudah nge-filter tugas
    // pakai field ini (`it.workspaceId == wsId`). Efeknya ekspor & Laporan Belajar per-Ruang-
    // Kerja gak akan PERNAH menyertakan tugas apa pun, tanpa error/tanda apa-apa. wsId dibuat
    // opsional (default null) — tetap boleh bikin tugas tanpa kaitin ke Ruang Kerja manapun.
    fun addTask(
        title: String, desc: String, deadline: String, priority: Int,
        syncCalendar: Boolean = false, reminderEnabled: Boolean = false, reminderMinutes: Int = 1440,
        wsId: String? = null, googleAccessToken: String? = null
    ) = viewModelScope.launch {
        val app = getApplication<Application>()
        var task = Task(
            title = title, description = desc, deadline = deadline, priority = priority,
            workspaceId = wsId,
            reminderEnabled = reminderEnabled && deadline.isNotBlank(),
            reminderMinutesBefore = reminderMinutes
        )
        if (syncCalendar && deadline.isNotBlank()) {
            try {
                // [BARU] googleAccessToken cuma keisi kalau user login DAN udah authorize scope
                // Calendar (lihat TaskScreen.kt: ensureGoogleCalendarAccess) — kalau nggak login,
                // tetap jalan kayak sebelumnya lewat CalendarService (kalender on-device).
                if (googleAccessToken != null) {
                    val eventId = GoogleCalendarService.addTask(googleAccessToken, title, desc, deadline)
                    task = task.copy(googleCalendarEventId = eventId)
                } else {
                    val eventId = CalendarService.addTask(app, title, desc, deadline)
                    task = task.copy(calendarEventId = eventId)
                }
            } catch (e: Exception) {
                _taskError.value = "Tugas disimpan, tapi gagal sinkron ke kalender: ${e.message}"
            }
        }
        repo.insertTask(task)
        if (task.reminderEnabled) NotificationHelper.scheduleReminder(app, task)
    }

    fun toggleTask(t: Task) = viewModelScope.launch {
        val newStatus = when (t.status) { 0 -> 1; 1 -> 2; else -> 0 }
        val updated = t.copy(status = newStatus)
        repo.updateTask(updated)
        val app = getApplication<Application>()
        if (newStatus == 2) NotificationHelper.cancelReminder(app, t.id)
        else if (updated.reminderEnabled) NotificationHelper.scheduleReminder(app, updated)
    }

    fun deleteTask(id: String, googleAccessToken: String? = null) = viewModelScope.launch {
        val app = getApplication<Application>()
        val t = tasks.value.find { it.id == id }
        if (t != null) {
            NotificationHelper.cancelReminder(app, t.id)
            // [BARU] googleAccessToken di sini didapat best-effort/diam-diam (lihat
            // GoogleCalendarAuth.getAccessTokenSilently) — kalau gagal/null, event Google
            // Calendar-nya dibiarin nyangkut (gak nge-block hapus tugasnya sendiri), sama
            // persis semangatnya kayak catch(_:Exception){} di bawah buat jalur on-device.
            if (t.googleCalendarEventId != null) {
                if (googleAccessToken != null) {
                    try { GoogleCalendarService.deleteTask(googleAccessToken, t.googleCalendarEventId) } catch (_: Exception) {}
                }
            } else if (t.calendarEventId != null) {
                try { CalendarService.deleteTask(app, t.calendarEventId) } catch (_: Exception) {}
            }
        }
        repo.deleteTask(id)
    }

    /** Toggle sinkron kalender buat tugas yang udah ada. Kalau googleAccessToken ada, sinkron
     * ke Google Calendar akun user; kalau nggak, fallback ke kalender on-device kayak sebelumnya. */
    fun toggleCalendarSync(t: Task, googleAccessToken: String? = null) = viewModelScope.launch {
        val app = getApplication<Application>()
        if (t.calendarEventId != null || t.googleCalendarEventId != null) {
            if (t.googleCalendarEventId != null) {
                if (googleAccessToken != null) {
                    try { GoogleCalendarService.deleteTask(googleAccessToken, t.googleCalendarEventId) } catch (_: Exception) {}
                }
            } else if (t.calendarEventId != null) {
                try { CalendarService.deleteTask(app, t.calendarEventId) } catch (_: Exception) {}
            }
            repo.updateTask(t.copy(calendarEventId = null, googleCalendarEventId = null))
        } else {
            if (t.deadline.isBlank()) { _taskError.value = "Tugas ini belum punya deadline."; return@launch }
            try {
                if (googleAccessToken != null) {
                    val eventId = GoogleCalendarService.addTask(googleAccessToken, t.title, t.description, t.deadline)
                    repo.updateTask(t.copy(googleCalendarEventId = eventId))
                } else {
                    val eventId = CalendarService.addTask(app, t.title, t.description, t.deadline)
                    repo.updateTask(t.copy(calendarEventId = eventId))
                }
            } catch (e: Exception) { _taskError.value = "Gagal sinkron ke kalender: ${e.message}" }
        }
    }

    /** Toggle pengingat notifikasi lokal buat tugas yang udah ada. Butuh deadline. */
    fun toggleReminder(t: Task) = viewModelScope.launch {
        val app = getApplication<Application>()
        if (t.reminderEnabled) {
            NotificationHelper.cancelReminder(app, t.id)
            repo.updateTask(t.copy(reminderEnabled = false))
        } else {
            if (t.deadline.isBlank()) { _taskError.value = "Tugas ini belum punya deadline."; return@launch }
            val updated = t.copy(reminderEnabled = true)
            repo.updateTask(updated)
            NotificationHelper.scheduleReminder(app, updated)
        }
    }

    // ── ScheduleEntry — "Jadwal Hari Ini" di Beranda (BARU, 16 Nov — redesain Beranda, lihat
    // Entities.kt/AppRepository.kt buat alasan lengkap kenapa entity ini lokal-only) ──────────
    // Pola StateFlow+CRUD sama persis kayak Tasks di atas, cuma jauh lebih simpel — TIDAK ada
    // integrasi kalender/notifikasi sama sekali di ronde ini (scope sengaja dibatasi).
    val scheduleEntries = repo.scheduleEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addScheduleEntry(title: String, startTime: String, endTime: String, location: String, daysOfWeek: Set<Int>) = viewModelScope.launch {
        if (title.isBlank() || daysOfWeek.isEmpty()) return@launch
        repo.insertScheduleEntry(
            ScheduleEntry(title = title.trim(), startTime = startTime.trim(), endTime = endTime.trim(), location = location.trim(), daysOfWeek = daysOfWeek)
        )
    }
    fun updateScheduleEntry(s: ScheduleEntry) = viewModelScope.launch { repo.updateScheduleEntry(s) }
    fun deleteScheduleEntry(id: String) = viewModelScope.launch { repo.deleteScheduleEntry(id) }

    // ── "Lanjutkan" (kartu Beranda) & akses-cepat "AI" — bab TERAKHIR dibuka (BARU, 16 Nov) ──
    // key "last_chapter_id" ditulis di SATU titik: selectChapter() di atas. StateFlow di sini
    // CUMA buat Beranda nampilin kartunya — bukan sumber kebenaran (itu tetap SharedPreferences
    // + _chapter/_chId yang udah ada), jadi aman kalau null (belum pernah buka bab apa pun, atau
    // bab/workspace-nya udah kehapus sejak terakhir dibuka — gak crash, kartunya cuma gak nongol).
    private val _lastChapterCard = MutableStateFlow<Pair<Chapter, Workspace>?>(null)
    val lastChapterCard: StateFlow<Pair<Chapter, Workspace>?> = _lastChapterCard

    private fun resolveLastChapterCard(chapterId: String?) {
        if (chapterId.isNullOrBlank()) { _lastChapterCard.value = null; return }
        // [HOTFIX, 17 Nov] Ini fungsi yang paling DICURIGAI jadi sumber force-close William —
        // dipanggil dari init{} tiap app baru nyala, dan untuk user yang SEBELUMNYA udah pernah
        // buka bab (kayak William, yang udah punya data asli), "last_chapter_id" di SharedPreferences
        // udah KEISI dari pemakaian lama → fungsi ini LANGSUNG jalan penuh di awal startup, bukan
        // cuma kondisi kosong/baru-install. Dibungkus try/catch biar kalau ADA apa pun yang gagal
        // di sini (repo.chapter, repo.allWorkspacesOnce, atau apa pun), app TETAP bisa kebuka —
        // paling buruk kartu "Lanjutkan" gak nongol, bukan seluruh app force-close.
        viewModelScope.launch {
            try {
                val ch = repo.chapter(chapterId)
                val ws = ch?.workspaceId?.let { wsId -> repo.allWorkspacesOnce().find { it.id == wsId } }
                _lastChapterCard.value = if (ch != null && ws != null) ch to ws else null
            } catch (e: Exception) {
                _lastChapterCard.value = null
            }
        }
    }
}
