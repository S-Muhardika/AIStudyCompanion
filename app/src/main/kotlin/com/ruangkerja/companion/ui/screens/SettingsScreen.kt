package com.ruangkerja.companion.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import com.ruangkerja.companion.BuildConfig
import com.ruangkerja.companion.network.AiProvider
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.launch

// ── Settings full-screen, mobile-first, list-grouped (pola shadcn Dialog/Settings) ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    Scaffold(
        containerColor = AppBg,
        topBar = { AppHeader(title = Str.settingsTitle, onBack = onBack) }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            Spacer(Modifier.height(Spacing.lg))
            TampilanGroup(vm)
            LanjutanGroup(vm)
            NotifikasiGroup(vm)
            AkunGroup(vm)
            DataGroup(vm)
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

// ── Grup: Tampilan ────────────────────────────────────────
@Composable
private fun TampilanGroup(vm: AppViewModel) {
    val themeMode by vm.themeMode.collectAsState()
    val accent by vm.accent.collectAsState()
    val darkActive = themeMode == ThemeMode.DARK

    SettingsGroup(title = Str.groupTampilanTitle, subtitle = Str.groupTampilanSubtitle) {
        SettingsRow(
            icon = if (darkActive) Icons.Default.DarkMode else Icons.Default.LightMode,
            title = if (darkActive) "Mode Gelap" else "Mode Terang",
            desc  = "Kurangi ketegangan mata saat belajar malam"
        ) {
            Switch(
                checked = darkActive,
                onCheckedChange = { vm.toggleDarkMode() },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppAccent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppBorder)
            )
        }
        SettingsDivider()
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md)) {
            Text("Warna Aksen", color = AppText, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
            Text("Pilih warna utama antarmuka", color = AppTextMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp, bottom = Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                AccentChoice.entries.forEach { choice ->
                    val isSelected = accent == choice
                    val circleColor = if (darkActive) choice.dark else choice.light
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clickable { vm.setAccent(choice) }) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(circleColor)
                                .border(width = if (isSelected) 2.5.dp else 0.dp, color = if (isSelected) AppText.copy(alpha = 0.5f) else Color.Transparent, shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) { if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
                        Text(choice.label, color = AppTextMuted, fontSize = 9.5.sp)
                    }
                }
            }
        }
        SettingsDivider()
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md)) {
            Text(Str.languageRowTitle, color = AppText, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
            Text(Str.languageRowDesc, color = AppTextMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp, bottom = Spacing.md))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val currentLang by vm.language.collectAsState()
                AppLanguage.entries.forEach { lang ->
                    val isSelected = currentLang == lang
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(Radius.control))
                            .background(if (isSelected) AppAccentSoft else AppBgMuted)
                            .border(1.dp, if (isSelected) AppAccent else AppBorder, RoundedCornerShape(Radius.control))
                            .clickable { vm.setLanguage(lang) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isSelected) Icon(Icons.Default.Check, null, tint = AppAccent, modifier = Modifier.size(14.dp))
                        Text(lang.label, color = if (isSelected) AppAccent else AppTextMuted, fontSize = 12.5.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

// ── [BARU] Grup: Fitur Lanjutan ───────────────────────────
// Saklar induk untuk pengaturan tambahan. Untuk sekarang isinya baru satu: Konfigurasi API (API key
// sendiri). Dimatikan = konfigurasi API tetap TERSIMPAN tapi tidak dipakai, aplikasi kembali ke AI
// bawaan (lihat AiEngine.advancedEnabled). Grup "Konfigurasi AI" (kartu status, info model, Tes
// koneksi AI) sudah dihapus — hanya berguna saat debugging, dan penyebab kegagalan AI sekarang
// tampil langsung di pesan error tiap fitur.
@Composable
private fun LanjutanGroup(vm: AppViewModel) {
    var enabled by remember { mutableStateOf(vm.ai.advancedEnabled) }
    val hasSaved = remember(enabled) { vm.ai.savedCustomConfig() != null }

    SettingsGroup(title = Str.groupLanjutanTitle, subtitle = Str.groupLanjutanSubtitle) {
        SettingsRow(
            icon = Icons.Default.Tune, title = "Aktifkan Fitur Lanjutan",
            desc = when {
                enabled -> "Aktif — pengaturan tambahan di bawah ini dapat digunakan"
                hasSaved -> "Nonaktif — Konfigurasi API tetap tersimpan dan dipakai kembali saat diaktifkan"
                else -> "Nonaktif — aplikasi memakai pengaturan bawaan"
            }
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    vm.ai.advancedEnabled = checked
                    enabled = checked
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppAccent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppBorder)
            )
        }
        if (enabled) {
            SettingsDivider()
            ApiConfigSection(vm)
        }
    }
}

