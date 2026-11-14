package com.ruangkerja.companion.ui.theme
import androidx.compose.ui.unit.dp
object Spacing {
    val xs  = 4.dp;  val sm  = 8.dp;  val md = 12.dp
    val lg  = 16.dp; val xl  = 20.dp; val xxl = 24.dp
}

// ── Radius — skala radius sudut, kelipatan 4 semua biar rapi & sesuai hierarki:
// kontrol (tombol/input/menu kecil) & kartu konten SEKARANG SAMA (8dp) — arah appnya emang
// mengurangi ketergantungan ke "kotak berbingkai" sebagai dekorasi, jadi kartu gak perlu lagi
// kelihatan beda-jauh dari kontrol biasa. Dialog TETAP lebih besar (16dp) karena itu lapisan
// modal yang bener-bener mengambang di atas konten, wajar kalau kesannya sedikit lebih lembut.
object Radius {
    val control = 8.dp   // tombol, input teks, dropdown/menu kecil (dulu 10dp)
    val card    = 8.dp   // kartu konten (dulu 12dp, sebelumnya lagi 14–20dp campur-campur)
    val dialog  = 16.dp  // AppDialog & AlertDialog — sengaja lebih besar dari kartu/kontrol
}
