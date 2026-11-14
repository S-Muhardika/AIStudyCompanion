package com.ruangkerja.companion.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import com.ruangkerja.companion.ui.theme.*

private val ALIGN_TAG = Regex("^<(center|right)>(.*)</\\1>$")
private val CHECKLIST_MARKER = Regex("^-\\s\\[[ xX]]\\s")
private val NUMBERED_MARKER = Regex("^(\\d+)\\.\\s")
private val IMAGE_TAG = Regex("!\\[[^\\]]*]\\(([^)]+)\\)")
private val TABLE_SEPARATOR_CELL = Regex("^:?-{2,}:?$")
// [BARU, 12 Nov — Tahap 1 arsitektur, 2 gap yang ketemu pas audit] HR_LINE: baris isinya CUMA
// ---/***/___ (≥3 karakter sama) dirender sbg horizontal divider — SEBELUMNYA jatuh ke paragraf
// biasa & tampil mentah apa adanya. LINK_TAG: dipakai di dalam mdInline() (beda dari IMAGE_TAG
// yang dicek per-BARIS) — link SEBELUMNYA gak ditangani sama sekali, "[teks](url)" tampil
// mentah persis. Teks link ditampilin bergaris-bawah + warna aksen (nunjukkin "ini link"),
// SENGAJA belum bisa diketuk (buka URL) — di luar scope perbaikan "markdown mentah kelihatan"
// ini, fitur terpisah kalau suatu saat dibutuhkan.
private val HR_LINE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val LINK_TAG = Regex("\\[([^\\]]*)]\\([^)]*\\)")

