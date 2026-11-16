package com.ruangkerja.companion.network

import android.content.Context
// [BARU, 14-15 Nov — streaming native Diskusi] dipakai chatStream() di bawah.
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Antarmuka tipis biar AiEngine bisa gonta-ganti backend (Gemini/Groq/Cerebras/custom)
 * lewat satu jalur kode yang sama buat semua jenis panggilan AI di app ini.
 */
interface AiBackend {
    suspend fun summarize(text: String): String
    // [UBAH, 13 Nov — permintaan William: pilih level thinking Gemini di Diskusi] thinkingLevel
    // null = "Default" (biarkan model pakai defaultnya sendiri). HANYA benar-benar dipakai oleh
    // GeminiService (lihat thinkingConfigFor di sana) — OpenAiCompatibleService (Groq/Cerebras/
    // Custom BYOK) mengabaikannya sepenuhnya, SESUAI keputusan eksplisit William (reasoning
    // effort 1 model, BUKAN pemilihan model lain).
    suspend fun chat(history: List<Pair<String, String>>, ctx: String?, thinkingLevel: String? = null): String
    suspend fun mindMap(text: String): String
    suspend fun enrichMindMap(structure: String, maxLinks: Int): String
    suspend fun textAction(action: String, text: String): String
    suspend fun flashcards(text: String): String
    suspend fun chaptersFromTopic(topic: String, count: Int?): String
    suspend fun chaptersFromText(text: String): String
}

/**
 * Delapan fitur AI yang SAMA untuk semua backend. [DIGABUNG, audit duplikasi 2 Okt] Dulu fungsi-fungsi ini
 * (summarize … chaptersFromText) ditulis dua kali persis sama di GeminiService dan OpenAiCompatibleService:
 * prompt, system prompt, DAN batas token (4000/2000/2500) harus diubah di dua tempat, dan kalau lupa satu,
 * jalur bawaan dan jalur API key sendiri diam-diam berbeda perilaku. Sekarang semuanya di sini; subclass
 * cukup menyediakan [complete] = cara memanggil modelnya.
 */
abstract class PromptedAiBackend : AiBackend {
    // [UBAH, 25 Okt] Tambah parameter `feature` — nama fitur yang manggil, dikirim APA ADANYA
    // sampai ke edge function `ai-proxy` (lihat GeminiService.callEdgeProxy/EdgeChatReq).
    // Alasannya: ai-proxy sekarang perlu tau fitur mana yang manggil (mind map vs lainnya)
    // buat milih urutan provider yang beda (lihat catatan di index.ts), dan nebak dari
    // maxTokens SENGAJA dihindari (gampang ketuker kalau budget token fitur lain ikut
    // berubah nanti) — nama fitur eksplisit lebih jelas sumbernya. WAJIB diisi di semua
    // pemanggilan complete() di bawah, tidak dikasih nilai default di sini biar gak ada yang
    // kelewat (sama kayak pola `action` di EdgeChatReq).
    // [UBAH, 13 Nov] Tambah parameter trailing `thinkingLevel` (default null) — HANYA dipakai
    // lewat jalur chat() di bawah; 7 override lain (summarize, mindMap, dst.) sama sekali TIDAK
    // berubah pemanggilannya, otomatis lewat null lewat default parameter. Kenapa ditaruh di
    // complete() yang dipakai bersama (bukan bikin fungsi completeChat() terpisah): supaya
    // GeminiService cukup override SATU fungsi complete() seperti sebelumnya, bukan dua.
    protected abstract suspend fun complete(messages: List<Pair<String, String>>, sys: String, maxTokens: Int, feature: String, thinkingLevel: String? = null): String

    override suspend fun summarize(text: String): String =
        complete(listOf("user" to AiPrompts.summarize(text)), AiPrompts.SYSTEM, DEFAULT_MAX_TOKENS, "summarize")

    override suspend fun chat(history: List<Pair<String, String>>, ctx: String?, thinkingLevel: String?): String =
        complete(history, AiPrompts.chatSystem(ctx), DEFAULT_MAX_TOKENS, "chat", thinkingLevel)

    override suspend fun mindMap(text: String): String =
        complete(listOf("user" to AiPrompts.mindMap(text)), AiPrompts.MINDMAP_SYS, MINDMAP_MAX_TOKENS, "mindmap")

    /**
     * Dipakai tombol "Lengkapi dengan AI" — struktur mind map SUDAH final (dari algoritma/AI/edit pengguna), AI hanya
     * diminta melengkapi metadata: kategori, urutan, hubungan, dan cross-link (lihat AiPrompts.enrichMindMap).
     * Label & hierarki di sisi Kotlin tetap sumber kebenaran; hasil digabung per id di MindMapOps.enrich.
     */
    override suspend fun enrichMindMap(structure: String, maxLinks: Int): String =
        complete(listOf("user" to AiPrompts.enrichMindMap(structure, maxLinks)), AiPrompts.ENRICH_SYS, 4000, "enrichMindMap")

