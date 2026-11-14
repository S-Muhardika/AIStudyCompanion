package com.ruangkerja.companion.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Penyimpanan kecil khusus bookkeeping sinkronisasi (watermark terakhir sync, daftar
 * tombstone, timestamp per-chapter buat flashcard set) — SATU file (sync_meta.json).
 * Pola baca-ubah-tulis-seluruh-objek sama persis kayak LocalStorage.kt, tapi jauh lebih
 * sederhana karena cuma satu objek kecil, bukan beberapa list yang bisa gede.
 *
 * SENGAJA terpisah dari LocalStorage.kt yang udah ada — biar fitur sync ini seutuhnya
 * "nempel di atas" tanpa nyentuh sama sekali alur baca-tulis lokal yang udah jalan & teruji.
 */
class SyncMetaStore(context: Context) {
    private val file = File(context.filesDir, "sync_meta.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // Lock biar dua pemanggil beruntun cepat (mis. saveFlashcards dipanggil berkali-kali saat
    // regenerate banyak chapter) nggak baca-ubah-tulis bersamaan dan saling nimpa — pelajaran
    // yang sama kayak race condition initJob yang pernah diperbaiki di LocalStorage.kt.
    private val mutex = Mutex()

    private suspend fun load(): SyncMeta = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) json.decodeFromString(file.readText()) else SyncMeta()
        } catch (_: Exception) { SyncMeta() }
    }

    private suspend fun save(meta: SyncMeta) = withContext(Dispatchers.IO) {
        try { file.writeText(json.encodeToString(meta)) } catch (_: Exception) {}
    }

    /** Dipakai SyncManager di awal syncNow() — baca snapshot saat ini, di luar lock (aman,
     * karena SEMUA perubahan selama satu sync cycle numpuk lewat update() di bawah, bukan lewat
     * pemanggil lain yang nulis bersamaan di tengah-tengah satu sync berlangsung). */
    suspend fun snapshot(): SyncMeta = load()

    suspend fun update(transform: (SyncMeta) -> SyncMeta): SyncMeta = mutex.withLock {
        val updated = transform(load())
        save(updated)
        updated
    }

    suspend fun recordTombstone(entity: String, id: String, deletedAt: Long = System.currentTimeMillis()) {
        update { meta -> meta.copy(tombstones = meta.tombstones + Tombstone(entity, id, deletedAt)) }
    }

    suspend fun touchFlashcardSet(chapterId: String, updatedAt: Long = System.currentTimeMillis()) {
        update { meta -> meta.copy(flashcardSetUpdatedAt = meta.flashcardSetUpdatedAt + (chapterId to updatedAt)) }
    }
}
