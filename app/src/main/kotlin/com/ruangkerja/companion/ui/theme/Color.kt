package com.ruangkerja.companion.ui.theme
import androidx.compose.ui.graphics.Color

// ── Accent options — 5 pilihan sesuai referensi, + Terracotta (diadaptasi dari foto referensi
// warna yang dikasih user: merah bata gelap → krem, tapi dicerahin/redupin dikit di sini biar
// tetep kebaca sebagai aksen di layar gelap tanpa jadi seneon 5 pilihan lain) ──────────
enum class AccentChoice(val label: String, val dark: Color, val light: Color) {
    INDIGO     ("Indigo",     Color(0xFF818CF8), Color(0xFF6366F1)),
    TEAL       ("Teal",       Color(0xFF2DD4BF), Color(0xFF0D9488)),
    BLUE       ("Blue",       Color(0xFF60A5FA), Color(0xFF2563EB)),
    VIOLET     ("Violet",     Color(0xFFA78BFA), Color(0xFF7C3AED)),
    EMERALD    ("Emerald",    Color(0xFF34D399), Color(0xFF059669)),
    TERRACOTTA ("Terracotta", Color(0xFFC2552D), Color(0xFF953C1C)),
}

// ── DARK — Netral (bukan Stone/warm lagi) ──────────────────
// Dulu palet "Stone" (abu-abu condong hangat) dipakai di sini — stone-950 murni (0C0A09)
// awalnya kerasa "hampir hitam pekat"/gak nyaman di mata lama-lama (apalagi di AMOLED),
// makanya digeser ke titik tengah stone-950/stone-900 (0xFF141210, deket baseline dark
// theme Material 121212) biar masih gelap & fokus tapi gak lagi berasa pure black.
// [UBAH, 21 Okt — masukan William: "aksen background nya kenapa kayak agak warm ya?
// Kenapa nggak hitam biasa aja"] Rona hangat/stone-nya dihilangin SEKARANG — tiap warna
// di bawah diganti ke abu-abu murni (R=G=B, dihitung dari rata-rata R/G/B versi warm-nya)
// PERSIS DI TINGKAT KETERANGAN YANG SAMA, jadi kepekatan & kontras TIDAK berubah (gak
// balik ke masalah stone-950 yang bikin diganti di awal) — cuma rona hangatnya yang
// hilang. DarkBg hasilnya pas ketemu 121212, baseline dark theme Material beneran.
// DarkText/DarkTextMuted SENGAJA tetap apa adanya — itu warna TEKS, bukan "aksen
// background" yang ditanya, dan warnanya udah nyaris netral dari awal.
val DarkBg        = Color(0xFF121212)  // dulu 0xFF141210 (warm) — netral, kepekatan sama persis
val DarkBgCard    = Color(0xFF191919)  // dulu 0xFF1C1917 (warm) — netral, kepekatan sama persis
val DarkBgMuted   = Color(0xFF191919)  // konsisten sama kartu
val DarkBgAccent  = Color(0xFF262626)  // dulu 0xFF292524 (warm) — netral, kepekatan sama persis
val DarkText      = Color(0xFFFAFAF9)  // stone-50 — TETAP, lihat catatan di atas
val DarkTextMuted = Color(0xFFA8A29E)  // stone-400 — TETAP, lihat catatan di atas
val DarkBorder    = Color(0xFF404040)  // dulu 0xFF44403C (warm) — netral, tetap jelas kelihatan

// ── LIGHT — Stone/Warm ────────────────────────────────────
val LightBg        = Color(0xFFFAFAF9)  // stone-50
val LightBgCard    = Color(0xFFFFFFFF)  // white
val LightBgMuted   = Color(0xFFF5F5F4)  // stone-100
val LightBgAccent  = Color(0xFFF5F5F4)  // stone-100
val LightText      = Color(0xFF1C1917)  // stone-900
val LightTextMuted = Color(0xFF57534E)  // stone-600
val LightBorder    = Color(0xFFE7E5E4)  // stone-200

// ── Semantic — sama di semua tema ─────────────────────────
val Danger       = Color(0xFFF43F5E)
val DangerSoft   = Color(0xFFFFE4E6)
val DangerDark   = Color(0xFFFB7185)
val DangerSoftDk = Color(0xFF4A1D27)
val Success      = Color(0xFF10B981)
val SuccessSoft  = Color(0xFFD1FAE5)
val SuccessDark  = Color(0xFF34D399)
val Warn         = Color(0xFFF59E0B)
val WarnSoft     = Color(0xFFFEF3C7)
val WarnDark     = Color(0xFFFBBF24)

// Identity colors untuk workspace/chapter — 6 pilihan calm
val IdentitySpectrum = listOf(
    Color(0xFF6366F1), // indigo
    Color(0xFF0D9488), // teal
    Color(0xFF2563EB), // blue
    Color(0xFF7C3AED), // violet
    Color(0xFF059669), // emerald
    Color(0xFFF59E0B)  // amber
)
fun identityColorFor(key: String): Color {
    val idx = key.fold(0) { acc, c -> acc + c.code }
    return IdentitySpectrum[idx % IdentitySpectrum.size]
}
