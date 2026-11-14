package com.ruangkerja.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.screens.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.util.MatchConfidence
import com.ruangkerja.companion.util.draftDisplayTitle
import com.ruangkerja.companion.viewmodel.AppViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // [BARU, Fase 3 ekosistem draft] Notifikasi saran draft (NotificationHelper.
        // buildSuggestionNotification) nyisipin extra ini biar app kebuka LANGSUNG ke
        // halaman Notifikasi, bukan Beranda seperti biasa.
        val openNotifications = intent?.getBooleanExtra(
            com.ruangkerja.companion.notification.NotificationHelper.EXTRA_OPEN_NOTIFICATIONS, false
        ) ?: false
        setContent {
            val vm: AppViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val accent by vm.accent.collectAsStateWithLifecycle()
            val language by vm.language.collectAsStateWithLifecycle()
            AppTheme(themeMode = themeMode, accent = accent, language = language) {
                RuangKerjaApp(vm, openNotifications = openNotifications)
            }
        }
    }
}

private data class NavTab(val route: String, val label: String, val icon: ImageVector)
private val TABS = listOf(
    NavTab("home",     "Beranda", Icons.Default.Home),
    NavTab("tasks",    "Tugas",   Icons.Default.CheckBox),
    NavTab("progress", "Progres", Icons.Default.BarChart)
)
private val TAB_ROUTES = TABS.map { it.route }.toSet()

@Composable
private fun navLabel(route: String): String = when (route) {
    "home" -> Str.navHome
    "tasks" -> Str.navTasks
    "progress" -> Str.navProgress
    else -> route
}

