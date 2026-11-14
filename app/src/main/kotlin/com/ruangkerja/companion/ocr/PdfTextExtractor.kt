package com.ruangkerja.companion.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfTextExtractor {
    // [FIX, 29 Okt — audit RENDAH #1] Batas sisi terpanjang bitmap HASIL upscale — PDF dengan
    // halaman berdimensi ekstrem (dibuat sengaja atau nggak) sebelumnya bisa minta alokasi
    // bitmap raksasa (page.width*2 x page.height*2 x 4 byte ARGB_8888) dan bikin OOM lokal pas
    // scan OCR-PDF. Jumlah halaman udah dibatasi (maks 30, di bawah) tapi dimensi per halaman
    // belum. Nilai 3000 longgar buat ukuran halaman wajar (A4 2x upscale ~1190x1684, jauh di
    // bawah ini) tapi tetap ngebatasin alokasi puncak ke ~36MB per bitmap — boleh disetel ulang
    // kalau ketemu kasus nyata yang perlu beda di device.
    private const val MAX_DIMENSION_PX = 3000

    suspend fun extractText(
        context: Context, uri: Uri,
        onProgress: (page: Int, total: Int) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "ocr_temp_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw Exception("Tidak bisa membuka file PDF")

        val fd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val sb = StringBuilder()
        try {
            val pageCount = renderer.pageCount
            if (pageCount > 30) throw Exception("PDF terlalu panjang (maks 30 halaman)")
            for (i in 0 until pageCount) {
                onProgress(i + 1, pageCount)
                val page = renderer.openPage(i)
                // Upscale 2x kalau masih di bawah MAX_DIMENSION_PX, turun ke faktor lebih kecil
                // (minimal 1x, gak pernah pecahan di bawah itu) kalau 2x bakal ngelewatin batas.
                val scale = maxOf(1, minOf(2, MAX_DIMENSION_PX / maxOf(page.width, page.height, 1)))
                val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                val text = OcrHelper.recognize(bitmap)
                if (text.isNotBlank()) sb.append(text).append("\n\n")
                bitmap.recycle()
            }
        } finally { renderer.close(); fd.close(); tempFile.delete() }
        sb.toString().trim()
    }
}
