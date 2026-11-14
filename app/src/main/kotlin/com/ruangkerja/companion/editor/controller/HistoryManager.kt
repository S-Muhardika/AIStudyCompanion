package com.ruangkerja.companion.editor.controller

import androidx.compose.runtime.mutableStateListOf

// ── Layer 2 (RKR Editor Engine) — Fase 3 migrasi arsitektur "jalan tengah" (lanjutan
// BlockManager.kt + CommandDispatcher.kt). Bookkeeping stack undo/redo MURNI (push/pop/
// clear) — nyimpen snapshot markdown SELURUH DOKUMEN sebagai String, gak tau apa isi
// dokumennya atau cara nerapin balik snapshot ke blocks (itu tetap tanggung jawab
// NotebookEditor.kt lewat restoreSnapshot(), soalnya nerapin balik butuh BlockManager +
// liveText yang bukan domain HistoryManager). Batas 100 entri — SAMA kayak sebelumnya.
//
// SENGAJA gak include logic debounce "stabil 2 tick @ 300ms" — itu TETAP di
// NotebookEditor.kt (LaunchedEffect polling) karena nyatu sama auto-save & liveText
// tracking yang notabene BUKAN concern history (lihat komentar di LaunchedEffect
// NotebookEditor.kt soal kenapa 1 loop polling ngerjain beberapa hal sekaligus —
// workaround reactivity bug RichTextState). `suppressHistoryPush` juga TETAP di
// NotebookEditor.kt dengan alasan sama — itu koordinasi antara restoreSnapshot() &
// loop polling, bukan state internal history itu sendiri.
internal class HistoryManager {
    private val undoStack = mutableStateListOf<String>()
    private val redoStack = mutableStateListOf<String>()

    val canUndo: Boolean get() = undoStack.size > 1
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Isi snapshot AWAL — dipanggil sekali di awal LaunchedEffect(Unit), sama kayak
     * `undoStack.add(liveText)` yang dulu jadi baris pertama di situ. */
    fun seed(initial: String) {
        undoStack.add(initial)
    }

    /** Dipanggil dari loop polling pas teks udah stabil 2 tick — no-op kalau isinya SAMA
     * kayak snapshot terakhir (persis logic `now != undoStack.lastOrNull()` yang lama). */
    fun pushIfChanged(snapshot: String) {
        if (snapshot == undoStack.lastOrNull()) return
        undoStack.add(snapshot)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    /** Balikin snapshot yang harus di-restore, atau null kalau gak ada histori lagi buat di-undo. */
    fun undo(): String? {
        if (undoStack.size < 2) return null
        redoStack.add(undoStack.removeAt(undoStack.size - 1))
        return undoStack.last()
    }

    /** Balikin snapshot yang harus di-restore, atau null kalau gak ada histori buat di-redo. */
    fun redo(): String? {
        if (redoStack.isEmpty()) return null
        val snap = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(snap)
        return snap
    }
}
