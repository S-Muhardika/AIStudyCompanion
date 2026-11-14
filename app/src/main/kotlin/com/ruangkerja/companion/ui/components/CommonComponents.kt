package com.ruangkerja.companion.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import com.ruangkerja.companion.ui.theme.*
import kotlinx.coroutines.launch

// ── CardOverflowMenu — menu titik tiga reusable buat card apapun. Dulu DropdownMenu (bubble
// mengambang deket ikon), sekarang ModalBottomSheet (nyelip dari bawah, kayak referensi Drive
// yang dikasih user) — dipicu SAMA PERSIS dari 2 jalur yang udah ada (tap titik tiga ATAU
// tekan-lama item), soalnya keduanya udah sama-sama cuma nge-toggle `expanded` dari luar.
data class CardMenuAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardOverflowMenu(actions: List<CardMenuAction>, modifier: Modifier = Modifier, expanded: Boolean? = null, onExpandedChange: ((Boolean) -> Unit)? = null) {
    var internalExpanded by remember { mutableStateOf(false) }
    val isExpanded = expanded ?: internalExpanded
    val setExpanded: (Boolean) -> Unit = onExpandedChange ?: { internalExpanded = it }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    Box(modifier) {
        IconButton(onClick = { setExpanded(true) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, "Menu", tint = AppTextDim, modifier = Modifier.size(18.dp))
        }
        if (isExpanded) {
            ModalBottomSheet(
                onDismissRequest = { setExpanded(false) },
                sheetState = sheetState,
                containerColor = AppBgCard,
                contentColor = AppText,
                dragHandle = { BottomSheetDefaults.DragHandle(color = AppBorder) }
            ) {
                Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.md)) {
                    actions.forEachIndexed { idx, action ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = action.enabled) {
                                    // Jalanin aksinya duluan (langsung, kayak versi DropdownMenu
                                    // lama), animasi nutup sheet-nya nyusul di belakang.
                                    action.onClick()
                                    scope.launch { sheetState.hide() }.invokeOnCompletion { setExpanded(false) }
                                }
                                .padding(horizontal = Spacing.xl, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                        ) {
                            Icon(
                                action.icon, null,
                                tint = if (!action.enabled) AppTextDim else if (action.danger) Danger else AppTextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                action.label,
                                color = if (!action.enabled) AppTextDim else if (action.danger) Danger else AppText,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (idx < actions.lastIndex) HorizontalDivider(color = AppBorder.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

// ── AppCard — warm surface, soft shadow, radius dari token Radius.card ────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    cornerRadius: Dp = Radius.card,
    contentPadding: Dp = Spacing.lg,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(100), label = "s")
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(cornerRadius))
            .background(AppBgCard)
            .then(
                if (onClick != null) Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick?.let { longClick -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); longClick() } }
                ) else Modifier
            )
            .padding(contentPadding),
        content = content
    )
}

// ── IconBox — accent-soft bg ──────────────────────────────
@Composable
fun IconBox(icon: ImageVector, tint: Color = AppAccent, bg: Color = AppAccentSoft, size: Int = 44, shape: Shape = RoundedCornerShape(12.dp)) {
    Box(
        Modifier.size(size.dp).clip(shape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.46f).dp))
    }
}

// ── Pill / badge ──────────────────────────────────────────
@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Section label ─────────────────────────────────────────
@Composable
fun EyebrowLabel(text: String, color: Color = AppTextMuted, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = color, fontSize = 11.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
        modifier = modifier
    )
}

// ── PrimaryBtn ────────────────────────────────────────────
// [UBAH, 19 Okt — ronde 4/4 redesign popup] Tambah `trailingIcon` (default false = PERSIS
// perilaku lama, ikon di KIRI teks) — dibutuhkan buat tombol "Pindahkan →" di popup saran
// draft (ikon panah di KANAN teks). Parameter baru di-taruh di AKHIR daftar, jadi semua
// pemanggil lain yang udah ada tetap jalan tanpa ubahan.
@Composable
fun PrimaryBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, loading: Boolean = false, trailingIcon: Boolean = false) {
    Button(
        onClick = onClick, modifier = modifier.height(44.dp), enabled = enabled && !loading,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppAccent, contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = AppAccent.copy(alpha = 0.3f), disabledContentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        if (loading) { CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
        else if (icon != null && !trailingIcon) { Icon(icon, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (!loading && icon != null && trailingIcon) { Spacer(Modifier.width(6.dp)); Icon(icon, null, Modifier.size(15.dp)) }
    }
}

// ── GhostBtn ──────────────────────────────────────────────
@Composable
fun GhostBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = AppText, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.height(44.dp), enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border = BorderStroke(1.dp, AppBorder)
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

// ── AppInput ──────────────────────────────────────────────
@Composable
fun AppInput(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = AppTextDim, fontSize = 14.sp) },
        singleLine = singleLine, minLines = minLines,
        shape = RoundedCornerShape(10.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = AppText),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor    = AppAccent,
            unfocusedBorderColor  = AppBorder,
            focusedContainerColor = AppBgMuted,
            unfocusedContainerColor = AppBgMuted,
            focusedTextColor      = AppText,
            unfocusedTextColor    = AppText,
            cursorColor           = AppAccent
        )
    )
}

