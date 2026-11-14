package com.ruangkerja.companion.editor.controller

import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.ruangkerja.companion.editor.commands.EditorCommand
import com.ruangkerja.companion.editor.core.Block
import com.ruangkerja.companion.editor.core.InlineStyle
import com.ruangkerja.companion.editor.core.NativeTextState
import com.ruangkerja.companion.editor.core.lineBoundsAt
import com.ruangkerja.companion.editor.core.toggleBulletPrefix
import com.ruangkerja.companion.editor.core.toggleInlineStyle
import com.ruangkerja.companion.editor.core.toggleNumberedPrefix

// ── Layer 2 (RKR Editor Engine) — Fase 2. Satu-satunya tempat yang tau CARA eksekusi
// EditorCommand.
//
// ── [PEROMBAKAN BESAR — mesin native] Command level-span/paragraf (Toggle*/SetHeading)
// SEKARANG NYABANG jadi 2 jalur tergantung tipe blok yang lagi fokus — Block.Text pakai
// fungsi span-based (bukan lagi manipulasi teks mentah, lihat editor/core/NativeFormatting.kt),
// Block.Quote TETAP pakai API RichTextState kayak sebelumnya (state.toggleSpanStyle dkk).
// Kalau gak ada blok teks yang fokus, no-op — SAMA PERSIS perilaku dari awal. ──
internal class CommandDispatcher(private val blockManager: BlockManager) {
    fun dispatch(command: EditorCommand) {
        if (command == EditorCommand.InsertTable) { blockManager.insertTableAtCursor(); return }
        if (command == EditorCommand.InsertCallout) { blockManager.insertQuoteAtCursor(); return }
        if (command is EditorCommand.InsertBlock) { blockManager.insertBlock(command.type, command.atIndex); return }
        if (command is EditorCommand.DeleteBlock) { blockManager.deleteBlock(command.id); return }
        if (command is EditorCommand.UpdateBlockContent) { blockManager.updateBlockContent(command.id, command.text); return }
        if (command is EditorCommand.ConvertBlockType) { blockManager.convertBlockType(command.id, command.newType); return }
        if (command is EditorCommand.MoveBlock) { blockManager.moveBlock(command.id, command.toIndex); return }

        when (val focused = blockManager.focusedBlock()) {
            is Block.Text -> dispatchNative(focused.nativeState, command)
            is Block.Quote -> dispatchRich(focused.state, command)
            else -> Unit
        }
    }

    private fun headingLevelFor(style: HeadingStyle): Int = when (style) {
        HeadingStyle.H1 -> 1
        HeadingStyle.H2 -> 2
        else -> 0
    }

    private fun dispatchNative(s: NativeTextState, command: EditorCommand) {
        when (command) {
            EditorCommand.ToggleBold -> toggleInlineStyle(s.text, s.inlineStyles, s.selectionStart, s.selectionEnd, InlineStyle.Bold).let { s.applyStyled(it.text, it.styles, it.selectionStart, it.selectionEnd) }
            EditorCommand.ToggleItalic -> toggleInlineStyle(s.text, s.inlineStyles, s.selectionStart, s.selectionEnd, InlineStyle.Italic).let { s.applyStyled(it.text, it.styles, it.selectionStart, it.selectionEnd) }
            EditorCommand.ToggleUnderline -> toggleInlineStyle(s.text, s.inlineStyles, s.selectionStart, s.selectionEnd, InlineStyle.Underline).let { s.applyStyled(it.text, it.styles, it.selectionStart, it.selectionEnd) }
            EditorCommand.ToggleStrikethrough -> toggleInlineStyle(s.text, s.inlineStyles, s.selectionStart, s.selectionEnd, InlineStyle.Strikethrough).let { s.applyStyled(it.text, it.styles, it.selectionStart, it.selectionEnd) }
            EditorCommand.ToggleCode -> toggleInlineStyle(s.text, s.inlineStyles, s.selectionStart, s.selectionEnd, InlineStyle.Code).let { s.applyStyled(it.text, it.styles, it.selectionStart, it.selectionEnd) }
            // [FIX13→FIX15] [FIX13] sempat coba toggle satu Int headingLevel per BLOK PENUH —
            // SALAH, karena satu blok rutin berisi banyak paragraf sekaligus (lihat catatan
            // panjang di MarkdownStyleCodec.kt), jadi toggle Subjudul di satu baris bikin
            // SELURUH blok ikut kegedein. SEKARANG toggle di baris tempat kursor doang
            // (lineBoundsAt, sama caranya kayak bullet/nomor di bawah) — baris lain di blok
            // yang sama sama sekali gak kesentuh.
            is EditorCommand.SetHeading -> {
                val level = headingLevelFor(command.style)
                val bounds = lineBoundsAt(s.text, s.selectionEnd)
                s.toggleHeadingAtLine(bounds.first, bounds.last, level)
            }
            EditorCommand.ToggleBulletList -> toggleBulletPrefix(s.text, s.inlineStyles, s.selectionEnd).let { s.applyStyled(it.text, it.styles, it.selectionStart, it.selectionEnd) }
            EditorCommand.ToggleNumberedList -> toggleNumberedPrefix(s.text, s.inlineStyles, s.selectionEnd).let { s.applyStyled(it.text, it.styles, it.selectionStart, it.selectionEnd) }
            else -> Unit
        }
    }

    private fun dispatchRich(state: RichTextState, command: EditorCommand) {
        when (command) {
            EditorCommand.ToggleBold -> state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            EditorCommand.ToggleItalic -> state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            EditorCommand.ToggleUnderline -> state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
            EditorCommand.ToggleStrikethrough -> state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
            is EditorCommand.SetHeading -> state.setHeadingStyle(command.style)
            EditorCommand.ToggleBulletList -> state.toggleUnorderedList()
            EditorCommand.ToggleNumberedList -> state.toggleOrderedList()
            EditorCommand.ToggleCode -> state.toggleCodeSpan()
            else -> Unit
        }
    }
}
