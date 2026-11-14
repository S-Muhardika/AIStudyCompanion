package com.ruangkerja.companion.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Serializable data class OaMsg(val role: String, val content: String)
@Serializable data class OaReq(val model: String, val messages: List<OaMsg>, val temperature: Float = 0.7f, val max_tokens: Int = 1500)
// [FIX] DTO RESPONS dipisah dari OaMsg (request). Model reasoning (mis. gpt-oss di Groq/Cerebras) bisa
// membalas `content: null` kalau token habis dipakai buat "berpikir" — dulu itu jadi kegagalan parse
// generik ("respons tidak terbaca"). Sekarang content opsional + finish_reason dibaca buat pesan yang tepat.
@Serializable data class OaRespMsg(val content: String? = null)
@Serializable data class OaChoice(val message: OaRespMsg? = null, val finish_reason: String? = null)
@Serializable data class OaErrorBody(val message: String = "")
@Serializable data class OaResp(val choices: List<OaChoice>? = null, val error: OaErrorBody? = null)

/**
 * Backend generik buat provider mana pun yang OpenAI-compatible (chat/completions,
 * auth Bearer di header) — Groq, Cerebras, atau endpoint custom yang user isi sendiri
 * di Settings > Lanjutan. Satu class ini yang bikin arsitektur AI di app nggak kebatas
 * cuma provider yang dikenal — asal OpenAI-compatible, tinggal pasang baseUrl-nya.
 *
 * Katalog model di tier gratis provider kayak gini dikenal gampang berubah (ada laporan
 * katalog Cerebras pernah menyusut drastis dalam hitungan bulan) — makanya kalau model
 * di posisi sekarang balikin 404, otomatis coba model berikutnya di daftar dulu sebelum
 * benar-benar menyerah.
 */
