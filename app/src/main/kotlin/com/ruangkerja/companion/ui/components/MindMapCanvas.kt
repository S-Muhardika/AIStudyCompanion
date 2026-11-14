package com.ruangkerja.companion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruangkerja.companion.data.db.MindMapLink
import com.ruangkerja.companion.data.db.MindMapNode
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.util.MindMapCategories
import com.ruangkerja.companion.util.MindMapOps
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Warna sebuah kategori. Kategori BUKAN taksonomi tetap (spek Mind Map): warna dipilih per KELOMPOK makna
 * (lihat MindMapCategories.Family), dan kategori teks bebas di luar kosakata mendapat warna stabil dari hash
 * namanya. Proses generate (algoritma ataupun AI) tidak perlu peduli warna — fungsi ini satu-satunya sumbernya.
 */
@Composable
fun categoryColor(category: String): Color = when (MindMapCategories.familyOf(category)) {
    MindMapCategories.Family.INTI -> Success
    MindMapCategories.Family.STRUKTUR -> AppAccent
    MindMapCategories.Family.PROSES -> Color(0xFF4F8EF7)
    MindMapCategories.Family.HUBUNGAN -> Color(0xFF9C6ADE)
    MindMapCategories.Family.PENERAPAN -> Warn
    MindMapCategories.Family.PERINGATAN -> Danger
    MindMapCategories.Family.CATATAN -> Color(0xFFC08A2E)
    MindMapCategories.Family.NETRAL -> AppTextMuted
    MindMapCategories.Family.LAIN -> {
        val palette = listOf(Success, AppAccent, Color(0xFF4F8EF7), Color(0xFF9C6ADE), Warn, Color(0xFF2BA6A0))
        palette[(category.trim().lowercase().hashCode() and 0x7fffffff) % palette.size]
    }
}

// Node yang sedang tampil (anak node yang diciutkan tidak ikut dibuat).
private class VNode(val node: MindMapNode, val depth: Int, val stepNo: Int) {
    val kids = ArrayList<VNode>()
    var hidden = 0   // jumlah turunan yang tersembunyi kalau node ini diciutkan
}

private fun buildVisible(n: MindMapNode, collapsed: Set<String>, depth: Int = 0, stepNo: Int = 0): VNode {
    val v = VNode(n, depth, stepNo)
    if (n.children.isNotEmpty()) {
        if (depth == 0 || n.id !in collapsed) {
            n.children.forEachIndexed { i, c -> v.kids.add(buildVisible(c, collapsed, depth + 1, if (n.ordered) i + 1 else 0)) }
        } else {
            v.hidden = MindMapOps.count(n) - 1
        }
    }
    return v
}

private fun flatten(v: VNode, out: MutableList<VNode> = ArrayList()): List<VNode> {
    out.add(v)
    v.kids.forEach { flatten(it, out) }
    return out
}

// Satu ruas garis siap gambar (kurva kubik); koordinat sudah dalam piksel konten.
private class Seg(
    val x0: Float, val y0: Float, val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float,
    val x3: Float, val y3: Float, val color: Color, val dashed: Boolean
)

/**
 * Canvas mind map POHON BEBAS KEDALAMAN dengan zoom/pan: satu kolom per tingkat (kiri → kanan), node bisa
 * diciutkan/dibuka, urutan langkah bernomor, label hubungan di garis, dan cross-link putus-putus antar cabang.
 *
 * Arsitektur: SubcomposeLayout — SETIAP node (dan label hubungan) di-compose & diukur dulu (real Compose Text,
 * jadi wrapping/ellipsis akurat), baru posisi & garis dihitung dari hasil ukur itu (lihat MindMapLayout), semua
 * dalam SATU proses yang pasti sinkron. Ini menghindari pola lama (onGloballyPositioned + state map lintas-frame)
 * yang terbukti bikin teks node kadang nggak ke-render.
 *
 * @param root akar TAMPILAN (bisa sub-cabang saat "fokus"). Cross-link yang salah satu ujungnya di luar [root]
 * tidak digambar; ujung yang tersembunyi di cabang diciutkan diarahkan ke leluhur terdekat yang terlihat.
 * @param highlightIds null = tanpa filter; selain itu node di luar himpunan ini diredupkan (filter kategori).
 */
