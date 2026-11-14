package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.*
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.util.MindMapCategories
import com.ruangkerja.companion.util.MindMapOps
import com.ruangkerja.companion.viewmodel.AppViewModel

// ── TAB: MIND MAP ─────────────────────────────────────────
// Peta POHON BEBAS kedalaman (spek Mind Map): ketuk node → detail (path, kategori, hubungan, rincian, cross-link);
// chevron di node = ciut/buka cabang; legenda kategori = filter; "Fokus" = tampilkan satu cabang saja; mode Edit =
// ubah/tambah/hapus/geser node, atur kategori & urutan, dan buat cross-link. Kepadatan tinggi ditangani lewat zoom,
// ciut/buka, fokus, dan filter — batas tampilan BUKAN batas struktur pengetahuan.
@Composable
internal fun TabMindMap(ch: Chapter, vm: AppViewModel) {
    val mm by vm.mindMap.collectAsStateWithLifecycle()
    val mmState by vm.mindMapState.collectAsStateWithLifecycle()
    val loading by vm.mindMapLoading.collectAsStateWithLifecycle()
    var editMode by remember { mutableStateOf(false) }
    var outlineDialog by remember { mutableStateOf(false) }

    // State tampilan per peta: id root berubah tiap peta baru dibuat/dihapus → semuanya kembali ke awal.
    val mapKey = mm?.root?.id
    var scale by remember(mapKey) { mutableFloatStateOf(1f) }
    var offset by remember(mapKey) { mutableStateOf(Offset.Zero) }
    var collapsed by remember(mapKey) { mutableStateOf(mm?.let { MindMapOps.defaultCollapsed(it) } ?: emptySet<String>()) }
    var focusId by remember(mapKey) { mutableStateOf<String?>(null) }
    var filter by remember(mapKey) { mutableStateOf<String?>(null) }
    var selectedId by remember(mapKey) { mutableStateOf<String?>(null) }
    var addParentId by remember(mapKey) { mutableStateOf<String?>(null) }
    var linkFromId by remember(mapKey) { mutableStateOf<String?>(null) }

    if (outlineDialog) {
        MindMapOutlineDialog(
            onConfirm = { text -> vm.generateMindMapFromOutline(ch, text); outlineDialog = false },
            onDismiss = { outlineDialog = false }
        )
    }

    if (mm == null) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.lg).verticalScroll(rememberScrollState()).emptyEntrance(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EmptyIconBadge(Icons.Outlined.AccountTree)
            Spacer(Modifier.height(Spacing.md))
            Text("Belum ada mind map untuk bab ini", color = AppText, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Mind map ini menunjukkan struktur pengetahuan materi (konsep, bagian-bagiannya, dan hubungan antar-konsep), bukan alur pengerjaan.", color = AppTextMuted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Spacing.md))
            mmState.error?.let {
                Spacer(Modifier.height(Spacing.sm))
                Text(it, color = Danger, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(Spacing.lg))

            // Jalur 1: algoritma dari ringkasan (offline, tanpa AI)
            OutlinedButton(
                onClick = { vm.generateMindMapFromSummary(ch) },
                enabled = ch.summary != null,
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Icon(Icons.Default.AccountTree, null, Modifier.size(14.dp)); Spacer(Modifier.width(8.dp))
                Text("Susun Otomatis dari Ringkasan", fontSize = 13.sp)
            }
            Text("Algoritma, tanpa AI — hierarki heading/bullet ringkasan dipertahankan sedalam teksnya", color = AppTextDim, fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp, bottom = Spacing.sm))

            // Jalur 2: algoritma dari outline yang diketik sendiri
            OutlinedButton(
                onClick = { outlineDialog = true },
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Icon(Icons.Default.FormatListBulleted, null, Modifier.size(14.dp)); Spacer(Modifier.width(8.dp))
                Text("Tempel/Ketik Outline Sendiri", fontSize = 13.sp)
            }
            Text("Algoritma, tanpa AI — cocok untuk struktur dari buku panduan", color = AppTextDim, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp, bottom = Spacing.sm))

            // Jalur 3: AI (butuh internet)
            OutlinedButton(
                onClick = { vm.generateMindMap(ch) },
                enabled = !loading && ch.summary != null,
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTextMuted),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                if (loading) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppAccent); Spacer(Modifier.width(8.dp)); Text("Membuat mind map…", fontSize = 13.sp) }
                else { Icon(Icons.Default.AutoAwesome, null, Modifier.size(14.dp)); Spacer(Modifier.width(8.dp)); Text("Buat dengan AI", fontSize = 13.sp) }
            }
            Text("Butuh internet — AI menyusun struktur adaptif, kategori, urutan, dan hubungan antar-konsep", color = AppTextDim, fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp, bottom = Spacing.sm))

            if (ch.summary == null) {
                Spacer(Modifier.height(2.dp))
                Text("Tips: buat ringkasan terlebih dahulu di tab Ringkasan supaya opsi otomatis & AI dapat digunakan.", color = AppTextDim, fontSize = 11.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Spacing.sm))
            }

            // Jalur 4: manual sepenuhnya
            TextButton(onClick = { vm.startManualMindMap(ch); editMode = true }) {
                Icon(Icons.Default.Edit, null, Modifier.size(13.dp), tint = AppTextDim); Spacer(Modifier.width(6.dp))
                Text("Atau susun manual dari kosong", color = AppTextDim, fontSize = 12.5.sp)
            }
        }
        return
    }

    val data = mm!!
    val focusNode = focusId?.let { MindMapOps.find(data.root, it) }
    val displayRoot = focusNode ?: data.root
    val highlight = filter?.let { MindMapOps.matchingIds(data.root, it) }
    val selected = selectedId?.let { MindMapOps.find(data.root, it) }
    val nodeCount = remember(data) { MindMapOps.count(data.root) }

    Box(Modifier.fillMaxSize()) {
        var headerHeightPx by remember { mutableStateOf(0) }
        val density = LocalDensity.current

        // ── Header toolbar ──
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(AppBg)
                .zIndex(2f)
                .onSizeChanged { headerHeightPx = it.height }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    EyebrowLabel("Mind Map")
                    Text("$nodeCount node" + if (data.links.isNotEmpty()) " · ${data.links.size} hubungan silang" else "", color = AppTextDim, fontSize = 10.5.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editMode = !editMode }) {
                        Icon(if (editMode) Icons.Default.CheckCircle else Icons.Default.Edit, null, modifier = Modifier.size(14.dp), tint = if (editMode) AppAccent else AppTextDim)
                        Spacer(Modifier.width(4.dp)); Text(if (editMode) "Selesai" else "Edit", color = if (editMode) AppAccent else AppTextDim, fontSize = 12.sp)
                    }
                    TextButton(onClick = { vm.clearMindMap(); editMode = false }) { Text("Hapus", color = AppTextDim, fontSize = 12.sp) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { collapsed = MindMapOps.collapseAll(displayRoot) }) { Text("Ciutkan semua", color = AppTextDim, fontSize = 11.5.sp) }
                TextButton(onClick = { collapsed = emptySet() }) { Text("Buka semua", color = AppTextDim, fontSize = 11.5.sp) }
                if (editMode) Text("Ketuk node untuk mengubah", color = AppAccent, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
            }
            if (focusNode != null) {
                // Navigasi antar-level: ketuk salah satu tingkat di jalur untuk naik ke sana; "Seluruh peta" = kembali ke root.
                val trail = MindMapOps.pathTo(data.root, focusNode.id).drop(1)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Seluruh peta", color = AppAccent, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppAccent.copy(alpha = 0.12f))
                            .clickable { focusId = null }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    trail.forEachIndexed { i, step ->
                        Text("›", color = AppTextDim, fontSize = 12.sp)
                        if (i == trail.size - 1) {
                            Text(step.label, color = AppText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        } else {
                            Text(
                                step.label, color = AppTextMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(8.dp)).clickable { focusId = step.id }.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            mmState.error?.let {
                Text(it, color = Danger, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 4.dp))
            }
        }

        // ── Canvas ──
        val topPad = with(density) { headerHeightPx.toDp() }
        MindMapCanvasView(
            root = displayRoot,
            links = data.links,
            collapsed = collapsed,
            highlightIds = highlight,
            modifier = Modifier.fillMaxSize().padding(top = topPad, bottom = 72.dp),
            onNodeClick = { selectedId = it.id },
            onToggleCollapse = { id -> collapsed = if (id in collapsed) collapsed - id else collapsed + id },
            scale = scale, offset = offset,
            onTransform = { pan, zoom -> scale = (scale * zoom).coerceIn(0.3f, 2.5f); offset += pan }
        )

        // ── Kontrol bawah ──
        if (!editMode) {
            MindMapLegend(
                categories = MindMapOps.categoriesUsed(data.root),
                selected = filter,
                onSelect = { filter = it },
                modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.md)
            )
        } else {
            OutlinedButton(
                onClick = { addParentId = displayRoot.id },
                modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.md),
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTextMuted, containerColor = AppBgCard)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(14.dp)); Spacer(Modifier.width(7.dp)); Text("Tambah Cabang", fontSize = 13.sp)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.md),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!editMode) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppBgCard)
                        .border(1.dp, AppAccent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .clickable(enabled = !loading) { vm.enrichMindMapWithAI(ch) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = AppAccent)
                        Text("Memproses…", color = AppTextDim, fontSize = 12.sp)
                    } else {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp), tint = AppAccent)
                        Text("Lengkapi dengan AI", color = AppAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            MindMapZoomBar(
                zoomPercent = (scale * 100).toInt(),
                onZoomOut = { scale = (scale - 0.2f).coerceAtLeast(0.3f) },
                onZoomIn  = { scale = (scale + 0.2f).coerceAtMost(2.5f) },
                onFit     = { scale = 1f; offset = Offset.Zero }
            )
        }
    }

    // ── Lembar detail / edit node ──
    if (selected != null) {
        if (editMode) {
            NodeEditSheet(
                data = data, node = selected,
                onDismiss = { selectedId = null },
                onSave = { label, category, relation, ordered, note -> vm.updateMindMapNode(selected.id, label, category, relation, ordered, note) },
                onAddChild = { addParentId = selected.id },
                onLink = { linkFromId = selected.id },
                onMove = { delta -> vm.moveMindMapNode(selected.id, delta) },
                onDelete = { vm.deleteMindMapNode(selected.id); selectedId = null },
                onDeleteLink = { from, to -> vm.deleteMindMapLink(from, to) }
            )
        } else {
            NodeDetailSheet(
                data = data, node = selected,
                canFocus = selected.children.isNotEmpty() && selected.id != displayRoot.id,
                onDismiss = { selectedId = null },
                onFocus = { focusId = selected.id; selectedId = null; offset = Offset.Zero; scale = 1f },
                onAddChild = { addParentId = selected.id },
                onEdit = { editMode = true }
            )
        }
    }

    addParentId?.let { parentId ->
        val parent = MindMapOps.find(data.root, parentId)
        if (parent == null) addParentId = null else {
            AddNodeDialog(
                parentLabel = parent.label,
                onConfirm = { label, category ->
                    vm.addMindMapNode(parentId, label, category)
                    collapsed = collapsed - parentId   // buka induknya supaya node baru langsung terlihat
                    addParentId = null
                },
                onDismiss = { addParentId = null }
            )
        }
    }

    linkFromId?.let { fromId ->
        val from = MindMapOps.find(data.root, fromId)
        if (from == null) linkFromId = null else {
            LinkDialog(
                data = data, from = from,
                onConfirm = { toId, relation -> vm.addMindMapLink(fromId, toId, relation); linkFromId = null },
                onDismiss = { linkFromId = null }
            )
        }
    }
}