// ── AppInputFlat — versi TANPA kotak/latar dari AppInput di atas. AppInput SENGAJA
// dibiarin apa adanya (masih dipakai ~16 tempat lain di app ini) — field baru ini
// buat dialog yang mau ikutin arah yang udah dimulai DraftEditorOverlay
// (WorkspaceListScreen.kt: "dulu dialog kecil AppInput berkotak... sekarang TANPA
// kotak/border, mirip rasa nulis di notebook beneran"). Bedanya sama draft: di sini
// ada beberapa field berdekatan dalam satu dialog, jadi tetap dikasih garis tipis di
// bawah tiap field (bukan kotak PENUH) — biar tetap ada pemisah antar-field tanpa
// balik jadi "kotak-kotak numpuk". ──
@Composable
fun AppInputFlat(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1, maxLength: Int? = null) {
    Column(modifier) {
        BasicTextField(
            value = value, onValueChange = { if (maxLength == null || it.length <= maxLength) onChange(it) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = AppText),
            cursorBrush = SolidColor(AppAccent),
            singleLine = singleLine, minLines = minLines,
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(placeholder, color = AppTextDim, fontSize = 13.sp); inner() }
            }
        )
        if (maxLength != null) {
            Text("${value.length}/$maxLength", color = AppTextDim, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.End).padding(top = 2.dp))
        }
        HorizontalDivider(color = AppBorder, thickness = 1.dp, modifier = Modifier.padding(top = 7.dp))
    }
}

// ── EmptyState — dashed card + badge ikon, dipakai buat semua kondisi "belum ada
// data". EmptyIconBadge dipisah dari EmptyView supaya bisa dipakai berdiri sendiri
// di tempat yang udah punya struktur/tombol sendiri (Mind Map, Diskusi, Search) —
// biar nggak numpuk kartu di dalam kartu, tapi ikonnya tetap konsisten se-app. ──
@Composable
fun EmptyIconBadge(icon: ImageVector, tint: Color = AppAccent, bg: Color = AppAccentSoft, size: Int = 64) {
    Box(Modifier.size((size * 1.4f).dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(tint.copy(alpha = 0.08f)))
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape((size * 0.28f).dp))
                .background(bg)
                .border(1.dp, tint.copy(alpha = 0.12f), RoundedCornerShape((size * 0.28f).dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.44f).dp))
        }
    }
}

// Entrance halus (fade + scale) — bikin elemen kosong kerasa "muncul dengan sengaja"
// pas layar dibuka, bukan cuma nongol diem kayak elemen statis biasa. Dipisah dari
// EmptyView supaya dipakai bareng oleh tempat yang cuma pakai EmptyIconBadge polos
// tanpa card (Mind Map, Diskusi, Search) — biar transisi masuknya konsisten se-app.
@Composable
fun Modifier.emptyEntrance(): Modifier {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val entranceAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(320), label = "emptyAlpha")
    val entranceScale by animateFloatAsState(if (visible) 1f else 0.94f, tween(320, easing = FastOutSlowInEasing), label = "emptyScale")
    return this.graphicsLayer { alpha = entranceAlpha; scaleX = entranceScale; scaleY = entranceScale }
}

@Composable
fun EmptyView(
    icon: ImageVector,
    title: String,
    sub: String = "",
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    actionLoading: Boolean = false,
    onAction: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .emptyEntrance()
            .clip(RoundedCornerShape(Radius.card))
            .border(1.5.dp, AppBorder, RoundedCornerShape(Radius.card), PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
            .padding(vertical = 44.dp, horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmptyIconBadge(icon)
        Spacer(Modifier.height(18.dp))
        Text(title, color = AppText, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(sub, color = AppTextMuted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        }
        // Ajakan bertindak langsung di kartu kosong — bukan cuma nunjukin kondisi kosong,
        // tapi juga jalan pintas buat ngisinya, tanpa harus nyari tombol lain di layar.
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryBtn(actionLabel, onAction, icon = actionIcon, loading = actionLoading)
        }
    }
}

// ── EmptyHint — versi ringkas satu baris, buat sub-bagian kecil di tengah halaman
// yang udah rame konten lain (mis. daftar ringkas di dalam kartu Progress) — EmptyView
// penuh bakal kelihatan numpuk kartu-dalam-kartu kalau dipasang di situ. ──
@Composable
fun EmptyHint(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(icon, null, tint = AppTextDim, modifier = Modifier.size(14.dp))
        Text(text, color = AppTextDim, fontSize = 12.5.sp)
    }
}

// Helper for dashed border
private fun Modifier.border(width: Dp, color: Color, shape: RoundedCornerShape, pathEffect: PathEffect): Modifier =
    this.drawBehind {
        val strokeWidth = width.toPx()
        val paint = androidx.compose.ui.graphics.Paint().apply {
            this.color = color
            this.pathEffect = pathEffect
            this.style = androidx.compose.ui.graphics.PaintingStyle.Stroke
            this.strokeWidth = strokeWidth
        }
        drawContext.canvas.drawRoundRect(
            strokeWidth / 2, strokeWidth / 2,
            size.width - strokeWidth / 2, size.height - strokeWidth / 2,
            shape.topStart.toPx(size, this), shape.topStart.toPx(size, this), paint
        )
    }

