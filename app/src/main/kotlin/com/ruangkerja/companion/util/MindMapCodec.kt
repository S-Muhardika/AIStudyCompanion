package com.ruangkerja.companion.util

import com.ruangkerja.companion.data.db.MindMapData
import com.ruangkerja.companion.data.db.MindMapLink
import com.ruangkerja.companion.data.db.MindMapNode
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Baca/tulis mind map sebagai JSON (disimpan di Chapter.mindMapJson dan ikut tersinkron sebagai teks, jadi
 * tidak ada perubahan skema).
 *
 * - Format v2 (sekarang): {"v":2,"root":{id,label,category?,relation?,ordered?,note?,children?[...]},"links":[{from,to,relation?}]}
 * - Format LAMA (sebelum pohon bebas kedalaman): {"root":"Judul","branches":[{label,type,children:[{label,type}]}]} —
 *   tetap bisa dibaca (diubah ke pohon; tipe lama dipetakan ke kategori baru), jadi peta yang sudah tersimpan tidak hilang.
 * - Keluaran AI yang terpotong (batas token) diselamatkan sebanyak mungkin lewat [repairTruncated].
 *
 * Dibuat manual lewat JsonElement (bukan kelas @Serializable) supaya toleran terhadap field hilang/salah tipe
 * dan tidak terkena jebakan encodeDefaults.
 */