// ── Detail node (mode lihat) ──────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeDetailSheet(
    data: MindMapData, node: MindMapNode, canFocus: Boolean,
    onDismiss: () -> Unit, onFocus: () -> Unit, onAddChild: () -> Unit, onEdit: () -> Unit
) {
    val path = remember(data, node.id) { MindMapOps.pathTo(data.root, node.id) }
    val labelOf = remember(data) { MindMapOps.allNodes(data.root).associate { it.id to it.label } }
    val nodeLinks = data.links.filter { it.from == node.id || it.to == node.id }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppBgCard, contentColor = AppText,
        dragHandle = { BottomSheetDefaults.DragHandle(color = AppBorder) }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            if (path.size > 1) {
                Text(path.dropLast(1).joinToString(" › ") { it.label }, color = AppTextDim, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(node.label, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (node.category.isNotBlank()) {
                    val c = categoryColor(node.category)
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                        Text(node.category, color = c, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (node.ordered) InfoChip("Langkah berurutan")
            }
            if (node.relation.isNotBlank()) {
                Text("Hubungan ke induk: ${node.relation}", color = AppTextMuted, fontSize = 12.5.sp)
            }
            if (node.note.isNotBlank()) {
                // [FIX, 12 Nov — Tahap 1 arsitektur, audit "markdown mentah kelihatan"] note
                // bisa hasil "Buat dengan AI"/"Lengkapi dengan AI" — MarkdownInlineText render
                // bold/italic/strikethrough/kode-nya, bukan tampil mentah.
                MarkdownInlineText(node.note, color = AppText, fontSize = 13.5.sp, lineHeight = 20.sp)
            }
            if (node.children.isNotEmpty()) {
                EyebrowLabel("Sub-node (${node.children.size})")
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    node.children.take(12).forEachIndexed { i, c ->
                        Text((if (node.ordered) "${i + 1}. " else "• ") + c.label, color = AppTextMuted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (node.children.size > 12) Text("+ ${node.children.size - 12} lainnya", color = AppTextDim, fontSize = 11.5.sp)
                }
            }
            if (nodeLinks.isNotEmpty()) {
                EyebrowLabel("Berhubungan dengan")
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    nodeLinks.forEach { l ->
                        val outgoing = l.from == node.id
                        val other = labelOf[if (outgoing) l.to else l.from] ?: "?"
                        Text(linkText(outgoing, l.relation, other), color = AppTextMuted, fontSize = 12.5.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (canFocus) GhostBtn("Fokus", onFocus, Modifier.weight(1f))
                // Sub-node bisa punya sub-node lagi: tombol ini ada di SETIAP node, di tingkat berapa pun.
                GhostBtn("Sub-node", onAddChild, Modifier.weight(1f), icon = Icons.Default.Add)
                PrimaryBtn("Ubah", onEdit, Modifier.weight(1f))
            }
        }
    }
}

/** Teks satu cross-link di daftar: "→ menyebabkan · Node" / "← Node · menyebabkan"; tanpa label, tidak mengarang label generik. */
private fun linkText(outgoing: Boolean, relation: String, other: String): String = when {
    relation.isBlank() -> if (outgoing) "→ $other" else "← $other"
    outgoing -> "→ $relation · $other"
    else -> "← $other · $relation"
}

@Composable
private fun InfoChip(text: String) {
    Text(
        text, color = AppTextMuted, fontSize = 11.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppBgMuted).padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

// ── Edit node (mode edit) ─────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeEditSheet(
    data: MindMapData, node: MindMapNode,
    onDismiss: () -> Unit,
    onSave: (label: String, category: String, relation: String, ordered: Boolean, note: String) -> Unit,
    onAddChild: () -> Unit, onLink: () -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit,
    onDeleteLink: (String, String) -> Unit
) {
    val isRoot = node.id == data.root.id
    var label by remember(node.id) { mutableStateOf(node.label) }
    var category by remember(node.id) { mutableStateOf(node.category) }
    var relation by remember(node.id) { mutableStateOf(node.relation) }
    var ordered by remember(node.id) { mutableStateOf(node.ordered) }
    var note by remember(node.id) { mutableStateOf(node.note) }
    var confirmDelete by remember(node.id) { mutableStateOf(false) }

    val path = remember(data, node.id) { MindMapOps.pathTo(data.root, node.id) }
    val parent = path.getOrNull(path.size - 2)
    val idx = parent?.children?.indexOfFirst { it.id == node.id } ?: -1
    val canUp = idx > 0
    val canDown = parent != null && idx >= 0 && idx < parent.children.size - 1
    val canOrder = node.children.size >= 2
    val labelOf = remember(data) { MindMapOps.allNodes(data.root).associate { it.id to it.label } }
    val nodeLinks = data.links.filter { it.from == node.id || it.to == node.id }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppBgCard, contentColor = AppText,
        dragHandle = { BottomSheetDefaults.DragHandle(color = AppBorder) }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            EyebrowLabel(if (isRoot) "Ubah judul utama" else "Ubah node")
            if (path.size > 1) Text(path.dropLast(1).joinToString(" › ") { it.label }, color = AppTextDim, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Struktur di ATAS (tambah sub-node, hubungkan, geser) supaya mudah ditemukan — sub-node bisa punya sub-node lagi.
            EyebrowLabel("Struktur")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = onAddChild, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Sub-node", fontSize = 12.5.sp)
                }
                OutlinedButton(onClick = onLink, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Link, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Hubungkan", fontSize = 12.5.sp)
                }
            }
            if (!isRoot) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { onMove(-1) }, enabled = canUp, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Naik", fontSize = 12.5.sp)
                    }
                    OutlinedButton(onClick = { onMove(1) }, enabled = canDown, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Turun", fontSize = 12.5.sp)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppBorder))
            AppInput(label, { label = it.take(MindMapOps.MAX_LABEL) }, "Nama node (istilah singkat, bukan kalimat)")

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyebrowLabel("Kategori")
                CategoryPicker(selected = category, onSelect = { category = it })
            }

            if (!isRoot) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EyebrowLabel("Hubungan ke induk (opsional)")
                    AppInput(relation, { relation = it.take(MindMapOps.MAX_RELATION) }, "Kosongkan untuk hubungan biasa (bagian dari)")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MindMapCategories.RELATIONS.forEach { r ->
                            Text(
                                r, color = if (relation == r) AppAccent else AppTextMuted, fontSize = 11.5.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(if (relation == r) AppAccent.copy(alpha = 0.14f) else AppBgMuted)
                                    .clickable { relation = if (relation == r) "" else r }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Sub-node adalah langkah berurutan", color = if (canOrder) AppText else AppTextDim, fontSize = 13.sp)
                    Text(if (canOrder) "Ditampilkan bernomor (prosedur, tahapan, siklus)" else "Perlu minimal 2 sub-node", color = AppTextDim, fontSize = 11.sp)
                }
                Switch(checked = ordered && canOrder, onCheckedChange = { ordered = it }, enabled = canOrder)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyebrowLabel("Rincian (opsional)")
                AppInput(note, { note = it.take(MindMapOps.MAX_NOTE) }, "Penjelasan singkat yang dibuka lewat detail node", singleLine = false, minLines = 2)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GhostBtn("Batal", onDismiss, Modifier.weight(1f))
                PrimaryBtn(
                    "Simpan",
                    { onSave(label, category, if (isRoot) "" else relation, ordered && canOrder, note); onDismiss() },
                    Modifier.weight(2f), enabled = label.isNotBlank()
                )
            }

            if (nodeLinks.isNotEmpty()) {
                EyebrowLabel("Hubungan silang")
                nodeLinks.forEach { l ->
                    val outgoing = l.from == node.id
                    val other = labelOf[if (outgoing) l.to else l.from] ?: "?"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(linkText(outgoing, l.relation, other), color = AppTextMuted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onDeleteLink(l.from, l.to) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Hapus hubungan", tint = AppTextDim, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (!isRoot) {
                if (!confirmDelete) {
                    GhostBtn("Hapus node ini", { confirmDelete = true }, Modifier.fillMaxWidth(), color = Danger)
                } else {
                    val extra = MindMapOps.count(node) - 1
                    Text(
                        "Hapus \"${node.label}\"" + (if (extra > 0) " beserta $extra sub-node-nya?" else "?"),
                        color = Danger, fontSize = 12.5.sp, lineHeight = 18.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        GhostBtn("Batal", { confirmDelete = false }, Modifier.weight(1f))
                        PrimaryBtn("Ya, hapus", onDelete, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ── Pemilih kategori: kosakata sebagai saran, teks bebas tetap boleh ──
@Composable
private fun CategoryPicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppBgMuted)
                .border(1.dp, AppBorder, RoundedCornerShape(10.dp))
                .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(categoryColor(selected)))
            Text(selected.ifBlank { "Tanpa kategori" }, color = AppText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = AppTextDim, modifier = Modifier.size(18.dp))
        }
        if (open) {
            Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("") + MindMapCategories.VOCABULARY).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { cat ->
                            val isSel = cat.equals(selected, ignoreCase = true)
                            val c = categoryColor(cat)
                            Row(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) c.copy(alpha = 0.15f) else AppBgMuted)
                                    .border(1.dp, if (isSel) c else AppBorder, RoundedCornerShape(10.dp))
                                    .clickable { onSelect(cat); open = false }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                                Text(cat.ifBlank { "Tanpa" }, color = if (isSel) AppText else AppTextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            AppInput(custom, { custom = it.take(24) }, "Atau ketik kategori sendiri…")
            if (custom.isNotBlank()) GhostBtn("Pakai \"${custom.trim()}\"", { onSelect(MindMapCategories.normalize(custom)); custom = ""; open = false }, Modifier.fillMaxWidth())
        }
    }
}

// ── Dialog: tambah node ──
@Composable
private fun AddNodeDialog(parentLabel: String, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    AppDialog("Tambah di \"${parentLabel.take(28)}\"", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AppInput(text, { text = it.take(MindMapOps.MAX_LABEL) }, "Nama node (istilah singkat)")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyebrowLabel("Kategori (opsional)")
                CategoryPicker(selected = category, onSelect = { category = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GhostBtn("Batal", onDismiss, Modifier.weight(1f))
                PrimaryBtn("Tambah", { if (text.isNotBlank()) onConfirm(text, category) }, Modifier.weight(2f), enabled = text.isNotBlank())
            }
        }
    }
}

// ── Dialog: cross-link (pilih node tujuan, lalu jenis hubungan) ──
@Composable
private fun LinkDialog(data: MindMapData, from: MindMapNode, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf<MindMapNode?>(null) }
    var query by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }
    val parents = remember(data) { MindMapOps.parentMap(data.root) }
    val candidates = remember(data, query, from.id) {
        MindMapOps.allNodes(data.root).filter {
            it.id != from.id && parents[it.id] != from.id && parents[from.id] != it.id &&
                (query.isBlank() || it.label.contains(query, ignoreCase = true))
        }
    }
    val picked = target
    AppDialog(if (picked == null) "Hubungkan ke…" else "Jenis hubungan", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (picked == null) {
                Text("Pilih node tujuan untuk \"${from.label.take(30)}\". Hubungan silang paling berguna antar-cabang yang berbeda.", color = AppTextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                AppInput(query, { query = it }, "Cari node…")
                LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(candidates, key = { it.id }) { n ->
                        val where = remember(n.id) { MindMapOps.pathTo(data.root, n.id).dropLast(1).joinToString(" › ") { it.label } }
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { target = n }.padding(horizontal = 8.dp, vertical = 7.dp)
                        ) {
                            Text(n.label, color = AppText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (where.isNotBlank()) Text(where, color = AppTextDim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (candidates.isEmpty()) Text("Tidak ada node yang cocok.", color = AppTextDim, fontSize = 12.sp)
                GhostBtn("Batal", onDismiss, Modifier.fillMaxWidth())
            } else {
                Text("${from.label.take(26)}  →  ${picked.label.take(26)}", color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                AppInput(relation, { relation = it.take(MindMapOps.MAX_RELATION) }, "mis. memengaruhi (boleh kosong)")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MindMapCategories.RELATIONS.forEach { r ->
                        Text(
                            r, color = if (relation == r) AppAccent else AppTextMuted, fontSize = 11.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (relation == r) AppAccent.copy(alpha = 0.14f) else AppBgMuted)
                                .clickable { relation = if (relation == r) "" else r }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    GhostBtn("Kembali", { target = null }, Modifier.weight(1f))
                    PrimaryBtn("Hubungkan", { onConfirm(picked.id, relation.trim()) }, Modifier.weight(2f))
                }
            }
        }
    }
}

@Composable
private fun MindMapOutlineDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AppDialog("Tempel/Ketik Outline", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                "Contoh format (kedalaman bebas, ikuti indentasi):\nKomputer\n  Hardware\n    CPU\n    RAM\n  Software\n    Sistem Operasi\n    Aplikasi",
                color = AppTextDim, fontSize = 11.sp
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Tempel outline di sini…", fontSize = 13.sp) },
                minLines = 6, maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppAccent, unfocusedBorderColor = AppBorder,
                    focusedContainerColor = AppBgCard, unfocusedContainerColor = AppBgCard
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GhostBtn("Batal", onDismiss, Modifier.weight(1f))
                PrimaryBtn("Susun", { if (text.isNotBlank()) onConfirm(text) }, Modifier.weight(2f), enabled = text.isNotBlank())
            }
        }
    }
}
