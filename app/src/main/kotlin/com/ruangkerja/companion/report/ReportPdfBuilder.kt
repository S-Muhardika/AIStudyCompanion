package com.ruangkerja.companion.report

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import com.ruangkerja.companion.data.db.Chapter
import com.ruangkerja.companion.data.db.Material
import com.ruangkerja.companion.data.db.Task
import com.ruangkerja.companion.data.db.Workspace
import com.ruangkerja.companion.ui.components.parseTableLines
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.util.isQuoteLine
import com.ruangkerja.companion.util.isTableRow
import com.ruangkerja.companion.util.isTableSeparator
import java.io.File

/** Data satu bab yang udah diringkas buat laporan (jumlah materi/flashcard dihitung
 *  sekali di ViewModel, bukan di sini, biar builder ini nggak nyentuh repo sama sekali —
 *  murni fungsi rendering, gampang dipakai ulang buat renderer DOCX nanti). */
data class ChapterReportData(
    val chapter: Chapter,
    val materials: List<Material>,
    val flashcardCount: Int
)

data class WorkspaceReportData(
    val workspace: Workspace,
    val chapters: List<ChapterReportData>,
    val tasks: List<Task>
)

/**
 * Renderer PDF laporan belajar per-workspace, ditulis manual pakai android.graphics.pdf.PdfDocument
 * + StaticLayout (API bawaan Android, TANPA dependency baru) — konsisten sama prinsip proyek ini
 * (full control, minim dependency luar). Paginasi otomatis: tiap blok teks diukur dulu tingginya,
 * kalau nggak muat di sisa halaman, halaman baru dibuka sebelum blok itu digambar.
 */
object ReportPdfBuilder {

    private const val PAGE_W = 595   // A4 @ 72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 48f
    private const val FOOTER_RESERVE = 14f
    private const val QUOTE_PAD = 8f
    private const val QUOTE_BAR_W = 3f
    private const val TABLE_CELL_PAD = 6f
    private val TASK_STATUS_LABEL = arrayOf("Belum", "Dikerjakan", "Selesai")

    private fun render(data: WorkspaceReportData): PdfDocument {
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN

        fun drawFooter() {
            val p = TextPaint().apply { textSize = 9f; color = Color.GRAY; isAntiAlias = true }
            canvas.drawText("Ruang Kerja Ringkas  •  Halaman $pageNum", MARGIN, PAGE_H - MARGIN / 2f, p)
        }

        fun finishCurrentPage() {
            drawFooter()
            doc.finishPage(page)
        }

        fun newPage() {
            finishCurrentPage()
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > PAGE_H - MARGIN - FOOTER_RESERVE) newPage()
        }

        // indent (baru) — offset dari MARGIN kiri, dipakai buat nunjukkin kedalaman struktur
        // SECARA VISUAL (bukan cuma lewat nomor/font). Sebelumnya semua elemen (Bab, Materi,
        // heading, paragraf, bullet) rata di margin yang sama persis — makanya PDF-nya keliatan
        // "gepeng", nggak ada kesan menjorok padahal nomornya udah berjenjang (1 / 1.1 / 1.1.1).
        fun drawParagraph(text: String, size: Float, color: Int, bold: Boolean = false, spaceBefore: Float = 0f, spaceAfter: Float = 8f, indent: Float = 0f) {
            if (text.isBlank()) return
            val paint = TextPaint().apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isAntiAlias = true
            }
            val width = (PAGE_W - 2 * MARGIN - indent).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setLineSpacing(2f, 1.1f)
                .build()
            ensureSpace(spaceBefore + layout.height + spaceAfter)
            y += spaceBefore
            canvas.save()
            canvas.translate(MARGIN + indent, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + spaceAfter
        }