// Konfigurasi API (BYOK): form API key sendiri. Form diisi dari konfigurasi TERSIMPAN (bukan yang
// sedang berlaku) dan dibaca ulang setiap bagian ini muncul lagi setelah saklar dinyalakan.
@Composable
private fun ApiConfigSection(vm: AppViewModel) {
    val existing = remember { vm.ai.savedCustomConfig() }
    var expanded by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf(existing?.provider ?: AiProvider.GEMINI) }
    var key by remember { mutableStateOf(existing?.key ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var hasSaved by remember { mutableStateOf(existing != null) }
    LaunchedEffect(saved) { if (saved) { kotlinx.coroutines.delay(2200); saved = false } }

    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = AppTextDim, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text("Konfigurasi API", color = AppText, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
            Text("Gunakan API key sendiri untuk memilih provider atau model AI", color = AppTextDim, fontSize = 11.sp)
        }
    }

    if (expanded) {
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiProvider.entries.forEach { p ->
                val isSelected = selectedProvider == p
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) AppAccent else AppBgMuted)
                        .border(1.dp, if (isSelected) AppAccent else AppBorder, RoundedCornerShape(10.dp))
                        .clickable { selectedProvider = p }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(p.label, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else AppTextMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        if (selectedProvider == AiProvider.CUSTOM) {
            Text("Base URL (OpenAI-compatible)", color = AppText, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = Spacing.sm),
                placeholder = { Text("https://api.contoh.com/v1", color = AppTextDim) },
                shape = RoundedCornerShape(10.dp), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppAccent, unfocusedBorderColor = AppBorder, focusedContainerColor = AppBgMuted, unfocusedContainerColor = AppBgMuted, focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = AppAccent)
            )
            Text("Nama Model", color = AppText, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            OutlinedTextField(
                value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = Spacing.sm),
                placeholder = { Text("gpt-4o-mini", color = AppTextDim) },
                shape = RoundedCornerShape(10.dp), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppAccent, unfocusedBorderColor = AppBorder, focusedContainerColor = AppBgMuted, unfocusedContainerColor = AppBgMuted, focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = AppAccent)
            )
        }

        Text("${selectedProvider.label} API Key", color = AppText, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
        Text(
            when (selectedProvider) {
                AiProvider.GEMINI -> "Dapatkan gratis di aistudio.google.com/apikey"
                AiProvider.GROQ -> "Dapatkan gratis di console.groq.com/keys"
                AiProvider.CEREBRAS -> "Dapatkan gratis di cloud.cerebras.ai"
                AiProvider.CUSTOM -> "Sesuai key dari provider OpenAI-compatible pilihan Anda"
            },
            color = AppTextMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp, bottom = Spacing.sm)
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("API key...", color = AppTextDim) },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = AppTextMuted) } },
            shape = RoundedCornerShape(10.dp), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppAccent, unfocusedBorderColor = AppBorder, focusedContainerColor = AppBgMuted, unfocusedContainerColor = AppBgMuted, focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = AppAccent)
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryBtn(
                if (saved) "Tersimpan" else "Simpan",
                {
                    vm.ai.saveCustomConfig(selectedProvider, key.trim(), baseUrl.trim(), model.trim())
                    // Form ini hanya tampil saat Fitur Lanjutan aktif — simpan nilai itu secara eksplisit, supaya
                    // tidak ikut berubah lewat nilai bawaan (yang bergantung ada/tidaknya key tersimpan).
                    vm.ai.advancedEnabled = true
                    saved = true; hasSaved = true
                },
                Modifier.weight(1f), enabled = key.isNotBlank(), icon = if (saved) Icons.Default.CheckCircle else Icons.Default.Save
            )
            if (hasSaved) {
                TextButton(onClick = {
                    vm.ai.clearCustomConfig()
                    vm.ai.advancedEnabled = true   // tetap aktif setelah key dihapus (lihat komentar di tombol Simpan)
                    key = ""; baseUrl = ""; model = ""; selectedProvider = AiProvider.GEMINI; hasSaved = false
                }) { Text("Hapus konfigurasi", color = AppTextMuted, fontSize = 12.sp) }
            }
        }
    }
}