@Composable
fun RuangKerjaApp(vm: AppViewModel, openNotifications: Boolean = false) {
    val nav = rememberNavController()
    val currentEntry by nav.currentBackStackEntryAsState()
    // [FIX, 15 Okt — laporan William: bottom nav bar + FAB "+" di Beranda "loncat" pas app baru
    // dibuka, tombolnya sempet gak kena pas diklik] currentBackStackEntryAsState() masih null di
    // FRAME PERTAMA — NavHost di bawah baru nempelin graph & pindah ke startDestination belakangan
    // di composition pass yang sama, sedangkan State dari currentBackStackEntryAsState() baru
    // kebaruin di recomposition BERIKUTNYA. Akibatnya currentRoute null sesaat → isHome/isBeranda
    // ikut false → NavigationBar() di slot bottomBar Scaffold bawah SAMA SEKALI gak dirender di
    // frame itu. WorkspaceListScreen nempatin FAB "+"-nya lewat Box(Modifier.align(BottomEnd)) yang
    // ngisi tinggi NavHost (dikurangin padding dari Scaffold ini) — pas NavigationBar belum ada,
    // tinggi yang kesedia buat NavHost SEMENTARA lebih tinggi dari harusnya, jadi FAB-nya kerender
    // lebih ke bawah. Begitu frame berikutnya currentRoute kebaca "home" & NavigationBar nongol,
    // Scaffold ngitung ulang padding-nya (NavHost ngecil nyisain tempat buat bar), FAB-nya "loncat"
    // naik — pas-pasan sama momen user ngeklik, jadi tombolnya kerasa gak kena/gak responsif.
    // Fallback "home" di bawah ini BUKAN tebakan sembarangan: itu persis startDestination NavHost
    // ("startDestination = "home"", lihat di bawah) — satu-satunya rute yang MUNGKIN aktif sebelum
    // graph-nya nempel. Abis frame pertama, currentEntry gak pernah null lagi selama app hidup
    // (termasuk pas dibuka dari notifikasi — LaunchedEffect(openNotifications) baru jalan belakangan,
    // frame pertamanya tetap "home" juga), jadi fallback ini gak pernah kepake di luar momen startup.
    val currentRoute = currentEntry?.destination?.route ?: "home"
    val isHome = currentRoute in TABS.map { it.route }   // = rute yang punya bottom bar (Beranda/Tugas/Progres), BUKAN cuma Beranda
    // [FIX, 1 Okt — laporan William: drawer bisa dibuka dari mana saja, harusnya cuma di Beranda]
    // ModalNavigationDrawer punya gesturesEnabled = true secara bawaan, jadi geser dari tepi kiri
    // membuka drawer di SEMUA rute (Tugas, Progres, Pengaturan, Detail Ruang Kerja/Bab, Editor,
    // Notifikasi). Padahal isinya (draf, saran, pintasan) hanya masuk akal di Beranda: tombol "Draf
    // baru" di drawer cuma mengubah state yang overlay-nya dirender WorkspaceListScreen, jadi dari
    // rute lain overlay-nya baru muncul tiba-tiba saat kembali ke Beranda. Sekarang gestur hanya
    // aktif di rute "home" (bukan Tugas/Progres, makanya `isHome` di atas tidak dipakai di sini).
    // Tombol Menu (onOpenDrawer) memang sudah cuma dipasang di WorkspaceListScreen.
    val isBeranda = currentRoute == "home"

    // [FIX, 22 Sep — laporan William: side drawer bikin konten "digeser ke bawah" & animasi
    // berantakan pas dibuka] Drawer Beranda DIPINDAH ke sini dari WorkspaceListScreen, ngebungkus
    // Scaffold PALING LUAR di bawah ini (termasuk bottomBar-nya) — bukan cuma konten NavHost
    // kayak sebelumnya. Akar masalahnya: dulu bottomBar disembunyiin manual pas drawer kebuka
    // (lewat isDrawerOpen di ViewModel) soalnya dia "slot" terpisah dari konten NavHost yang
    // dibungkus drawer, jadi kalau gak disembunyiin dia nempel di atas & gak ikut ketutup. Tapi
    // nyembunyiin/nampilin bottomBar itu sendiri bikin Scaffold paling luar ngitung ulang tinggi
    // & padding kontennya SAAT ITU JUGA — persis pas drawer lagi animasi slide masuk — makanya
    // kerasa "ketimpa jadi geser" & dua animasi itu saling nabrak/nggak singkron. Sekarang
    // bottomBar SELALU ada di komposisi apa adanya (gak pernah ditoggle lagi), drawer cuma
    // numpuk scrim+sheet DI ATAS segalanya (termasuk bottomBar) — layout di baliknya gak pernah
    // resize, jadi keliatan bener-bener ketimpa kayak drawer chat AI pada umumnya.
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val suggestionHistory by vm.suggestionHistory.collectAsStateWithLifecycle()

    // [BARU, Fase 3 ekosistem draft] Sekali doang pas pertama kali dibuka dari notifikasi
    // saran draft — navigate ke halaman Notifikasi abis NavHost-nya siap (startDestination
    // "home" udah sempat ke-compose dulu sebelum ini jalan).
    LaunchedEffect(openNotifications) {
        if (openNotifications) nav.navigate("notifications")
    }

    // Pindah dari Beranda saat drawer masih terbuka (mis. buka Notifikasi lewat notifikasi sistem):
    // tutup dulu. Gestur sudah mati di rute lain, jadi drawer tidak boleh dibiarkan menggantung terbuka.
    LaunchedEffect(isBeranda) { if (!isBeranda && drawerState.isOpen) drawerState.close() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isBeranda,
        drawerContent = {
            HomeDrawerContent(
                drafts = drafts,
                pendingSuggestionCount = suggestionHistory.count { it.status == "SEEN" || it.status == "SNOOZED" || it.status == "PENDING" },
                onOpenDraft = { d -> drawerScope.launch { drawerState.close(); vm.setDraftEditTarget(d) } },
                onNewDraft = { drawerScope.launch { drawerState.close(); vm.setShowCreateDraft(true) } },
                onDeleteDraft = { d -> vm.deleteDraft(d.id) },
                onMoveDraft = { d -> drawerScope.launch { drawerState.close(); vm.startManualMove(d) } },
                onSettings = { drawerScope.launch { drawerState.close(); nav.navigate("settings") } },
                onNotifications = { drawerScope.launch { drawerState.close(); nav.navigate("notifications") } }
            )
        }
    ) {
    Scaffold(
        containerColor = AppBg,
        // Tiap layar (AppHeader, header "Selamat pagi" di Beranda, dst) udah nangani
        // padding status bar-nya SENDIRI. Scaffold paling luar ini HARUS dimatiin auto-inset-nya,
        // soalnya kalau nggak, dia bakal nambahin padding status bar SEKALI LAGI di atas punya
        // tiap layar (dobel), dan Scaffold cuma otomatis skip itu kalau ada parameter `topBar`
        // yang diisi (di sini nggak ada, tiap layar bikin headernya sendiri-sendiri).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Sekarang ModalNavigationDrawer ngebungkus Scaffold INI (lihat RuangKerjaApp di
            // atas), jadi bottomBar ini otomatis ikut ke-scrim & ketutup drawer sheet pas
            // dibuka — gak perlu lagi ditoggle manual berdasarkan status drawer.
            if (isHome) {
                NavigationBar(containerColor = AppBgCard, tonalElevation = 0.dp) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    launchSingleTop = true
                                    popUpTo("home") { saveState = true }
                                    restoreState = true
                                }
                            },
                            icon  = { Icon(tab.icon, navLabel(tab.route)) },
                            label = { Text(navLabel(tab.route)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = AppAccent,
                                selectedTextColor   = AppAccent,
                                unselectedIconColor = AppTextMuted,
                                unselectedTextColor = AppTextMuted,
                                indicatorColor      = AppAccentSoft
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav, startDestination = "home", modifier = Modifier.padding(padding).background(AppBg),
            // Pindah antar tab bawah (Beranda/Tugas/Progres) dibikin fade tipis & cepat —
            // nggak butuh animasi geser penuh kayak buka halaman detail, dan lebih ringan
            // buat dirender di HP yang speknya pas-pasan. Navigasi "masuk lebih dalam" (buka
            // workspace/bab/pengaturan) tetap pakai slide, biar kerasa arah navigasinya —
            // durasinya udah dipangkas dua kali (260→190→130) soalnya masih kerasa "delay".
            enterTransition = {
                if (initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES) fadeIn(animationSpec = tween(140))
                else slideInHorizontally(animationSpec = tween(130), initialOffsetX = { it / 5 }) + fadeIn(animationSpec = tween(120))
            },
            exitTransition = {
                if (initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES) fadeOut(animationSpec = tween(120))
                else slideOutHorizontally(animationSpec = tween(110), targetOffsetX = { -it / 8 }) + fadeOut(animationSpec = tween(100))
            },
            popEnterTransition = {
                if (initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES) fadeIn(animationSpec = tween(140))
                else slideInHorizontally(animationSpec = tween(130), initialOffsetX = { -it / 8 }) + fadeIn(animationSpec = tween(120))
            },
            popExitTransition = {
                if (initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES) fadeOut(animationSpec = tween(120))
                else slideOutHorizontally(animationSpec = tween(110), targetOffsetX = { it / 5 }) + fadeOut(animationSpec = tween(100))
            }
        ) {
            composable("home") {
                WorkspaceListScreen(
                    vm = vm,
                    onOpen = { id -> vm.selectWorkspace(id); nav.navigate("workspace/$id") },
                    onOpenChapter = { id -> vm.selectChapter(id); nav.navigate("chapter/$id") },
                    onSettings = { nav.navigate("settings") },
                    onNotifications = { nav.navigate("notifications") },
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                )
            }
            composable("workspace/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val id = it.arguments?.getString("id") ?: return@composable
                LaunchedEffect(id) { vm.selectWorkspace(id) }
                WorkspaceDetailScreen(
                    wsId = id, vm = vm,
                    onOpen = { cId -> vm.clearTransientState(); vm.selectChapter(cId); nav.navigate("chapter/$cId") },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("chapter/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val id = it.arguments?.getString("id") ?: return@composable
                LaunchedEffect(id) { vm.selectChapter(id) }
                ChapterDetailScreen(chId = id, vm = vm, onBack = { nav.popBackStack() }, onOpenEditor = { nav.navigate("editor") })
            }
            composable("editor") {
                NotebookEditorScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable("tasks")    { TaskScreen(vm) }
            composable("progress") { ProgressScreen(vm) }
            composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
            composable("notifications") { NotificationCenterScreen(vm, onBack = { nav.popBackStack() }) }
        }
    }
    } // tutup ModalNavigationDrawer

    // [DIPINDAH, 19 Okt — masukan William: "untuk munculin pop up nya nggak usah di tutup
    // halaman notifikasi biar aja tetep di dalam sana, ribet kalau harus buka tutup lagi"]
    // Alur "ekosistem draft" (draftEditTarget, draftMatch, chapterPicker, newChapterPrompt) —
    // dulu SEMUA dirender di dalam WorkspaceListScreen.kt (Beranda doang), jadi kalau trigger-nya
    // (SuggestionRow di NotificationCenterScreen) dipencet dari layar LAIN, popup-nya gak akan
    // pernah kebaca — NavHost di atas cuma nge-compose SATU rute aktif per waktu, dan
    // WorkspaceListScreen gak termasuk rute aktif itu kalau user lagi di halaman Notifikasi.
    // Sekarang keempatnya dirender di SINI — level RuangKerjaApp, sejajar ModalNavigationDrawer
    // di atas — jadi SELALU ter-compose berapa pun & di mana pun rute aktifnya. Isi & aksi tiap
    // dialognya PERSIS SAMA kayak sebelumnya, cuma lokasinya yang pindah (plus draft.title.
    // ifBlank{"Tanpa Judul"} di 2 tempat diganti draftDisplayTitle(draft) — lihat masukan
    // William soal draft tanpa judul, util/ChapterNamer.kt).
    val draftEditTarget by vm.draftEditTarget.collectAsStateWithLifecycle()
    draftEditTarget?.let { d ->
        DraftEditorOverlay(vm = vm, initial = d, onDismiss = { vm.setDraftEditTarget(null) })
    }

    // [BARU, Fase 1] Ekosistem draft — muncul abis draft ditutup, kalau kata kunci nama salah
    // satu workspace nyantol di draft itu (lihat AppViewModel.checkDraftMatch/util/DraftMatcher.kt).
    val draftMatch by vm.draftMatch.collectAsStateWithLifecycle()
    draftMatch?.let { (draft, match) ->
        // [FIX, 18 Okt — masukan William: "jangan 'kemungkinan cocok', kayak plin-plan"] Ganti
        // frasa hedging ("kemungkinan besar"/"mungkin") jadi pernyataan langsung — kata kunci
        // yang nyantol itu FAKTA (hasil regex, bukan tebakan AI), yang "gak pasti" cuma soal
        // relevan-nggaknya buat user, bukan soal ketemunya sendiri. HIGH/MEDIUM tetap dibedain
        // lewat kadar kata-katanya, bukan lewat hedge "mungkin"/"kemungkinan". Sama persis
        // dengan SuggestionRow di NotificationCenterScreen.kt, biar konsisten di 2 tempat.
        val penjelasan = if (match.confidence == MatchConfidence.HIGH)
            "cocok dengan" else "ada kaitan dengan"
        // [UBAH, 19 Okt — ronde 4/4, redesign dari referensi gambar William] Badge ikon (IconBox
        // bawaan AppDialog, lihat CommonComponents.kt) di atas judul; "Lihat"/"Pindahkan" tetap 2
        // tombol sejajar, "Pindahkan" nambah panah di kanan (trailingIcon di PrimaryBtn).
        // [UBAH, 21 Okt — masukan William atas hasil ronde 4/4 di atas] 3 perbaikan kecil, AKSI
        // (snoozeDraftMatch/dismissDraftMatch/moveDraftToWorkspace/clearDraftMatchPopup) SAMA
        // PERSIS kayak sebelumnya, murni ubah tampilan + 1 ubah pemetaan tombol-aksi:
        //   - "Ingatkan nanti": ikon kotak centang (CheckBoxOutlineBlank) dihapus, baris kustom
        //     border+clickable manual diganti GhostBtn biasa (ikon lonceng tetap, kiri teks) —
        //     biar jelas kebaca sebagai TOMBOL, bukan item checklist yang bisa "dicentang".
        //   - "Batal": dulu SALAH manggil dismissDraftMatch() (nandain status Diabaikan permanen)
        //     — sekarang cuma nutup pop-up TANPA ubah status (clearDraftMatchPopup, sama persis
        //     kayak tombol X/tap-di-luar dialog), sesuai konfirmasi William bagian ini salah.
        //   - "Abaikan" dikembaliin jadi tombol sendiri yang KELIHATAN (sempat ke-gabung diam-
        //     diam ke tombol Batal pas redesign ronde 4/4 — itu yang bikin William nanya "tombol
        //     abaikan nya kemana?"). Manggil dismissDraftMatch() kayak dulu, biar kategori
        //     "Diabaikan" di Notification Center tetap keisi.
        AppDialog("Saran Ruang Kerja", { vm.clearDraftMatchPopup() }, icon = Icons.Outlined.Description) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "Draft \"${draftDisplayTitle(draft)}\" $penjelasan Ruang Kerja \"${match.workspace.name}\", berdasarkan kata kunci yang terdapat pada judul atau isinya.",
                    color = AppTextMuted, fontSize = 13.sp, lineHeight = 19.sp
                )
                GhostBtn("Ingatkan nanti", { vm.snoozeDraftMatch() }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Notifications)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // "Lihat" nutup pop-up TANPA dismiss/snooze, terus buka draft-nya —
                    // biar user baca dulu sebelum mutusin (lihat clearDraftMatchPopup()).
                    GhostBtn("Lihat", { vm.clearDraftMatchPopup(); vm.setDraftEditTarget(draft) }, Modifier.weight(1f))
                    PrimaryBtn("Pindahkan", { vm.moveDraftToWorkspace() }, Modifier.weight(1f), icon = Icons.Default.ArrowForward, trailingIcon = true)
                }
                HorizontalDivider(color = AppBorder)
                // [UBAH, 21 Okt] "Batal" (cancel murni) & "Abaikan" (dismissDraftMatch, status
                // permanen) sekarang 2 tombol teks terpisah sejajar — dulu sempat ke-gabung jadi
                // 1 tombol "Batal" doang yang diam-diam manggil dismissDraftMatch().
                Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.dismissDraftMatch() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Abaikan", color = AppTextMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.clearDraftMatchPopup() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Batal", color = AppTextMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    // [BARU, Fase 1] Muncul kalau workspace tujuan (dari dialog di atas, tombol "Pindahkan")
    // punya lebih dari 1 bab — biar draft nyasar ke bab yang relevan, bukan asal dump.
    val chapterPicker by vm.chapterPicker.collectAsStateWithLifecycle()
    chapterPicker?.let { (draft, ws, chapters) ->
        AppDialog("Pilih Bab Tujuan", { vm.cancelChapterPicker() }) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                chapters.forEach { ch ->
                    GhostBtn(ch.name.ifBlank { "(Tanpa Judul Bab)" }, { vm.moveDraftToChapter(ch) }, Modifier.fillMaxWidth())
                }
                GhostBtn("+ Buat Bab Baru", { vm.moveDraftToNewChapter() }, Modifier.fillMaxWidth())
            }
        }
    }

    // [BARU, 18 Okt — masukan William: "nanti di tanya mau di masukkan ke suatu materi yang
    // udah ada atau bikin baru dengan nama buatan sendiri atau dari sistem, dan nama dari
    // sistem jangan template"] Muncul abis user mutusin draft perlu Bab BARU (baik dari alur
    // otomatis maupun pemindahan manual) — field nama udah keisi SARAN dari
    // generateChapterName() (lihat util/ChapterNamer.kt), tapi user bebas ganti total sebelum
    // Bab-nya beneran dibikin di AppViewModel.confirmNewChapterName().
    val newChapterPrompt by vm.newChapterPrompt.collectAsStateWithLifecycle()
    newChapterPrompt?.let { prompt ->
        var name by remember(prompt) { mutableStateOf(prompt.suggestedName) }
        AppDialog("Nama Bab Baru", { vm.cancelNewChapterPrompt() }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Draft \"${draftDisplayTitle(prompt.draft)}\" akan dipindahkan ke Bab baru di Ruang Kerja \"${prompt.workspace.name}\".",
                    color = AppTextMuted, fontSize = 13.sp, lineHeight = 19.sp
                )
                AppInputFlat(name, { name = it }, "Nama Bab")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 4.dp)) {
                    GhostBtn("Batal", { vm.cancelNewChapterPrompt() }, Modifier.weight(1f))
                    PrimaryBtn("Buat & Pindahkan", { vm.confirmNewChapterName(name) }, Modifier.weight(2f), enabled = name.isNotBlank())
                }
            }
        }
    }
}
