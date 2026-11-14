package com.ruangkerja.companion.data.db

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Workspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // [BARU] Buat sinkronisasi cloud (Last-Write-Wins) — lihat data/sync/SyncManager.kt.
    // Default = createdAt (bukan System.currentTimeMillis() terpisah), konsisten sama pola
    // Draft di bawah: baris yang baru dibuat & belum pernah diedit ulang, "terakhir diubah"-nya
    // ya pas dia dibuat, titik waktu yang SAMA persis, bukan dua pembacaan clock yang beda.
    val updatedAt: Long = createdAt
)

@Serializable
data class Chapter(
    val id: String = UUID.randomUUID().toString(),
    val workspaceId: String,
    val name: String,
    val description: String = "",
    val summary: String? = null,
    val notes: String = "",
    val orderIndex: Int = 0,
    val mindMapJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // [BARU] Buat sinkronisasi cloud (Last-Write-Wins) — lihat data/sync/SyncManager.kt.
    val updatedAt: Long = createdAt
)

@Serializable
data class Material(
    val id: String = UUID.randomUUID().toString(),
    val chapterId: String,
    val title: String = "",
    val content: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // [BARU] Buat sinkronisasi cloud (Last-Write-Wins) — lihat data/sync/SyncManager.kt.
    val updatedAt: Long = createdAt
)

// ── [BARU] Chunk materi buat RAG (Diskusi grounded) — potongan konten Material + vektor
// embedding-nya. Disimpen LOKAL (chunks.json, pola sama kayak entity lain di file ini) —
// keputusan "hybrid": lokal itu PRIMARY, Supabase (kalau dikonfigurasi nanti) OPSIONAL buat
// sync/backup doang, app tetep jalan penuh tanpa itu. chapterId didup dari Material buat query
// cepat "semua chunk di bab ini" tanpa join manual tiap pencarian. */
@Serializable
data class MaterialChunk(
    val id: String = UUID.randomUUID().toString(),
    val materialId: String,
    val chapterId: String,
    val chunkIndex: Int,
    val text: String,
    val embedding: List<Float>,
    val updatedAt: Long = System.currentTimeMillis()
)

// ── Catatan cepat — TANPA Workspace/Chapter, buat kebutuhan mendadak. Sengaja minimal
// (cuma content, gak ada title terpisah) — filosofinya "secepat mungkin nulis", bukan
// terstruktur kayak Material. Ekosistem lanjutan (saran pindah ke Materi/Workspace abis
// ~seminggu) BELUM digarap — masih rencana. ──
@Serializable
data class Draft(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    // [DEPRECATED, 16 Okt — temuan audit notifikasi #2] Cuma nampung SATU id, jadi draft yang
    // di-"Abaikan" ke workspace A lalu belakangan ke workspace B bikin dismissal A KETIMPA &
    // kelupaan. Diganti dismissedMatchWorkspaceIds (Set) di bawah. Field ini DIBIARIN ADA di
    // class-nya BUKAN sebagai tambalan permanen — dia MASIH DIPAKAI, tapi cuma sesaat, sebagai
    // "jembatan baca" migrasi satu-arah: LocalStorage.coreJob (tempat drafts.json dibaca dari
    // disk) langsung gabungin isi field ini ke dismissedMatchWorkspaceIds begitu ke-load TERUS
    // ngosongin field ini lagi (null) di memori — lihat komentar lengkap di sana. Jadi data lama
    // gak pernah ILANG (digabung, bukan dibuang) dan gak ada lagi skenario "bisa nongol lagi
    // sekali abis update" — DraftMatcher/UI selalu baca state yang udah termigrasi sejak app
    // kebuka. Satu-satunya sisa dari field ini: `drafts.json` di disk masih nyimpen key lama
    // itu buat draft yang kebetulan gak pernah disave ulang abis migrasi (gak masalah, gak
    // kebaca/kepake lagi dari sini) — bukan karena logic-nya sengaja dibiarin baca dua sumber.
    val dismissedMatchWorkspaceId: String? = null,
    // [FIX, 16 Okt — temuan audit notifikasi #2] Ganti field di atas — nampung SEMUA id workspace
    // yang pernah di-"Abaikan" buat draft ini (bukan cuma yang terakhir). PENTING: field ini HARUS
    // dibawa manual tiap bikin Draft(...) baru dari draft lama (lihat DraftEditor.kt
    // commitAndClose & autosave) — bukan cuma lewat .copy(), soalnya dua tempat itu konstruksi
    // Draft dari nol tiap kali, sama kayak snoozedMatchUntil/dismissedMatchWorkspaceId yang udah
    // lebih dulu butuh perlakuan sama.
    val dismissedMatchWorkspaceIds: Set<String> = emptySet(),
    // [BARU, Fase 1 ekosistem draft] "Nanti" (snooze) BEDA dari "Abaikan" (field di atas) —
    // "Abaikan" = jangan saranin PASANGAN draft+workspace ini lagi; "Nanti" = jangan saranin
    // APAPUN buat draft ini dulu sampai waktu ini lewat (draft-nya lagi gak mau dipikirin,
    // bukan berarti saran-nya salah). epoch millis; 0 = gak lagi di-snooze.
    val snoozedMatchUntil: Long = 0L
)