    /**
     * Aksi AI di teks terpilih (dipakai NotebookEditor) — rewrite/expand/shorten/
     * improve/tone butuh teks yang diseleksi, continue melanjutkan dari teks
     * sebelum kursor. Balasan diminta polos (tanpa markdown/tanda kutip) karena
     * hasilnya langsung menggantikan/menyisip ke posisi teks di editor.
     */
    override suspend fun textAction(action: String, text: String): String {
        val (prompt, sys, maxTokens) = AiPrompts.textAction(action, text)
        return complete(listOf("user" to prompt), sys, maxTokens, "textAction")
    }

    override suspend fun flashcards(text: String): String =
        complete(listOf("user" to AiPrompts.flashcards(text)), AiPrompts.FLASHCARDS_SYS, DEFAULT_MAX_TOKENS, "flashcards")

    override suspend fun chaptersFromTopic(topic: String, count: Int?): String =
        complete(listOf("user" to AiPrompts.chaptersFromTopic(topic, count)), AiPrompts.CHAPTERS_SYS, 2000, "chaptersFromTopic")

    override suspend fun chaptersFromText(text: String): String =
        complete(listOf("user" to AiPrompts.chaptersFromText(text)), AiPrompts.CHAPTERS_SYS, 2500, "chaptersFromText")

    private companion object {
        const val DEFAULT_MAX_TOKENS = 1500   // sama dengan bawaan call() di kedua backend
        // Mind map adaptif bisa jauh lebih dalam dari 3 tingkat lama — dan kalau tetap terpotong,
        // MindMapCodec.repairTruncated menyelamatkan bagian yang sudah lengkap. [UBAH, 4 Nov]
        // 6000→8000 — batas MAX_ALLOWED_TOKENS server di ai-proxy/index.ts, gak bisa lebih
        // tinggi lagi dari ini. Keputusan William: mind map paling sering kepotong di tengah
        // dibanding fitur lain karena strukturnya paling kompleks/dalam.
        const val MINDMAP_MAX_TOKENS = 8000
    }
}

enum class AiProvider(val id: String, val label: String) {
    GEMINI("gemini", "Gemini"),
    GROQ("groq", "Groq"),
    CEREBRAS("cerebras", "Cerebras"),
    CUSTOM("custom", "Lainnya (custom)");
    companion object { fun fromId(id: String?) = entries.find { it.id == id } ?: GEMINI }
}

data class AiCustomConfig(val provider: AiProvider, val key: String, val baseUrl: String, val model: String)

/**
 * Orkestrator AI. Default (bukan BYOK): SEMUA panggilan lewat edge function `ai-proxy` di
 * Supabase — key Gemini/Groq/Cerebras bawaan disimpan sebagai secrets di server (dulu di
 * BuildConfig/local.properties, ke-bundle plain-text di APK & bisa diekstrak lewat decompile;
 * lihat diskusi keamanan 18 Sep). Edge function-nya sendiri yang nyoba Gemini→Groq→Cerebras
 * di server; dari sisi Kotlin, jalur default cukup 1 panggilan ke geminiSvc (lihat
 * GeminiService.callEdgeProxy/embedViaEdgeProxy) — nggak ada lagi chain 3 provider di sini.
 *
 * Kalau user mengaktifkan Fitur Lanjutan lalu mengisi provider + key sendiri di Settings >
 * Fitur Lanjutan > Konfigurasi API, SEMUA panggilan AI tetap
 * LANGSUNG ke provider aslinya (BYOK = kendali penuh di tangan user, termasuk provider
 * "Lainnya (custom)" buat endpoint OpenAI-compatible apa pun yang nggak ada di daftar bawaan)
 * — nggak lewat edge function sama sekali, karena toh udah key milik user sendiri.
 */
class AiEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    // --- Model default per provider bawaan. Kalau provider ganti/hentikan model,
    // cukup ubah daftar ini — nggak perlu ubah logic di tempat lain. ---
    // llama-3.3-70b-versatile & llama-3.1-8b-instant di-deprecate Groq per 17 Juni 2026
    // (khusus tier gratis/developer) — diganti ke rekomendasi resmi mereka.
    private val GROQ_MODELS = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")
    private val CEREBRAS_MODELS = listOf("gpt-oss-120b", "zai-glm-4.7")

    // --- Fitur Lanjutan (saklar induk) ---
    // Belum pernah diatur → aktif HANYA kalau sudah ada konfigurasi API tersimpan, supaya pengguna
    // yang dulu sudah mengisi key sendiri tidak tiba-tiba kembali ke AI bawaan setelah update.
    // Dimatikan = konfigurasi API tetap TERSIMPAN tapi tidak dipakai (bukan dihapus).
    var advancedEnabled: Boolean
        get() = prefs.getBoolean("ai_advanced_enabled", hasSavedApiConfig())
        set(value) { prefs.edit().putBoolean("ai_advanced_enabled", value).apply() }

    private fun hasSavedApiConfig(): Boolean =
        !prefs.getString("ai_custom_key", "").isNullOrBlank() || !prefs.getString("gemini_api_key", "").isNullOrBlank()

    /** Konfigurasi API yang SEDANG berlaku: ada hanya kalau Fitur Lanjutan aktif DAN ada konfigurasi tersimpan. */
    fun customConfig(): AiCustomConfig? = if (advancedEnabled) savedCustomConfig() else null

    // --- Konfigurasi lanjutan (BYOK), disimpan manual oleh user lewat Settings ---
    /** Konfigurasi tersimpan APA ADANYA, terlepas dari saklar Fitur Lanjutan (dipakai buat mengisi form di Settings). */
    fun savedCustomConfig(): AiCustomConfig? {
        var key = prefs.getString("ai_custom_key", "") ?: ""
        var provider = AiProvider.fromId(prefs.getString("ai_custom_provider", null))
        if (key.isBlank()) {
            // Migrasi otomatis dari key Gemini manual yang sempat disimpan lewat alur
            // Settings versi sebelumnya, biar key yang udah dimasukkan nggak hilang.
            val legacy = prefs.getString("gemini_api_key", "") ?: ""
            if (legacy.isNotBlank()) {
                key = legacy; provider = AiProvider.GEMINI
                saveCustomConfig(provider, key, "", "")
            }
        }
        if (key.isBlank()) return null
        return AiCustomConfig(
            provider = provider,
            key = key,
            baseUrl = prefs.getString("ai_custom_base_url", "") ?: "",
            model = prefs.getString("ai_custom_model", "") ?: ""
        )
    }

    fun saveCustomConfig(provider: AiProvider, key: String, baseUrl: String, model: String) {
        prefs.edit()
            .putString("ai_custom_provider", provider.id)
            .putString("ai_custom_key", key.trim())
            .putString("ai_custom_base_url", baseUrl.trim())
            .putString("ai_custom_model", model.trim())
            .apply()
    }

    fun clearCustomConfig() {
        prefs.edit()
            .remove("ai_custom_provider").remove("ai_custom_key")
            .remove("ai_custom_base_url").remove("ai_custom_model")
            .remove("gemini_api_key")
            .apply()
    }

    // [BARU, 13 Nov — permintaan William: pilih level thinking Gemini di Diskusi] null =
    // "Default" (thinkingConfig TIDAK dikirim sama sekali, model pakai bawaannya sendiri).
    // Disimpan di SharedPreferences yang sama dengan konfigurasi lain di file ini, biar pilihan
    // user kepilih balik tiap buka app, bukan ke-reset tiap kali masuk Diskusi. Nilai yang
    // valid: null, "low", "medium", "high" — divalidasi/di-uppercase di GeminiService pas
    // beneran dipakai, BUKAN di sini (di sini cuma penyimpanan mentah).
    var chatThinkingLevel: String?
        get() = prefs.getString("chat_thinking_level", null)
        set(value) { prefs.edit().putString("chat_thinking_level", value).apply() }

    private val geminiSvc by lazy { GeminiService(context) }

    private fun backendFor(cfg: AiCustomConfig): AiBackend = when (cfg.provider) {
        AiProvider.GEMINI -> GeminiService(context, overrideKey = cfg.key)
        AiProvider.GROQ -> OpenAiCompatibleService("https://api.groq.com/openai/v1", listOf(cfg.key), listOf(cfg.model.ifBlank { GROQ_MODELS.first() }), "Groq")
        AiProvider.CEREBRAS -> OpenAiCompatibleService("https://api.cerebras.ai/v1", listOf(cfg.key), listOf(cfg.model.ifBlank { CEREBRAS_MODELS.first() }), "Cerebras")
        AiProvider.CUSTOM -> OpenAiCompatibleService(cfg.baseUrl.trimEnd('/'), listOf(cfg.key), listOf(cfg.model.ifBlank { "gpt-4o-mini" }), "Custom")
    }

    // [UBAH] Dulu di sini ada chain 3 provider (coba Gemini, gagal lanjut Groq, gagal lanjut
    // Cerebras — lihat riwayat file ini kalau perlu bandingin). Rantai itu SEKARANG PINDAH ke
    // DALAM edge function `ai-proxy` (server yang nyoba 3-3-nya sebelum nyerah), jadi di sini
    // cukup 1 panggilan ke geminiSvc — dia otomatis lewat edge proxy kalau nggak ada BYOK key
    // aktif (lihat GeminiService.callEdgeProxy/embedViaEdgeProxy). Exception dari geminiSvc
    // (termasuk CancellationException kalau user pindah layar di tengah request) tinggal
    // nembus ke atas apa adanya — nggak ada lagi "provider lain" buat dicoba di sini.
    private suspend fun <T> run(block: suspend (AiBackend) -> T): T {
        customConfig()?.let { return block(backendFor(it)) }
        return block(geminiSvc)
    }

    suspend fun summarize(text: String) = run { it.summarize(text) }
    // [UBAH, 13 Nov] Signature publik SENGAJA TETAP 2 parameter (tidak minta pemanggil nambah
    // argumen) — chatThinkingLevel disuntik dari sini (AiEngine sendiri yang nyimpennya),
    // bukan dioper eksplisit tiap kali oleh AppViewModel. Nggak ada pemanggil chat() lain yang
    // perlu diubah.
    suspend fun chat(history: List<Pair<String, String>>, ctx: String?) = run { it.chat(history, ctx, chatThinkingLevel) }

    // [BARU, 14-15 Nov — streaming native Diskusi, lihat project doc
    // audit-rencana-streaming-checkpoint-14nov2026.md] Jalur BARU, TERPISAH dari chat() di atas
    // (tidak menggantikan/menghapusnya) — hanya dipakai AppViewModel.sendChat() versi streaming.
    //
    // SCOPE (dikonfirmasi William 14 Nov, lihat project doc §6.1): HANYA jalur default
    // (shared-key/ai-proxy, lewat geminiSvc TANPA overrideKey). BYOK (Gemini/Groq/Cerebras/
    // Custom) TIDAK dapat streaming/checkpoint pada versi ini — cabang customConfig() di bawah
    // membungkus chat() LAMA (satu balasan utuh, TIDAK diubah sama sekali) jadi Flow berisi
    // Delta+Done satu-kali-pancar, PERSIS kelakuan sebelum fitur ini ada, cuma lewat tipe Flow
    // yang sama — supaya AppViewModel TIDAK PERLU tahu/cek apakah jawaban yang sedang diproses
    // itu streaming asli atau BYOK yang "dipalsukan" jadi streaming sekali-pancar. thinkingLevel
    // tetap disuntik dari chatThinkingLevel (BUKAN null) supaya perilaku BYOK 100% identik
    // dengan memanggil chat() lama — tidak ada regresi apa pun buat pengguna BYOK dari fitur ini.
    fun chatStream(history: List<Pair<String, String>>, ctx: String?): Flow<ChatStreamEvent> {
        customConfig()?.let { cfg ->
            return flow {
                val text = backendFor(cfg).chat(history, ctx, chatThinkingLevel)
                emit(ChatStreamEvent.Delta(text = text, provider = cfg.provider.id, model = "non-streaming"))
                emit(ChatStreamEvent.Done(provider = cfg.provider.id, model = "non-streaming", finishReason = "buffered", outputCharacters = text.length))
            }
        }
        // 1500 = PromptedAiBackend.DEFAULT_MAX_TOKENS di bawah (private di companion object-nya,
        // tidak bisa direferensi langsung dari sini) — HARUS disamakan manual kalau nilai itu
        // pernah diubah di sana.
        return geminiSvc.chatStream(history, AiPrompts.chatSystem(ctx), 1500, chatThinkingLevel)
    }

    suspend fun mindMap(text: String) = run { it.mindMap(text) }
    suspend fun enrichMindMap(structure: String, maxLinks: Int) = run { it.enrichMindMap(structure, maxLinks) }
    suspend fun textAction(action: String, text: String) = run { it.textAction(action, text) }
    suspend fun flashcards(text: String) = run { it.flashcards(text) }
    suspend fun chaptersFromTopic(topic: String, count: Int?) = run { it.chaptersFromTopic(topic, count) }
    suspend fun chaptersFromText(text: String) = run { it.chaptersFromText(text) }

    // [BARU] Embedding buat RAG (Diskusi grounded) — SENGAJA gak lewat run{}/rantai fallback
    // provider kayak method lain di atas: cuma Gemini yang punya kemampuan embedding di app
    // ini (Groq/Cerebras/Custom BYOK gak dijamin punya endpoint embedding yang kompatibel),
    // jadi tetep pakai geminiSvc langsung SEKALIPUN user lagi pakai provider custom buat chat.
    // Kalau nggak ada BYOK-Gemini aktif, geminiSvc otomatis lewat edge proxy (server) buat
    // embedding-nya juga. Kalau itu pun gagal (server nggak ada key Gemini, atau soal jaringan
    // lain), ini gagal jelas (exception) — pemanggil (ViewModel) tangkep itu & DIAMKAN (fitur
    // RAG cuma gak aktif, fitur lain tetep jalan normal).
    suspend fun embed(texts: List<String>, taskType: String) = geminiSvc.embed(texts, taskType)
}
