package com.ruangkerja.companion.data.sync

import com.ruangkerja.companion.data.db.Chapter
import com.ruangkerja.companion.data.db.Draft
import com.ruangkerja.companion.data.db.FlashcardData
import com.ruangkerja.companion.data.db.Material
import com.ruangkerja.companion.data.db.Task
import com.ruangkerja.companion.data.db.Workspace
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────
// DTO KHUSUS buat komunikasi lewat Postgrest — SENGAJA terpisah dari
// Entities.kt (Workspace/Chapter/dkk), bukan numpang @SerialName langsung di
// kelas yang sama. Alasannya:
//  1. Kolom Postgres pakai snake_case (konvensi SQL standar di schema.sql),
//     sementara Entities.kt pakai camelCase (konvensi Kotlin) — DTO ini yang
//     jembatanin lewat @SerialName, tanpa numpuk anotasi remote-only di kelas
//     yang JUGA dipakai buat format file JSON lokal (workspaces.json, dst).
//  2. Ada field yang CUMA relevan pas sinkron (userId, deletedAt) yang
//     SENGAJA nggak nyampur ke Entities.kt/LocalStorage.kt — biar format
//     file lokal & alur baca-tulis lokal yang udah jalan sama sekali nggak
//     kesenggol nambahin fitur ini.
//  3. Ada field LOKAL yang SENGAJA nggak ikut ke server (Task.calendarEventId
//     — ID Calendar Provider Android yang cuma valid di device asalnya).
//     Lihat catatan lengkap di supabase/schema.sql poin 6.
//
// ⚠️ [FIX] Field yang map ke kolom NOT NULL di schema.sql (description, notes, pinned, title,
// deadline, priority, status, orderIndex, reminderEnabled, reminderMinutesBefore, cards) SENGAJA
// TIDAK dikasih default value Kotlin (kayak `= ""` / `= false` / `= 0`), walau field yang sama di
// Entities.kt PUNYA default. Ini bukan lupa — ini FIX buat bug nyata: kotlinx.serialization by
// default (encodeDefaults = false) MENGHILANGKAN dari JSON field mana pun yang nilainya kebetulan
// SAMA PERSIS sama default Kotlin-nya. Kalau dalam SATU batch upsert ada baris yang nilainya beda
// dari default (field-nya KEBAWA di JSON) dan baris LAIN yang nilainya pas sama default (field-nya
// HILANG dari JSON), PostgREST insert NULL literal buat baris yang "hilang" itu — BUKAN pakai
// DEFAULT kolom di database (itu cuma kejadian kalau ada header Prefer: missing=default, yang
// TIDAK dikirim postgrest-kt secara default) — nabrak NOT NULL constraint. Field yang beneran
// nullable di database (summary, mind_map_json, workspace_id-nya Task, deleted_at) TETAP dikasih
// default Kotlin karena omisi di situ memang aman (kolomnya sendiri nullable).
// ─────────────────────────────────────────────────────────────────────────

@Serializable
data class RemoteWorkspace(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val description: String,
    val pinned: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null
)

fun Workspace.toRemote(userId: String): RemoteWorkspace = RemoteWorkspace(
    id = id, userId = userId, name = name, description = description, pinned = pinned,
    createdAt = createdAt, updatedAt = updatedAt
)

fun RemoteWorkspace.toLocal(): Workspace = Workspace(
    id = id, name = name, description = description, pinned = pinned,
    createdAt = createdAt, updatedAt = updatedAt
)

@Serializable
data class RemoteChapter(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val name: String,
    val description: String,
    val summary: String? = null,
    val notes: String,
    @SerialName("order_index") val orderIndex: Int,
    @SerialName("mind_map_json") val mindMapJson: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null
)

fun Chapter.toRemote(userId: String): RemoteChapter = RemoteChapter(
    id = id, userId = userId, workspaceId = workspaceId, name = name, description = description,
    summary = summary, notes = notes, orderIndex = orderIndex, mindMapJson = mindMapJson,
    createdAt = createdAt, updatedAt = updatedAt
)

fun RemoteChapter.toLocal(): Chapter = Chapter(
    id = id, workspaceId = workspaceId, name = name, description = description, summary = summary,
    notes = notes, orderIndex = orderIndex, mindMapJson = mindMapJson,
    createdAt = createdAt, updatedAt = updatedAt
)

@Serializable
data class RemoteMaterial(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("chapter_id") val chapterId: String,
    val title: String,
    val content: String,
    val pinned: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null
)

fun Material.toRemote(userId: String): RemoteMaterial = RemoteMaterial(
    id = id, userId = userId, chapterId = chapterId, title = title, content = content,
    pinned = pinned, createdAt = createdAt, updatedAt = updatedAt
)

fun RemoteMaterial.toLocal(): Material = Material(
    id = id, chapterId = chapterId, title = title, content = content, pinned = pinned,
    createdAt = createdAt, updatedAt = updatedAt
)

@Serializable
data class RemoteTask(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    val description: String,
    val deadline: String,
    val priority: Int,
    val status: Int,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("reminder_enabled") val reminderEnabled: Boolean,
    @SerialName("reminder_minutes_before") val reminderMinutesBefore: Int,
    // [BARU] Beda dari calendarEventId (device-local, sengaja gak ikut) — ini ID Google
    // Calendar API, valid lintas-device, jadi SENGAJA diikutkan ke sync.
    @SerialName("google_calendar_event_id") val googleCalendarEventId: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null
    // calendarEventId SENGAJA TIDAK ADA — lihat catatan header file ini & schema.sql.
)

