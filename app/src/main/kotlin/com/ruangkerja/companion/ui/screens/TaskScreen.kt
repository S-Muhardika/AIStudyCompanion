package com.ruangkerja.companion.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.auth.GoogleCalendarAuth
import com.ruangkerja.companion.data.db.Task
import com.ruangkerja.companion.data.db.Workspace
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.util.DateUtils
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.launch

private fun priColor(p: Int) = when (p) { 2 -> Danger; 1 -> Warn; else -> Success }
private fun priLabel(p: Int) = when (p) { 2 -> "Tinggi"; 1 -> "Sedang"; else -> "Rendah" }
private fun statusLabel(s: Int) = when (s) { 2 -> "Selesai"; 1 -> "Dikerjakan"; else -> "Belum" }
private val ReminderPresets = listOf(30 to "30 mnt", 60 to "1 jam", 1440 to "1 hari", 4320 to "3 hari")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(vm: AppViewModel) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val taskError by vm.taskError.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    // [BARU] Dipakai buat nentuin jalur sinkron kalender: login → Google Calendar API akun
    // sendiri; belum login → tetap kalender on-device kayak sebelumnya (login opsional, lihat
    // SupabaseAuthManager.kt — jangan pernah gate fitur ini di belakang status login).
    val currentUser by vm.currentUser.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(-1) }
    var showAdd by remember { mutableStateOf(false) }
    val filtered = if (filter == -1) tasks else tasks.filter { it.status == filter }
    val active = tasks.count { it.status != 2 }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    // Satu launcher izin kalender & satu launcher izin notifikasi dipakai bareng
    // buat dialog tambah tugas maupun toggle di TaskRow, lewat "pending action" pattern.
    var pendingCalGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingNotifGrant by remember { mutableStateOf<(() -> Unit)?>(null) }

    val calPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) pendingCalGrant?.invoke()
        pendingCalGrant = null
    }
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingNotifGrant?.invoke()
        pendingNotifGrant = null
    }

    // [BARU] Launcher buat layar consent Google Calendar (beda dari izin Android biasa di atas
    // — ini scope OAuth, ditangani lewat GoogleCalendarAuth, bukan ContextCompat/Manifest.permission).
    var pendingGoogleCalGrant by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val googleCalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        when (val outcome = GoogleCalendarAuth.resultFromIntent(context, result.data)) {
            is GoogleCalendarAuth.AuthOutcome.Authorized -> pendingGoogleCalGrant?.invoke(outcome.accessToken)
            is GoogleCalendarAuth.AuthOutcome.Failed -> scope.launch { snackbarHost.showSnackbar(outcome.message) }
            is GoogleCalendarAuth.AuthOutcome.NeedsConsent -> {} // gak mungkin balik NeedsConsent lagi dari sini
        }
        pendingGoogleCalGrant = null
    }

    fun ensureCalendarPermission(onGranted: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (granted) onGranted()
        else { pendingCalGrant = onGranted; calPermLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }
    }
    fun ensureNotificationPermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { onGranted(); return }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) onGranted()
        else { pendingNotifGrant = onGranted; notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    // [BARU] Sinkron kalender sekarang terhubung ke Google Calendar AKUN USER SENDIRI kalau dia
    // login — bukan lagi asal nulis ke kalender apa pun yang kebetulan "primary" di HP. Belum
    // login → fallback ke ensureCalendarPermission (perilaku LAMA, on-device), biar tetap
    // kepake buat yang belum/nggak mau login.
    // [FIX, audit item 3 — 28 Sep] Dulu kalau Google Calendar gagal diakses (offline, Play
    // Services error, scope dicabut, dll), fungsi ini cuma nampilin Snackbar lalu NYERAH TOTAL
    // — user yang login malah kehilangan sinkron kalender sama sekali sampai online lagi,
    // padahal user yang LOGOUT tetap bisa pakai jalur on-device. Sekarang login = PREFERENSI
    // (pakai Google Calendar kalau bisa), bukan KOMITMEN MUTLAK — gagal di titik mana pun di
    // alur Google, turun ke ensureCalendarPermission (jalur on-device, sama kayak user logout)
    // biar fitur ini tetap jalan walau offline. Lihat catatan relationship CalendarService vs
    // GoogleCalendarService vs NotificationHelper di AppViewModel.addTask().
    fun ensureGoogleCalendarAccess(onDeviceFallback: () -> Unit, onGoogleToken: (String) -> Unit) {
        if (currentUser == null) { ensureCalendarPermission(onDeviceFallback); return }
        scope.launch {
            when (val outcome = GoogleCalendarAuth.authorize(context)) {
                is GoogleCalendarAuth.AuthOutcome.Authorized -> onGoogleToken(outcome.accessToken)
                is GoogleCalendarAuth.AuthOutcome.NeedsConsent -> {
                    pendingGoogleCalGrant = onGoogleToken
                    googleCalLauncher.launch(outcome.intentSenderRequest)
                }
                is GoogleCalendarAuth.AuthOutcome.Failed -> {
                    snackbarHost.showSnackbar("Google Calendar tidak dapat diakses sekarang — gunakan kalender HP terlebih dahulu.")
                    ensureCalendarPermission(onDeviceFallback)
                }
            }
        }
    }

    LaunchedEffect(taskError) {
        taskError?.let { snackbarHost.showSnackbar(it); vm.clearTaskError() }
    }

    Scaffold(
        containerColor = AppBg,
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }, containerColor = AppAccent, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Add, "Tugas Baru", Modifier.size(22.dp))
            }
        },
        topBar = { AppHeader(title = "Tugas & Jadwal", subtitle = "$active aktif sekarang") }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(-1 to "Semua", 0 to "Belum", 1 to "Dikerjakan", 2 to "Selesai")) { (v, l) ->
                    FilterChip(
                        selected = filter == v, onClick = { filter = v },
                        label = { Text(l, fontSize = 12.sp, fontWeight = if (filter == v) FontWeight.SemiBold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppAccent, selectedLabelColor = MaterialTheme.colorScheme.onPrimary, containerColor = AppBgMuted, labelColor = AppTextMuted),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == v, borderColor = AppBorder, selectedBorderColor = AppAccent)
                    )
                }
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                    if (filter == -1) {
                        EmptyView(
                            Icons.Outlined.TaskAlt,
                            "Belum ada tugas",
                            "Catat tugas kuliah dan tenggatnya di sini, lengkap dengan pengingat dan sinkron kalender.",
                            actionLabel = "Tambah Tugas",
                            actionIcon = Icons.Default.Add,
                            onAction = { showAdd = true }
                        )
                    } else {
                        EmptyView(
                            Icons.Outlined.TaskAlt,
                            "Belum ada tugas \"${statusLabel(filter)}\"",
                            "Tugas dengan status ini akan muncul di sini.",
                            actionLabel = "Tampilkan Semua",
                            actionIcon = Icons.Default.Close,
                            onAction = { filter = -1 }
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(Spacing.lg, Spacing.sm, Spacing.lg, 96.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    itemsIndexed(filtered, key = { _, it -> it.id }) { idx, task ->
                        EntranceItem(idx) {
                            TaskRow(
                                task = task,
                                onToggle = { vm.toggleTask(task) },
                                onDelete = {
                                    if (task.googleCalendarEventId != null) {
                                        scope.launch { vm.deleteTask(task.id, GoogleCalendarAuth.getAccessTokenSilently(context)) }
                                    } else {
                                        vm.deleteTask(task.id)
                                    }
                                },
                                onToggleCalendar = {
                                    when {
                                        task.googleCalendarEventId != null ->
                                            scope.launch { vm.toggleCalendarSync(task, GoogleCalendarAuth.getAccessTokenSilently(context)) }
                                        task.calendarEventId != null -> vm.toggleCalendarSync(task)
                                        else -> ensureGoogleCalendarAccess(
                                            onDeviceFallback = { vm.toggleCalendarSync(task) },
                                            onGoogleToken = { token -> vm.toggleCalendarSync(task, token) }
                                        )
                                    }
                                },
                                onToggleReminder = {
                                    if (task.reminderEnabled) vm.toggleReminder(task)
                                    else ensureNotificationPermission { vm.toggleReminder(task) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var deadline by remember { mutableStateOf("") }
        var priority by remember { mutableIntStateOf(1) }
        var syncCalendar by remember { mutableStateOf(false) }
        var googleCalToken by remember { mutableStateOf<String?>(null) }
        var reminderOn by remember { mutableStateOf(false) }
        var reminderMinutes by remember { mutableIntStateOf(1440) }
        var showDatePicker by remember { mutableStateOf(false) }
        var showWorkspacePicker by remember { mutableStateOf(false) }
        var selectedWsId by remember { mutableStateOf<String?>(null) }
        val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun closeAddSheet() { scope.launch { addSheetState.hide() }.invokeOnCompletion { showAdd = false } }

        // [FIX, referensi desain William] Dulu AppDialog (kartu di tengah layar, EyebrowLabel
        // + kotak per-field). Sekarang ModalBottomSheet niru referensi: ikon di kiri tiap
        // field, garis tipis di bawah sebagai pemisah (bukan kotak numpuk), Prioritas jadi
        // teks+garis-bawah (bukan chip), Ruang Kerja dipencet buka picker sendiri (bukan chip
        // inline — daftarnya bisa panjang, beda dari Prioritas yang cuma 3 opsi tetap).
        ModalBottomSheet(
            onDismissRequest = { showAdd = false }, sheetState = addSheetState,
            containerColor = AppBgCard, contentColor = AppText,
            dragHandle = { BottomSheetDefaults.DragHandle(color = AppBorder) }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, top = 0.dp, end = Spacing.md, bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Box(Modifier.fillMaxWidth()) {
                        IconButton(onClick = { closeAddSheet() }, modifier = Modifier.size(24.dp).align(Alignment.CenterStart)) {
                            Icon(Icons.Default.Close, "Tutup", tint = AppTextMuted, modifier = Modifier.size(19.dp))
                        }
                    }
                }
                item {
                    Column {
                        Text("Tugas Baru", color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Rencanakan hari, capai tujuan.", color = AppTextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }

                item {
                    TaskFieldRow(Icons.Default.Title, "Judul") {
                        AppInputFlat(title, { title = it }, "Contoh: Ringkasan Bab 2 Fisika", maxLength = 100)
                    }
                }

                item {
                    TaskFieldRow(Icons.Outlined.Description, "Deskripsi (Opsional)") {
                        Column {
                            AppInput(desc, { if (it.length <= 500) desc = it }, "Tambahkan detail tugas di sini...", singleLine = false, minLines = 2)
                            Text("${desc.length}/500", color = AppTextDim, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.End).padding(top = 3.dp))
                        }
                    }
                }

                item {
                    TaskFieldRow(Icons.Outlined.CalendarMonth, "Deadline (Opsional)") {
                        Row(Modifier.fillMaxWidth().clickable { showDatePicker = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (deadline.isBlank()) "Pilih tanggal" else DateUtils.formatDeadlineDisplay(deadline),
                                color = if (deadline.isBlank()) AppTextDim else AppText, fontSize = 13.sp, modifier = Modifier.weight(1f)
                            )
                            if (deadline.isNotBlank()) {
                                IconButton(onClick = { deadline = ""; syncCalendar = false; googleCalToken = null; reminderOn = false }, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Default.Close, "Hapus tanggal", tint = AppTextDim, modifier = Modifier.size(14.dp))
                                }
                            } else {
                                Icon(Icons.Default.ChevronRight, null, tint = AppTextDim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                item {
                    TaskFieldRow(Icons.Outlined.Flag, "Prioritas") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf(0 to "Rendah", 1 to "Sedang", 2 to "Tinggi").forEach { (p, l) ->
                                val selected = priority == p
                                val c = if (selected) priColor(p) else AppTextMuted
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { priority = p }.padding(vertical = 2.dp)) {
                                    Text(l, color = c, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    Spacer(Modifier.height(4.dp))
                                    Box(Modifier.width(28.dp).height(2.dp).background(if (selected) c else Color.Transparent))
                                }
                            }
                        }
                    }
                }

                // [FIX] Sebelumnya dialog ini gak punya cara buat kaitin tugas ke Ruang Kerja
                // sama sekali — Task.workspaceId jadi selalu null walau field & filter buat
                // Ekspor/Laporan per-Ruang-Kerja udah ada. Baris ini opsional (default "Tidak
                // ada"), cuma muncul kalau minimal ada 1 Ruang Kerja.
                if (workspaces.isNotEmpty()) {
                    item {
                        TaskFieldRow(Icons.Outlined.FolderOpen, "Ruang Kerja (Opsional)", caption = "Anda bisa mengubahnya nanti.") {
                            Row(Modifier.fillMaxWidth().clickable { showWorkspacePicker = true }, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    selectedWsId?.let { id -> workspaces.find { it.id == id }?.name } ?: "Pilih workspace",
                                    color = if (selectedWsId == null) AppTextDim else AppText, fontSize = 13.sp, modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.Default.ChevronRight, null, tint = AppTextDim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                item {
                    SyncToggleRow(
                        title = "Sinkron ke Kalender",
                        desc = if (currentUser != null) "Buat acara otomatis di Google Calendar akun Anda." else "Buat acara otomatis di aplikasi kalender HP.",
                        checked = syncCalendar, enabled = deadline.isNotBlank(), icon = Icons.Outlined.CalendarMonth,
                        onCheckedChange = { checked ->
                            if (checked) ensureGoogleCalendarAccess(
                                onDeviceFallback = { syncCalendar = true },
                                onGoogleToken = { token -> googleCalToken = token; syncCalendar = true }
                            ) else { syncCalendar = false; googleCalToken = null }
                        },
                        disabledHint = if (deadline.isBlank()) "Isi deadline terlebih dahulu untuk mengaktifkan ini" else null
                    )
                }

                item {
                    SyncToggleRow(
                        title = "Ingatkan Aku", desc = "Notifikasi lokal sebelum deadline.",
                        checked = reminderOn, enabled = deadline.isNotBlank(), icon = Icons.Default.Notifications, showDivider = false,
                        onCheckedChange = { checked -> if (checked) ensureNotificationPermission { reminderOn = true } else reminderOn = false },
                        disabledHint = if (deadline.isBlank()) "Isi deadline terlebih dahulu untuk mengaktifkan ini" else null
                    ) {
                        if (reminderOn) {
                            Row(Modifier.padding(top = 6.dp, start = 27.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ReminderPresets.forEach { (m, l) ->
                                    FilterChip(
                                        selected = reminderMinutes == m, onClick = { reminderMinutes = m },
                                        label = { Text(l, fontSize = 10.sp) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppAccent.copy(alpha = 0.15f), selectedLabelColor = AppAccent, containerColor = AppBgMuted, labelColor = AppTextMuted),
                                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = reminderMinutes == m, borderColor = AppBorder, selectedBorderColor = AppAccent),
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        } else if (syncCalendar && currentUser == null) {
                            // [BARU, audit item 3] Reminder bawaan kalender HP itu best-effort —
                            // ditangani app Kalender/OS, bukan RKR (lihat catatan lengkap di
                            // AppViewModel.addTask()). Nudge ini nolong user yang taunya cuma
                            // nyalain "Sinkron ke Kalender" terus ngarep ada notifikasi pasti.
                            Text(
                                "Kalender HP tidak selalu menampilkan pengingat tepat waktu. Aktifkan ini juga apabila membutuhkan notifikasi yang pasti muncul.",
                                color = AppTextDim, fontSize = 10.5.sp, lineHeight = 13.sp,
                                modifier = Modifier.padding(top = 6.dp, start = 27.dp)
                            )
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 2.dp)) {
                        OutlinedButton(
                            onClick = { closeAddSheet() }, modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(50), colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
                            border = BorderStroke(1.dp, AppBorder)
                        ) { Text("Batal", fontWeight = FontWeight.Medium, fontSize = 14.sp) }
                        Button(
                            onClick = {
                                if (title.isNotBlank()) {
                                    vm.addTask(title.trim(), desc.trim(), deadline.trim(), priority, syncCalendar, reminderOn, reminderMinutes, selectedWsId, googleCalToken)
                                    closeAddSheet()
                                }
                            },
                            modifier = Modifier.weight(2f).height(44.dp), enabled = title.isNotBlank(),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = AppAccent, contentColor = MaterialTheme.colorScheme.onPrimary, disabledContainerColor = AppAccent.copy(alpha = 0.3f))
                        ) { Text("Tambah", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                    }
                }
            }
        }

        if (showWorkspacePicker) {
            WorkspacePickerSheet(
                workspaces = workspaces, selectedId = selectedWsId,
                onSelect = { id -> selectedWsId = id },
                onDismiss = { showWorkspacePicker = false }
            )
        }

        if (showDatePicker) {
            val pickerState = rememberDatePickerState(initialSelectedDateMillis = DateUtils.deadlineToUtcMillis(deadline) ?: System.currentTimeMillis())
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { deadline = DateUtils.utcMillisToDeadline(it) }
                        showDatePicker = false
                    }) { Text("Pilih", color = AppAccent, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Batal", color = AppTextMuted) } },
                colors = DatePickerDefaults.colors(containerColor = AppBgCard)
            ) {
                DatePicker(state = pickerState, showModeToggle = false)
            }
        }
    }
}

// ── Pola dialog "Tugas Baru" versi baru (referensi William): tiap field ikon di kiri,
// label di atas kontennya, garis tipis di bawah sebagai pemisah (bukan kotak per-field) —
// TaskFieldRow bungkus pola ini biar gak ditulis ulang 5x. ──
@Composable
private fun TaskFieldRow(icon: ImageVector, label: String, caption: String? = null, showDivider: Boolean = true, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = AppTextMuted, modifier = Modifier.size(17.dp).padding(top = 1.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                content()
                if (caption != null) Text(caption, color = AppTextDim, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (showDivider) HorizontalDivider(color = AppBorder, thickness = 1.dp, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Picker Ruang Kerja sebagai bottom sheet terpisah (dipicu dari baris "Ruang Kerja" yang
 * cuma nampilin chevron, bukan chip inline lagi — beda dari Prioritas yang cuma 3 opsi tetap
 * jadi tetap pantas ditampilin langsung). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspacePickerSheet(workspaces: List<Workspace>, selectedId: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pickerScope = rememberCoroutineScope()
    fun pick(id: String?) {
        onSelect(id)
        pickerScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = AppBgCard, contentColor = AppText,
        dragHandle = { BottomSheetDefaults.DragHandle(color = AppBorder) }
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.lg).padding(bottom = Spacing.md)) {
            Text("Pilih Ruang Kerja", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = Spacing.sm))
            WorkspacePickerRow("Tidak ada", selectedId == null) { pick(null) }
            workspaces.forEach { ws -> WorkspacePickerRow(ws.name, selectedId == ws.id) { pick(ws.id) } }
        }
    }
}

@Composable
private fun WorkspacePickerRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (selected) AppAccent else AppText, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, null, tint = AppAccent, modifier = Modifier.size(18.dp))
    }
}

/** Baris toggle reusable buat opsi kalender/pengingat di dialog tambah tugas. */
@Composable
private fun SyncToggleRow(
    title: String, desc: String, checked: Boolean, enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit, disabledHint: String? = null,
    icon: ImageVector? = null, showDivider: Boolean = true,
    extra: @Composable () -> Unit = {}
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            if (icon != null) Icon(icon, null, tint = AppTextMuted, modifier = Modifier.size(17.dp).padding(top = 1.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Spacing.sm)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppText)
                    Text(desc, fontSize = 11.sp, color = AppTextMuted, lineHeight = 14.sp)
                    if (!enabled && disabledHint != null) Text(disabledHint, fontSize = 10.sp, color = AppTextDim, modifier = Modifier.padding(top = 2.dp))
                }
                Switch(
                    checked = checked, enabled = enabled, onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = AppAccent, checkedTrackColor = AppAccent.copy(alpha = 0.4f)),
                    modifier = Modifier.scale(0.85f)
                )
            }
        }
        extra()
        if (showDivider) HorizontalDivider(color = AppBorder, thickness = 1.dp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun TaskRow(
    task: Task, onToggle: () -> Unit, onDelete: () -> Unit,
    onToggleCalendar: () -> Unit, onToggleReminder: () -> Unit
) {
    val pColor = priColor(task.priority)
    val calendarSynced = task.calendarEventId != null || task.googleCalendarEventId != null
    val menuActions = listOf(
        if (calendarSynced)
            CardMenuAction("Hapus dari Kalender", Icons.Outlined.EventBusy, onClick = onToggleCalendar)
        else
            CardMenuAction("Sinkron ke Kalender", Icons.Outlined.CalendarMonth, enabled = task.deadline.isNotBlank(), onClick = onToggleCalendar),
        if (task.reminderEnabled)
            CardMenuAction("Matikan Pengingat", Icons.Outlined.NotificationsOff, onClick = onToggleReminder)
        else
            CardMenuAction("Aktifkan Pengingat", Icons.Outlined.NotificationsActive, enabled = task.deadline.isNotBlank(), onClick = onToggleReminder),
        CardMenuAction("Hapus Tugas", Icons.Default.DeleteOutline, danger = true, onClick = onDelete)
    )
    // Sengaja TANPA background/border kartu per tugas — kalau tugasnya banyak, kotak
    // berbingkai satu-satu numpuk gak enak dipandang (arahan terbaru: tab Tugas jangan
    // terlalu ngandelin card). Aksen warna prioritas di kiri + garis tipis di bawah udah
    // cukup buat misahin tiap baris, pola checklist biasa.
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(pColor))
            Row(Modifier.padding(vertical = Spacing.md, horizontal = Spacing.sm).weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                IconButton(onClick = onToggle, modifier = Modifier.size(24.dp).padding(top = 2.dp)) {
                    Icon(if (task.status == 2) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (task.status == 2) Success else AppBorder, modifier = Modifier.size(21.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(task.title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (task.status == 2) AppTextDim else AppText, textDecoration = if (task.status == 2) TextDecoration.LineThrough else TextDecoration.None)
                    if (task.description.isNotBlank()) Text(task.description, fontSize = 12.sp, color = AppTextMuted, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
                    Row(
                        Modifier.padding(top = 7.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Pill(priLabel(task.priority), pColor)
                        Pill(statusLabel(task.status), AppTextMuted)
                        if (task.deadline.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Outlined.Schedule, null, tint = AppTextDim, modifier = Modifier.size(11.dp))
                                Text(DateUtils.formatDeadlineDisplay(task.deadline), color = AppTextDim, fontSize = 11.sp)
                            }
                        }
                        if (task.googleCalendarEventId != null) Icon(Icons.Outlined.CalendarMonth, "Tersinkron ke Google Calendar", tint = AppAccent, modifier = Modifier.size(13.dp))
                        else if (task.calendarEventId != null) Icon(Icons.Outlined.CalendarMonth, "Tersinkron ke kalender HP", tint = AppAccent, modifier = Modifier.size(13.dp))
                        if (task.reminderEnabled) Icon(Icons.Outlined.NotificationsActive, "Pengingat aktif", tint = AppAccent, modifier = Modifier.size(13.dp))
                    }
                }
                CardOverflowMenu(menuActions, modifier = Modifier.padding(top = 2.dp))
            }
        }
        HorizontalDivider(color = AppBorder)
    }
}