@Composable
fun MindMapCanvasView(
    root: MindMapNode,
    links: List<MindMapLink>,
    collapsed: Set<String>,
    highlightIds: Set<String>?,
    modifier: Modifier = Modifier,
    onNodeClick: (MindMapNode) -> Unit = {},
    onToggleCollapse: (String) -> Unit = {},
    scale: Float = 1f,
    offset: Offset = Offset.Zero,
    onTransform: (Offset, Float) -> Unit = { _, _ -> }
) {
    // Warna dihitung di sini (context @Composable) karena categoryColor() baca tema dinamis —
    // TIDAK BISA dipanggil dari dalam measure-lambda SubcomposeLayout (bukan context @Composable).
    val colorById = HashMap<String, Color>()
    for (n in MindMapOps.allNodes(root)) colorById[n.id] = categoryColor(n.category)
    val linkColor = Color(0xFF9C6ADE)
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val parentIds = remember(root) { MindMapOps.parentMap(root) }
    val vroot = remember(root, collapsed) { buildVisible(root, collapsed) }

    Box(
        modifier
            .fillMaxSize()
            .background(AppBg)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ -> onTransform(pan, zoom) }
            }
    ) {
        SubcomposeLayout(
            modifier = Modifier
                .graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f)
                )
                .padding(28.dp)
        ) { _ ->
            val loose = Constraints()
            val gapX = 44.dp.toPx()
            val rootGap = 26.dp.toPx()
            val siblingGap = 10.dp.toPx()
            val visible = flatten(vroot)

            // ── 1) Ukur semua node + label hubungan ──
            val placeables = HashMap<String, Placeable>()
            val relPlaceables = HashMap<String, Placeable>()
            for (v in visible) {
                val id = v.node.id
                placeables[id] = subcompose("n:$id") {
                    MindMapNodeView(
                        node = v.node, depth = v.depth, stepNo = v.stepNo, hidden = v.hidden,
                        color = colorById[id] ?: AppTextMuted, onPrimary = onPrimary,
                        dim = highlightIds != null && id !in highlightIds,
                        onClick = { onNodeClick(v.node) }, onToggle = { onToggleCollapse(id) }
                    )
                }.first().measure(loose)
                if (v.depth > 0 && v.node.relation.isNotBlank()) {
                    relPlaceables[id] = subcompose("r:$id") { MindMapEdgeLabel(v.node.relation) }
                        .first().measure(Constraints(maxWidth = 110.dp.roundToPx()))
                }
            }

            // ── 2) Tata letak ──
            fun toBox(v: VNode): LayoutBox<VNode> {
                val p = placeables.getValue(v.node.id)
                val b = LayoutBox(v, v.depth, p.width.toFloat(), p.height.toFloat(), relPlaceables[v.node.id]?.width?.toFloat() ?: 0f)
                v.kids.forEach { b.kids.add(toBox(it)) }
                return b
            }
            val rootBox = toBox(vroot)
            val res = MindMapLayout.layout(rootBox, gapX, rootGap, siblingGap)
            val boxOf = HashMap<String, LayoutBox<VNode>>()
            fun index(b: LayoutBox<VNode>) { boxOf[b.tag.node.id] = b; b.kids.forEach { index(it) } }
            index(rootBox)

            // ── 3) Garis hierarki + posisi label hubungan ──
            val segs = ArrayList<Seg>()
            val relPos = ArrayList<Triple<Placeable, Float, Float>>()
            fun edges(b: LayoutBox<VNode>) {
                for (k in b.kids) {
                    val x0 = b.x + b.w; val y0 = b.y + b.h / 2f
                    val x3 = k.x; val y3 = k.y + k.h / 2f
                    val mx = (x0 + x3) / 2f
                    segs.add(Seg(x0, y0, mx, y0, mx, y3, x3, y3, colorById[k.tag.node.id]?.copy(alpha = 0.55f) ?: linkColor, false))
                    relPlaceables[k.tag.node.id]?.let { relPos.add(Triple(it, mx, (y0 + y3) / 2f)) }
                    edges(k)
                }
            }
            edges(rootBox)

            // ── 4) Cross-link (putus-putus) ──
            fun visibleBox(id: String): LayoutBox<VNode>? {
                var cur: String? = id
                while (cur != null) {
                    boxOf[cur]?.let { return it }
                    cur = parentIds[cur]
                }
                return null
            }
            var extraRight = 0f
            val linkSegs = ArrayList<Seg>()
            val linkLabelPos = ArrayList<Triple<Placeable, Float, Float>>()
            links.forEachIndexed { idx, l ->
                val a = visibleBox(l.from) ?: return@forEachIndexed
                val b = visibleBox(l.to) ?: return@forEachIndexed
                if (a === b) return@forEachIndexed
                val y0 = a.y + a.h / 2f; val y3 = b.y + b.h / 2f
                val x0: Float; val x3: Float; val cx: Float
                if (b.x > a.x + a.w - 1f) {                    // tujuan di kolom kanan
                    x0 = a.x + a.w; x3 = b.x; cx = (x0 + x3) / 2f
                } else if (a.x > b.x + b.w - 1f) {             // tujuan di kolom kiri
                    x0 = a.x; x3 = b.x + b.w; cx = (x0 + x3) / 2f
                } else {                                       // kolom sama: lengkung ke kanan
                    x0 = a.x + a.w; x3 = b.x + b.w; cx = max(x0, x3) + 56.dp.toPx()
                    extraRight = max(extraRight, cx)
                }
                linkSegs.add(Seg(x0, y0, cx, y0, cx, y3, x3, y3, linkColor, true))
                if (l.relation.isNotBlank()) {
                    val lp = subcompose("l:$idx") { MindMapEdgeLabel(l.relation, linkColor) }
                        .first().measure(Constraints(maxWidth = 120.dp.roundToPx()))
                    linkLabelPos.add(Triple(lp, (x0 + 6f * cx + x3) / 8f, (y0 + y3) / 2f))
                }
            }

            val totalW = (max(res.width, extraRight) + 1f).roundToInt()
            val totalH = (res.height + 1f).roundToInt()
            val arrowLen = 9.dp.toPx(); val arrowHalf = 4.5.dp.toPx()
            val strokeW = 1.6.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))

            val canvasPlaceable = subcompose("edges") {
                Canvas(Modifier.fillMaxSize()) {
                    for (s in segs) {
                        val path = Path().apply { moveTo(s.x0, s.y0); cubicTo(s.c1x, s.c1y, s.c2x, s.c2y, s.x3, s.y3) }
                        drawPath(path, s.color, style = Stroke(width = strokeW))
                    }
                    for (s in linkSegs) {
                        val path = Path().apply { moveTo(s.x0, s.y0); cubicTo(s.c1x, s.c1y, s.c2x, s.c2y, s.x3, s.y3) }
                        drawPath(path, s.color.copy(alpha = 0.85f), style = Stroke(width = strokeW, pathEffect = dash))
                        val dir = if (s.x3 >= s.c2x) 1f else -1f
                        val head = Path().apply {
                            moveTo(s.x3, s.y3)
                            lineTo(s.x3 - dir * arrowLen, s.y3 - arrowHalf)
                            lineTo(s.x3 - dir * arrowLen, s.y3 + arrowHalf)
                            close()
                        }
                        drawPath(head, s.color.copy(alpha = 0.85f))
                    }
                }
            }.first().measure(Constraints.fixed(totalW, totalH))

            layout(totalW, totalH) {
                canvasPlaceable.place(0, 0)
                for (b in boxOf.values) placeables.getValue(b.tag.node.id).place(b.x.roundToInt(), b.y.roundToInt())
                for ((p, mx, my) in relPos) p.place((mx - p.width / 2f).roundToInt(), (my - p.height / 2f).roundToInt())
                for ((p, mx, my) in linkLabelPos) p.place((mx - p.width / 2f).roundToInt(), (my - p.height / 2f).roundToInt())
            }
        }
    }
}