        fun drawDivider(spaceAfter: Float = 12f) {
            ensureSpace(1f + spaceAfter)
            val paint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, paint)
            y += spaceAfter
        }

        // ── Kotak Contoh (blockquote) — kotak abu-abu dengan bar aksen di kiri, niru tampilan
        // di editor. Diukur dulu tingginya (StaticLayout) baru digambar, sama pola-nya kayak
        // drawParagraph. ──
        fun drawQuoteBox(text: String, spaceAfter: Float = 8f) {
            if (text.isBlank()) return
            val paint = TextPaint().apply { textSize = 11f; color = Color.rgb(70, 70, 70); isAntiAlias = true }
            val innerWidth = (PAGE_W - 2 * MARGIN - 2 * QUOTE_PAD - QUOTE_BAR_W).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, innerWidth)
                .setLineSpacing(2f, 1.1f).build()
            val boxHeight = layout.height + 2 * QUOTE_PAD
            ensureSpace(boxHeight + spaceAfter)
            val boxTop = y
            canvas.drawRect(MARGIN, boxTop, PAGE_W - MARGIN, boxTop + boxHeight, Paint().apply { color = Color.rgb(245, 245, 245) })
            canvas.drawRect(MARGIN, boxTop, MARGIN + QUOTE_BAR_W, boxTop + boxHeight, Paint().apply { color = Color.rgb(150, 150, 150) })
            canvas.save()
            canvas.translate(MARGIN + QUOTE_BAR_W + QUOTE_PAD, boxTop + QUOTE_PAD)
            layout.draw(canvas)
            canvas.restore()
            y = boxTop + boxHeight + spaceAfter
        }

        // ── Tabel — grid beneran (garis + sel), bukan teks pipe mentah. Kolom lebar SAMA RATA
        // (disederhanain, bukan auto-sizing per isi) — cukup buat tabel ringkasan, bukan DTP
        // presisi. Cek ruang per-BARIS (bukan per-tabel), jadi bisa kepotong halaman kalau
        // tabelnya panjang — header TIDAK diulang di halaman baru (disederhanain juga). ──
        fun drawTable(rows: List<List<String>>, spaceAfter: Float = 10f) {
            if (rows.isEmpty()) return
            val numCols = rows.maxOf { it.size }.coerceAtLeast(1)
            val tableWidth = PAGE_W - 2 * MARGIN
            val colWidth = tableWidth / numCols
            val headerPaint = TextPaint().apply { textSize = 10.5f; color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val bodyPaint = TextPaint().apply { textSize = 10.5f; color = Color.rgb(40, 40, 40); isAntiAlias = true }
            val borderPaint = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 1f }
            rows.forEachIndexed { rIdx, row ->
                val paint = if (rIdx == 0) headerPaint else bodyPaint
                val cellLayouts = (0 until numCols).map { c ->
                    val text = row.getOrElse(c) { "" }
                    StaticLayout.Builder.obtain(text, 0, text.length, paint, (colWidth - 2 * TABLE_CELL_PAD).toInt().coerceAtLeast(20))
                        .setLineSpacing(1f, 1f).build()
                }
                val rowHeight = (cellLayouts.maxOfOrNull { it.height } ?: 0) + 2 * TABLE_CELL_PAD
                ensureSpace(rowHeight)
                val rowTop = y
                if (rIdx == 0) canvas.drawRect(MARGIN, rowTop, MARGIN + tableWidth, rowTop + rowHeight, Paint().apply { color = Color.rgb(235, 235, 235) })
                cellLayouts.forEachIndexed { c, layout ->
                    canvas.save()
                    canvas.translate(MARGIN + c * colWidth + TABLE_CELL_PAD, rowTop + TABLE_CELL_PAD)
                    layout.draw(canvas)
                    canvas.restore()
                }
                for (c in 0..numCols) canvas.drawLine(MARGIN + c * colWidth, rowTop, MARGIN + c * colWidth, rowTop + rowHeight, borderPaint)
                canvas.drawLine(MARGIN, rowTop, MARGIN + tableWidth, rowTop, borderPaint)
                canvas.drawLine(MARGIN, rowTop + rowHeight, MARGIN + tableWidth, rowTop + rowHeight, borderPaint)
                y = rowTop + rowHeight
            }
            y += spaceAfter
        }

        val ws = data.workspace
        val total = data.tasks.size
        val done = data.tasks.count { it.status == 2 }
        val inProgress = data.tasks.count { it.status == 1 }
        val todo = data.tasks.count { it.status == 0 }
        val pct = if (total > 0) done * 100 / total else 0

        // ── Header ──
        drawParagraph(ws.name, 22f, Color.BLACK, bold = true, spaceAfter = 4f)
        drawParagraph("Laporan Belajar  •  ${DateUtils.formatDisplayDateLong(System.currentTimeMillis())}", 11f, Color.DKGRAY, spaceAfter = if (ws.description.isNotBlank()) 10f else 14f)
        if (ws.description.isNotBlank()) drawParagraph(ws.description, 12f, Color.rgb(70, 70, 70), spaceAfter = 14f)
        drawDivider()

        // ── Ringkasan progres ──
        drawParagraph("Ringkasan Progres", 15f, Color.BLACK, bold = true, spaceAfter = 6f)
        val progressLine = if (total > 0) "${data.chapters.size} bab   •   $done dari $total tugas selesai ($pct%)" else "${data.chapters.size} bab   •   Belum ada tugas"
        drawParagraph(progressLine, 12f, Color.rgb(40, 40, 40), spaceAfter = 3f)
        drawParagraph("Dikerjakan: $inProgress     Belum dikerjakan: $todo", 11f, Color.GRAY, spaceAfter = 14f)
        drawDivider()

        // ── Rincian bab — isi CATATAN LENGKAP tiap materi (bukan cuplikan ringkasan AI lagi,
        // permintaan user), dengan penomoran bertingkat: 1 = bab, 1.1 = materi, 1.1.1 = heading
        // (Judul/H1) di dalam isi materi. Heading level 2 (Subjudul) ditebalin tapi TANPA nomor
        // sendiri, biar penomorannya gak sampe 4 tingkat. ──
        drawParagraph("Rincian Bab", 15f, Color.BLACK, bold = true, spaceAfter = 8f)
        if (data.chapters.isEmpty()) {
            drawParagraph("Belum ada bab di ruang kerja ini.", 11f, Color.GRAY, spaceAfter = 8f)
        } else {
            data.chapters.forEachIndexed { i, cd ->
                val ch = cd.chapter
                drawParagraph("${i + 1}. ${ch.name}", 13f, Color.BLACK, bold = true, spaceBefore = if (i > 0) 14f else 0f, spaceAfter = 3f)
                if (ch.description.isNotBlank()) drawParagraph(ch.description, 11f, Color.rgb(90, 90, 90), spaceAfter = 3f, indent = 16f)
                val metaParts = buildList {
                    add("${cd.materials.size} materi")
                    if (cd.flashcardCount > 0) add("${cd.flashcardCount} flashcard")
                }
                drawParagraph(metaParts.joinToString("   •   "), 10.5f, Color.GRAY, spaceAfter = 6f, indent = 16f)

                if (cd.materials.isEmpty()) {
                    drawParagraph("Belum ada materi di bab ini.", 10.5f, Color.GRAY, spaceAfter = 4f, indent = 16f)
                }
                cd.materials.forEachIndexed { j, mat ->
                    val matNum = "${i + 1}.${j + 1}"
                    drawParagraph("$matNum  ${mat.title.ifBlank { "Materi ${j + 1}" }}", 12f, Color.rgb(25, 25, 25), bold = true, spaceBefore = 8f, spaceAfter = 4f, indent = 16f)
                    if (mat.content.isBlank()) {
                        drawParagraph("(kosong)", 10.5f, Color.GRAY, spaceAfter = 4f, indent = 32f)
                    } else {
                        var subNum = 0
                        var orderedNum = 0
                        // Heading di konten materi bisa H1 ATAU H2 (dua-duanya yang didukung
                        // editor & AiPrompts.MARKDOWN_RULES app ini). H3+ cuma mungkin muncul
                        // dari konten hasil impor URL (Jina Reader), bukan dari editor/AI sendiri
                        // — tetap ditangani biar gak pecah, tapi disederhanain satu gaya (jangan
                        // sampai kelihatan lebih penting dari H2, sesuai prinsip "kedalaman
                        // heading harus sesuai kebutuhan, bukan sekadar ukuran font berjenjang
                        // tanpa arti"). Sebelumnya H2 s.d. H6 SEMUA dirender identik (11f/abu),
                        // nol pembeda — sekarang H1 > H2 > H3+ jelas beda, tanpa nambah level
                        // penomoran baru (matNum.subNum di H1 aja, biar gak lebih dari 3 tingkat
                        // nomor: Bab.Materi.Heading).
                        //
                        // Daftar bernomor ("1. item") sebelumnya ke-flatten jadi bullet titik
                        // kayak daftar biasa — sekarang dirender pakai angka urut beneran
                        // (di-hitung ulang di sini, BUKAN dipakai angka aslinya dari teks, biar
                        // tetap rapi kalau AI/user penomorannya kurang presisi). orderedNum
                        // di-reset ke 0 tiap kali runtun bullet-bernomornya keputus (ganti jadi
                        // paragraf/heading/bullet-biasa/dll), biar list KEDUA di materi yang sama
                        // mulai dari 1 lagi, bukan nyambung dari list pertama.
                        //
                        // indent 32 buat heading/paragraf isi materi (satu tingkat lebih dalam
                        // dari judul Materi yang 16), 44 buat bullet (satu tingkat lagi di bawah
                        // paragraf) — biar keliatan menjorok sesuai kedalamannya, bukan rata semua.
                        parseMdLines(mat.content).forEach { line ->
                            if (line !is MdLine.Bullet || !line.ordered) orderedNum = 0
                            when (line) {
                                is MdLine.Heading -> when {
                                    line.level <= 1 -> {
                                        subNum++
                                        drawParagraph("$matNum.$subNum  ${line.text}", 12f, Color.rgb(25, 25, 25), bold = true, spaceBefore = 8f, spaceAfter = 3f, indent = 32f)
                                    }
                                    line.level == 2 -> drawParagraph(line.text, 11f, Color.rgb(55, 55, 55), bold = true, spaceBefore = 5f, spaceAfter = 2f, indent = 32f)
                                    else -> drawParagraph(line.text, 10.5f, Color.rgb(90, 90, 90), bold = false, spaceBefore = 4f, spaceAfter = 2f, indent = 32f)
                                }
                                is MdLine.Para -> drawParagraph(line.text, 11f, Color.rgb(60, 60, 60), spaceAfter = 4f, indent = 32f)
                                is MdLine.Bullet -> if (line.ordered) {
                                    orderedNum++
                                    drawParagraph("$orderedNum.  ${line.text}", 11f, Color.rgb(60, 60, 60), spaceAfter = 2f, indent = 44f)
                                } else {
                                    drawParagraph("•  ${line.text}", 11f, Color.rgb(60, 60, 60), spaceAfter = 2f, indent = 44f)
                                }
                                is MdLine.Table -> drawTable(line.rows)
                                is MdLine.Quote -> drawQuoteBox(line.text)
                            }
                        }
                    }
                }
            }
        }

        // ── Daftar tugas ──
        if (data.tasks.isNotEmpty()) {
            drawDivider()
            drawParagraph("Daftar Tugas", 15f, Color.BLACK, bold = true, spaceAfter = 8f)
            data.tasks.sortedBy { it.status }.forEachIndexed { i, t ->
                val statusLabel = TASK_STATUS_LABEL.getOrElse(t.status) { "?" }
                val deadlinePart = if (t.deadline.isNotBlank()) "  •  Tenggat: ${t.deadline}" else ""
                drawParagraph("•  ${t.title}  —  $statusLabel$deadlinePart", 11.5f, Color.rgb(40, 40, 40), spaceBefore = if (i > 0) 3f else 0f, spaceAfter = 2f, indent = 16f)
            }
        }

        finishCurrentPage()
        return doc
    }

    private fun safeFileName(workspaceName: String): String =
        "Laporan ${workspaceName.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).ifBlank { "Laporan" }}.pdf"

    /** Perilaku LAMA — tulis ke cache internal, buat di-share sementara lewat FileProvider.
     * Dipertahanin apa adanya kalau suatu saat butuh alur share lagi. */
    fun build(context: Context, data: WorkspaceReportData): File {
        val doc = render(data)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, safeFileName(data.workspace.name))
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** BARU — simpen LANGSUNG ke folder publik Documents/Ruang Kerja/ lewat MediaStore, biar
     * permanen & keliatan di file manager biasa (bukan cache sementara buat di-share). Butuh
     * API 29+ (Android 10) — MediaStore.MediaColumns.RELATIVE_PATH gak ada di API sebelum itu.
     * Cek versi di RUNTIME (bukan cuma @RequiresApi) biar aman walau minSdk project di bawah 29.
     * Gak butuh permission tambahan — nulis file BARU lewat MediaStore selalu diizinkan tanpa
     * WRITE_EXTERNAL_STORAGE, izin itu cuma buat modifikasi file MILIK APP LAIN. */
    fun buildToDocuments(context: Context, data: WorkspaceReportData): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val doc = render(data)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeFileName(data.workspace.name))
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Ruang Kerja")
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> doc.writeTo(out) }
        doc.close()
        return uri
    }

    /** Bersihin syntax markdown dasar dari ringkasan AI biar kebaca polos di cuplikan PDF —
     *  bukan renderer lengkap kayak MarkdownRenderer.kt, cuma buat teks pendek satu paragraf. */
    // ── Parser markdown SANGAT ringan, khusus buat render laporan ini — beda tujuan dari
    // parseBlocks() di editor/ (itu bikin RichTextState buat Compose, laporan ini gak nyentuh
    // Compose sama sekali). Ngenalin heading (#/##), paragraf, Tabel, dan Kotak Contoh (quote)
    // sebagai blok terpisah, masing-masing dirender caranya sendiri (lihat drawTable/
    // drawQuoteBox di render()). ──
    private sealed class MdLine {
        data class Heading(val level: Int, val text: String) : MdLine()
        data class Para(val text: String) : MdLine()
        data class Bullet(val text: String, val ordered: Boolean) : MdLine()
        data class Table(val rows: List<List<String>>) : MdLine()
        data class Quote(val text: String) : MdLine()
    }

    // Regex bullet — SAMA pola-nya kayak bulletRegex di util/MindMapParser.kt (dash, asterisk,
    // bullet char, atau list bernomor), biar konsisten ngenalin format list di seluruh app.
    private val bulletRegexMd = Regex("^\\s*(?:[-*•▪◦]|\\d+[.)])\\s+")
    // Sub-pola KHUSUS buat bedain "1. item" (bernomor) dari "- item"/"• item" (bullet biasa) —
    // dua-duanya kena bulletRegexMd, tapi cuma yang ini yang harus dirender sebagai daftar
    // bernomor (bukan diratain jadi titik semua kayak sebelumnya).
    private val orderedMarkerRegexMd = Regex("^\\s*\\d+[.)]\\s+")
    // Label "1. "/"2) "/"A. " dst yang kadang ditempel AI SENDIRI di teks heading (ikutin
    // AiPrompts.MARKDOWN_RULES) — dibuang dari judul sebelum dirender, biar gak numpuk sama
    // nomor hierarki (matNum.subNum) yang di-generate ReportPdfBuilder sendiri.
    private val stripLeadingLabelMd = Regex("^(?:\\d+|[A-Za-z])[.)]\\s+")

    // Deteksi baris tabel/pemisah/kutipan (isTableRow, isTableSeparator, isQuoteLine) DIPAKAI BERSAMA dengan
    // parser editor (BlockManager) lewat util/MarkdownLines.kt — sebelumnya diduplikasi di sini biar report/
    // tidak bergantung ke editor/, sekarang tempat netralnya util/. Cell-parsing-nya (parseTableLines)
    // DI-IMPORT dari TableEditor.kt, bukan diduplikasi, soalnya itu logic yang lebih substansial
    // (splitting per sel) — biar sekali sumber.
    private fun parseMdLines(raw: String): List<MdLine> {
        val lines = raw.lines()
        val result = mutableListOf<MdLine>()
        // Buffer generik buat blok yang lagi "terbuka" — bisa paragraf ATAU satu item bullet.
        // Sebelumnya cuma ada SATU buffer tanpa status (selalu jadi Para), jadi baris bullet
        // ikut ketelen dan digabung rata sama baris paragraf biasa → semua list flatten jadi
        // satu paragraf. Sekarang statusnya dilacak (bufIsBullet), dan baris bullet BARU selalu
        // nutup dulu blok sebelumnya (apa pun jenisnya) sebelum mulai item baru — jadi tiap
        // bullet jadi MdLine.Bullet sendiri-sendiri, bukan nyambung ke tetangganya.
        val buf = StringBuilder()
        var bufIsBullet = false
        var bufIsOrdered = false
        fun flushBuf() {
            val t = buf.toString().trim()
            if (t.isNotEmpty()) {
                result.add(if (bufIsBullet) MdLine.Bullet(cleanInline(t), bufIsOrdered) else MdLine.Para(cleanInline(t)))
            }
            buf.clear()
            bufIsBullet = false
            bufIsOrdered = false
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val headingMatch = Regex("^(#{1,6})\\s+(.*)").find(line)
            val isBulletLine = bulletRegexMd.containsMatchIn(line)
            when {
                isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                    flushBuf()
                    val tableLines = mutableListOf(line, lines[i + 1].trim())
                    var j = i + 2
                    while (j < lines.size && isTableRow(lines[j])) { tableLines.add(lines[j].trim()); j++ }
                    result.add(MdLine.Table(parseTableLines(tableLines)))
                    i = j
                }
                isQuoteLine(line) -> {
                    flushBuf()
                    val quoteLines = mutableListOf(line.removePrefix(">").trim())
                    var j = i + 1
                    while (j < lines.size && isQuoteLine(lines[j])) { quoteLines.add(lines[j].trim().removePrefix(">").trim()); j++ }
                    result.add(MdLine.Quote(cleanInline(quoteLines.joinToString(" "))))
                    i = j
                }
                headingMatch != null -> {
                    flushBuf()
                    // stripLeadingLabelMd buang label "1. "/"2) "/"A. " dst yang AI tempel sendiri
                    // di teks heading (sesuai AiPrompts.MARKDOWN_RULES) — TANPA ini, hasilnya
                    // dobel sama nomor hierarki yg di-generate render()-nya sendiri (kejadian
                    // beneran: "1.1.1 1. Apa Itu Akuntansi?"). Sekarang cuma nomor hierarki yang
                    // tampil, judul aslinya bersih.
                    result.add(MdLine.Heading(headingMatch.groupValues[1].length, stripLeadingLabelMd.replace(cleanInline(headingMatch.groupValues[2].trim()), "")))
                    i++
                }
                line.isEmpty() -> { flushBuf(); i++ }
                isBulletLine -> {
                    // Baris bullet baru → tutup blok sebelumnya dulu (Para ATAU Bullet lain),
                    // baru buka item bullet baru.
                    flushBuf()
                    bufIsOrdered = orderedMarkerRegexMd.containsMatchIn(line)
                    buf.append(bulletRegexMd.replace(line, ""))
                    bufIsBullet = true
                    i++
                }
                else -> {
                    // Lanjutan baris tanpa marker: nyambung ke blok yang lagi terbuka (paragraf
                    // biasa, ATAU bullet yang teksnya wrap ke baris berikutnya tanpa marker baru).
                    if (buf.isNotEmpty()) buf.append(" ")
                    buf.append(line)
                    i++
                }
            }
        }
        flushBuf()
        return result
    }

    private fun cleanInline(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<!\\w)\\*(.+?)\\*(?!\\w)"), "$1")
        .replace(Regex("(?<!\\w)_(.+?)_(?!\\w)"), "$1")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}