// ── Markdown renderer — heading, bold/italic, checklist, list, quote, code, tabel, gambar ──
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, onImageClick: ((String) -> Unit)? = null) {
    val lines = remember(text) { text.split("\n") }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                // code block ```...```
                line.trimStart().startsWith("```") -> {
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { codeLines.add(lines[i]); i++ }
                    if (i < lines.size) i++ // lewati fence penutup
                    Box(Modifier.fillMaxWidth().background(AppBgMuted, RoundedCornerShape(8.dp)).padding(10.dp)) {
                        Text(codeLines.joinToString("\n"), color = AppText, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                    }
                }
                // tabel | a | b |
                line.trimStart().startsWith("|") -> {
                    val tableLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) { tableLines.add(lines[i]); i++ }
                    MarkdownTable(tableLines)
                }
                // perataan paragraf <center>...</center> / <right>...</right>
                ALIGN_TAG.find(line) != null -> {
                    val m = ALIGN_TAG.find(line)!!
                    val align = if (m.groupValues[1] == "center") TextAlign.Center else TextAlign.End
                    Text(
                        mdInline(m.groupValues[2]), color = AppText, fontSize = 12.sp, lineHeight = 20.sp,
                        textAlign = align, modifier = Modifier.fillMaxWidth()
                    )
                    i++
                }
                line.startsWith("# ") -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                        Icon(Icons.Outlined.Bookmark, null, tint = AppAccent, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(mdInline(line.drop(2)), color = AppAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    i++
                }
                line.startsWith("## ") -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp)) {
                        Icon(Icons.Outlined.Bookmark, null, tint = AppAccent, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(mdInline(line.drop(3)), color = AppAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    i++
                }
                line.startsWith("### ") -> { Text(mdInline(line.drop(4)), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)); i++ }
                line.startsWith("> ") -> {
                    // Kumpulin SEMUA baris "> " yang berurutan jadi satu grup — sebelumnya
                    // tiap baris dapet bar aksennya sendiri-sendiri (kelihatan kepotong-potong),
                    // padahal itu satu kategori yang sama ("Contoh"). Sekarang satu bar aksen
                    // nyambung buat seluruh grup, isinya baru dipecah per baris di dalam.
                    val quoteLines = mutableListOf(line.drop(2))
                    i++
                    while (i < lines.size && lines[i].startsWith("> ")) {
                        quoteLines.add(lines[i].drop(2))
                        i++
                    }
                    Row(Modifier.padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(AppAccent.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            quoteLines.forEach { inner ->
                                val displayText = if (inner.startsWith("- ") || inner.startsWith("* ")) "•  " + inner.drop(2) else inner
                                Text(mdInline(displayText), color = AppTextMuted, fontSize = 12.sp, fontStyle = FontStyle.Italic, lineHeight = 20.sp)
                            }
                        }
                    }
                }
                // Horizontal divider ---/***/___ — SEBELUMNYA gak ada case buat ini sama sekali,
                // jatuh ke paragraf biasa (else) & tampil mentah. Dicek SETELAH blockquote/tabel/
                // code-block (biar gak kesenggol separator tabel yang juga pakai "-") tapi SEBELUM
                // checklist/bullet (biar "---" gak kecoba dicocokin ke pola list dulu).
                HR_LINE.matches(line.trim()) -> { HorizontalDivider(color = AppBorder, modifier = Modifier.padding(vertical = 6.dp)); i++ }
                CHECKLIST_MARKER.containsMatchIn(line.trimStart()) -> {
                    // .trimStart() dulu buat deteksi & indent-nya dihitung dari sisa spasi di
                    // depan — sebelumnya list yang DI-INDENT (sub-poin, sering muncul di
                    // Ringkasan hasil AI) nggak kedetect sama sekali karena regex-nya dulu
                    // nempel langsung ke line mentah, jatuh ke paragraf biasa & tanda mentahnya
                    // (misal "*") ikut kelihatan apa adanya.
                    val trimmed = line.trimStart()
                    val indentDp = ((line.length - trimmed.length) / 2 * 14).dp
                    val checked = trimmed.startsWith("- [x", ignoreCase = true)
                    val content = trimmed.replaceFirst(CHECKLIST_MARKER, "")
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp, horizontal = indentDp)) {
                        Icon(
                            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                            tint = if (checked) Success else AppTextDim, modifier = Modifier.size(16.dp).padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            mdInline(content), color = if (checked) AppTextDim else AppText, fontSize = 12.sp, lineHeight = 20.sp,
                            textDecoration = if (checked) TextDecoration.LineThrough else null
                        )
                    }
                    i++
                }
                NUMBERED_MARKER.find(line.trimStart()) != null -> {
                    val trimmed = line.trimStart()
                    val indentDp = ((line.length - trimmed.length) / 2 * 14).dp
                    val m = NUMBERED_MARKER.find(trimmed)!!
                    val num = m.groupValues[1]
                    val content = trimmed.substring(m.value.length)
                    Row(Modifier.padding(vertical = 2.dp, horizontal = indentDp)) {
                        Text("$num.", color = AppAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(22.dp))
                        Text(mdInline(content), color = AppText, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                    i++
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    // Sebelumnya titik bullet-nya digeser pakai .padding(top = 8.dp) SETELAH
                    // .size(5.dp) — urutan modifier itu bikin batas Box efektifnya jadi nggak
                    // presisi lagi buat nyamain baris pertama teks (kelihatan nggak pas kalau
                    // ukuran fontnya beda). Sekarang titiknya ditaro di kotak pembanding yang
                    // tingginya PERSIS samain lineHeight teks, terus dipusatkan di situ —
                    // presisi ngikutin baris pertama berapa pun ukuran fontnya. Deteksinya juga
                    // sekarang trim-aware (lihat catatan di CHECKLIST_MARKER di atas).
                    val trimmed = line.trimStart()
                    val indentDp = ((line.length - trimmed.length) / 2 * 14).dp
                    Row(Modifier.padding(vertical = 2.dp, horizontal = indentDp)) {
                        Box(Modifier.width(14.dp).height(20.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(AppAccent))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(mdInline(trimmed.drop(2)), color = AppText, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                    i++
                }
                line.trimStart().startsWith("![") -> {
                    val path = IMAGE_TAG.find(line)?.groupValues?.get(1)
                    if (path != null) MarkdownImage(path, onImageClick) else Text(line, color = AppTextDim, fontSize = 12.sp)
                    i++
                }
                line.isBlank() -> { Spacer(Modifier.height(4.dp)); i++ }
                else -> { Text(mdInline(line), color = AppText, fontSize = 12.sp, lineHeight = 20.sp); i++ }
            }
        }
    }
}

/** Parse format inline: **bold**, _italic_, dan kode `seperti ini` jadi AnnotatedString. */
private fun mdInline(raw: String): AnnotatedString = buildAnnotatedString {
    var idx = 0
    while (idx < raw.length) {
        when {
            raw.startsWith("**", idx) -> {
                val end = raw.indexOf("**", idx + 2)
                if (end == -1) { append(raw.substring(idx)); idx = raw.length }
                else { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.substring(idx + 2, end)) }; idx = end + 2 }
            }
            raw.startsWith("<u>", idx) -> {
                val end = raw.indexOf("</u>", idx + 3)
                if (end == -1) { append(raw.substring(idx)); idx = raw.length }
                else { withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(raw.substring(idx + 3, end)) }; idx = end + 4 }
            }
            raw.startsWith("~~", idx) -> {
                val end = raw.indexOf("~~", idx + 2)
                if (end == -1) { append(raw.substring(idx)); idx = raw.length }
                else { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(raw.substring(idx + 2, end)) }; idx = end + 2 }
            }
            raw.startsWith("`", idx) -> {
                val end = raw.indexOf("`", idx + 1)
                if (end == -1) { append(raw.substring(idx)); idx = raw.length }
                else { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22FFFFFF))) { append(raw.substring(idx + 1, end)) }; idx = end + 1 }
            }
            // [BARU, 12 Nov] "[teks](url)" — SEBELUMNYA gak ada case ini sama sekali, tampil
            // mentah persis "[teks](url)". Teks-nya doang yang ditampilin (bergaris-bawah,
            // nunjukkin "ini link") — url-nya dibuang, SENGAJA belum diketuk-buka (di luar
            // scope perbaikan "markdown mentah kelihatan" ini).
            // [FIX, 12 Nov — build AndroidIDE William: "Functions which invoke @Composable
            // functions must be marked with the @Composable annotation" / "@Composable
            // invocations can only happen from the context of a @Composable function"]
            // SEBELUMNYA pakai `color = AppAccent` di sini — AppAccent itu `@Composable get()`
            // (baca MaterialTheme.colorScheme.primary, lihat Theme.kt) jadi gak bisa dibaca dari
            // mdInline() yang SENGAJA bukan @Composable. Dibuang warnanya (garis-bawah doang
            // udah cukup nunjukkin "ini link", sama kayak pola underline/strikethrough yang
            // dari awal juga cuma pakai textDecoration tanpa warna sama sekali).
            raw.startsWith("[", idx) -> {
                val m = LINK_TAG.matchAt(raw, idx)
                if (m == null) { append(raw[idx]); idx++ }
                else {
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(m.groupValues[1]) }
                    idx = m.range.last + 1
                }
            }
            (raw.startsWith("*", idx) && !raw.startsWith("**", idx)) || raw.startsWith("_", idx) -> {
                val marker = raw[idx]
                // Syarat markdown standar: nggak ada spasi nempel di dalam pembuka/penutup —
                // bedain "*emphasis*" dari "2 * 3" (perkalian). Penting di sini soalnya fungsi
                // ini BENERAN ngilangin karakter penandanya dari hasil tampilan (bukan cuma
                // nyembunyiin kayak di editor) — kalau ke-anggep italic, tanda kalinya lenyap.
                val validOpen = idx + 1 < raw.length && !raw[idx + 1].isWhitespace()
                if (!validOpen) { append(raw[idx]); idx++; }
                else {
                    val end = raw.indexOf(marker, idx + 1)
                    val validClose = end != -1 && end > idx + 1 && !raw[end - 1].isWhitespace()
                    if (!validClose) { append(raw[idx]); idx++ }
                    else { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(raw.substring(idx + 1, end)) }; idx = end + 1 }
                }
            }
            else -> { append(raw[idx]); idx++ }
        }
    }
}