// ── AppDialog ─────────────────────────────────────────────
// [UBAH, 19 Okt — ronde 4/4 redesign popup] Tambah `icon` opsional (default null = PERSIS
// perilaku lama, tanpa badge). Diselipin SEBELUM `content` (bukan sesudah) tapi tetap AMAN
// buat semua ~18 pemanggil `AppDialog` yang ada di app ini (dicek satu-satu) — semuanya
// manggil dengan persis 2 argumen posisi (title, onDismiss) + trailing lambda buat content,
// gak ada yang lewatin argumen posisi ke-3, jadi `icon` otomatis jatuh ke default null dan
// trailing lambda tetap ke-bind ke `content` (parameter TERAKHIR) seperti biasa.
@Composable
fun AppDialog(title: String, onDismiss: () -> Unit, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(AppBgCard, RoundedCornerShape(Radius.dialog))
                .border(1.dp, AppBorder, RoundedCornerShape(Radius.dialog))
                .padding(Spacing.xxl)
        ) {
            if (icon != null) {
                IconBox(icon, shape = CircleShape)
                Spacer(Modifier.height(Spacing.md))
            }
            Text(title, color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.lg))
            content()
        }
    }
}

// ── Sebagian HP (terutama Honor/Magic OS dengan kamera punch-hole) ngelaporin
// tinggi status bar yang jauh lebih gede dari yang keliatan aslinya, bikin header
// keliatan ngambang jauh dari atas layar. Batasin maksimal 32dp — cukup buat nutupin
// status bar HP kebanyakan, tapi nggak bakal se-berlebihan itu di HP yang "ngaku" lebih. ──
@Composable
fun cappedStatusBarTopPadding(max: Dp = 32.dp): Dp {
    val reported = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return reported.coerceAtMost(max)
}

// ── AppHeader — matches reference header ──────────────────
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    // ── [BARU] Slot opsional buat GANTI area judul jadi composable custom (misal field yang
    // bisa diketik langsung), bukan cuma Text. Default null = PERSIS PERILAKU LAMA (Text
    // biasa) — semua ~15 pemanggil AppHeader yang lain di app ini TETAP jalan tanpa ubahan.
    // Dipake NotebookEditor.kt biar judul materi bisa diedit LANGSUNG di header, gak perlu
    // field terpisah lagi di badan halaman. ──
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier.fillMaxWidth().background(AppBg).padding(top = cappedStatusBarTopPadding())) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = AppAccent)
                }
            }
            Column(Modifier.weight(1f)) {
                if (titleContent != null) titleContent()
                else Text(title, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = AppTextMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        HorizontalDivider(color = AppBorder, thickness = 1.dp)
    }
}

// ── EntranceItem — fade + slide up ───────────────────────
@Composable
fun EntranceItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay((index.coerceAtMost(8)) * 24L); visible = true }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 5 }) { content() }
}

// ── SpeedDialFab ──────────────────────────────────────────
data class SpeedDialItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun SpeedDialFab(items: List<SpeedDialItem>, mainIcon: ImageVector = Icons.Default.Add, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = false })
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEachIndexed { i, item ->
                AnimatedVisibility(visible = expanded, enter = fadeIn(tween(120, i * 35)) + slideInVertically(tween(160, i * 35)) { it / 2 }, exit = fadeOut(tween(80)) + slideOutVertically(tween(100)) { it / 2 }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.background(AppBgCard, RoundedCornerShape(8.dp)).border(1.dp, AppBorder, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(item.label, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        SmallFloatingActionButton(onClick = { item.onClick(); expanded = false }, containerColor = AppBgCard, contentColor = AppAccent, shape = RoundedCornerShape(12.dp)) {
                            Icon(item.icon, item.label, Modifier.size(19.dp))
                        }
                    }
                }
            }
            val rotation by animateFloatAsState(if (expanded) 45f else 0f, tween(200), label = "r")
            FloatingActionButton(onClick = { expanded = !expanded }, containerColor = AppAccent, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(16.dp)) {
                Icon(mainIcon, null, Modifier.size(22.dp).rotate(rotation))
            }
        }
    }
}

/**
 * Tombol ikon polos 38dp (header & toolbar). [DIGABUNG, audit duplikasi 2 Okt] Dulu HeaderIconBtn
 * (WorkspaceListScreen) dan DraftToolbarBtn (DraftEditor) dua salinan identik yang beda cuma ukuran ikon
 * (20dp vs 19dp); keduanya sekarang pembungkus satu baris yang memanggil ini.
 */
@Composable
fun IconActionBtn(icon: ImageVector, contentDescription: String, iconSize: Dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription, tint = AppTextMuted, modifier = Modifier.size(iconSize))
    }
}
