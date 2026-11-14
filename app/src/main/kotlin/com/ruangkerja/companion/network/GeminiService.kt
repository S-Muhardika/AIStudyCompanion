package com.ruangkerja.companion.network

import android.content.Context
import com.ruangkerja.companion.BuildConfig
import com.ruangkerja.companion.auth.SupabaseAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Serializable data class GeminiReq(val contents: List<GContent>, val systemInstruction: GSysInstr? = null, val generationConfig: GConfig? = null)
@Serializable data class GContent(val role: String, val parts: List<GPart>)
@Serializable data class GPart(val text: String)
@Serializable data class GSysInstr(val parts: List<GPart>)
// [UBAH, 13 Nov — permintaan William: pilih level thinking Gemini di Diskusi] thinkingConfig
// BARU, opsional. Dibangun lewat thinkingConfigFor() di bawah — TIDAK langsung diisi di sini,
// karena validitasnya tergantung MODEL yang lagi dicoba (lihat catatan lengkap di
// thinkingConfigFor soal kenapa generasi model penting di sini).
@Serializable data class GConfig(val temperature: Float = 0.7f, val maxOutputTokens: Int = 1500, val thinkingConfig: GThinkingConfig? = null)
// [BARU, 13 Nov] Field dicek LANGSUNG ke dokumentasi resmi generateContent (ai.google.dev/api/
// generate-content, GenerationConfig.ThinkingConfig) — BUKAN tebakan: "thinkingLevel" (enum
// LOW/MEDIUM/HIGH, juga ada MINIMAL) eksplisit "Recommended for Gemini 3 or later models. Use
// with earlier models results in an error." Makanya thinkingConfigFor() di bawah SENGAJA cuma
// mengisi field ini kalau model yang dicoba "gemini-3*" — model 2.5 (masih ada di MODELS/
// GEMINI_MODELS sebagai salah satu kandidat fallback) TIDAK PERNAH dikasih field ini.
@Serializable data class GThinkingConfig(val thinkingLevel: String)
// [FIX] DTO RESPONS dibedakan dari DTO request (GContent/GPart). Dulu respons ikut memakai GContent
// (role & parts wajib) dan GPart (text wajib) — padahal Gemini bisa membalas kandidat TANPA `parts`
// (finishReason MAX_TOKENS saat token habis dipakai "thinking" di model 2.5, atau SAFETY), dan
// itu meledak jadi SerializationException mentah di layar. Sekarang semua field respons opsional.
@Serializable data class GeminiResp(val candidates: List<GCandidate>? = null, val error: GError? = null, val promptFeedback: GPromptFeedback? = null)
@Serializable data class GCandidate(val content: GRespContent? = null, val finishReason: String? = null)
@Serializable data class GRespContent(val parts: List<GRespPart>? = null)
@Serializable data class GRespPart(val text: String? = null, val thought: Boolean? = null)
@Serializable data class GPromptFeedback(val blockReason: String? = null)
@Serializable data class GError(val message: String = "", val code: Int = 0, val status: String = "")

// ── [BARU] DTO khusus buat batchEmbedContents — BEDA bentuk dari generateContent di atas,
// dicek langsung dari dokumentasi resmi (ai.google.dev/api/embeddings), bukan tebakan.
// "content" versi embedding gak punya field "role" (beda dari GContent buat chat), makanya
// dibikin tipe terpisah (GEmbedContent) daripada maksa reuse GContent. ──
@Serializable data class GEmbedRequest(val requests: List<GEmbedContentRequest>)
@Serializable data class GEmbedContentRequest(val model: String, val content: GEmbedContent, val embedContentConfig: GEmbedConfig? = null)
@Serializable data class GEmbedContent(val parts: List<GPart>)
@Serializable data class GEmbedConfig(val taskType: String? = null, val outputDimensionality: Int? = null)
@Serializable data class GEmbedResponse(val embeddings: List<GContentEmbedding>? = null, val error: GError? = null)
@Serializable data class GContentEmbedding(val values: List<Float> = emptyList())

