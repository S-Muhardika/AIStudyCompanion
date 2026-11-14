package com.ruangkerja.companion.util

/**
 * Bahan baku mind map untuk AI. [BARU] Dulu AI hanya melihat RINGKASAN bab, sehingga peta tidak mungkin lebih rinci daripada
 * ringkasannya — padahal spek meminta mind map dibangun dari MATERI, bukan dari ringkasan materi. Sekarang AI menerima ringkasan
 * (sebagai kerangka) DAN materi sumber (sebagai rincian), dengan batas total supaya permintaan tidak membengkak di tier gratis
 * (±4 ribu token).
 */
internal object MindMapSource {
    /**
     * @param materials pasangan (judul, isi) materi bab, urutan dipertahankan.
     * Pembagian jatah ADIL: materi pendek dipakai utuh, sisa jatahnya dibagi ke materi yang lebih panjang — jadi satu materi
     * panjang tidak menghabiskan seluruh jatah dan membuat materi lain tak terbaca sama sekali.
     */
    fun build(summary: String, materials: List<Pair<String, String>>, summaryCap: Int = 4000, materialsCap: Int = 9000): String {
        val sum = summary.trim().take(summaryCap)
        val mats = materials.filter { it.second.isNotBlank() }
        val sb = StringBuilder()
        if (sum.isNotBlank()) sb.append("RINGKASAN BAB (kerangka):\n").append(sum)
        if (mats.isNotEmpty()) {
            var budget = materialsCap
            val order = mats.indices.sortedBy { mats[it].second.trim().length }
            val take = IntArray(mats.size)
            order.forEachIndexed { rank, idx ->
                val share = budget / (order.size - rank)
                take[idx] = minOf(mats[idx].second.trim().length, share)
                budget -= take[idx]
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("MATERI SUMBER (rincian):")
            mats.forEachIndexed { i, (title, content) ->
                sb.append("\n\n")
                if (title.isNotBlank()) sb.append("[").append(title.trim()).append("]\n")
                sb.append(content.trim().take(take[i]))
            }
        }
        return sb.toString()
    }
}