fun Task.toRemote(userId: String): RemoteTask = RemoteTask(
    id = id, userId = userId, title = title, description = description, deadline = deadline,
    priority = priority, status = status, workspaceId = workspaceId,
    reminderEnabled = reminderEnabled, reminderMinutesBefore = reminderMinutesBefore,
    googleCalendarEventId = googleCalendarEventId,
    createdAt = createdAt, updatedAt = updatedAt
)

// calendarEventId TIDAK diisi dari sini (selalu null) — field ini device-local, remote nggak
// pernah membawanya. Nilai LOKAL yang sudah ada (kalau task-nya udah ada sebelumnya di device
// ini) di-merge belakangan di AppRepository.applyRemoteUpsertTask(), bukan di sini, biar mapper
// di file ini tetap "polos" (murni konversi bentuk data, tanpa perlu tau soal apa yang udah ada
// di local storage). googleCalendarEventId BEDA — itu valid lintas-device, jadi diisi LANGSUNG
// dari remote di sini kayak field lain, tanpa perlu di-merge lagi di AppRepository.
fun RemoteTask.toLocal(): Task = Task(
    id = id, title = title, description = description, deadline = deadline, priority = priority,
    status = status, workspaceId = workspaceId, createdAt = createdAt,
    calendarEventId = null, googleCalendarEventId = googleCalendarEventId, reminderEnabled = reminderEnabled,
    reminderMinutesBefore = reminderMinutesBefore, updatedAt = updatedAt
)

@Serializable
data class RemoteDraft(
    val id: String,
    @SerialName("user_id") val userId: String,
    val content: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null
)

fun Draft.toRemote(userId: String): RemoteDraft = RemoteDraft(
    id = id, userId = userId, content = content, createdAt = createdAt, updatedAt = updatedAt
)

fun RemoteDraft.toLocal(): Draft = Draft(id = id, content = content, createdAt = createdAt, updatedAt = updatedAt)

// Bukan turunan Workspace/Chapter/dkk — ini mewakili SATU BARIS flashcard_sets utuh
// (chapter_id + seluruh isi kartunya), bukan satu FlashcardData tunggal. Lihat catatan
// panjang soal ini di supabase/schema.sql.
@Serializable
data class RemoteFlashcardSet(
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("user_id") val userId: String,
    val cards: List<FlashcardData>,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null
)

// ─────────────────────────────────────────────────────────────────────────
// Bookkeeping sinkronisasi — disimpen di SATU file kecil terpisah
// (sync_meta.json, lihat SyncMetaStore.kt), TIDAK numpang ke file JSON
// LocalStorage.kt yang udah ada. Isinya:
//  - lastSyncedAt: watermark global, dasar buat query "updated_at > ini" pas
//    push/pull SEMUA entity (kecuali flashcard, lihat di bawah).
//  - tombstones: daftar ID yang baru dihapus LOKAL sejak sync terakhir, biar
//    push berikutnya tau device lain juga perlu diberitahu buat soft-delete
//    baris yang sama di server (dikosongkan lagi begitu berhasil di-push).
//  - flashcardSetUpdatedAt: PENGGANTI updatedAt buat flashcard_sets, karena
//    FlashcardData/LocalStorage nggak nyimpen timestamp per-chapter sama
//    sekali (cuma Map<chapterId, List<FlashcardData>> polos). Di-update tiap
//    kali AppRepository.saveFlashcards() dipanggil. Chapter yang BELUM PERNAH
//    tercatat di sini (data lama dari SEBELUM fitur sync ini ada) tetap ikut
//    ke-push di sinkronisasi pertama — lihat SyncManager.pushFlashcardSets().
// ─────────────────────────────────────────────────────────────────────────
@Serializable
data class Tombstone(val entity: String, val id: String, val deletedAt: Long)

// ─────────────────────────────────────────────────────────────────────────
// syncedAccountId/syncedAccountEmail — [BARU] jawaban buat kasus "device yang sama, akun Google
// yang beda" (logout akun 1, login akun 2 di HP yang SAMA). LocalStorage.kt TIDAK memisahkan
// data per akun (satu set file JSON aja, siapa pun yang login) — kalau dibiarkan, sync polos
// bakal nge-push data akun 1 ke cloud-nya akun 2, atau nyampur pull akun 2 ke data lokal akun 1.
//
// syncedAccountId dicatat begitu SUKSES sync pertama kali sama suatu akun. Tiap syncNow()
// berikutnya BANDINGIN akun yang lagi login sekarang sama nilai ini DULU (murni perbandingan
// lokal, TANPA butuh jaringan) — kalau beda, syncNow() nolak jalan sama sekali (lihat
// SyncManager.checkAccount) sampai user EKSPLISIT milih "pindah akun" lewat
// SyncManager.switchAccountAndPullFresh(), yang backup dulu baru bersihin lokal baru pull fresh.
//
// syncedAccountEmail CUMA buat ditampilin ke user ("akun terakhir: budi@gmail.com") — bukan
// dasar perbandingan (yang dipakai banding tetap syncedAccountId, id Supabase yang stabil).
// ─────────────────────────────────────────────────────────────────────────
@Serializable
data class SyncMeta(
    val lastSyncedAt: Long = 0L,
    val tombstones: List<Tombstone> = emptyList(),
    val flashcardSetUpdatedAt: Map<String, Long> = emptyMap(),
    val syncedAccountId: String? = null,
    val syncedAccountEmail: String? = null
)

/** Nama entity dipakai sebagai penanda tombstone (bukan nama tabel Postgres langsung,
 * biar SyncManager yang nentuin mapping ke tabel — satu tempat kalau nama tabel berubah). */
object SyncEntity {
    const val WORKSPACE = "workspace"
    const val CHAPTER = "chapter"
    const val MATERIAL = "material"
    const val TASK = "task"
    const val DRAFT = "draft"
    const val FLASHCARD_SET = "flashcard_set"
}
