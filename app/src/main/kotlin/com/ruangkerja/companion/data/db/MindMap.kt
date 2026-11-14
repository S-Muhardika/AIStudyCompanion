package com.ruangkerja.companion.data.db

import java.util.UUID

/**
 * Satu node mind map. Pohon BEBAS kedalaman (spek Mind Map: "tidak ada jumlah tingkat maksimum yang
 * wajib" — kedalaman mengikuti materi): setiap node boleh punya anak, sedalam apa pun yang memang perlu.
 *
 * - [category]: kategori jenis informasi, TEKS BEBAS dari kosakata (util/MindMapCategories) — bukan enum
 *   tetap, tidak wajib dipakai semua. Kosong = belum dikategorikan.
 * - [ordered]: true → anak-anaknya adalah langkah/tahapan BERURUTAN (proses/prosedur), ditampilkan bernomor.
 * - [relation]: label hubungan node ini ke induknya, HANYA bila bukan sekadar "bagian dari"
 *   (mis. "menyebabkan", "digunakan untuk"). Kosong = hubungan biasa.
 * - [note]: rincian pendukung (1–2 kalimat), dibuka lewat detail node. Label node sendiri harus tetap
 *   singkat — bukan kalimat penjelasan.
 */
data class MindMapNode(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val category: String = "",
    val children: List<MindMapNode> = emptyList(),
    val ordered: Boolean = false,
    val relation: String = "",
    val note: String = ""
)

/** Cross-link antara dua node (id) dari cabang berbeda, dengan label hubungannya. */
data class MindMapLink(val from: String, val to: String, val relation: String = "")

/** [links] = cross-link; hubungan induk→anak sudah diwakili struktur pohon dan tidak dicatat di sini. */
data class MindMapData(val root: MindMapNode, val links: List<MindMapLink> = emptyList())
