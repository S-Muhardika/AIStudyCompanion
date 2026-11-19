package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruangkerja.companion.ui.components.AppHeader
import com.ruangkerja.companion.ui.theme.AppBg
import com.ruangkerja.companion.ui.theme.AppTextDim

// ═══════════════════════════════════════════════════════════════════════════════════════════
// [BARU, 18 Nov — Task #51, bagian dari redesain nav bawah jadi 3 tab] Tab "Aktivitas" BARU.
// Sesuai jawaban eksplisit William ("Aktivitas itu sebenarnya progres cuma kan bar progres udah
// di beranda, bisa bikin jadi halaman kosong aja dulu, lupa aku") — ini SENGAJA cuma placeholder
// kosong, BUKAN duplikat dari ring/ringkasan Progres yang sudah ada di Beranda (ProgresRingRow,
// WorkspaceListScreen.kt) ataupun dari tab "Progres" lama (ProgressScreen.kt, rutenya "progress",
// masih hidup apa adanya tapi sekarang lepas dari bottom-nav — lihat catatan di MainActivity.kt).
//
// Isinya nanti NYUSUL setelah William tentuin lebih lanjut mau dipakai buat apa (log aktivitas?
// riwayat perubahan? sesuatu yang lain?) — nol asumsi ditambahkan di sini sampai itu jelas.
// ═══════════════════════════════════════════════════════════════════════════════════════════

@Composable
fun AktivitasScreen() {
    Scaffold(
        containerColor = AppBg,
        topBar = { AppHeader(title = "Aktivitas") }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.History, null, tint = AppTextDim, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "Halaman ini masih kosong — belum ditentukan isinya.",
                    color = AppTextDim, fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }
        }
    }
}