internal object MindMapCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(data: MindMapData): String {
        fun node(n: MindMapNode): JsonObject = buildJsonObject {
            put("id", n.id)
            put("label", n.label)
            if (n.category.isNotBlank()) put("category", n.category)
            if (n.relation.isNotBlank()) put("relation", n.relation)
            if (n.ordered) put("ordered", true)
            if (n.note.isNotBlank()) put("note", n.note)
            if (n.children.isNotEmpty()) put("children", JsonArray(n.children.map { node(it) }))
        }
        return buildJsonObject {
            put("v", 2)
            put("root", node(data.root))
            if (data.links.isNotEmpty()) {
                put("links", JsonArray(data.links.map { l ->
                    buildJsonObject {
                        put("from", l.from)
                        put("to", l.to)
                        if (l.relation.isNotBlank()) put("relation", l.relation)
                    }
                }))
            }
        }.toString()
    }

    /**
     * @param fresh true untuk keluaran AI: id dari sumber hanya penanda sementara (mis. "n3") sehingga diganti
     * UUID baru dan link dipetakan ulang. false untuk data tersimpan: id asli dipertahankan (link tetap valid).
     */
    fun decode(raw: String, fresh: Boolean = false): MindMapData? {
        val obj = parseObj(raw) ?: return null
        val rootEl = obj["root"]
        val data = when (rootEl) {
            is JsonObject -> decodeV2(obj, rootEl, fresh)
            is JsonPrimitive -> decodeLegacy(obj)
            else -> null
        } ?: return null
        val sane = MindMapOps.sanitize(data, mergeRepeats = fresh)
        // Keluaran AI dibatasi jumlah cross-link-nya; data tersimpan (hasil edit manual) tidak pernah dipangkas.
        return if (fresh) sane.copy(links = sane.links.take(MindMapOps.aiLinkCap(MindMapOps.count(sane.root)))) else sane
    }

    /** Baca balasan AI (boleh dibungkus teks/pagar ```json, boleh terpotong). */
    fun decodeAi(raw: String): MindMapData? {
        decode(extractJsonSpan(raw, '{', '}'), fresh = true)?.let { return it }
        val repaired = repairTruncated(fromFirstBrace(raw)) ?: return null
        return decode(repaired, fresh = true)
    }

    fun decodeEnrichment(raw: String): MindMapEnrichment? {
        val obj = parseObj(extractJsonSpan(raw, '{', '}'))
            ?: repairTruncated(fromFirstBrace(raw))?.let { parseObj(it) }
            ?: return null
        val nodes = (obj["nodes"] as? JsonArray)?.mapNotNull { e ->
            (e as? JsonObject)?.let { o ->
                val id = o.str("id")
                if (id.isBlank()) null else NodeMeta(id, o.str("category"), o.str("relation"), o.bool("ordered"))
            }
        } ?: emptyList()
        val links = (obj["links"] as? JsonArray)?.mapNotNull { e ->
            (e as? JsonObject)?.let { o -> MindMapLink(o.str("from"), o.str("to"), o.str("relation")) }
        } ?: emptyList()
        return MindMapEnrichment(nodes, links)
    }

    /**
     * Balasan AI sering terpotong di tengah elemen karena batas token. Cari titik potong terakhir yang
     * jatuh tepat setelah '}' / ']' (elemen yang sudah lengkap), tutup kurung yang masih terbuka, dan ambil
     * kandidat pertama yang valid. null kalau tak ada yang bisa diselamatkan.
     */
    fun repairTruncated(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s[0] != '{') return null
        val cuts = ArrayList<Int>()
        var inStr = false
        var esc = false
        for (i in s.indices) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else if (c == '"') {
                inStr = true
            } else if (c == '}' || c == ']') {
                cuts.add(i)
            }
        }
        var tries = 0
        for (k in cuts.indices.reversed()) {
            if (++tries > 120) break
            val prefix = s.substring(0, cuts[k] + 1)
            val closers = closersFor(prefix) ?: continue
            val candidate = prefix + closers
            if (parseObj(candidate) != null) return candidate
        }
        return null
    }

    // ── internal ──────────────────────────────────────────
    private fun fromFirstBrace(raw: String): String {
        val i = raw.indexOf('{')
        return if (i >= 0) raw.substring(i) else raw
    }

    private fun parseObj(s: String): JsonObject? =
        try { json.parseToJsonElement(s) as? JsonObject } catch (e: Exception) { null }

    /** Penutup untuk kurung yang masih terbuka di [prefix], terdalam dulu. null kalau ada string yang menggantung. */
    private fun closersFor(prefix: String): String? {
        val stack = ArrayList<Char>()
        var inStr = false
        var esc = false
        for (c in prefix) {
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> stack.add('}')
                    '[' -> stack.add(']')
                    '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    else -> {}
                }
            }
        }
        if (inStr) return null
        return stack.reversed().joinToString("")
    }

    private fun JsonObject.str(key: String): String {
        val p = this[key] as? JsonPrimitive ?: return ""
        return if (p is JsonNull) "" else p.content.trim()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.booleanOrNull ?: when (p.content.trim().lowercase()) {
            "true", "ya" -> true
            "false", "tidak" -> false
            else -> null
        }
    }

    private fun decodeV2(obj: JsonObject, rootEl: JsonObject, fresh: Boolean): MindMapData {
        val idMap = HashMap<String, String>()   // id sumber → id akhir
        fun node(e: JsonObject): MindMapNode {
            val srcId = e.str("id")
            val finalId = if (fresh || srcId.isBlank()) UUID.randomUUID().toString() else srcId
            if (srcId.isNotBlank()) idMap[srcId] = finalId
            val kids = (e["children"] as? JsonArray)?.mapNotNull { c -> (c as? JsonObject)?.let { node(it) } } ?: emptyList()
            return MindMapNode(
                id = finalId, label = e.str("label"), category = MindMapCategories.normalize(e.str("category")),
                children = kids, ordered = e.bool("ordered") ?: false,
                relation = if (fresh) MindMapCategories.cleanRelation(e.str("relation")) else e.str("relation"), note = e.str("note")
            )
        }
        val root = node(rootEl)
        val links = (obj["links"] as? JsonArray)?.mapNotNull { l ->
            (l as? JsonObject)?.let { o ->
                val f = idMap[o.str("from")]; val t = idMap[o.str("to")]
                if (f == null || t == null) null else MindMapLink(f, t, if (fresh) MindMapCategories.cleanRelation(o.str("relation")) else o.str("relation"))
            }
        } ?: emptyList()
        return MindMapData(root, links)
    }

    private fun legacyCategory(type: String): String = when (type.uppercase()) {
        "KONSEP_UTAMA" -> "Konsep"
        "CONTOH" -> "Contoh"
        "CATATAN_PENTING" -> "Catatan Penting"
        "PERINGATAN" -> "Kesalahan Umum"
        else -> ""   // PENJELASAN (bawaan lama) dan yang tak dikenal = tanpa kategori
    }

    private fun decodeLegacy(obj: JsonObject): MindMapData? {
        val branches = obj["branches"] as? JsonArray ?: return null
        val kids = branches.mapNotNull { b ->
            (b as? JsonObject)?.let { bo ->
                val grand = (bo["children"] as? JsonArray)?.mapNotNull { c ->
                    (c as? JsonObject)?.let { co -> MindMapNode(label = co.str("label"), category = legacyCategory(co.str("type"))) }
                } ?: emptyList()
                MindMapNode(label = bo.str("label"), category = legacyCategory(bo.str("type")), children = grand)
            }
        }
        return MindMapData(MindMapNode(label = obj.str("root").ifBlank { "Materi" }, children = kids))
    }
}