// ── Grup: Notifikasi ──────────────────────────────────────
@Composable
private fun NotifikasiGroup(vm: AppViewModel) {
    val quietHours by vm.quietHoursEnabled.collectAsState()
    val dailyReminder by vm.dailyReminderEnabled.collectAsState()
    val draftSuggestionNotif by vm.draftSuggestionNotifEnabled.collectAsState()
    val context = LocalContext.current
    var permWarning by remember { mutableStateOf(false) }

    fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permWarning = !granted
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    // Cek ulang tiap kali salah satu toggle notifikasi nyala, biar hint izin selalu akurat
    // (misal user cabut izin dari Setelan HP di luar app, terus balik lagi ke sini).
    LaunchedEffect(dailyReminder, draftSuggestionNotif) {
        permWarning = (dailyReminder || draftSuggestionNotif) && !hasNotifPermission()
    }

    SettingsGroup(title = Str.groupNotifTitle, subtitle = Str.groupNotifSubtitle) {
        SettingsRow(icon = Icons.Default.Bedtime, title = "Jam Tenang", desc = "Tunda pengingat tugas yang jatuh pukul 22:00–06:00 ke jam 06:00") {
            Switch(checked = quietHours, onCheckedChange = { vm.setQuietHoursEnabled(it) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppAccent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppBorder))
        }
        SettingsDivider()
        SettingsRow(icon = Icons.Default.Alarm, title = "Pengingat Harian", desc = "Notif jam 8 malam kalau belum buka bab apapun hari ini") {
            Switch(
                checked = dailyReminder,
                onCheckedChange = { checked ->
                    // Switch-nya SELALU langsung merespons — izin notifikasi dicek terpisah
                    // di bawah, nggak nge-block toggle-nya sendiri kalau izin belum ada/ditolak.
                    vm.setDailyReminderEnabled(checked)
                    if (checked && !hasNotifPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppAccent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppBorder)
            )
        }
        SettingsDivider()
        SettingsRow(icon = Icons.Default.Notifications, title = "Saran Draft", desc = "Notif sore hari kalau ada draft yang mungkin berkaitan dengan salah satu Ruang Kerja") {
            Switch(
                checked = draftSuggestionNotif,
                onCheckedChange = { checked ->
                    vm.setDraftSuggestionNotifEnabled(checked)
                    if (checked && !hasNotifPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppAccent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppBorder)
            )
        }
        if (dailyReminder && permWarning) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { openAppSettings() }.padding(top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.WarningAmber, null, tint = Warn, modifier = Modifier.size(14.dp))
                Column {
                    Text(
                        "Izin notifikasi belum aktif — pengingat sudah dijadwalkan, tetapi tidak akan muncul sampai izin diberikan.",
                        color = Warn, fontSize = 11.sp, lineHeight = 15.sp
                    )
                    Text("Ketuk untuk membuka Pengaturan Izin →", color = Warn, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        SettingsDivider()
        SettingsRow(icon = Icons.Default.Event, title = "Tugas & Deadline", desc = "Notifikasi lokal otomatis aktif saat menambahkan tugas dengan tanggal") {}
    }
}

// ── Grup: Data & Privasi ──────────────────────────────────
// ── Grup: Akun & Sinkronisasi (opsional, V2.0.1) ───────────
@Composable
private fun AkunGroup(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by vm.currentUser.collectAsState()
    val loading by vm.authLoading.collectAsState()
    val error by vm.authError.collectAsState()
    val syncLoading by vm.syncLoading.collectAsState()
    val syncError by vm.syncError.collectAsState()
    val lastSyncedAt by vm.lastSyncedAt.collectAsState()
    val accountMismatchEmail by vm.accountMismatchEmail.collectAsState()
    var showSwitchConfirm by remember { mutableStateOf(false) }

    SettingsGroup(title = Str.groupAkunTitle, subtitle = Str.groupAkunSubtitle) {
        if (user == null) {
            // [UBAH, 27 Okt] Desc disebutin dua alasan — sync (lama) + AI bawaan (baru, sejak
            // ai-proxy wajib login, lihat SupabaseAuthManager.kt). BYOK di Pengaturan > Lanjutan
            // sengaja TIDAK disebut di sini biar deskripsinya tetap singkat — penjelasan lengkap
            // soal BYOK-gak-perlu-login baru muncul kalau fitur AI-nya sendiri ditolak (pesan error
            // dari GeminiService.edgePost sudah menyebut opsi itu).
            SettingsRow(icon = Icons.Default.CloudOff, title = "Belum Masuk", desc = "Masuk untuk menyinkronkan data ke cloud & memakai fitur AI bawaan") {}
            SettingsDivider()
            PrimaryBtn(
                text = if (loading) "Menghubungkan…" else "Masuk dengan Google",
                onClick = { vm.signInWithGoogle(context) },
                modifier = Modifier.fillMaxWidth(),
                loading = loading,
                icon = if (!loading) Icons.Default.Login else null
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = Spacing.sm))
            }
        } else {
            SettingsRow(icon = Icons.Default.CloudDone, title = user?.email ?: "Tersambung", desc = "Login dengan Google") {}
            SettingsDivider()
            // [BARU] Akun beda dari yang terakhir disinkron di HP ini -> JANGAN nampilin tombol
            // sinkron biasa (bakal ditolak SyncManager kalau dipencet, lihat checkAccount).
            // Ganti sama peringatan + jalur resolusi eksplisit.
            if (accountMismatchEmail != null) {
                SettingsRow(
                    icon = Icons.Default.WarningAmber,
                    title = "Akun Berbeda Terdeteksi",
                    desc = "Perangkat ini terakhir sinkron menggunakan $accountMismatchEmail. Sinkronisasi dijeda — fitur lain tetap berjalan normal secara offline."
                ) {}
                GhostBtn(
                    if (syncLoading) "Memproses…" else "Pindah ke Akun Ini",
                    { showSwitchConfirm = true },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    icon = if (!syncLoading) Icons.Default.SwapHoriz else null,
                    enabled = !syncLoading,
                    color = Danger
                )
            } else {
                SettingsRow(
                    icon = Icons.Default.Sync,
                    title = "Sinkronisasi Data",
                    desc = if (syncLoading) "Menyinkronkan…" else formatLastSynced(lastSyncedAt)
                ) {
                    GhostBtn(
                        if (syncLoading) "Menyinkron…" else "Sinkron",
                        { vm.syncNow() },
                        icon = if (!syncLoading) Icons.Default.Sync else null,
                        enabled = !syncLoading
                    )
                }
            }
            syncError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = Spacing.sm))
            }
            SettingsDivider()
            GhostBtn("Keluar", { vm.signOut() }, modifier = Modifier.fillMaxWidth(), icon = Icons.Default.Logout)
        }
    }

    // Konfirmasi eksplisit SEBELUM data lokal kehapus — pola sama kayak dialog hapus workspace
    // di WorkspaceListScreen.kt, biar konsisten gayanya di seluruh app.
    if (showSwitchConfirm) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = false },
            containerColor = AppBgCard,
            shape = RoundedCornerShape(Radius.dialog),
            icon = { Icon(Icons.Default.WarningAmber, null, tint = Danger) },
            title = { Text("Pindah ke Akun Ini?", color = AppText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Semua data lokal perangkat ini (dari akun sebelumnya) akan DI-BACKUP OTOMATIS terlebih dahulu ke penyimpanan aplikasi, " +
                        "kemudian DIHAPUS dari perangkat, lalu data akun ${user?.email ?: "ini"} akan ditarik ulang dari cloud. Membutuhkan koneksi internet.",
                    color = AppTextMuted, lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.switchAccount(); showSwitchConfirm = false }) {
                    Text("Backup, Lalu Pindah", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showSwitchConfirm = false }) { Text("Batal", color = AppTextMuted) } }
        )
    }
}