// [BARU, 12 Nov — Tahap 1 arsitektur, 2 dari 4 titik "markdown mentah kelihatan" yang ketemu
// pas audit: front/back TabFlashcard.kt + catatan TabMindMap.kt] Pengganti drop-in buat Text()
// biasa di tempat yang nampilin SATU field teks pendek (bukan dokumen multi-blok) yang isinya
// bisa aja ada bold/italic/strikethrough/underline/kode dari AI (flashcard/mind map didukung
// "Bantu AI"/"Buat dengan AI") — MarkdownText() PENUH (Column multi-baris, heading/tabel/code-
// block/gambar) gak cocok dipasang di sini (layoutnya beda, flashcard misalnya butuh
// textAlign=Center yang gak didukung MarkdownText). mdInline() doang yang dipakai ulang, jadi
// heading/list/blockquote/tabel/gambar/hr SENGAJA tidak diproses di sini (bukan lupa — field
// ini emang gak diharapkan punya struktur blok). 2 titik LAIN dari audit yang sama (preview
// kartu Materi + judul fallback materi) pakai markdownToPlainPreview() di util/MarkdownPreview.kt
// sebagai gantinya (String polos buat preview satu-baris, beda kebutuhan dari sini yang masih
// butuh AnnotatedString berstyle).
@Composable
fun MarkdownInlineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        mdInline(text), modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
        fontStyle = fontStyle, textAlign = textAlign, lineHeight = lineHeight, maxLines = maxLines, overflow = overflow
    )
}