// ── [BARU] DTO buat manggil edge function `ai-proxy` (proxy Supabase yang megang key
// Gemini/Groq/Cerebras bawaan di server) — dipakai HANYA kalau nggak ada key BYOK aktif
// (allKeys kosong). Bentuknya SENGAJA polos (role+content, bukan role+parts kayak GContent)
// karena ini kontrak sendiri dgn ai-proxy, bukan format asli Gemini.
// [FIX] `action` TIDAK BOLEH punya nilai default di sini: Json{} di file ini memakai encodeDefaults=false,
// jadi field yang nilainya sama dengan default Kotlin-nya (mis. "chat"/"embed") DIAM-DIAM tidak ikut
// terkirim — persis pola bug NOT NULL di SyncModels (lihat technical-principles). Sekarang wajib
// diisi eksplisit di tempat pemanggilan. ──
@Serializable data class EdgeChatMsg(val role: String, val content: String)
// [UBAH, 25 Okt] `feature` BARU — nama fitur pemanggil (lihat komentar lengkap di
// PromptedAiBackend.complete di AiEngine.kt), dipakai ai-proxy buat milih urutan provider
// per fitur (mind map vs lainnya). TIDAK dikasih default, sama kayak `action` di atas —
// alasan sama: encodeDefaults=false bikin field bernilai-default diam-diam gak ikut terkirim.
// [UBAH, 26 Okt] `deviceId` BARU — ID acak per-instalasi (lihat deviceId() di bawah), dipakai
// ai-proxy buat (1) nyatet statistik pemakaian per provider, (2) batas harian per device biar
// endpoint nggak bisa dihabisin dari luar app kalau URL+anon key ketauan orang lain (lihat
// komentar lengkap soal ini di ai-proxy/index.ts — bukan tembok sempurna, cuma nutup kasus
// paling umum). TIDAK dikasih default juga, sama alasan di atas — meski praktiknya UUID random
// nggak pernah kebetulan sama dengan default manapun, konsisten aja biar nggak ambigu.
// [BARU, 13 Nov — permintaan William: pilih level thinking Gemini di Diskusi] `thinkingLevel`
// BARU, NULLABLE dengan default null — BEDA dari `action`/`feature`/`deviceId` di atas (yang
// SENGAJA tidak dikasih default karena encodeDefaults=false bikin nilai-sama-dengan-default
// diam-diam gak ikut terkirim): di sini defaultnya MEMANG null, dan null MEMANG seharusnya
// tidak ikut terkirim (fitur selain chat, atau chat tanpa level dipilih user, tetap kirim
// body yang sama persis seperti sebelum field ini ada) — jadi TIDAK kena bug yang sama.
// PENTING: field ini SAAT INI diteruskan apa adanya ke ai-proxy tapi BELUM dibaca/diteruskan
// oleh index.ts ke Gemini sama sekali (dicek langsung ke source server, 13 Nov) — jadi utk
// jalur bawaan (non-BYOK), mengisi field ini belum ada efeknya ke respons AI sampai server
// diupdate. Lihat claude/keputusan-implementasi-ai-proxy-04nov2026.md §16 buat spek patch
// server yang persis dibutuhkan.
@Serializable data class EdgeChatReq(val action: String, val system: String, val messages: List<EdgeChatMsg>, val maxTokens: Int, val feature: String, val deviceId: String, val thinkingLevel: String? = null)
@Serializable data class EdgeChatResp(val text: String? = null, val error: String? = null)
@Serializable data class EdgeEmbedReq(val action: String, val texts: List<String>, val taskType: String, val deviceId: String)
@Serializable data class EdgeEmbedResp(val embeddings: List<List<Float>>? = null, val error: String? = null)

// ── [BARU, 26 Okt] DTO buat action="stats" — debug-only, baca statistik pemakaian AI hari ini
// dari ai-proxy. Dipakai AiUsageDebugActivity, BUKAN bagian dari alur AI utama (lihat
// fetchUsageStats() di bawah). Semua field dikasih default biar parsing toleran kalau bentuk
// respons meleset dikit (beda dari DTO request di atas — ini DTO respons, bukan yang dikirim).
@Serializable data class AiUsageReq(val action: String, val deviceId: String)
// [UBAH, 26 Okt] `budget` BARU — anggaran harian GLOBAL yang RKR tetapkan SENDIRI buat provider
// ini (lihat PROVIDER_DAILY_BUDGET di ai-proxy/index.ts), null kalau entah kenapa provider itu
// gak kebagian budget. BUKAN "limit asli provider" — beberapa provider gak expose angka resminya
// sama sekali, lihat komentar panjang di index.ts.
// [BARU, 5 Nov] `configured`/`models`/`activeInRouting` — permintaan William buat bisa debug
// "provider mana yang key-nya udah di-set" langsung dari app (lihat PROVIDER_INFO & usageStats()
// di ai-proxy/index.ts). `configured` TIDAK PERNAH membawa isi key-nya sendiri, cuma ada/kosong.
@Serializable data class ProviderUsage(
    val provider: String = "",
    val count: Int = 0,
    val budget: Int? = null,
    val configured: Boolean = false,
    val models: List<String> = emptyList(),
    val activeInRouting: Boolean = false,
)
@Serializable data class DeviceUsage(val deviceId: String = "", val count: Int = 0, val cap: Int = 0)
// [BARU, 27 Okt] Pemakaian AKUN (bukan device) hari ini — lapisan PERTAMA/utama sejak ai-proxy
// wajib login (lihat ai_usage_account_daily di ai-proxy/index.ts). `device` di atas masih
// dikirim & ditampilkan sebagai sinyal TAMBAHAN (bukan lagi yang utama), konsisten sama catatan
// di planning: device_id boleh jadi lapisan tambahan, bukan identitas utama.
@Serializable data class AccountUsage(val count: Int = 0, val cap: Int = 0)
@Serializable data class AiUsageStatsResp(
    val today: String? = null,
    val providers: List<ProviderUsage>? = null,
    val device: DeviceUsage? = null,
    val account: AccountUsage? = null,
    val error: String? = null
)

