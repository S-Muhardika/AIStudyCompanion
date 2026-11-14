package com.ruangkerja.companion.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ruangkerja.companion.network.AiUsageStatsResp
import com.ruangkerja.companion.network.GeminiService
import com.ruangkerja.companion.network.ProviderUsage
import com.ruangkerja.companion.auth.SupabaseAuthManager
import com.ruangkerja.companion.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * [BARU, 26 Okt] Activity debug terpisah (ikon launcher sendiri, "Cek Limit AI") — pola yang
 * sama kayak debug/PoeAuthTestActivity.kt dulu (sudah dihapus, tapi pola foldernya dipakai lagi
 * di sini): SAMA SEKALI gak nyentuh AiEngine.kt/fitur AI yang beneran dipakai user, murni baca
 * statistik dari ai-proxy (action="stats") lewat GeminiService.fetchUsageStats().
 *
 * PENTING buat dibaca: cuma 2 dari 10 provider (Groq, SiliconFlow) sebenarnya expose cara cek
 * SISA kuota lewat API mereka sendiri — dan dua-duanya pun ada catatan (lihat riset 26 Okt).
 * 8 provider lain SAMA SEKALI nggak punya cara itu. Makanya layar ini NGGAK nunjukkin "sisa
 * limit" per provider (nggak ada angka itu buat dibandingkan, dan daripada nampilin angka
 * karang-karangan), tapi "sudah kepake berapa kali HARI INI" — dicatat sendiri oleh ai-proxy
 * tiap kali berhasil dapet jawaban dari provider itu. Tetap berguna buat liat provider mana yang
 * paling sering kepake / provider mana yang kayaknya nggak pernah berhasil (potensi key/model
 * bermasalah), plus pemakaian+batas harian AKUN yang lagi login (lihat ai-proxy/index.ts).
 *
 * [UBAH, 27 Okt] ai-proxy sekarang wajib login (lihat CHANGELOG) — layar ini otomatis ikut butuh
 * sesi aktif (action="stats" juga lewat gerbang auth yang sama kayak chat/embed). Kalau belum
 * login, bakal muncul pesan error yang sama kayak fitur AI lain ("silakan login dulu").
 *
 * [BARU, 5 Nov — permintaan William: "butuh untuk debug provider dan model... soalnya aku juga
 * belum pasang api ke edge function supabase"] Tiap provider SEKARANG selalu tampil (dulu cuma
 * yang sudah pernah kepanggil hari ini), plus status "Key sudah/belum di-set" (dibaca server-side
 * dari Supabase secrets, BUKAN isi key-nya — lihat PROVIDER_INFO di ai-proxy/index.ts) dan model
 * yang lagi dipasang provider itu. Berguna khusus pas lagi nambahin key satu-satu kayak sekarang:
 * langsung kelihatan dari app provider mana yang masih kosong, tanpa bolak-balik buka kode/dashboard
 * Supabase. Provider yang TIDAK dipakai fitur manapun sekarang (zai, nvidia — lihat FEATURE_
 * PRIORITY/BASE_ORDER di index.ts) tetap tampil tapi diberi label kecil biar gak disangka aktif.
 */
class AiUsageDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AiUsageDebugScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiUsageDebugScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<AiUsageStatsResp?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            result = try {
                GeminiService(context).fetchUsageStats()
            } catch (e: Exception) {
                AiUsageStatsResp(error = e.message ?: "Gagal mengambil statistik.")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cek Limit AI") },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Muat ulang")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                result?.error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Gagal memuat statistik", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(result?.error ?: "", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Catatan: fitur ini butuh tabel ai_usage_daily sudah dibuat di Supabase " +
                            "(lihat migrasi SQL yang dikirim) dan ai-proxy versi terbaru sudah di-deploy.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                else -> {
                    val data = result
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Statistik hari ini (${data?.today ?: "-"})", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Jumlah panggilan BERHASIL per provider — bukan \"sisa limit\" (kebanyakan " +
                                "provider nggak expose angka itu lewat API, lihat catatan di komentar kode).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))

                        // [BARU, 27 Okt] Akun = lapisan UTAMA sejak ai-proxy wajib login — ditaruh
                        // paling atas. user?.email null cuma kalau akun Google-nya emang gak expose
                        // email (jarang) — tetap aman ditampilkan "Akun ini" sebagai fallback.
                        val account = data?.account
                        if (account != null) {
                            val email = SupabaseAuthManager.currentUser.value?.email
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(email ?: "Akun ini", fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${account.count} / ${account.cap} panggilan hari ini (semua device, semua provider)")
                                    LinearProgressIndicator(
                                        progress = { if (account.cap > 0) (account.count.toFloat() / account.cap).coerceIn(0f, 1f) else 0f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }

                        // Device = sinyal TAMBAHAN doang sekarang (lihat catatan di atas), ditaruh
                        // di bawah akun, teksnya dikecilin biar jelas bukan lagi yang utama.
                        val device = data?.device
                        if (device != null) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Perangkat ini (sinyal tambahan)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${device.count} / ${device.cap} panggilan hari ini", style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator(
                                        progress = { if (device.cap > 0) (device.count.toFloat() / device.cap).coerceIn(0f, 1f) else 0f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }

                        Text("Per provider (semua perangkat)", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "● = key sudah di-set di Supabase, ○ = belum",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        val providers = data?.providers.orEmpty()
                        if (providers.isEmpty()) {
                            Text("Belum ada provider terdaftar.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(providers) { p: ProviderUsage ->
                                    // [BARU, 5 Nov] Card per provider (dulu 1 baris polos) — sekarang
                                    // nampung status "configured" + daftar model, bukan cuma angka
                                    // pemakaian. Lihat PROVIDER_INFO/usageStats() di ai-proxy/index.ts
                                    // buat dari mana data ini datang.
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        if (p.configured) "●" else "○",
                                                        color = if (p.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(p.provider, fontWeight = FontWeight.Bold)
                                                    if (!p.activeInRouting) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            "(tidak dipakai fitur manapun)",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.outline
                                                        )
                                                    }
                                                }
                                                // [BARU, 26 Okt] budget = anggaran yang RKR tetapkan SENDIRI
                                                // (bukan limit asli provider, lihat PROVIDER_DAILY_BUDGET di
                                                // ai-proxy/index.ts) — null ditampilkan tanpa "/budget".
                                                val budget = p.budget
                                                Text(
                                                    if (budget != null) "${p.count} / $budget" else "${p.count}x",
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            if (p.models.isNotEmpty()) {
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    "Model: " + p.models.joinToString(", "),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
