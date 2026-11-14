package com.ruangkerja.companion.editor.commands

import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.ruangkerja.companion.editor.core.BlockId
import com.ruangkerja.companion.editor.core.BlockType

// ── Layer 2 (RKR Editor Engine) — Fase 2 migrasi arsitektur "jalan tengah" (LAMA, udah kelar
// duluan — lanjutan Block.kt + BlockManager.kt). Command = niat pengguna (dari toolbar ATAU
// slash command), TANPA tau caranya dieksekusi — itu tugas CommandDispatcher (editor/
// controller/). UI (NotebookEditor.kt) manggil `dispatcher.dispatch(EditorCommand.ToggleBold)`,
// gak manggil RichTextState/BlockManager method langsung lagi.
//
// Dikumpulin 1 file (bukan 1 file per command kayak sketsa folder awal usulan) karena tiap
// command di sini cuma penanda niat, kebanyakan gak punya data sama sekali — 10 file
// terpisah isinya 1 baris masing-masing nambah lompatan file di editor mobile tanpa nambah
// kejelasan. Kalau nanti ada command yang badannya membesar (butuh state/logic sendiri),
// baru dipecah ke file sendiri.
internal sealed class EditorCommand {
    data object ToggleBold : EditorCommand()
    data object ToggleItalic : EditorCommand()
    data object ToggleUnderline : EditorCommand()
    data object ToggleStrikethrough : EditorCommand()
    data class SetHeading(val style: HeadingStyle) : EditorCommand()
    data object ToggleBulletList : EditorCommand()
    data object ToggleNumberedList : EditorCommand()
    data object ToggleCode : EditorCommand()
    data object InsertTable : EditorCommand()
    data object InsertCallout : EditorCommand()

    // ── [Migrasi Cascade Fase 1, BARU — penomoran beda dari migrasi "jalan tengah" di atas]
    // 5 command struktural baru, niru vocabulary EditorAction Cascade (InsertBlock/
    // DeleteBlock/UpdateBlockContent/ConvertBlockType/MoveBlocks — dicek dari source asli
    // io.github.linreal.cascade.editor.action.EditorAction, bukan tebakan). BELUM ada trigger
    // UI buat kelimanya (belum ada tombol "sisip blok"/drag-reorder/dst di toolbar) — ini
    // "fondasi aman" doang: definisi vocabulary + jalur eksekusi lewat CommandDispatcher,
    // BUKAN fitur baru yang langsung kepake user sesi ini.
    //
    // Beda dari shape Cascade asli di 2 tempat, SENGAJA (source Companion jadi ground truth
    // buat nyesuain, bukan niru buta):
    // - InsertBlock ambil `type: BlockType` (bukan `block: Block` kayak Cascade) — Block
    //   Companion butuh RichTextState/rows yang di-construct FRESH tiap insert (bukan pure
    //   immutable data macam Cascade), jadi BlockManager yang construct instance-nya; command
    //   cuma bilang MAU tipe apa.
    // - UpdateBlockContent ambil `text: String` (bukan `content: BlockContent`) — Companion
    //   belum punya abstraksi BlockContent terpisah (lihat komentar di Block.kt kenapa belum
    //   dibikin buat BlockType-nya juga), "isi" blok Text/Quote di Companion ya teks markdown
    //   yang masuk ke RichTextState.setMarkdown(), String cukup & jujur ke bentuk asli. ──
    data class InsertBlock(val type: BlockType, val atIndex: Int? = null) : EditorCommand()
    data class DeleteBlock(val id: BlockId) : EditorCommand()
    data class UpdateBlockContent(val id: BlockId, val text: String) : EditorCommand()
    data class ConvertBlockType(val id: BlockId, val newType: BlockType) : EditorCommand()
    data class MoveBlock(val id: BlockId, val toIndex: Int) : EditorCommand()
}
