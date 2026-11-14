package com.ruangkerja.companion.util

import com.ruangkerja.companion.data.db.MindMapData
import com.ruangkerja.companion.data.db.MindMapLink
import com.ruangkerja.companion.data.db.MindMapNode
import java.util.UUID

/** Metadata hasil AI "enrich" untuk satu node (id = id singkat di prompt, mis. "n3"). Field null = tak disebut AI. */
internal data class NodeMeta(val id: String, val category: String?, val relation: String?, val ordered: Boolean?)

/** Hasil AI "enrich": metadata node + usulan cross-link. */
internal data class MindMapEnrichment(val nodes: List<NodeMeta>, val links: List<MindMapLink>)

/**
 * Operasi MURNI pada pohon mind map (tanpa state, tanpa Android) — semua perubahan lewat sini supaya
 * invariannya (id unik, label tidak kosong, link valid, kedalaman/ukuran terbatas) dijaga di satu tempat
 * lewat [sanitize].
 */
internal object MindMapOps {
    /** Batas TEKNIS (bukan batas struktur pengetahuan): melindungi tampilan dari data patologis. */
    const val MAX_DEPTH = 12
    const val MAX_NODES = 400
    const val MAX_LABEL = 160
    const val MAX_NOTE = 400
    const val MAX_RELATION = 40
    private const val COLLAPSE_THRESHOLD = 80

    // ── Baca ──────────────────────────────────────────────
    fun find(root: MindMapNode, id: String): MindMapNode? {
        if (root.id == id) return root
        for (c in root.children) { find(c, id)?.let { return it } }
        return null
    }

    fun pathTo(root: MindMapNode, id: String): List<MindMapNode> {
        if (root.id == id) return listOf(root)
        for (c in root.children) {
            val p = pathTo(c, id)
            if (p.isNotEmpty()) return listOf(root) + p
        }
        return emptyList()
    }

    /** Semua node, urutan DFS (induk dulu). */
    fun allNodes(root: MindMapNode): List<MindMapNode> {
        val out = ArrayList<MindMapNode>()
        fun walk(n: MindMapNode) { out.add(n); n.children.forEach { walk(it) } }
        walk(root)
        return out
    }

    fun count(root: MindMapNode): Int = 1 + root.children.sumOf { count(it) }

    /** id anak → id induk (root tidak punya entri). */
    fun parentMap(root: MindMapNode): Map<String, String> {
        val out = HashMap<String, String>()
        fun walk(n: MindMapNode) { n.children.forEach { c -> out[c.id] = n.id; walk(c) } }
        walk(root)
        return out
    }

    /** Kategori yang dipakai, urutan kemunculan (untuk legenda). */
    fun categoriesUsed(root: MindMapNode): List<String> =
        allNodes(root).map { it.category }.filter { it.isNotBlank() }.distinct()

    /** id node berkategori [category] + semua leluhurnya (supaya jalur ke node itu tetap terang saat difilter). */
    fun matchingIds(root: MindMapNode, category: String): Set<String> {
        val out = HashSet<String>()
        fun walk(n: MindMapNode): Boolean {
            var hit = n.category.equals(category, ignoreCase = true)
            for (c in n.children) { if (walk(c)) hit = true }
            if (hit) out.add(n.id)
            return hit
        }
        walk(root)
        return out
    }

