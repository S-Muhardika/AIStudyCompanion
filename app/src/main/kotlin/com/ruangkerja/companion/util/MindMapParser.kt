package com.ruangkerja.companion.util

import com.ruangkerja.companion.data.db.MindMapData
import com.ruangkerja.companion.data.db.MindMapNode

/**
 * Parser outline/markdown → pohon mind map TANPA AI (jalur offline: dari Ringkasan atau outline tempelan).
 *
 * [DITULIS ULANG sesuai spek Mind Map] Dulu hasilnya dipaksa 3 tingkat (root → cabang → anak) dan semua level
 * yang lebih dalam diratakan jadi anak cabang terakhir, sehingga hierarki materi hilang. Sekarang hierarkinya
 * dipertahankan sedalam teksnya: judul `#`…`######` = tingkat 1…6, baris polos mengikuti indentasi, dan bullet
 * berada satu tingkat di bawah judul/baris induknya (bullet beranak bila diberi indentasi). Daftar bernomor
 * ("1." / "1)") ditandai sebagai urutan (ordered), dan kategori hanya ditebak dari AWALAN label (lihat
 * MindMapCategories.infer) — tidak pernah dipaksa. Paragraf (kalimat tanpa indentasi tepat di bawah judul)
 * BUKAN node: spek menolak "mengubah paragraf menjadi daftar node", jadi kalimat itu disimpan sebagai
 * rincian (note) judulnya dan dibuka lewat detail node.
 */
object MindMapParser {
    private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
    private val bulletRegex = Regex("^\\s*(?:[-*•▪◦]|\\d+[.)])\\s+")
    private val numberedRegex = Regex("^\\s*\\d+[.)]\\s+")
    private val introRegex = Regex("^(berikut|berikut ini|rangkuman|ringkasan)\\b.*[:：]?$", RegexOption.IGNORE_CASE)

    private class Item(val depth: Int, val text: String, val numbered: Boolean, val rootCandidate: Boolean) {
        var note: String = ""
    }

    private class Builder(val label: String, val depth: Int, val numbered: Boolean, val note: String) {
        val kids = ArrayList<Builder>()
    }

    /**
     * @param forceRoot true → root SELALU [fallbackRoot] (jalur Ringkasan: judul bab). false → kalau baris
     * pertama polos & tanpa indentasi, dipakai sebagai root.
     * @return null kalau tidak ada satu pun cabang yang bisa dibentuk.
     */
    fun parseOutline(raw: String, fallbackRoot: String, forceRoot: Boolean = false): MindMapData? {
        val lines = raw.lines().map { it.replace("\t", "  ") }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val items = ArrayList<Item>()
        val plainStack = ArrayList<Int>()     // tumpukan indentasi baris polos
        val bulletStack = ArrayList<Int>()    // tumpukan indentasi bullet dalam satu blok
        var lastDepth = 0                     // tingkat item non-bullet terakhir
        var afterHeading = false              // true bila item terakhir judul (atau paragraf rincian judul itu)
        for ((i, line) in lines.withIndex()) {
            val heading = headingRegex.find(line.trim())
            val isBullet = bulletRegex.containsMatchIn(line)

            // Baris pembuka basa-basi ("Berikut ringkasannya:") bukan konsep.
            if (i == 0 && heading == null && !isBullet && introRegex.matches(line.trim())) continue

            if (heading != null) {
                val depth = heading.groupValues[1].length
                items.add(Item(depth, cleanText(heading.groupValues[2]), false, false))
                lastDepth = depth
                bulletStack.clear()
                afterHeading = true
                continue
            }
            if (isBullet) {
                afterHeading = false
                val level = bulletLevel(bulletStack, line.takeWhile { it == ' ' }.length)
                val text = cleanText(line.replace(bulletRegex, ""))
                if (text.isNotBlank()) items.add(Item(lastDepth + 1 + level, text, numberedRegex.containsMatchIn(line), false))
                continue
            }
            bulletStack.clear()
            val lvl = plainLevel(plainStack, line.takeWhile { it == ' ' }.length)
            val text = cleanText(line)
            if (text.isBlank()) continue
            if (lvl == 0 && afterHeading && items.isNotEmpty() && isSentence(text)) {
                val last = items.last()
                last.note = (last.note + " " + text).trim()
                continue
            }
            afterHeading = false
            items.add(Item(lvl, text, false, lvl == 0))
            lastDepth = lvl
        }
        if (items.isEmpty()) return null

        val useFirstAsRoot = !forceRoot && items.first().rootCandidate
        val rootLabel = if (useFirstAsRoot) items.first().text else fallbackRoot
        val body = if (useFirstAsRoot) items.drop(1) else items

        val root = Builder(rootLabel, 0, false, "")
        val stack = ArrayList<Builder>()
        stack.add(root)
        for (item in body) {
            val d = item.depth.coerceAtLeast(1)
            while (stack.size > 1 && stack.last().depth >= d) stack.removeAt(stack.size - 1)
            val b = Builder(item.text, d, item.numbered, item.note)
            stack.last().kids.add(b)
            stack.add(b)
        }
        if (root.kids.isEmpty()) return null
        return MindMapOps.sanitize(MindMapData(toNode(root)), mergeRepeats = true)
    }

    private fun toNode(b: Builder): MindMapNode {
        val kids = b.kids.map { toNode(it) }
        return MindMapNode(
            label = b.label,
            category = MindMapCategories.infer(b.label),
            children = kids,
            ordered = kids.size >= 2 && b.kids.all { it.numbered },
            note = b.note
        )
    }

    /** Kalimat/paragraf (bukan istilah singkat): berakhir tanda baca kalimat atau ≥ 9 kata. */
    private fun isSentence(text: String): Boolean =
        text.endsWith(".") || text.endsWith("!") || text.endsWith("?") || text.split(' ').size >= 9

    private fun cleanText(s: String): String {
        val collapsed = s.trim().removePrefix("**").removeSuffix("**").trim()
            .replace(Regex("\\s+"), " ") // jaga-jaga ada \n/\r/tab nyelip di tengah teks
        return collapsed.take(160)
    }

    /**
     * Level bullet DALAM SATU BLOK: indentasi lebih dalam ≥ 2 spasi = anak bullet sebelumnya. Selisih < 2 spasi
     * dianggap sama (toleran terhadap indentasi tak rapi dari AI/ketikan manual).
     */
    private fun bulletLevel(stack: MutableList<Int>, indent: Int): Int {
        while (stack.isNotEmpty() && indent <= stack.last() - 2) stack.removeAt(stack.size - 1)
        if (stack.isNotEmpty() && indent < stack.last() + 2) return stack.size - 1
        stack.add(indent)
        return stack.size - 1
    }

    /**
     * Level baris polos dari indentasi relatif (indent-stack standar ala Python/YAML); level 0 = paling kiri.
     * Pop dulu semua level yang lebih dalam dari baris sekarang, BARU cek apakah indent ini pas dengan nenek
     * moyang yang tersisa atau level baru di bawahnya. (BUG LAMA yang sudah diperbaiki: indent "di antara"
     * dua level — mis. 3 spasi di antara 2 dan 4 — dulu me-reset seluruh stack dan hierarki ikut runtuh.)
     */
    private fun plainLevel(stack: MutableList<Int>, indent: Int): Int {
        while (stack.isNotEmpty() && indent < stack.last()) stack.removeAt(stack.size - 1)
        if (stack.isNotEmpty() && stack.last() == indent) return stack.size - 1
        stack.add(indent)
        return stack.size - 1
    }
}
