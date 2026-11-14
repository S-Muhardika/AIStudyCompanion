package com.ruangkerja.companion.util

import com.ruangkerja.companion.data.db.Draft
import com.ruangkerja.companion.data.db.Workspace

// [BARU] "Ekosistem draft" — cocokin draft ke workspace pakai KATA KUNCI doang, BUKAN AI
// (keputusan William 19 Sep: gratis & instan lebih penting daripada akurasi maksimal buat
// fitur ini). Kata kunci diambil dari NAMA workspace doang (bukan isi chapter/materi di
// dalamnya) — sengaja dangkal biar tetep simpel & murah dihitung tiap draft ditutup.

private val STOPWORDS = setOf(
    "dan", "yang", "di", "ke", "dari", "untuk", "atau", "dengan", "pada", "dalam",
    "ini", "itu", "juga", "saja", "adalah", "akan", "oleh", "para", "sang", "si", "ada"
)

/** Pecah nama workspace jadi kata-kata "bermakna" — huruf kecil semua, buang kata umum
 *  Indonesia & kata pendek (<4 huruf) SELAMA masih ada kata lain yang cukup panjang buat
 *  jadi penanda. [FIX] Kalau HABIS difilter gitu hasilnya malah kosong sama sekali — kasus
 *  umum: nama workspace-nya singkatan pendek kayak "IPA"/"IPS"/"PPKn" yang tiap katanya
 *  <4 huruf — filter itu dilonggarin (≥2 huruf) KHUSUS buat kasus ini, karena singkatan
 *  pendek justru penanda paling spesifik, bukan kata umum yang perlu disaring. Whole-word
 *  boundary match di findMatchingWorkspace() tetap jaga dari false-positive substring. */
private fun keywordsOf(name: String): List<String> {
    val words = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
    val significant = words.filter { it.length >= 4 && it !in STOPWORDS }
    return significant.ifEmpty { words.filter { it.length >= 2 && it !in STOPWORDS } }.distinct()
}

/** [BARU, Fase 1] Cuma dua tingkat karena tanpa AI gak ada dasar buat lebih dari ini —
 *  1 kata kunci nyantol = MEDIUM (spek: "boleh ditampilkan sebagai suggestion"), 2+ = HIGH
 *  (spek: "suggestion yang lebih terlihat"). LOW (0 kata kunci nyantol) = gak disaranin sama
 *  sekali, ditangani lewat findMatchingWorkspace() yang return null. */
enum class MatchConfidence { MEDIUM, HIGH }

data class DraftMatch(val workspace: Workspace, val confidence: MatchConfidence)

// [BARU, 17 Okt — masukan William: pop-up/notifikasi jangan instan, tunggu draft "didiemin"
// dulu] Dipakai BERSAMA oleh AppViewModel.checkDormantMatches() (pop-up dalam-app) DAN
// DraftSuggestionCheckReceiver (notifikasi Android harian) — satu angka, dua tempat pakai,
// biar gak ada risiko dua sisi diam-diam beda nilai. "Didiemin" diukur dari Draft.updatedAt
// (BUKAN timestamp baru terpisah): draft yang masih aktif diedit otomatis nunda terus karena
// updatedAt-nya ikut maju tiap disave, draft yang beneran didiemin baru lewat ambang ini.
const val DRAFT_DORMANT_MS = 7L * 24 * 60 * 60 * 1000L

/**
 * Cari workspace yang paling "cocok" buat [draft], berdasarkan berapa banyak kata kunci
 * (dari nama workspace) yang nongol UTUH (whole-word, regex \b — bukan potongan substring,
 * jadi "IPA" gak ke-anggep cocok cuma gara-gara nemu "sipa" atau semacamnya) di judul+isi
 * draft. Workspace yang sudah pernah di-dismiss KHUSUS UNTUK draft ini dilewati (lihat
 * Draft.dismissedMatchWorkspaceIds — [FIX, 16 Okt] dulu field tunggal gampang ketimpa kalau
 * draft sempat di-dismiss ke lebih dari satu workspace beda waktu, sekarang Set jadi SEMUA
 * yang pernah di-dismiss tetap diinget, bukan cuma yang terakhir), tapi tetap bisa disaranin
 * ulang buat draft lain.
 *
 * Return null kalau nggak ada satu pun workspace yang kata kuncinya nyantol (LOW = no-op,
 * sesuai spek — bukan cuma "confidence rendah" tapi beneran gak disaranin sama sekali).
 */
fun findMatchingWorkspace(draft: Draft, workspaces: List<Workspace>): DraftMatch? {
    val haystack = "${draft.title} ${draft.content}".lowercase()
    return workspaces
        .asSequence()
        .filter { it.id !in draft.dismissedMatchWorkspaceIds }
        .map { ws -> ws to keywordsOf(ws.name).count { kw -> Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(haystack) } }
        .filter { (_, hits) -> hits > 0 }
        .maxByOrNull { (_, hits) -> hits }
        ?.let { (ws, hits) -> DraftMatch(ws, if (hits >= 2) MatchConfidence.HIGH else MatchConfidence.MEDIUM) }
}