    /**
     * Peta sangat besar (> [COLLAPSE_THRESHOLD] node): ciutkan cabang di kedalaman ≥ 3 (spek: collapse/expand untuk materi
     * panjang). Tiga tingkat teratas (root, cabang, sub-cabang) tetap terlihat; yang lebih dalam dibuka lewat chevron "+N".
     * Peta yang lebih kecil tampil UTUH — dulu batasnya 45 node dan mulai dari kedalaman 2, sehingga peta menengah terlihat
     * "kurang rinci" padahal rinciannya hanya tersembunyi.
     */
    fun defaultCollapsed(data: MindMapData): Set<String> {
        if (count(data.root) <= COLLAPSE_THRESHOLD) return emptySet()
        val out = HashSet<String>()
        fun walk(n: MindMapNode, depth: Int) {
            if (depth >= 3 && n.children.isNotEmpty()) out.add(n.id)
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(data.root, 0)
        return out
    }

    /** Ciutkan semua node bercabang di bawah [root] kecuali [root] sendiri. */
    fun collapseAll(root: MindMapNode): Set<String> =
        allNodes(root).filter { it.id != root.id && it.children.isNotEmpty() }.map { it.id }.toSet()

    // ── Ubah ──────────────────────────────────────────────
    private fun mapNode(n: MindMapNode, id: String, f: (MindMapNode) -> MindMapNode): MindMapNode =
        if (n.id == id) f(n) else n.copy(children = n.children.map { mapNode(it, id, f) })

    fun addChild(data: MindMapData, parentId: String, node: MindMapNode): MindMapData =
        sanitize(data.copy(root = mapNode(data.root, parentId) { it.copy(children = it.children + node) }))

    fun update(
        data: MindMapData, id: String, label: String, category: String, relation: String, ordered: Boolean, note: String
    ): MindMapData = sanitize(
        data.copy(root = mapNode(data.root, id) {
            it.copy(label = label, category = category, relation = relation, ordered = ordered, note = note)
        })
    )

    /** Hapus node beserta seluruh turunannya (root tidak bisa dihapus). Link yang menunjuk node hilang ikut terbuang. */
    fun remove(data: MindMapData, id: String): MindMapData {
        if (data.root.id == id) return data
        fun drop(n: MindMapNode): MindMapNode = n.copy(children = n.children.filter { it.id != id }.map { drop(it) })
        return sanitize(data.copy(root = drop(data.root)))
    }

    /** Geser node [delta] posisi di antara saudaranya (−1 = naik, +1 = turun). */
    fun move(data: MindMapData, id: String, delta: Int): MindMapData {
        fun mv(n: MindMapNode): MindMapNode {
            val i = n.children.indexOfFirst { it.id == id }
            if (i < 0) return n.copy(children = n.children.map { mv(it) })
            val j = i + delta
            if (j !in n.children.indices) return n
            val list = n.children.toMutableList()
            val item = list.removeAt(i)
            list.add(j, item)
            return n.copy(children = list)
        }
        return data.copy(root = mv(data.root))
    }

    fun addLink(data: MindMapData, from: String, to: String, relation: String): MindMapData =
        sanitize(data.copy(links = data.links + MindMapLink(from, to, relation)))

    fun removeLink(data: MindMapData, from: String, to: String): MindMapData =
        data.copy(links = data.links.filterNot { it.from == from && it.to == to })

    // ── Validasi ──────────────────────────────────────────
    fun cleanLabel(s: String): String = s.replace("**", "").replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)

    private fun flatten(list: List<MindMapNode>): List<MindMapNode> {
        val out = ArrayList<MindMapNode>()
        fun w(n: MindMapNode) { out.add(n); n.children.forEach { w(it) } }
        list.forEach { w(it) }
        return out
    }

    /**
     * Pastikan data layak pakai, apa pun asalnya (AI, JSON tersimpan, edit manual): label dirapikan dan node
     * berlabel kosong dibuang, id dibuat unik, kategori dinormalkan, `ordered` hanya untuk node beranak ≥ 2,
     * node di kedalaman [MAX_DEPTH] menampung SELURUH turunannya sebagai anak langsung (jadi paling dalam MAX_DEPTH + 1; tidak ada yang dibuang), total node
     * dibatasi [MAX_NODES], dan link yang tidak valid (id hilang, ke diri sendiri, sudah terhubung lewat
     * hierarki, ganda) dibuang. Jumlah link TIDAK dibatasi di sini (lihat [aiLinkCap]). [mergeRepeats] — khusus data
     * impor — menggabungkan node yang hanya mengulang (lihat [mergeRepeatsDeep]).
     */
    fun sanitize(data: MindMapData, mergeRepeats: Boolean = false): MindMapData {
        val base = sanitizeStructure(data)
        if (!mergeRepeats) return base
        // Putaran kedua: invarian (ordered ≥ 2 anak, link valid, id unik) dihitung ulang setelah node digabung.
        return sanitizeStructure(base.copy(root = mergeRepeatsDeep(base.root)))
    }

    /**
     * Spek (cek REDUNDANCY & ARTIFICIAL DEPTH): node yang hanya mengulang node lain digabung. (a) anak berlabel SAMA dengan
     * induknya (Aset › Aset › Kas) → turunannya naik satu tingkat; (b) saudara berlabel sama → satu node, anaknya digabung.
     * Top-down supaya anak hasil gabungan ikut diperiksa di tingkatnya. Hanya untuk data impor (AI/parser).
     */
    private fun mergeRepeatsDeep(n: MindMapNode): MindMapNode {
        var kids = n.children
        var category = n.category
        var note = n.note
        var ordered = n.ordered
        while (kids.any { it.label.equals(n.label, ignoreCase = true) }) {
            val next = ArrayList<MindMapNode>()
            for (k in kids) {
                if (k.label.equals(n.label, ignoreCase = true)) {
                    next.addAll(k.children)
                    if (category.isBlank()) category = k.category
                    if (note.isBlank()) note = k.note
                    if (kids.size == 1 && k.ordered) ordered = true
                } else {
                    next.add(k)
                }
            }
            kids = next
        }
        val byLabel = LinkedHashMap<String, MindMapNode>()
        for (k in kids) {
            val key = k.label.trim().lowercase()
            val first = byLabel[key]
            byLabel[key] = if (first == null) k else first.copy(
                children = first.children + k.children,
                category = first.category.ifBlank { k.category },
                note = first.note.ifBlank { k.note }
            )
        }
        return n.copy(category = category, note = note, ordered = ordered, children = byLabel.values.map { mergeRepeatsDeep(it) })
    }

