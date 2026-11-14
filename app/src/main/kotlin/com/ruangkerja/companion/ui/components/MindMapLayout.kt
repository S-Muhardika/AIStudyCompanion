package com.ruangkerja.companion.ui.components

import kotlin.math.max

/**
 * Kotak node untuk tata letak pohon (kiri → kanan). Murni hitungan (tanpa Compose) supaya bisa disimulasikan.
 * [tag] = pembawa data pemanggil (mis. node + Placeable); [relW] = lebar label hubungan ke induk (0 = tak ada).
 */
internal class LayoutBox<T>(val tag: T, val depth: Int, val w: Float, val h: Float, val relW: Float = 0f) {
    val kids = ArrayList<LayoutBox<T>>()
    var x = 0f
    var y = 0f
    var subtreeH = 0f
}

internal object MindMapLayout {
    class Result(val width: Float, val height: Float)

    /**
     * Tata letak pohon KEDALAMAN BEBAS: satu kolom per tingkat (lebar kolom = node terlebar di tingkat itu),
     * jarak antar kolom = [gapX] + lebar label hubungan terlebar di kolom tujuan (supaya label tidak menimpa node),
     * anak ditumpuk vertikal dan induk diletakkan di tengah blok anaknya. [rootGap] = jarak antar anak langsung
     * root (cabang utama dilonggarkan), [siblingGap] = jarak antar saudara di tingkat lebih dalam.
     */
    fun <T> layout(root: LayoutBox<T>, gapX: Float, rootGap: Float, siblingGap: Float): Result {
        var maxDepth = 0
        fun depthScan(b: LayoutBox<T>) { maxDepth = max(maxDepth, b.depth); b.kids.forEach { depthScan(it) } }
        depthScan(root)
        val colW = FloatArray(maxDepth + 1)
        val relW = FloatArray(maxDepth + 1)
        fun widthScan(b: LayoutBox<T>) {
            colW[b.depth] = max(colW[b.depth], b.w)
            relW[b.depth] = max(relW[b.depth], b.relW)
            b.kids.forEach { widthScan(it) }
        }
        widthScan(root)
        val colX = FloatArray(maxDepth + 1)
        for (d in 1..maxDepth) colX[d] = colX[d - 1] + colW[d - 1] + gapX + relW[d]

        fun gapOf(parent: LayoutBox<T>): Float = if (parent.depth == 0) rootGap else siblingGap
        fun blockH(b: LayoutBox<T>): Float {
            var sum = 0f
            b.kids.forEachIndexed { i, k -> sum += k.subtreeH; if (i > 0) sum += gapOf(b) }
            return sum
        }
        fun height(b: LayoutBox<T>) {
            b.kids.forEach { height(it) }
            b.subtreeH = max(b.h, blockH(b))
        }
        fun place(b: LayoutBox<T>, top: Float) {
            b.x = colX[b.depth]
            b.y = top + (b.subtreeH - b.h) / 2f
            var cur = top + (b.subtreeH - blockH(b)) / 2f
            for (k in b.kids) { place(k, cur); cur += k.subtreeH + gapOf(b) }
        }
        height(root)
        place(root, 0f)

        var width = 0f
        fun extent(b: LayoutBox<T>) { width = max(width, b.x + b.w); b.kids.forEach { extent(it) } }
        extent(root)
        return Result(width, root.subtreeH)
    }
}
