package com.ruangkerja.companion.util

import com.ruangkerja.companion.data.db.Draft

// [BARU, 18 Okt — ekosistem draft ronde 3, masukan William: "nama dari sistem jangan template,
// lebih bervariasi"] Dulu Bab baru SELALU dinamain "Catatan Cepat" hardcode (2 tempat beda di
// AppViewModel.kt: moveDraftToWorkspace & moveDraftToNewChapter). Sekarang heuristik NON-AI
// (konsisten sama filosofi DraftMatcher 19 Sep "gratis & instan > akurat", dan William sendiri
// milih opsi non-AI pas ditanya) — tapi hasilnya di sini CUMA SARAN, bukan final:
// AppViewModel.confirmNewChapterName() tetap nanya & biarin user ganti manual dulu sebelum
// Bab-nya beneran dibikin (masukan William: "perlu konfirmasi user, kalau nggak setuju user
// boleh bikin nama sendiri").
private val FALLBACK_PREFIXES = listOf("Catatan", "Ringkasan", "Seputar", "Materi", "Catatan Singkat")

/** Draft yang punya judul → judul itu sendiri udah nama yang pas & variatif secara alami (beda
 *  tiap draft, gak perlu dihias apa-apa). Draft TANPA judul (draft "Catatan Cepat" memang
 *  sengaja gak punya field judul terpisah — lihat komentar di Entities.kt) → ambil baris
 *  pertama isinya yang gak kosong, dipotong wajar, dikasih prefix ACAK dari beberapa pilihan
 *  (bukan satu kata tetap) biar gak kerasa template pas banyak draft tanpa judul dipindah
 *  beruntun. Draft kosong total (judul & isi) → nama bertanggal, tetap gak hardcode satu string. */
fun generateChapterName(draft: Draft): String {
    val title = draft.title.trim()
    if (title.isNotBlank()) return title
    val firstLine = draft.content.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (firstLine.isBlank()) return "${FALLBACK_PREFIXES.random()} ${DateUtils.formatDisplayDate(System.currentTimeMillis())}"
    val snippet = if (firstLine.length > 40) firstLine.take(40).trimEnd() + "…" else firstLine
    return "${FALLBACK_PREFIXES.random()} $snippet"
}

// [BARU, 19 Okt — masukan William: "untuk draft yang tanpa judul sedikit susah untuk di
// kenali, bisa spoiler dikit gitu isinya"] Diekstrak dari logic yang sebelumnya CUMA ada di
// HomeDrawerRow (HomeDrawer.kt, pola `d.title.ifBlank { baris pertama isinya }`) — sekarang
// jadi satu sumber dipakai bareng 4 tempat: HomeDrawerRow (drawer), popup saran draftMatch,
// dialog "Nama Bab Baru" (ketiganya punya objek Draft hidup), DAN SuggestionRow di
// NotificationCenterScreen (cuma punya snapshot title+content, bukan Draft utuh — makanya
// overload String,String di bawah, dipanggil draft.title/draft.content oleh overload Draft).

/** Judul tampilan draft buat UI — draft BERJUDUL, judulnya sendiri dipakai apa adanya. Draft
 *  TANPA judul: ambil baris pertama isinya yang gak kosong, dipotong sepanjang [maxLen], biar
 *  gampang dikenali tanpa harus buka draft-nya dulu. Draft kosong total (judul & isi) →
 *  "Draft Kosong". */
fun draftDisplayTitle(title: String, content: String, maxLen: Int = 60): String =
    title.ifBlank {
        content.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(maxLen) ?: "Draft Kosong"
    }

fun draftDisplayTitle(draft: Draft, maxLen: Int = 60): String =
    draftDisplayTitle(draft.title, draft.content, maxLen)