    private fun sanitizeStructure(data: MindMapData): MindMapData {
        val seen = HashSet<String>()
        var budget = MAX_NODES
        fun cleanNode(n: MindMapNode, depth: Int, isRoot: Boolean): MindMapNode? {
            if (budget <= 0) return null
            val label = cleanLabel(n.label).ifBlank { if (isRoot) "Materi" else return null }
            budget--
            val id = if (n.id.isBlank() || !seen.add(n.id)) UUID.randomUUID().toString().also { seen.add(it) } else n.id
            val atLimit = depth >= MAX_DEPTH
            val kidsSrc = if (atLimit) flatten(n.children) else n.children
            val kids = ArrayList<MindMapNode>()
            for (c in kidsSrc) {
                val child = if (atLimit) c.copy(children = emptyList()) else c
                cleanNode(child, depth + 1, false)?.let { kids.add(it) }
            }
            return n.copy(
                id = id, label = label, category = MindMapCategories.normalize(n.category),
                children = kids, ordered = n.ordered && kids.size >= 2,
                relation = if (isRoot) "" else n.relation.trim().take(MAX_RELATION),
                note = n.note.trim().take(MAX_NOTE)
            )
        }
        val root = cleanNode(data.root, 0, true) ?: MindMapNode(label = "Materi")
        val ids = allNodes(root).map { it.id }.toHashSet()
        val parent = parentMap(root)
        val seenLinks = HashSet<Pair<String, String>>()
        val links = data.links.mapNotNull { l ->
            if (l.from !in ids || l.to !in ids || l.from == l.to) return@mapNotNull null
            if (parent[l.from] == l.to || parent[l.to] == l.from) return@mapNotNull null
            if (!seenLinks.add(l.from to l.to)) return@mapNotNull null
            l.copy(relation = l.relation.trim().take(MAX_RELATION))
        }
        return MindMapData(root, links)
    }

    /**
     * Batas jumlah cross-link yang diterima dari AI (bukan dari edit manual — pengguna tidak dibatasi): spek meminta
     * cross-link hanya bila bermakna, jadi keluaran AI yang membludak dipangkas. Link manual tidak pernah dibuang diam-diam.
     */
    fun aiLinkCap(nodeCount: Int): Int = maxOf(5, nodeCount / 3)

    // ── AI "enrich" ───────────────────────────────────────
    /** Teks pohon buat prompt: satu node per baris "n<i>: label [kategori]", indentasi 2 spasi per tingkat. */
    fun toPromptTree(data: MindMapData): Pair<String, Map<String, String>> {
        val sb = StringBuilder()
        val map = LinkedHashMap<String, String>()
        var i = 0
        fun walk(n: MindMapNode, depth: Int) {
            val short = "n${i++}"
            map[short] = n.id
            sb.append("  ".repeat(depth)).append(short).append(": ").append(n.label)
            if (n.category.isNotBlank()) sb.append("  [").append(n.category).append("]")
            sb.append('\n')
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(data.root, 0)
        return sb.toString().trimEnd() to map
    }

    /**
     * Gabungkan hasil AI ke peta TANPA mengubah label/hierarki: kategori & hubungan hanya mengisi yang masih
     * kosong (hasil edit pengguna tidak ditimpa), `ordered` hanya bisa dinyalakan, cross-link ditambahkan.
     */
    fun enrich(data: MindMapData, meta: MindMapEnrichment, idByShort: Map<String, String>): MindMapData {
        val byId = HashMap<String, NodeMeta>()
        for (m in meta.nodes) { idByShort[m.id]?.let { byId[it] = m } }
        fun walk(n: MindMapNode): MindMapNode {
            val kids = n.children.map { walk(it) }
            val m = byId[n.id]
            var cat = n.category; var rel = n.relation; var ord = n.ordered
            if (m != null) {
                if (cat.isBlank()) cat = MindMapCategories.normalize(m.category)
                if (rel.isBlank()) rel = MindMapCategories.cleanRelation(m.relation)
                if (!ord && m.ordered == true && kids.size >= 2) ord = true
            }
            return n.copy(category = cat, relation = rel, ordered = ord, children = kids)
        }
        val newLinks = meta.links.mapNotNull { l ->
            val f = idByShort[l.from]; val t = idByShort[l.to]
            if (f == null || t == null) null else MindMapLink(f, t, MindMapCategories.cleanRelation(l.relation))
        }.take(aiLinkCap(count(data.root)))
        return sanitize(MindMapData(walk(data.root), data.links + newLinks))
    }
}
