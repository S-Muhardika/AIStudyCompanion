package com.ruangkerja.companion.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

// ── Bahasa aplikasi — FONDASI multi-bahasa, bukan migrasi penuh. Ratusan string tersebar di
// ~20 file layar (Task, Progress, Workspace, Flashcard, dst) — migrasi semuanya sekaligus
// terlalu besar & berisiko buat satu sesi. Yang dicakup di sini: judul halaman + judul/subjudul
// 5 grup di Pengaturan, teks fitur Impor/Ekspor Data, dan label 3 tab navigasi bawah (Beranda/
// Tugas/Progres) — dipilih karena paling sering kelihatan (nav bar tampil di semua layar utama).
// Layar lain nyusul bertahap, satu layar per sesi.
//
// CATATAN SOAL ARAB (RTL): teks Arab-nya bakal kebaca benar (Compose Text render skrip Arab
// secara native), TAPI arah LAYOUT (posisi ikon, alignment kiri/kanan) belum ikut ke-mirror
// otomatis ke kanan-ke-kiri cuma dari ganti teks doang — itu kerjaan terpisah (perlu atur
// LayoutDirection), belum masuk sesi ini.
enum class AppLanguage(val label: String) {
    ID("Indonesia"),
    EN("English"),
    AR("العربية"),
    ZH("中文"),
    JA("日本語")
}

data class L10n(
    val settingsTitle: String,
    val groupTampilanTitle: String, val groupTampilanSubtitle: String,
    val groupLanjutanTitle: String, val groupLanjutanSubtitle: String,
    val groupNotifTitle: String, val groupNotifSubtitle: String,
    val groupAkunTitle: String, val groupAkunSubtitle: String,
    val groupDataTitle: String, val groupDataSubtitle: String,
    val languageRowTitle: String, val languageRowDesc: String,
    val exportTitle: String, val exportDesc: String,
    val importTitle: String, val importDesc: String, val importButton: String, val importingButton: String,
    val importConfirmTitle: String, val importConfirmText: String, val importConfirmButton: String, val cancelButton: String,
    val importSuccess: (Int) -> String, val importError: (String) -> String,
    val navHome: String, val navTasks: String, val navProgress: String
)

private val idL10n = L10n(
    settingsTitle = "Pengaturan",
    groupTampilanTitle = "Tampilan", groupTampilanSubtitle = "Sesuaikan tema dan warna aksen aplikasi",
    groupLanjutanTitle = "Fitur Lanjutan", groupLanjutanSubtitle = "Pengaturan tambahan untuk pengguna berpengalaman",
    groupNotifTitle = "Notifikasi", groupNotifSubtitle = "Atur kapan aplikasi boleh mengingatkan Anda",
    groupAkunTitle = "Akun & Sinkronisasi", groupAkunSubtitle = "Opsional — semua fitur belajar tetap berjalan penuh tanpa login",
    groupDataTitle = "Data & Privasi", groupDataSubtitle = "Kelola data yang tersimpan di perangkat Anda",
    languageRowTitle = "Bahasa", languageRowDesc = "Pilih bahasa tampilan aplikasi",
    exportTitle = "Ekspor Data", exportDesc = "Unduh seluruh Ruang Kerja, materi, dan tugas sebagai file Zip",
    importTitle = "Impor Data", importDesc = "Pulihkan dari file backup Zip (kebalikan dari Ekspor Data)",
    importButton = "Impor", importingButton = "Memproses…",
    importConfirmTitle = "Impor Data Backup?",
    importConfirmText = "Data dari file backup akan ditulis ke perangkat ini. Ruang Kerja/bab/materi/tugas yang ID-nya sama dengan yang ada sekarang akan DITIMPA oleh isi file backup. Lanjutkan?",
    importConfirmButton = "Pilih File & Impor", cancelButton = "Batal",
    importSuccess = { n -> "Berhasil memulihkan $n ruang kerja." },
    importError = { msg -> "Gagal mengimpor: $msg" },
    navHome = "Beranda", navTasks = "Tugas", navProgress = "Progres"
)

private val enL10n = L10n(
    settingsTitle = "Settings",
    groupTampilanTitle = "Appearance", groupTampilanSubtitle = "Customize the app's theme and accent color",
    groupLanjutanTitle = "Advanced Features", groupLanjutanSubtitle = "Extra settings for experienced users",
    groupNotifTitle = "Notifications", groupNotifSubtitle = "Control when the app is allowed to remind you",
    groupAkunTitle = "Account & Sync", groupAkunSubtitle = "Optional — every study feature works fully without signing in",
    groupDataTitle = "Data & Privacy", groupDataSubtitle = "Manage the data stored on your device",
    languageRowTitle = "Language", languageRowDesc = "Choose the app's display language",
    exportTitle = "Export Data", exportDesc = "Download every workspace, material, and task as a Zip file",
    importTitle = "Import Data", importDesc = "Restore from a Zip backup file (the reverse of Export Data)",
    importButton = "Import", importingButton = "Processing…",
    importConfirmTitle = "Import Backup Data?",
    importConfirmText = "Data from the backup file will be written to this device. Any workspace/chapter/material/task sharing the same ID as what's already here will be OVERWRITTEN by the backup file's contents. Continue?",
    importConfirmButton = "Choose File & Import", cancelButton = "Cancel",
    importSuccess = { n -> "Successfully restored $n workspace(s)." },
    importError = { msg -> "Import failed: $msg" },
    navHome = "Home", navTasks = "Tasks", navProgress = "Progress"
)