@Composable
private fun MarkdownTable(rawRows: List<String>) {
    val rows = rawRows.map { r -> r.trim().trim('|').split("|").map { it.trim() } }
    val isSeparator: (List<String>) -> Boolean = { r -> r.isNotEmpty() && r.all { it.isBlank() || TABLE_SEPARATOR_CELL.matches(it) } }
    val dataRows = rows.filterIndexed { idx, r -> !(idx == 1 && isSeparator(r)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, AppAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))) {
        dataRows.forEachIndexed { rIdx, row ->
            Row(Modifier.fillMaxWidth().then(if (rIdx == 0) Modifier.background(AppBgMuted) else Modifier)) {
                row.forEach { cell ->
                    Box(Modifier.weight(1f).border(1.dp, AppAccent.copy(alpha = 0.25f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(cell, color = AppText, fontSize = 12.sp, fontWeight = if (rIdx == 0) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownImage(path: String, onClick: ((String) -> Unit)?) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        bitmap = try {
            val f = java.io.File(path)
            if (!f.exists()) null else {
                // Jaga-jaga buat gambar lama yang sempet tersimpan mentah sebelum ada fix
                // downsampling di copyImageToLocal — decode dengan batas ukuran juga di sini,
                // bukan cuma pas nyimpen, biar dua-duanya aman dari risiko OutOfMemory.
                val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, boundsOpts)
                val maxDim = 1280
                var sampleSize = 1
                while (boundsOpts.outWidth / (sampleSize * 2) >= maxDim || boundsOpts.outHeight / (sampleSize * 2) >= maxDim) sampleSize *= 2
                android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize })
            }
        } catch (_: Exception) { null }
        failed = bitmap == null
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(), contentDescription = null,
            modifier = Modifier
                .fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp))
                .then(if (onClick != null) Modifier.clickable { onClick(path) } else Modifier)
                .padding(vertical = 4.dp),
            contentScale = ContentScale.Crop
        )
        failed -> Box(Modifier.fillMaxWidth().height(100.dp).background(AppBgMuted, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.BrokenImage, null, tint = AppTextDim, modifier = Modifier.size(22.dp))
        }
        else -> Box(Modifier.fillMaxWidth().height(100.dp).background(AppBgMuted, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppAccent)
        }
    }
}