// ── Tampilan satu node: gaya mengikuti tingkat (root / cabang utama / sub-node) ──
@Composable
private fun MindMapNodeView(
    node: MindMapNode, depth: Int, stepNo: Int, hidden: Int, color: Color, onPrimary: Color, dim: Boolean,
    onClick: () -> Unit, onToggle: () -> Unit
) {
    val dimMod = if (dim) Modifier.alpha(0.25f) else Modifier
    val hasKids = node.children.isNotEmpty()
    when (depth) {
        0 -> Box(
            dimMod
                .background(AppAccent, RoundedCornerShape(26.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Text(
                node.label, color = onPrimary, fontWeight = FontWeight.Bold,
                fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
        1 -> Row(
            dimMod
                .background(AppBgCard, RoundedCornerShape(Radius.card))
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(Radius.card))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (stepNo > 0) StepBadge(stepNo, color) else Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(
                node.label, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp)
            )
            if (hasKids) CollapseToggle(hidden, color, onToggle)
        }
        else -> Row(
            dimMod
                .background(color.copy(alpha = 0.10f), RoundedCornerShape(Radius.card))
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(Radius.card))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (stepNo > 0) StepBadge(stepNo, color)
            Text(
                node.label, color = AppText, fontSize = 12.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)
            )
            if (hasKids) CollapseToggle(hidden, color, onToggle)
        }
    }
}