private val arL10n = L10n(
    settingsTitle = "الإعدادات",
    groupTampilanTitle = "المظهر", groupTampilanSubtitle = "خصص سمة التطبيق ولونه المميز",
    groupLanjutanTitle = "الميزات المتقدمة", groupLanjutanSubtitle = "إعدادات إضافية للمستخدمين ذوي الخبرة",
    groupNotifTitle = "الإشعارات", groupNotifSubtitle = "تحكم في متى يمكن للتطبيق تذكيرك",
    groupAkunTitle = "الحساب والمزامنة", groupAkunSubtitle = "اختياري — جميع ميزات الدراسة تعمل بالكامل بدون تسجيل الدخول",
    groupDataTitle = "البيانات والخصوصية", groupDataSubtitle = "إدارة البيانات المخزنة على جهازك",
    languageRowTitle = "اللغة", languageRowDesc = "اختر لغة عرض التطبيق",
    exportTitle = "تصدير البيانات", exportDesc = "تنزيل جميع مساحات العمل والمواد والمهام كملف Zip",
    importTitle = "استيراد البيانات", importDesc = "الاستعادة من ملف نسخ احتياطي Zip (عكس تصدير البيانات)",
    importButton = "استيراد", importingButton = "جارٍ المعالجة…",
    importConfirmTitle = "استيراد بيانات النسخ الاحتياطي؟",
    importConfirmText = "سيتم كتابة بيانات ملف النسخ الاحتياطي على هذا الجهاز. أي مساحة عمل/فصل/مادة/مهمة لها نفس المعرف الحالي سيتم استبدالها بمحتوى ملف النسخ الاحتياطي. هل تريد المتابعة؟",
    importConfirmButton = "اختر ملفًا واستورد", cancelButton = "إلغاء",
    importSuccess = { n -> "تمت استعادة $n مساحة عمل بنجاح." },
    importError = { msg -> "فشل الاستيراد: $msg" },
    navHome = "الرئيسية", navTasks = "المهام", navProgress = "التقدم"
)

private val zhL10n = L10n(
    settingsTitle = "设置",
    groupTampilanTitle = "外观", groupTampilanSubtitle = "自定义应用的主题和强调色",
    groupLanjutanTitle = "高级功能", groupLanjutanSubtitle = "面向有经验用户的附加设置",
    groupNotifTitle = "通知", groupNotifSubtitle = "设置应用可以提醒你的时间",
    groupAkunTitle = "账户与同步", groupAkunSubtitle = "可选 — 无需登录即可使用全部学习功能",
    groupDataTitle = "数据与隐私", groupDataSubtitle = "管理设备上存储的数据",
    languageRowTitle = "语言", languageRowDesc = "选择应用显示语言",
    exportTitle = "导出数据", exportDesc = "将所有工作区、资料和任务下载为 Zip 文件",
    importTitle = "导入数据", importDesc = "从 Zip 备份文件恢复(与导出数据相反)",
    importButton = "导入", importingButton = "处理中…",
    importConfirmTitle = "导入备份数据?",
    importConfirmText = "备份文件中的数据将写入此设备。与现有 ID 相同的工作区/章节/资料/任务将被备份文件的内容覆盖。是否继续?",
    importConfirmButton = "选择文件并导入", cancelButton = "取消",
    importSuccess = { n -> "成功恢复了 $n 个工作区。" },
    importError = { msg -> "导入失败:$msg" },
    navHome = "首页", navTasks = "任务", navProgress = "进度"
)

private val jaL10n = L10n(
    settingsTitle = "設定",
    groupTampilanTitle = "外観", groupTampilanSubtitle = "アプリのテーマとアクセントカラーをカスタマイズ",
    groupLanjutanTitle = "高度な機能", groupLanjutanSubtitle = "経験のあるユーザー向けの追加設定",
    groupNotifTitle = "通知", groupNotifSubtitle = "アプリが通知するタイミングを設定",
    groupAkunTitle = "アカウントと同期", groupAkunSubtitle = "任意 — ログインなしでも全ての学習機能が利用可能",
    groupDataTitle = "データとプライバシー", groupDataSubtitle = "デバイスに保存されているデータを管理",
    languageRowTitle = "言語", languageRowDesc = "アプリの表示言語を選択",
    exportTitle = "データをエクスポート", exportDesc = "すべてのワークスペース・資料・タスクをZipファイルとしてダウンロード",
    importTitle = "データをインポート", importDesc = "Zipバックアップファイルから復元(データのエクスポートの逆)",
    importButton = "インポート", importingButton = "処理中…",
    importConfirmTitle = "バックアップデータをインポートしますか?",
    importConfirmText = "バックアップファイルのデータがこの端末に書き込まれます。既存のIDと同じワークスペース/章/資料/タスクは、バックアップファイルの内容で上書きされます。続行しますか?",
    importConfirmButton = "ファイルを選んでインポート", cancelButton = "キャンセル",
    importSuccess = { n -> "$n 件のワークスペースを復元しました。" },
    importError = { msg -> "インポートに失敗しました:$msg" },
    navHome = "ホーム", navTasks = "タスク", navProgress = "進捗"
)

val LocalAppLanguage = compositionLocalOf { AppLanguage.ID }

/** Akses cepat ke bundel string sesuai bahasa aktif — pola sama kayak AppBg/AppText dkk di
 *  Theme.kt (auto switch, tanpa perlu passing parameter manual di tiap Composable). */
val Str: L10n @Composable get() = when (LocalAppLanguage.current) {
    AppLanguage.EN -> enL10n
    AppLanguage.AR -> arL10n
    AppLanguage.ZH -> zhL10n
    AppLanguage.JA -> jaL10n
    AppLanguage.ID -> idL10n
}
