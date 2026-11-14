package com.ruangkerja.companion.ui.components

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.ruangkerja.companion.editor.core.HeadingLineMarker
import com.ruangkerja.companion.editor.core.InlineStyleRange
import com.ruangkerja.companion.editor.core.LiveStylePalette
import com.ruangkerja.companion.editor.core.NativeTextState
import com.ruangkerja.companion.editor.core.applyLiveSpans
import com.ruangkerja.companion.editor.core.extractHeadingLines
import com.ruangkerja.companion.editor.core.extractInlineStyles
import com.ruangkerja.companion.ui.theme.AppAccent
import com.ruangkerja.companion.ui.theme.AppTextDim
import com.ruangkerja.companion.ui.theme.AppTextMuted
import com.ruangkerja.companion.ui.theme.Success

/** EditText custom cuma buat expose onSelectionChanged — EditText BAWAAN gak punya listener
 * publik buat ini (TextWatcher cuma nangkep perubahan ISI teks, bukan gerak kursor/seleksi
 * tanpa ngetik, misal pas user TAP doang buat mindahin kursor). */
private class SelectionAwareEditText(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    var onSelectionChange: ((Int, Int) -> Unit)? = null
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChange?.invoke(selStart, selEnd)
    }
}

/**
 * Pengganti BasicRichTextEditor buat Block.Text — EditText Android ASLI lewat AndroidView.
 *
 * [v92] Beda dari v90/v91: gaya inline (Bold/Italic/dst) SEKARANG span ASLI langsung dari
 * [NativeTextState.inlineStyles] — bukan lagi disamarkan dari teks markdown mentah. Buffer
 * EditText murni teks + span, gak ada karakter markdown inline. TextWatcher SEKARANG dua
 * langkah: (1) baca balik span PENANDA yang ADA (extractInlineStyles/extractHeadingLines —
 * posisinya udah otomatis bener digeser Android sendiri), (2) sinkronin ke state, (3) gambar
 * ulang semua span (applyLiveSpans, termasuk styling visual prefix baris yang emang harus
 * discan ulang tiap kali).
 * [FIX15] Heading (HeadingLineMarker) ikutan disinkron di jalur yang SAMA — niru persis pola
 * inlineStyles, per-baris (bukan satu Int headingLevel per-blok kayak [FIX13] yang dibatalkan).
 *
 * SINKRONISASI 2 ARAH Compose↔View sama persis prinsipnya kayak v91 (baca komentar update{}
 * di bawah) — cuma sekarang yang dibandingin bukan cuma teks, tapi teks+gaya+heading, biar gak
 * ke-trigger gambar-ulang tiap kali PADAHAL asalnya dari EditText sendiri (infinite loop).
 */