class GeminiService(context: Context, private val overrideKey: String? = null) : PromptedAiBackend() {

    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    // [UBAH, migrasi ke edge function] Dulu digabung sama BuildConfig.GEMINI_API_KEY (key
    // bawaan, dari local.properties). Key bawaan sekarang cuma ada di Supabase secrets, jadi
    // TIDAK ADA LAGI di client — allKeys sekarang murni buat jalur BYOK doang (overrideKey dari
    // Settings > Lanjutan, atau pref lama gemini_api_key). Kosong = nggak ada BYOK aktif =
    // call()/embed() di bawah otomatis lewat callEdgeProxy()/embedViaEdgeProxy().
    private val allKeys: List<String>
        get() {
            if (!overrideKey.isNullOrBlank()) return listOf(overrideKey)
            val fromPrefs = prefs.getString("gemini_api_key", "") ?: ""
            return fromPrefs.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
        }

    private val keyIdx = AtomicInteger(0)
    private fun currentKey(): String {
        val k = allKeys
        return if (k.isEmpty()) "" else k[keyIdx.get() % k.size]
    }
    private fun rotateKey() { keyIdx.incrementAndGet() }

    // [BARU, 26 Okt] ID acak dibikin SEKALI pas pertama app ini manggil ai-proxy, disimpan lokal,
    // dipakai terus selanjutnya — cuma penanda "ini instalasi yang sama" buat ai-proxy ngitung
    // pemakaian per device. Reset otomatis kalau app di-uninstall/install ulang (SharedPreferences
    // ikut kehapus) — itu cukup buat tujuannya, nggak perlu lebih permanen dari itu.
    // [UBAH, 27 Okt] SEJAK ai-proxy wajib login, device_id ini TURUN jadi sinyal TAMBAHAN doang
    // (lihat planning: device_id gak boleh jadi identitas utama) — identitas SEBENARNYA sekarang
    // dari user_id hasil verifikasi token di server (lihat edgePost() & ai-proxy/index.ts),
    // bukan dari ID lokal kayak gini yang gampang ke-reset install ulang.
    private fun deviceId(): String {
        prefs.getString("ai_device_id", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("ai_device_id", id).apply()
        return id
    }

    // gemini-2.0-flash & gemini-2.0-flash-lite dihentikan Google per 1 Juni 2026 — fallback sekarang
    // ke gemini-2.5-flash-lite. Halaman deprecations Google (per Sep 2026) juga menyebut akses model
    // 2.5 dibatasi untuk pengguna yang sudah pernah memakainya, dan menyarankan 3.5 Flash-Lite buat
    // proyek baru — makanya gemini-3.5-flash-lite ditaruh PALING AKHIR sebagai cadangan (dicoba
    // otomatis kalau model di depannya 404/429/503; urutan utama tidak berubah).
    private val MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.5-flash-lite")
    private var modelIdx = 0
    private fun currentModel() = MODELS[modelIdx.coerceIn(0, MODELS.size - 1)]
    private fun rotateModel() { if (modelIdx < MODELS.size - 1) modelIdx++ }

    private val json   = Json { ignoreUnknownKeys = true }
    // [UBAH, 9 Nov — tahap 1 reliabilitas AI] Deadline Mind Map server direncanakan
    // 55 detik. Beri ruang 75 detik untuk membaca response + 90 detik hard cap seluruh call,
    // agar client tidak membatalkan request sebelum deadline server selesai.
    // Timeout ini berlaku untuk jalur proxy dan BYOK Gemini.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    // ── Jalur default (bukan BYOK): edge function `ai-proxy` di Supabase ──────────────────────────
    // Key Gemini/Groq/Cerebras bawaan cuma ada di secrets server (nggak pernah nyampe ke APK), dan
    // rantai Gemini→Groq→Cerebras dijalankan server — dari sisi Kotlin cukup 1 kali panggil.
    //
    // [FIX 29 Sep] Versi sebelumnya bikin kegagalan mustahil dibaca:
    //  1. Status HTTP tidak pernah dicek. Balasan gateway Supabase ({"code":401,"message":"Invalid JWT"}
    //     dst.) di-decode ke EdgeChatResp; semua field-nya null (ignoreUnknownKeys) dan yang tampil
    //     cuma "AI tidak merespons" — apa pun penyebabnya.
    //  2. Balasan non-JSON (halaman HTML dari WiFi publik/ISP) melempar SerializationException mentah.
    //  3. `action` di request punya nilai default + encodeDefaults=false → "chat"/"embed" tidak pernah
    //     ikut terkirim (lihat komentar DTO di atas). Sekarang selalu eksplisit.
    //  4. Kunci Supabase jenis baru (sb_publishable_...) BUKAN JWT. Kalau dikirim di header
    //     Authorization: Bearer, gateway menolaknya "Invalid JWT" sebelum function jalan. [SAAT ITU]
    //     kunci publishable hanya lewat header `apikey`; yang ikut jadi Bearer hanya kunci JWT lama.
    //     [SUDAH TIDAK BERLAKU, lihat edgePost() di bawah, 27 Okt] Sejak ai-proxy wajib login,
    //     Authorization SEKARANG SELALU token sesi user (bukan lagi kondisional berdasar bentuk
    //     anon key) — dan ai-proxy di-deploy TANPA "Verify JWT" dimatikan lagi (verify_jwt platform
    //     SEKARANG DINYALAKAN sebagai lapisan tambahan, karena Authorization sekarang selalu JWT
    //     asli). Poin 4 ini dibiarkan di sini sebagai riwayat kenapa kodenya dulu begitu.
    /**
     * [FIX 30 Sep] Alamat Supabase dirapikan sebelum dipakai. Penyebab nyata yang ketemu lewat tes
     * koneksi sementara (fiturnya sudah dihapus lagi): SUPABASE_URL di build tertulis "https://https://xxxx.supabase.co"
     * (skema dobel). Login/sinkron tetap jalan karena supabase-kt mengambil bagian setelah skema
     * TERAKHIR dan menambah "https://" sendiri, sedangkan jalur AI memakai OkHttp yang menolak
     * alamat seperti itu — jadi hanya AI yang gagal, tanpa tanda apa pun di bagian lain aplikasi.
     * Sekarang jalur AI memakai aturan yang sama: ambil bagian setelah skema terakhir, lalu pasang
     * satu skema (http:// hanya kalau itu satu-satunya skema yang tertulis, mis. Supabase lokal).
     * Alamat tanpa skema, spasi, tanda kutip, garis miring terbalik (sisa salah-ketik di
     * local.properties), dan "/" di ujung juga dirapikan.
     */
    private fun supabaseBaseUrl(raw: String = BuildConfig.SUPABASE_URL): String {
        val cleaned = raw.filterNot { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }.removePrefix("//")
        val rest = cleaned.substringAfterLast("://")
        if (rest.isEmpty()) return ""
        val plainHttpOnly = cleaned.contains("http://", ignoreCase = true) && !cleaned.contains("https://", ignoreCase = true)
        return "${if (plainHttpOnly) "http://" else "https://"}$rest".trimEnd('/')
    }

    private val edgeUrl: String get() = "${supabaseBaseUrl()}/functions/v1/ai-proxy"

    /**
     * Kirim [payload] ke ai-proxy; kembalikan (status HTTP, isi respons) APA ADANYA — penafsirannya di pemanggil.
     *
     * [UBAH, 27 Okt — keputusan William "wajib login semua" buat jalur ai-proxy, lihat planning
     * lengkap di percakapan 26-27 Okt & CHANGELOG] Dulu header Authorization isinya anon/publishable
     * key SENDIRI (dan cuma kekirim kalau kebetulan bentuknya JWT, yang buat proyek ini TIDAK
     * PERNAH kejadian — publishable key baru BUKAN JWT). Sekarang Authorization WAJIB berisi token
     * SESI USER yang login (dari SupabaseAuthManager, bukan key aplikasi) — itu satu-satunya cara
     * ai-proxy bisa tau SIAPA yang manggil buat nerapin kuota per akun, bukan cuma per device yang
     * gampang direset. Dicek di SINI (klien) dulu SEBELUM request dikirim — murni biar gagalnya
     * cepat & pesannya jelas (hemat 1 round-trip jaringan buat kasus yang udah pasti bakal ditolak
     * server); BUKAN satu-satunya pertahanan — ai-proxy sendiri tetap wajib verifikasi ulang token
     * ini di server (klien yang sudah di-decompile/dimodif bisa aja lewatin cek ini), lihat
     * komentar di ai-proxy/index.ts. Kunci apikey (anon/publishable, identitas PROYEK Supabase)
     * tetap selalu dikirim seperti biasa — itu beda hal dari identitas USER di Authorization.
     *
     * Jalur BYOK (allKeys terisi) TIDAK PERNAH lewat sini sama sekali (lihat call()/embed() di
     * bawah) — jadi orang yang pakai API key sendiri TETAP tidak perlu login, cuma yang makan
     * jatah AI gratis bawaan William yang sekarang wajib.
     */
    private suspend fun edgePost(payload: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val key = BuildConfig.SUPABASE_ANON_KEY.trim()
        if (supabaseBaseUrl().isEmpty() || key.isBlank())
            throw AiException(AiFailure.CONFIG, "Layanan AI bawaan belum dikonfigurasi pada aplikasi ini.")
        val accessToken = SupabaseAuthManager.currentUser.value?.accessToken
            ?: throw AiException(
                AiFailure.LOGIN_REQUIRED,
                "Fitur AI bawaan sekarang butuh masuk (login) dulu dengan akun Google — buka " +
                    "Pengaturan > Akun, lalu \"Masuk dengan Google\". Ini supaya jatah AI gratisnya " +
                    "bisa dijaga per akun, bukan gampang disalahgunakan orang lain. Kalau tidak mau " +
                    "login, isi API key Gemini/Groq/dll. milik sendiri di Pengaturan > Lanjutan."
            )
        val req = try {
            Request.Builder().url(edgeUrl)
                .header("apikey", key)
                .header("Authorization", "Bearer $accessToken")
                .post(payload.toRequestBody("application/json".toMediaType())).build()
        } catch (e: IllegalArgumentException) {
            throw AiException(AiFailure.CONFIG, "Alamat atau kunci server AI pada aplikasi ini tidak valid.")
        }
        try {
            client.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: IOException) {
            throw AiErrors.network(e, "AI")
        }
    }

    /** Ambil pesan error dari semua bentuk balasan: {"error":"..."} (ai-proxy), {"error":{"message":"..."}}, {"message":"..."} / {"msg":"..."} (gateway Supabase). */
    private fun edgeMessage(body: String): String? {
        val root: JsonElement? = try { json.parseToJsonElement(body) } catch (e: IllegalArgumentException) { null }
        val obj = root as? JsonObject ?: return null
        fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        return str(obj["error"]) ?: str((obj["error"] as? JsonObject)?.get("message")) ?: str(obj["message"]) ?: str(obj["msg"])
    }

    /** Ubah balasan yang BUKAN hasil sukses jadi AiException yang menyebut lapisan penyebabnya. */
    private fun edgeFailure(code: Int, body: String): AiException {
        val msg = edgeMessage(body)
        val isJson = body.trimStart().startsWith("{")
        val detail = msg?.take(100)?.let { ": $it" } ?: ""
        return when {
            // Bukan JSON + status 5xx = gateway/server yang sedang bermasalah (halaman error HTML). Bukan JSON
            // + status lain (mis. 200/403 berisi HTML) = jaringan yang mengalihkan/memblokir permintaan
            // (WiFi publik dengan halaman login, pemblokiran ISP).
            !isJson && code in 500..599 -> AiException(AiFailure.SERVER, "Server AI sedang mengalami gangguan (HTTP $code).")
            !isJson -> AiException(AiFailure.NETWORK, "Respons dari server AI tidak dapat dibaca (HTTP $code). Jaringan Anda mungkin memblokir atau mengalihkan permintaan; coba jaringan lain.")
            // [UBAH, 27 Okt] 401 sekarang paling sering berarti sesi login habis/tidak valid
            // (ai-proxy wajib login buat jalur ai-proxy, lihat edgePost() di atas) — pesan dari
            // server (msg) sudah ditulis supaya siap tampil apa adanya, jadi dipakai langsung
            // kalau ada (bukan dibungkus "akses ditolak" generik yang kurang jelas buat user).
            code == 401 || code == 403 -> AiException(AiFailure.AUTH, msg?.take(200) ?: "Akses ke server AI ditolak (HTTP $code).")
            code == 404 -> AiException(AiFailure.SERVER, "Layanan AI tidak ditemukan di server (HTTP 404).")
            // [UBAH, 26 Okt] Dulu selalu pesan generik "coba lagi dalam beberapa menit" buat SEMUA
            // 429 — sekarang pakai pesan dari body kalau ada (mis. batas harian per device di
            // ai-proxy balikin pesan "coba lagi besok", beda konteks dari provider yang rate-limit
            // sesaat). Fallback ke pesan generik lama kalau body-nya nggak ada pesan terbaca.
            code == 429 -> AiException(AiFailure.QUOTA, msg?.take(160) ?: "Layanan AI sedang dibatasi (HTTP 429). Coba lagi dalam beberapa menit.")
            msg != null -> AiException(AiFailure.SERVER, "Server AI: ${msg.take(160)}")
            code in 200..299 -> AiException(AiFailure.RESPONSE, "Server AI membalas tanpa hasil. Coba lagi dalam beberapa saat.")
            else -> AiException(AiFailure.SERVER, "Server AI sedang mengalami gangguan (HTTP $code).")
        }
    }

    private suspend fun callEdgeProxy(
        messages: List<Pair<String, String>>, sys: String, maxTokens: Int, feature: String, thinkingLevel: String? = null
    ): String {
        val payload = json.encodeToString(
            EdgeChatReq(
                action = "chat",
                system = sys,
                messages = messages.map { (r, t) -> EdgeChatMsg(if (r == "assistant") "assistant" else "user", t) },
                maxTokens = maxTokens,
                feature = feature,
                deviceId = deviceId(),
                thinkingLevel = thinkingLevel
            )
        )
        val (code, body) = edgePost(payload)
        if (code in 200..299) {
            val parsed = try { json.decodeFromString<EdgeChatResp>(body) } catch (e: IllegalArgumentException) { null }
            parsed?.text?.takeIf { it.isNotBlank() }?.let { return it }
        }
        throw edgeFailure(code, body)
    }

    private suspend fun embedViaEdgeProxy(texts: List<String>, taskType: String): List<List<Float>> {
        val payload = json.encodeToString(EdgeEmbedReq(action = "embed", texts = texts, taskType = taskType, deviceId = deviceId()))
        val (code, body) = edgePost(payload)
        if (code in 200..299) {
            val parsed = try { json.decodeFromString<EdgeEmbedResp>(body) } catch (e: IllegalArgumentException) { null }
            parsed?.embeddings?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        throw edgeFailure(code, body)
    }

    /**
     * [BARU, 26 Okt] Debug-only — baca statistik pemakaian AI hari ini dari ai-proxy
     * (action="stats"): berapa kali tiap provider berhasil dipanggil hari ini (gabungan semua
     * device), plus pemakaian+batas device INI sendiri. BUKAN bagian dari alur AI utama — cuma
     * dipakai AiUsageDebugActivity. Numpang edgePost()/edgeMessage() yang sama (termasuk
     * perbaikan bug URL dobel-skema dkk yang sudah ada), bukan bikin koneksi baru dari nol.
     */
    suspend fun fetchUsageStats(): AiUsageStatsResp {
        val payload = json.encodeToString(AiUsageReq(action = "stats", deviceId = deviceId()))
        val (code, body) = edgePost(payload)
        if (code in 200..299) {
            try { return json.decodeFromString<AiUsageStatsResp>(body) } catch (e: IllegalArgumentException) { /* jatuh ke error di bawah */ }
        }
        return AiUsageStatsResp(error = edgeMessage(body) ?: "Gagal mengambil statistik (HTTP $code).")
    }

    private suspend fun call(
        messages: List<Pair<String, String>>,
        sys: String = AiPrompts.SYSTEM,
        retry: Int = 0,
        maxTokens: Int = 1500,
        feature: String = "",
        thinkingLevel: String? = null
    ): String = withContext(Dispatchers.IO) {
        val key = currentKey()
        if (key.isBlank()) return@withContext callEdgeProxy(messages, sys, maxTokens, feature, thinkingLevel)
        val model = currentModel()
        val req = GeminiReq(
            contents = messages.map { (r, t) ->
                GContent(if (r == "assistant") "model" else "user", listOf(GPart(t)))
            },
            systemInstruction = GSysInstr(listOf(GPart(sys))),
            generationConfig  = GConfig(maxOutputTokens = maxTokens, thinkingConfig = thinkingConfigFor(model, thinkingLevel))
        )
        val body = json.encodeToString(req).toRequestBody("application/json".toMediaType())
        val resp = try {
            client.newCall(
                Request.Builder().url("$BASE/$model:generateContent?key=$key").post(body).build()
            ).execute()
        } catch (e: IOException) {
            // [FIX] execute() wajib dibungkus — tanpa itu exception mentah OkHttp (mis. "Unable to
            // resolve host ...") yang nyampe ke UI. Sekarang dibedakan: DNS / timeout / TLS / lainnya.
            throw AiErrors.network(e, "Gemini")
        }
        val code = resp.code
        val respStr = resp.body?.string() ?: throw AiException(AiFailure.RESPONSE, "Respons kosong dari server Gemini.")
        if (code == 429) {
            // [FIX] `retry` dulu dibandingkan ke `allKeys.size` doang, padahal dia juga naik
            // pas rotasi MODEL (bukan cuma rotasi key) — akibatnya kalau ada >1 model per key,
            // budget retry abis duluan SEBELUM semua key kebagian dicoba (nyerah prematur padahal
            // masih ada key valid yang belum sempet dites). Budget yang benar itu MODELS×keys
            // (jumlah kombinasi key×model unik), bukan cuma jumlah key.
            val maxAttempts = MODELS.size * allKeys.size
            if (retry + 1 >= maxAttempts) throw AiException(AiFailure.QUOTA, "Semua model dan key sedang rate limit. Coba lagi dalam beberapa menit.")
            if (modelIdx < MODELS.size - 1) rotateModel() else { rotateKey(); modelIdx = 0 }
            return@withContext call(messages, sys, retry + 1, maxTokens, feature, thinkingLevel)
        }
        val parsed = try { json.decodeFromString<GeminiResp>(respStr) } catch (e: IllegalArgumentException) {
            throw if (code in 200..299) AiException(AiFailure.RESPONSE, "Respons Gemini tidak dapat dibaca (HTTP $code).") else AiErrors.http(code, "Gemini")
        }
        val err = parsed.error
        if (err != null) {
            // [FIX] 404 (model tidak ada / tidak dilayani untuk key ini) dan 503 (model kelebihan beban)
            // dulu langsung nyerah — beda dari 429 yang sudah rotasi. Sekarang coba model berikutnya dulu.
            if ((code == 404 || code == 503) && modelIdx < MODELS.size - 1) {
                rotateModel()
                return@withContext call(messages, sys, retry + 1, maxTokens, feature, thinkingLevel)
            }
            throw geminiError(code, err)
        }
        if (code !in 200..299) throw AiErrors.http(code, "Gemini")
        val cand = parsed.candidates?.firstOrNull()
        val text = cand?.content?.parts?.firstOrNull { it.thought != true && !it.text.isNullOrBlank() }?.text
        if (text != null) return@withContext text
        throw AiException(
            AiFailure.RESPONSE,
            when {
                parsed.promptFeedback?.blockReason != null || cand?.finishReason == "SAFETY" ->
                    "Permintaan diblokir oleh filter keamanan Gemini. Ubah isi teks, lalu coba lagi."
                cand?.finishReason == "MAX_TOKENS" ->
                    "Respons Gemini terpotong sebelum jawaban selesai (batas token tercapai). Coba lagi atau persingkat teks."
                else -> "Model tidak mengembalikan respons. Coba lagi."
            }
        )
    }

    /** [BARU] Embedding buat RAG (Diskusi grounded) — model gemini-embedding-001 (confirmed
     * dari dokumentasi resmi, bukan tebakan), endpoint batchEmbedContents biar 1 panggilan
     * buat banyak chunk sekaligus (bukan 1 chunk = 1 call, ngirit kuota). Numpang PERSIS
     * infrastruktur rotasi key yang udah ada (currentKey/rotateKey) — otomatis kepakein
     * semua akun yang udah dikonfigurasi user, gak ada mekanisme rotasi baru yang perlu dibikin.
     *
     * [taskType] WAJIB beda antara nyimpen materi ("RETRIEVAL_DOCUMENT") vs nyari lewat
     * pertanyaan user ("RETRIEVAL_QUERY") — dua konstanta itu confirmed dari dokumentasi resmi
     * (TaskType enum), model-nya emang didesain beda cara embed teks "dokumen yang disimpen"
     * vs "query buat nyari", ngasih taskType yang salah bikin hasil pencarian kurang akurat.
     *
     * outputDimensionality dipangkas ke 768 (dari default 3072-dimensi model ini) — app nyimpen
     * embedding-nya LOKAL sebagai JSON biasa, vektor 3072 kegedean buat itu (~30-45KB PER
     * CHUNK kalau gak dipangkas). Model ini pakai Matryoshka Representation Learning (MRL,
     * confirmed dari dokumentasi resmi) — didesain justru buat dipotong gini tanpa ngerusak
     * kualitas semantik secara signifikan, beda dari sekadar motong vektor sembarang model. */
    suspend fun embed(texts: List<String>, taskType: String, retry: Int = 0): List<List<Float>> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val key = currentKey()
        if (key.isBlank()) return@withContext embedViaEdgeProxy(texts, taskType)
        val model = "gemini-embedding-001"
        val req = GEmbedRequest(
            requests = texts.map { t ->
                GEmbedContentRequest(
                    model = "models/$model",
                    content = GEmbedContent(listOf(GPart(t))),
                    embedContentConfig = GEmbedConfig(taskType = taskType, outputDimensionality = 768)
                )
            }
        )
        val body = json.encodeToString(req).toRequestBody("application/json".toMediaType())
        val resp = try {
            client.newCall(
                Request.Builder().url("$BASE/$model:batchEmbedContents?key=$key").post(body).build()
            ).execute()
        } catch (e: IOException) {
            throw AiErrors.network(e, "Gemini")
        }
        val code = resp.code
        val respStr = resp.body?.string() ?: throw AiException(AiFailure.RESPONSE, "Respons kosong dari server Gemini.")
        if (code == 429) {
            if (retry < allKeys.size) { rotateKey(); return@withContext embed(texts, taskType, retry + 1) }
            throw AiException(AiFailure.QUOTA, "Semua key sedang rate limit untuk embedding. Coba lagi dalam beberapa menit.")
        }
        val parsed = try { json.decodeFromString<GEmbedResponse>(respStr) } catch (e: IllegalArgumentException) {
            throw if (code in 200..299) AiException(AiFailure.RESPONSE, "Respons embedding Gemini tidak dapat dibaca (HTTP $code).") else AiErrors.http(code, "Gemini")
        }
        val err = parsed.error
        if (err != null) throw geminiError(code, err)
        parsed.embeddings?.map { it.values } ?: throw AiException(AiFailure.RESPONSE, "Model tidak mengembalikan embedding. Coba lagi.")
    }

    /** Satu-satunya titik masuk fitur AI bawaan kelas induk [PromptedAiBackend] ke Gemini (rotasi key/model + fallback ai-proxy ada di call()). */
    override suspend fun complete(messages: List<Pair<String, String>>, sys: String, maxTokens: Int, feature: String, thinkingLevel: String?): String =
        call(messages, sys, maxTokens = maxTokens, feature = feature, thinkingLevel = thinkingLevel)

    /**
     * [BARU, 13 Nov] `thinkingLevel` ("low"/"medium"/"high", dari AiEngine.chatThinkingLevel)
     * HANYA valid dikirim ke model Gemini 3+ — dikonfirmasi LANGSUNG dari dokumentasi resmi
     * generateContent (ai.google.dev/api/generate-content, GenerationConfig.ThinkingConfig):
     * field "thinkingLevel" "Recommended for Gemini 3 or later models. Use with earlier models
     * results in an error." MODELS di atas MENCAMPUR generasi (gemini-2.5-flash,
     * gemini-2.5-flash-lite DULUAN, gemini-3.5-flash-lite di urutan TERAKHIR sebagai cadangan —
     * lihat rotateModel()), dan model yang dicoba bisa beda-beda antar percobaan (429/404/503).
     * Kalau field ini dipaksa ikut utk SEMUA model, percobaan ke model 2.5 pasti error duluan
     * (bukan cuma diabaikan) — jadi SENGAJA dikosongkan sama sekali kalau model yang lagi
     * dicoba bukan generasi 3. Efeknya: pilihan level thinking user "kelewat" pas kebetulan
     * giliran jatuh ke model 2.5 (yang justru paling sering dicoba DULUAN di app ini — lihat
     * urutan MODELS), BUKAN bikin Diskusi gagal total — trade-off yang jauh lebih aman daripada
     * memaksakan & menjamin error.
     */
    private fun thinkingConfigFor(model: String, level: String?): GThinkingConfig? {
        if (level.isNullOrBlank() || !model.startsWith("gemini-3")) return null
        return GThinkingConfig(thinkingLevel = level.uppercase())
    }

    /**
     * [FIX] Menggantikan cleanError() lama yang mencocokkan potongan kata pada pesan mentah. Bug-nya:
     * `raw.contains("rate")` juga cocok dengan "gene-RATE-Content", jadi error 404 model Gemini
     * ("...is not supported for generateContent") tampil sebagai "Kuota API habis sementara. Sedang
     * mencoba model cadangan..." — salah diagnosis, dan sebenarnya tidak ada model cadangan yang dicoba.
     * Sekarang diputuskan dari status HTTP + `status` bawaan Google (RESOURCE_EXHAUSTED, NOT_FOUND, dst.).
     */
    private fun geminiError(code: Int, err: GError): AiException {
        val status = err.status
        val msg = err.message
        return when {
            code == 429 || status == "RESOURCE_EXHAUSTED" ->
                AiException(AiFailure.QUOTA, "Kuota Gemini habis atau dibatasi sementara. Coba lagi dalam beberapa menit.")
            msg.contains("API key not valid", ignoreCase = true) || msg.contains("API_KEY_INVALID", ignoreCase = true) ->
                AiException(AiFailure.AUTH, "API key Gemini tidak valid. Periksa kembali di Pengaturan > Fitur Lanjutan.")
            code == 401 || code == 403 || status == "UNAUTHENTICATED" || status == "PERMISSION_DENIED" ->
                AiException(AiFailure.AUTH, "API key Gemini ditolak atau tidak memiliki akses ke model ini (HTTP $code).")
            code == 404 || status == "NOT_FOUND" ->
                AiException(AiFailure.MODEL, "Model Gemini tidak ditemukan atau tidak tersedia untuk key ini (HTTP $code).")
            code == 503 || status == "UNAVAILABLE" ->
                AiException(AiFailure.SERVER, "Server Gemini sedang sibuk. Coba lagi dalam beberapa saat.")
            code in 500..599 ->
                AiException(AiFailure.SERVER, "Server Gemini sedang mengalami gangguan (HTTP $code).")
            else ->
                AiException(AiFailure.SERVER, "Gemini menolak permintaan (HTTP $code${if (msg.isNotBlank()) ": ${msg.take(100)}" else ""}).")
        }
    }
}