@Serializable
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val deadline: String = "",
    val priority: Int = 1,
    val status: Int = 0,
    val workspaceId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val calendarEventId: Long? = null,       // ID event kalender ON-DEVICE (CalendarContract) kalau tugas ini disinkron lewat jalur lama
    // [BARU] ID event di Google Calendar API (akun Google user sendiri, BUKAN kalender
    // default HP) — string bawaan Google, BEDA sifat dari calendarEventId di atas: yang ini
    // valid lintas-device (bukan device-local), jadi SENGAJA diikutkan ke sync cloud di
    // SyncModels.kt (calendarEventId tetap tidak).
    val googleCalendarEventId: String? = null,
    val reminderEnabled: Boolean = false,    // pengingat notifikasi lokal aktif/tidak
    val reminderMinutesBefore: Int = 1440,   // berapa menit sebelum deadline (default 1 hari)
    // [BARU] Buat sinkronisasi cloud (Last-Write-Wins) — lihat data/sync/SyncManager.kt.
    // CATATAN: calendarEventId di atas SENGAJA TIDAK ikut ke server (ID Calendar Provider
    // Android itu device-local, lihat supabase/schema.sql poin 6).
    val updatedAt: Long = createdAt
)

// [BARU, 13 Nov — permintaan William: Diskusi dengan histori multi-thread + pilih level
// thinking, "kayak ai chat pada umumnya"] Satu ChatThread = satu percakapan Diskusi yang bisa
// di-switch/dihapus terpisah dari percakapan lain di bab yang sama. title diisi OTOMATIS dari
// pesan pertama (lihat AppViewModel.sendChat, pakai util/MarkdownPreview.markdownToPlainPreview
// yang sama dengan preview kartu Materi) — TIDAK ada UI rename manual di versi ini, scope
// sengaja dibatasi ke histori multi-thread + pilih level thinking saja, bukan manajemen thread
// penuh (lihat working-agreement soal audit-before-fix/scope eksplisit).
//
// Lokal-only, SENGAJA TIDAK ikut SyncManager — pola sama persis seperti MaterialChunk (RAG) di
// atas: AppRepository.chatThreads/chatMessages TIDAK memanggil syncMeta.touchXxx apa pun. Histori
// chat tidak disinkron ke Supabase/device lain; murni per-device via LocalStorage (chat_threads.json
// / chat_messages.json).
@Serializable
data class ChatThread(
    val id: String = UUID.randomUUID().toString(),
    val chapterId: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

// [UBAH, 13 Nov] Dulu cuma role+text — murni in-memory di AppViewModel._chat, direset tiap
// ganti bab (clearTransientState), TIDAK PERNAH benar-benar tersimpan ke disk. Sekarang
// persisten lewat LocalStorage (chat_messages.json), jadi butuh id (key stabil buat LazyColumn
// + target hapus), threadId (FK ke ChatThread di atas), dan createdAt (urutan tampil per
// thread). AMAN diubah bebas tanpa migrasi data lama — bentuk LAMA tidak pernah benar-benar ada
// di disk sama sekali.
@Serializable
data class ChatMsg(
    val id: String = UUID.randomUUID().toString(),
    val threadId: String,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

// Model Mind Map (MindMapNode/MindMapLink/MindMapData) pindah ke MindMap.kt — pohon bebas kedalaman; InfoType 5-tipe lama dihapus.

data class Flashcard(val id: String = UUID.randomUUID().toString(), val front: String, val back: String)

@Serializable
data class FlashcardData(
    val id: String = UUID.randomUUID().toString(),
    val front: String,
    val back: String
)

// [BARU, Fase 2 ekosistem draft] Riwayat saran pemindahan draft->workspace, buat layar
// Notification Center. id STABIL (draftId+"_"+workspaceId, lihat util/DraftMatcher.kt) biar
// draft yang diedit dikit gak bikin entri baru — entri LAMA di-update aja (lihat spek "duplicate
// prevention"). confidence/status disimpen sebagai String (bukan enum langsung) biar data lama
// tetap kebaca normal walau daftar status/tingkat kepercayaan di kode berubah di masa depan.
// Snapshot judul (draft bisa kehapus/keganti pas udah ACCEPTED) & nama workspace/bab disimpen
// literal di sini, BUKAN look-up live ke tabel Draft/Workspace/Chapter — biar histori tetap
// bisa dibaca walau data aslinya udah berubah/ilang.
@Serializable
data class DraftSuggestion(
    val id: String,
    val draftId: String,
    val draftTitleSnapshot: String,
    // [BARU, 19 Okt — masukan William: "untuk draft yang tanpa judul sedikit susah untuk di
    // kenali, bisa spoiler dikit gitu isinya"] Snapshot ISI draft (bukan cuma judul), dipakai
    // NotificationCenterScreen buat nampilin cuplikan isi pas draft-nya tanpa judul — field
    // baru dengan default "" biar data lama (sebelum field ini ada) tetap kebaca normal,
    // konsisten sama pola resolvedChapterNameSnapshot di bawah.
    val draftContentSnapshot: String = "",
    val workspaceId: String,
    val workspaceNameSnapshot: String,
    val confidence: String, // "MEDIUM" | "HIGH"
    val status: String, // "PENDING" | "SEEN" | "ACCEPTED" | "DISMISSED" | "SNOOZED" | "RESOLVED"
    val resolvedChapterNameSnapshot: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)