@Composable
private fun StepBadge(no: Int, color: Color) {
    Box(Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Text("$no", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Tombol buka/ciut cabang; saat diciutkan menampilkan jumlah node yang tersembunyi. */
@Composable
private fun CollapseToggle(hidden: Int, color: Color, onToggle: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).clickable(onClick = onToggle)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(if (hidden > 0) Icons.Default.ChevronRight else Icons.Default.ExpandMore, null, tint = color, modifier = Modifier.size(14.dp))
        if (hidden > 0) Text("$hidden", color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Label kecil di garis (hubungan ke induk atau cross-link); latar menutupi garis di belakangnya. */
@Composable
private fun MindMapEdgeLabel(text: String, color: Color = AppTextMuted) {
    Text(
        text, color = color, fontSize = 9.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(AppBg.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

// ── Zoom controls bar ────────────────────────────────────────
@Composable
fun MindMapZoomBar(zoomPercent: Int, onZoomOut: () -> Unit, onZoomIn: () -> Unit, onFit: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(Radius.card)).background(AppBgCard).border(1.dp, AppBorder, RoundedCornerShape(Radius.card)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onZoomOut, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Remove, null, tint = AppTextMuted, modifier = Modifier.size(16.dp)) }
        Text("$zoomPercent%", color = AppText, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onZoomIn, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, null, tint = AppTextMuted, modifier = Modifier.size(16.dp)) }
        Box(Modifier.width(1.dp).height(20.dp).background(AppBorder))
        IconButton(onClick = onFit, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Fullscreen, null, tint = AppTextMuted, modifier = Modifier.size(16.dp)) }
    }
}

/**
 * Legenda kategori yang BENAR-BENAR dipakai di peta (bukan daftar tetap), sekaligus filter — berbentuk DROPDOWN yang bisa
 * dikecilkan: tertutup = satu pil kecil (pratinjau warna + jumlah kategori, atau kategori yang sedang difilter dengan tombol ✕),
 * terbuka = daftar kategori. Ketuk satu kategori untuk menerangkan node berkategori itu (dan jalurnya) dan meredupkan sisanya;
 * ketuk lagi (atau ✕) untuk reset. Kartu menempel di sudut kiri-bawah, jadi saat dibuka ia tumbuh ke ATAS.
 */
@Composable
fun MindMapLegend(categories: List<String>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    if (categories.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(Radius.card)).background(AppBgCard)
            .border(1.dp, AppBorder, RoundedCornerShape(Radius.card))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selected != null) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(categoryColor(selected)))
                Text(selected, color = AppText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(
                    Icons.Default.Close, "Hapus filter", tint = AppTextDim,
                    modifier = Modifier.size(16.dp).clip(CircleShape).clickable { onSelect(null) }
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    categories.take(4).forEach { Box(Modifier.size(8.dp).clip(CircleShape).background(categoryColor(it))) }
                }
                Text("Kategori (${categories.size})", color = AppTextMuted, fontSize = 12.sp, maxLines = 1)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = AppTextDim, modifier = Modifier.size(18.dp))
        }
        if (expanded) {
            Column(
                Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                categories.forEach { cat ->
                    val active = selected != null && selected.equals(cat, ignoreCase = true)
                    val c = categoryColor(cat)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (active) c.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { onSelect(if (active) null else cat) }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
                        Text(
                            cat, color = if (active) AppText else AppTextMuted, fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