@Composable
internal fun NativeTextField(
    state: NativeTextState,
    modifier: Modifier = Modifier,
    textColor: Color,
    fontSizeSp: Float = 12f,
    placeholder: String? = null,
    shouldHaveFocus: Boolean = false,
    // [FIX5] Token yang SELALU naik tiap kali pemanggil BENERAN mau minta fokus dipindah ke
    // sini (lihat NotebookEditor.kt/EditorBlockList — dipasok dari focusRequestSeq). Dulu cuma
    // ada shouldHaveFocus (Boolean) — begitu View ini UDAH punya fokus & IME ditutup MANUAL
    // (mis. back sekali, yang di Android cuma nyembunyiin IME, GAK ngilangin View focus),
    // shouldHaveFocus TETEP true dari awal ke awal, gak pernah "berubah" jadi requestFocus()
    // gak pernah keulang & keyboard gak pernah muncul lagi — cuma tap presisi ke baris teks
    // yang jalan (itu lewat jalur sentuhan asli Android, bukan lewat shouldHaveFocus).
    focusRequestToken: Int = 0,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val accentArgb = AppAccent.toArgb()
    val dimArgb = AppTextDim.toArgb()
    val mutedArgb = AppTextMuted.toArgb()
    val successArgb = Success.toArgb()
    val textColorArgb = textColor.toArgb()
    val palette = remember(accentArgb, dimArgb, mutedArgb, successArgb) {
        LiveStylePalette(accent = accentArgb, dim = dimArgb, muted = mutedArgb, success = successArgb, codeBg = 0x22FFFFFF.toInt())
    }
    val appliedSpans = remember { mutableListOf<Any>() }
    // `factory` di AndroidView cuma jalan SEKALI — listener yang di-setup di dalamnya bakal
    // nyimpen closure lambda yang lagi berlaku SAAT ITU doang. `rememberUpdatedState` nyimpen
    // referensi yang SELALU ke-update, listener baca lewat itu (lihat juga catatan v91 yang
    // sama soal ini).
    val currentOnFocusChanged = rememberUpdatedState(onFocusChanged)
    // [FIX5] Nyimpen token TERAKHIR yang udah "dilayani" — array biasa (bukan Compose state),
    // dibaca/ditulis sinkron di dalam update{} doang, gak butuh reaktivitas Compose sendiri.
    val lastAppliedFocusToken = remember { intArrayOf(focusRequestToken - 1) }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            SelectionAwareEditText(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                minHeight = 0
                // [FIX10] Dulu gravity gak pernah di-set eksplisit — gak kelihatan masalahnya
                // karena view-nya emang selalu pas-pasan sama isinya. Begitu [FIX8] ngasih
                // tinggi minimum gede ke blok TERAKHIR (biar tap ruang kosong kena field
                // beneran), teks pendek jadi "ketarik" ke tengah vertikal box yang sekarang jauh
                // lebih tinggi dari isinya. Dikunci ke TOP|START biar teks selalu nempel di
                // atas apapun tinggi box-nya.
                gravity = Gravity.TOP or Gravity.START
                includeFontPadding = false
                textSize = fontSizeSp
                setTextColor(textColorArgb)
                setHintTextColor(dimArgb)
                setText(state.text)
                setSelection(state.selectionStart.coerceIn(0, state.text.length), state.selectionEnd.coerceIn(0, state.text.length))
                hint = placeholder
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                onSelectionChange = { s, e ->
                    state.syncFromEditable(text?.toString().orEmpty(), extractInlineStyles(editableText), extractHeadingLines(editableText), s, e)
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (s == null) return
                        // Baca DULU span yang ADA (posisinya udah digeser bener sama Android
                        // sendiri seiring user ngetik) — baru gambar ulang semuanya (termasuk
                        // styling visual prefix baris yang emang harus discan fresh tiap kali).
                        val extractedStyles = extractInlineStyles(s)
                        val extractedHeadings = extractHeadingLines(s)
                        applyLiveSpans(s, extractedStyles, extractedHeadings, appliedSpans, palette, resources.displayMetrics)
                        state.syncFromEditable(s.toString(), extractedStyles, extractedHeadings, selectionStart, selectionEnd)
                    }
                })
                setOnFocusChangeListener { _, hasFocus -> currentOnFocusChanged.value?.invoke(hasFocus) }
                // setText() di atas TIDAK micu TextWatcher (listener baru kepasang SETELAH
                // baris itu) — jadi span (termasuk styling prefix baris) gak pernah kepasang
                // buat ISI AWAL blok (misal buka catatan lama). Panggil manual sekali di sini.
                applyLiveSpans(editableText, state.inlineStyles, state.headingLines, appliedSpans, palette, resources.displayMetrics)
            }
        },
        update = { editText ->
            // Baca text & inlineStyles/headingLines TANPA SYARAT (subscription Compose ke
            // state yang BENERAN dibaca). Bandingin SEMUANYA (bukan cuma teks) — kalau cuma
            // teks yang dibandingin, toggle format dari toolbar (teks SAMA, cuma gaya/heading
            // berubah) gak bakal ke-detect sebagai "perubahan dari luar", gak pernah ke-render.
            val stateText = state.text
            val stateStyles = state.inlineStyles
            val stateHeadingLines = state.headingLines
            val stateSelStart = state.selectionStart.coerceIn(0, stateText.length)
            val stateSelEnd = state.selectionEnd.coerceIn(0, stateText.length)
            val currentText = editText.text?.toString().orEmpty()
            val currentStyles = extractInlineStyles(editText.editableText)
            val currentHeadingLines = extractHeadingLines(editText.editableText)
            val textChanged = currentText != stateText
            val stylesChanged = !stylesEqual(currentStyles, stateStyles)
            // [FIX15] Ganti dari bandingin Int headingLevel jadi bandingin List<HeadingLineMarker>
            // — toggle Normal/Judul/Subjudul di toolbar (teks & inline style SAMA, cuma heading
            // baris tertentu yang berubah) tetep harus ke-detect biar ke-render ulang.
            val headingLinesChanged = !headingLinesEqual(currentHeadingLines, stateHeadingLines)
            if (textChanged || stylesChanged || headingLinesChanged) {
                if (textChanged) editText.setText(stateText)
                applyLiveSpans(editText.editableText, stateStyles, stateHeadingLines, appliedSpans, palette, editText.resources.displayMetrics)
                editText.setSelection(stateSelStart, stateSelEnd)
            } else if (editText.selectionStart != stateSelStart || editText.selectionEnd != stateSelEnd) {
                // Teks & gaya SAMA, cuma seleksi yang beda (misal tap area kosong = fokus ke
                // akhir dokumen) — dorong seleksi doang.
                editText.setSelection(stateSelStart, stateSelEnd)
            }
            editText.setTextColor(textColorArgb)
            // [FIX5] Minta fokus native SECARA LANGSUNG — interop fokus Compose↔View lewat
            // AndroidView gak selalu reliable, manggil method View aslinya lebih pasti jalan.
            // requestFocus() doang GAK CUKUP buat mastiin keyboard MUNCUL LAGI: kalau EditText
            // ini UDAH punya View focus dari sebelumnya (mis. user nutup keyboard manual/back
            // sekali, yang di Android CUMA nyembunyiin IME, TIDAK ngilangin View focus),
            // requestFocus() jadi no-op (emang udah fokus) dan keyboard tetep gak nongol lagi
            // walau shouldHaveFocus tetep true dari awal. Makanya dicek lewat focusRequestToken
            // (SELALU naik tiap ada permintaan fokus baru dari pemanggil, walau ke blok yang
            // SAMA) — begitu beda dari token terakhir yang dilayani, IME diminta muncul SECARA
            // EKSPLISIT lewat InputMethodManager, bukan cuma andelin efek samping requestFocus().
            if (shouldHaveFocus && focusRequestToken != lastAppliedFocusToken[0]) {
                if (!editText.hasFocus()) editText.requestFocus()
                val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                // [FIX6] SHOW_IMPLICIT bisa DIABAIKAN DIAM-DIAM sama Android kalau sistem
                // "merasa" keyboard baru aja ditutup manual sama user buat View ini — PERSIS
                // skenario tap-area-kosong abis keyboard diturunkan. SHOW_FORCED itu permintaan
                // tegas yang gak kena aturan "hormati penutupan manual barusan" itu.
                imm?.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
            }
            lastAppliedFocusToken[0] = focusRequestToken
        }
    )
}

private fun stylesEqual(a: List<InlineStyleRange>, b: List<InlineStyleRange>): Boolean {
    if (a.size != b.size) return false
    val sa = a.sortedWith(compareBy({ it.start }, { it.end }, { it.style }))
    val sb = b.sortedWith(compareBy({ it.start }, { it.end }, { it.style }))
    return sa == sb
}

private fun headingLinesEqual(a: List<HeadingLineMarker>, b: List<HeadingLineMarker>): Boolean {
    if (a.size != b.size) return false
    val sa = a.sortedWith(compareBy({ it.start }, { it.end }, { it.level }))
    val sb = b.sortedWith(compareBy({ it.start }, { it.end }, { it.level }))
    return sa == sb
}
