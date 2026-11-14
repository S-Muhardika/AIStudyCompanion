package com.ruangkerja.companion.editor.core

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.mohamedrejeb.richeditor.model.RichTextState
import java.util.UUID

// Dua editor di app ini (Notebook & Draft) SENGAJA terpisah — lihat editor/ARSITEKTUR_EDITOR.md.
// ── Layer 2 (RKR Editor Engine) — model blok dokumen. compose-rich-editor (Layer 1) TETAP
// dipakai apa adanya, gak di-fork; RichTextState di bawah ini cuma DIPAKAI, bukan diubah.
// Fase 1 dari migrasi arsitektur "jalan tengah" (LAMA, udah kelar): ekstraksi MURNI dari
// NotebookEditor.kt (dulu `private sealed class Block` + `newId()` di sana) — bentuk & isi
// PERSIS SAMA, cuma pindah lokasi & jadi `internal` biar bisa dipake lintas file.
//
// ── MIGRASI CASCADE FASE 1 (BARU, beda penomoran dari migrasi "jalan tengah" di atas — itu
// udah kelar duluan, ini proyek terpisah nyusul). 2 perubahan di file ini:
// 1. `id` sekarang `BlockId` (value class), bukan String mentah — niru pola PERSIS
//    io.github.linreal.cascade.editor.core.BlockId (dicek langsung dari source asli Cascade,
//    bukan tebakan). Zero-cost wrapper (@JvmInline), gak ada overhead runtime tambahan.
//    UUID generation TETAP pakai java.util.UUID (sama kayak sebelumnya & sama kayak dipakai
//    di tempat lain seantero project — Entities.kt, TabMateri.kt) — SENGAJA gak ikut Cascade
//    pindah ke kotlin.uuid.Uuid (butuh @OptIn ExperimentalUuidApi), gak ada alasan kuat ganti
//    mekanisme UUID cuma buat niru persis, Companion Android-only gak butuh multiplatform Uuid.
// 2. Nambah `BlockType` — enum SEDERHANA (Text/Quote/Table doang, beda dari BlockType Cascade
//    yang punya Paragraph/Heading(level)/Todo/BulletList/dst) + `val Block.type` computed
//    property. SENGAJA gak nambah field `type` LITERAL ke tiap subclass (beda dari Cascade):
//    di Cascade banyak tipe BEDA pakai SATU content shape yang sama (BlockContent.Text
//    dipake bareng-bareng sama Paragraph/Heading/Todo/List, jadi butuh field type eksternal
//    biar bisa dibedain). Companion cuma 3 subclass, masing-masing UDAH beda shape lewat
//    sealed class Kotlin-nya sendiri (Text/Quote pegang RichTextState, Table pegang rows) —
//    nambah field type terpisah di sini cuma jadi redundant state yang bisa DESYNC dari
//    subclass aslinya, tanpa manfaat nyata. Computed property = 0 risiko desync, tetep kasih
//    nama yang seragam buat dipake command baru (ConvertBlockType/InsertBlock di
//    EditorCommand.kt).
// RichTextState TETAP di tempatnya PERSIS — TIDAK dihapus/diganti (aturan eksplisit migrasi
// Cascade Fase 1: "Jangan menghapus RichTextState sebelum ada pengganti yang sudah terbukti"). ──
// ── [BARU] Block.Text sekarang pegang NativeTextState (mesin EditText native), BUKAN
// RichTextState lagi — lihat NativeTextState.kt/NativeLiveStyle.kt/NativeTextField.kt buat
// alasan & mekanismenya. Block.Quote TETAP RichTextState buat sesi ini (scope sengaja
// dibatasin ke Teks dulu — itu yang paling sering dipakai & paling berat, Kotak Contoh jauh
// lebih jarang & pendek). Block.Table gak kepengaruh sama sekali (emang gak pernah pakai
// RichTextState). ──
internal sealed class Block(val id: BlockId) {
    class Text(id: BlockId, val nativeState: NativeTextState) : Block(id)
    class Quote(id: BlockId, val state: RichTextState) : Block(id)
    class Table(id: BlockId, val rows: SnapshotStateList<SnapshotStateList<String>>) : Block(id)
}

/** Identitas blok — value class (zero-cost wrapper), niru persis pola BlockId Cascade. */
@JvmInline
internal value class BlockId(val value: String) {
    companion object {
        fun generate(): BlockId = BlockId(UUID.randomUUID().toString())
    }
}

internal fun newId(): BlockId = BlockId.generate()

/** Tipe blok versi enum sederhana — vocabulary buat command baru (InsertBlock/
 * ConvertBlockType di EditorCommand.kt), BUKAN pengganti sealed class di atas. Computed dari
 * subclass asli tiap dipanggil, jadi gak mungkin desync dari kenyataan. */
internal enum class BlockType { Text, Quote, Table }

internal val Block.type: BlockType get() = when (this) {
    is Block.Text -> BlockType.Text
    is Block.Quote -> BlockType.Quote
    is Block.Table -> BlockType.Table
}