class OpenAiCompatibleService(
    private val baseUrl: String,
    private val keys: List<String>,
    private val models: List<String>,
    private val providerLabel: String
) : PromptedAiBackend() {

    private val keyIdx = AtomicInteger(0)
    private var modelIdx = 0
    private fun currentKey() = if (keys.isEmpty()) "" else keys[keyIdx.get() % keys.size]
    private fun currentModel() = if (models.isEmpty()) "" else models[modelIdx.coerceIn(0, models.size - 1)]

    private val json = Json { ignoreUnknownKeys = true }
    // [UBAH, 9 Nov — tahap 1 reliabilitas AI] Samakan batas HTTP BYOK OpenAI-compatible
    // dengan jalur Gemini. callTimeout menjadi hard cap keseluruhan, readTimeout memberi
    // ruang untuk response panjang tanpa menunggu tanpa batas.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private suspend fun call(
        messages: List<Pair<String, String>>,
        sys: String,
        retry: Int = 0,
        maxTokens: Int = 1500
    ): String = withContext(Dispatchers.IO) {
        val key = currentKey()
        if (key.isBlank()) throw AiException(AiFailure.CONFIG, "Belum ada API key $providerLabel.")
        val model = currentModel()
        if (model.isBlank()) throw AiException(AiFailure.CONFIG, "Belum ada model $providerLabel yang dikonfigurasi.")

        val req = OaReq(
            model = model,
            messages = listOf(OaMsg("system", sys)) +
                messages.map { (r, t) -> OaMsg(if (r == "assistant") "assistant" else "user", t) },
            max_tokens = maxTokens
        )
        val body = json.encodeToString(req).toRequestBody("application/json".toMediaType())
        // [FIX] Base URL custom yang kosong/salah bikin Request.Builder.url() melempar
        // IllegalArgumentException (bukan IOException) — dulu lolos jadi pesan mentah OkHttp.
        val httpReq = try {
            Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            throw AiException(AiFailure.CONFIG, "Alamat server $providerLabel tidak valid. Periksa Konfigurasi API di Pengaturan > Fitur Lanjutan.")
        }
        val resp = try {
            client.newCall(httpReq).execute()
        } catch (e: IOException) {
            throw AiErrors.network(e, providerLabel)
        }
        val code = resp.code
        val respStr = resp.body?.string() ?: throw AiException(AiFailure.RESPONSE, "Respons kosong dari $providerLabel")

        if (code == 429) {
            // [FIX] sama kayak bug di GeminiService.call() — `retry` ke-pakai bareng buat rotasi
            // model MAUPUN key, tapi dibandingkan ke `keys.size` doang. Kalau modelnya >1,
            // budget abis sebelum semua key sempet dicoba. Budget yang bener: models×keys.
            val maxAttempts = models.size * keys.size
            if (retry + 1 >= maxAttempts) throw AiException(AiFailure.QUOTA, "$providerLabel sedang rate limit di semua model/key yang ada.")
            if (modelIdx < models.size - 1) modelIdx++ else { keyIdx.incrementAndGet(); modelIdx = 0 }
            return@withContext call(messages, sys, retry + 1, maxTokens)
        }
        if (code == 401 || code == 403) throw AiException(AiFailure.AUTH, "API key $providerLabel tidak valid atau ditolak.")
        if (code == 404) {
            // Kemungkinan besar nama model ini sudah dihentikan provider — coba model
            // berikutnya di daftar dulu sebelum melempar error ke pemanggil.
            if (modelIdx < models.size - 1) { modelIdx++; return@withContext call(messages, sys, retry + 1, maxTokens) }
            throw AiException(AiFailure.MODEL, "Model \"$model\" di $providerLabel tidak ditemukan (mungkin sudah dihentikan). Cek nama model terbaru di dashboard $providerLabel.")
        }

        val parsed = try { json.decodeFromString<OaResp>(respStr) } catch (e: IllegalArgumentException) {
            throw if (code in 200..299) AiException(AiFailure.RESPONSE, "$providerLabel: respons tidak terbaca (HTTP $code).") else AiErrors.http(code, providerLabel)
        }
        val err = parsed.error
        if (err != null) throw AiException(AiFailure.SERVER, "$providerLabel: ${err.message.take(120)}")
        if (code !in 200..299) throw AiErrors.http(code, providerLabel)
        val choice = parsed.choices?.firstOrNull()
        val text = choice?.message?.content
        if (!text.isNullOrBlank()) return@withContext text
        throw AiException(
            AiFailure.RESPONSE,
            if (choice?.finish_reason == "length")
                "$providerLabel: respons terpotong sebelum jawaban selesai (batas token). Coba lagi atau persingkat teks."
            else "$providerLabel tidak mengembalikan respons. Coba lagi."
        )
    }

    /**
     * Satu-satunya titik masuk fitur AI kelas induk [PromptedAiBackend] ke penyedia kompatibel-OpenAI ini.
     * [UBAH, 25 Okt] Parameter `feature` ditambahkan di PromptedAiBackend buat kebutuhan ai-proxy (lihat
     * AiEngine.kt/GeminiService.kt) — di sini SENGAJA tidak dipakai: jalur BYOK manggil provider asli
     * LANGSUNG pakai key milik user sendiri, bukan lewat ai-proxy, jadi urutan provider per fitur yang
     * diatur di situ tidak relevan buat jalur ini.
     */
    // [UBAH, 13 Nov] Signature bertambah `thinkingLevel` (lihat PromptedAiBackend.complete di
    // AiEngine.kt) tapi SENGAJA diabaikan total di sini — Groq/Cerebras/Custom BYOK gak punya
    // konsep "thinking level" Gemini, dan keputusan eksplisit William: fitur ini "reasoning
    // effort 1 model" (Gemini), BUKAN pemilihan provider/model lain lewat parameter ini.
    override suspend fun complete(messages: List<Pair<String, String>>, sys: String, maxTokens: Int, feature: String, thinkingLevel: String?): String =
        call(messages, sys, maxTokens = maxTokens)

}
