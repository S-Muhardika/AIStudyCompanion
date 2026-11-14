package com.ruangkerja.companion.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

// ── Berbagi file ekspor lewat share sheet. [DIGABUNG, audit duplikasi 2 Okt] Dulu blok
// "URI FileProvider + ACTION_SEND + chooser" ditulis ulang di 6 tempat (ekspor ruang kerja ×2 dan
// ekspor bab ×2 — masing-masing satu salinan di tampilan grid dan satu di daftar —, ekspor materi, dan
// ekspor data), bedanya cuma MIME dan judul chooser. Sekarang satu fungsi. ──
internal fun shareExportFile(ctx: Context, file: File, mime: String, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, chooserTitle))
}

/**
 * Bangun file ekspor di background lalu bagikan; hasil kosong atau gagal ditampilkan lewat Toast.
 * Dipakai menu kartu Ruang Kerja dan kartu Bab (tampilan grid & daftar memakai fungsi yang sama).
 */
internal fun launchExportShare(
    scope: CoroutineScope, ctx: Context, mime: String, chooserTitle: String, emptyMessage: String,
    build: suspend () -> File?
) {
    scope.launch {
        try {
            val file = build()
            if (file == null) { Toast.makeText(ctx, emptyMessage, Toast.LENGTH_SHORT).show(); return@launch }
            shareExportFile(ctx, file, mime, chooserTitle)
        } catch (e: Exception) { Toast.makeText(ctx, "Gagal ekspor: ${e.message}", Toast.LENGTH_SHORT).show() }
    }
}