// Format ringkas "kapan terakhir sinkron" — belum ada helper waktu-relatif lain di project ini,
// jadi ditulis sendiri di sini, sengaja SEDERHANA (cuma buat satu baris status kecil), bukan
// utility umum buat dipakai di tempat lain.
private fun formatLastSynced(ts: Long?): String {
    if (ts == null) return "Belum pernah disinkronkan — ketuk Sinkron untuk menarik data dari akun cloud Anda"
    val diffMin = (System.currentTimeMillis() - ts) / 60000
    return when {
        diffMin < 1 -> "Baru saja disinkronkan"
        diffMin < 60 -> "Disinkron $diffMin menit lalu"
        diffMin < 1440 -> "Disinkron ${diffMin / 60} jam lalu"
        else -> "Disinkron ${diffMin / 1440} hari lalu"
    }
}

@Composable
private fun DataGroup(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = Str
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }

    // Impor lewat Storage Access Framework (GetContent) — user milih file JSON hasil "Ekspor
    // Data" sebelumnya, hasilnya Uri (bukan path langsung), makanya restoreFromFile terima Uri.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = vm.restoreFromFile(uri)
            importing = false
            result.onSuccess { n -> Toast.makeText(context, strings.importSuccess(n), Toast.LENGTH_LONG).show() }
                .onFailure { e -> Toast.makeText(context, strings.importError(e.message ?: "?"), Toast.LENGTH_LONG).show() }
        }
    }

    SettingsGroup(title = Str.groupDataTitle, subtitle = Str.groupDataSubtitle) {
        SettingsRow(icon = Icons.Default.Download, title = Str.exportTitle, desc = Str.exportDesc) {
            GhostBtn(if (exporting) "Menyiapkan…" else "Ekspor", {
                if (!exporting) {
                    exporting = true
                    scope.launch {
                        try {
                            val file = vm.buildExportFile()
                            shareExportFile(context, file, "application/zip", "Ekspor Data Ruang Kerja Ringkas")
                        } catch (e: Exception) {
                            Toast.makeText(context, "Gagal mengekspor data: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            exporting = false
                        }
                    }
                }
            }, icon = Icons.Default.Download, enabled = !exporting)
        }
        SettingsDivider()
        SettingsRow(icon = Icons.Default.Upload, title = Str.importTitle, desc = Str.importDesc) {
            GhostBtn(if (importing) Str.importingButton else Str.importButton, {
                if (!importing) showImportConfirm = true
            }, icon = Icons.Default.Upload, enabled = !importing)
        }
        SettingsDivider()
        SettingsRow(icon = Icons.Default.Info, title = "Versi Aplikasi", desc = "Ruang Kerja Ringkas v${BuildConfig.VERSION_NAME}") {}
        SettingsDivider()
        Text(
            "Semua data belajar (materi, tugas, catatan) tersimpan lokal di perangkat Anda secara default. Apabila Anda masuk, sebagian data disinkronkan ke akun cloud milik Anda sendiri. Selain itu, data hanya terkirim ke server saat Anda menggunakan fitur AI.",
            color = AppTextDim, fontSize = 11.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = Spacing.sm)
        )
    }

    // Konfirmasi SEBELUM buka file picker — impor bisa nimpa data yang ID-nya sama, jadi user
    // harus ngerti risikonya dulu (pola sama kayak dialog switch-akun & hapus workspace).
    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            containerColor = AppBgCard,
            shape = RoundedCornerShape(Radius.dialog),
            icon = { Icon(Icons.Default.WarningAmber, null, tint = Warn) },
            title = { Text(Str.importConfirmTitle, color = AppText, fontWeight = FontWeight.Bold) },
            text = { Text(Str.importConfirmText, color = AppTextMuted, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { showImportConfirm = false; importLauncher.launch("application/zip") }) {
                    Text(Str.importConfirmButton, color = Warn, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showImportConfirm = false }) { Text(Str.cancelButton, color = AppTextMuted) } }
        )
    }
}

// ── Reusable: Card group dengan title di luar (pola shadcn) ──
@Composable
private fun SettingsGroup(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(subtitle, color = AppTextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = Spacing.sm))
        Column(
            Modifier.fillMaxWidth().background(AppBgCard, RoundedCornerShape(Radius.card)).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).padding(Spacing.lg),
            content = content
        )
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, desc: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(AppAccentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AppAccent, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = AppText, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
            if (desc.isNotBlank()) Text(desc, color = AppTextMuted, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 1.dp))
        }
        action()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = AppBorder.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 2.dp))
}
