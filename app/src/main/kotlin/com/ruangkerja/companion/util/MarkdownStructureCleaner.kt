package com.ruangkerja.companion.util

/**
 * Pembersih struktur markdown hasil ekstraksi web — MURNI algoritma teks (regex/heuristik),
 * TANPA panggilan AI/jaringan sama sekali. Dipakai AppViewModel.importFromUrl() sebagai langkah
 * DEFAULT (gratis, instan) sebelum (opsional, kalau masih perlu) langkah AI "structureCleanup"
 * (AiPrompts.kt) yang jauh lebih mahal (token + latensi + resiko kepotong di dokumen panjang).
 *
 * Menangani DUA hal konkret yang paling sering bikin hasil impor URL "berantakan":
 *  1. Blok yang isinya PADAT LINK (nav, "pencarian populer", daftar kata terkait, dst yang ikut
 *     ke-scrape bareng readability) — dibuang pakai heuristik "link density", pola yang sama
 *     dipakai algoritma boilerplate-removal umum (mis. Boilerpipe).
 *  2. Penomoran & sub-penomoran list yang berantakan/hilang — dirapikan pakai indent-stack,
 *     pola SAMA PERSIS kayak fix util/MindMapParser.kt (pop dulu semua level yang lebih dalam
 *     dari baris sekarang, baru cocokkan/push — BUKAN reset paksa ke root kalau ketemu indentasi
 *     "di antara" dua level yang udah tercatat).
 *
 * YANG SENGAJA TIDAK ditangani di sini (butuh pemahaman semantik, gak ada heuristik teks murni
 * yang cukup andal buat ini): mengenali teks yang KEHILANGAN struktur tabelnya (kolom/barisnya
 * udah kegencet jadi paragraf datar) dan mengubahnya balik jadi tabel Markdown. Itu masih lewat
 * action AI "structureCleanup" kalau user memilih untuk memakainya secara manual.
 */
object MarkdownStructureCleaner {

    private val NUMBERED_ITEM = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val MD_LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
    // Dipakai isLinkHeavy() buat ngitung "kata penghubung" DI LUAR link — lihat penjelasan
    // lengkap di komentar isLinkHeavy. À-ÿ nyakup huruf berdiakritik Latin umum (kayak "matéri").
    private val WORD = Regex("[a-zA-ZÀ-ÿ]{2,}")

    fun clean(raw: String, sourceUrl: String? = null): String {
        val domainAdjusted = applyDomainRules(raw, sourceUrl)
        val trailingCut = cutTrailingBoilerplate(domainAdjusted)
        val deboilerplated = stripLinkHeavyBlocks(trailingCut)
        val renumbered = fixListNumbering(deboilerplated)
        return collapseBlankLines(renumbered).trim()
    }

    // ── 0.5) Pemotong boilerplate akhir-halaman — GENERAL, lintas situs (beda dari aturan
    // Wikipedia/Brainly di atas yang KHUSUS satu domain) ────────────────────────────────────
    /**
     * Kasus nyata yang mengungkap celah ini: artikel dari situs bank (OCBC) yang kebawa utuh
     * alamat kantor, daftar produk per-segmen (Individu/UKM/Korporasi/Syariah/Digital), notice
     * cookie, dan navigasi footer lengkap ("Login/Compare/Poinseru/dst") — SEMUANYA lolos dari
     * stripLinkHeavyBlocks() di bawah, karena isinya PLAIN TEXT (bullet "• Nyala", "• Premier
     * Banking", dst), bukan link markdown "[teks](url)". isLinkHeavy() cuma ngitung link, jadi
     * kalau hasil ekstraksi Jina buat blok itu udah gak nyisain markup link-nya sama sekali,
     * heuristik itu buta total — link count 0, gak pernah keanggep "link-heavy".
     *
     * Fix-nya BUKAN nambah kasus khusus per-situs (bakal gak abis, tiap bank/toko online punya
     * footer beda) — polanya sama kayak cutWikipediaReferences: cari SATU penanda yang HAMPIR
     * SELALU nunjukkin "konten artikel selesai, mulai furniture situs", potong semua setelahnya.
     * Bedanya, penanda di sini UMUM lintas situs (bukan citation marker spesifik MediaWiki),
     * jadi taruh di sini sebagai heuristik umum, bukan di registry applyDomainRules().
     *
     * Penanda WAJIB muncul sebagai baris pendek BERDIRI SENDIRI (≤60 karakter, bukan nyempil di
     * tengah paragraf panjang) — biar aman kalau ada artikel yang KEBETULAN membahas salah satu
     * topik ini beneran (mis. artikel tentang hukum privasi data yang genuinely ngebahas
     * "kebijakan privasi" sebagai bagian dari analisis, bukan cuma label footer).
     */
    private val TRAILING_BOILERPLATE_MARKERS = listOf(
        "baca juga", "artikel terkait", "artikel terbaru", "artikel lainnya", "produk terkait",
        "alamat kantor pusat", "kebijakan privasi", "syarat dan ketentuan", "penggunaan cookie",
        "ikuti kami", "bagikan artikel ini", "tertarik dengan artikel",
        // [BARU] Ditemukan dari kasus nyata OCBC yang TERNYATA masih lolos biarpun "alamat kantor
        // pusat" udah ada di daftar — lihat fix normalizeMarkerLine() di bawah buat akar masalah
        // sebenarnya (bukan daftar penanda yang kurang, tapi normalisasinya yang gak nyampe situ).
        // Tiga ini ditambah sekalian karena kepastian sama-sama muncul di kasus nyata itu.
        "produk dan layanan", "fitur lainnya", "hubungi kami", "ekosistem kami"
    )

    // [FIX] SEBELUMNYA: rawLine.trim().trimStart('#','*','-','•').trim().lowercase(). trimStart
    // berhenti di karakter PERTAMA yang gak ada di set-nya — spasi BUKAN anggota set itu, jadi
    // "### **Alamat Kantor Pusat**" (heading DIGABUNG bold, umum buat heading footer yang
    // di-emphasis) cuma kebuang "###"-nya doang: trimStart berhenti pas ketemu spasi SEBELUM
    // sempat nyentuh "**" yang nempel abis spasi itu. Hasilnya "**alamat kantor pusat**" —
    // startsWith("alamat kantor pusat") GAGAL, padahal markernya ADA di daftar. Dikonfirmasi
    // lewat simulasi: varian plain/bold-doang/heading-doang/bullet semua lolos di versi lama,
    // tapi heading+bold gabungan (persis kasus OCBC yang dilaporin) & bold+italic gabungan
    // berdua GAGAL kecocokan sama sekali. Fix: regex buang SEMUA run karakter dekorasi+spasi di
    // depan sekaligus (gak berhenti di spasi), baru trim sisa "**"/"__" yang nyangkut di ujung.
    private val LEADING_MARKDOWN_DECORATION = Regex("^[#*\\-•\\s]+")

    private fun normalizeMarkerLine(rawLine: String): String =
        rawLine.trim()
            .replace(LEADING_MARKDOWN_DECORATION, "")
            .trim('*', '_')
            .trim()
            .lowercase()

    private fun cutTrailingBoilerplate(text: String): String {
        val lines = text.lines()
        val idx = lines.indexOfFirst { rawLine ->
            val line = normalizeMarkerLine(rawLine)
            TRAILING_BOILERPLATE_MARKERS.any { marker ->
                if (!line.startsWith(marker)) return@any false
                // Penanda dianggap "berdiri sendiri" kalau: (a) itu doang isi barisnya, ATAU
                // (b) langsung disusul ":"/"?" (pola umum widget, mis. "Baca Juga: <daftar judul
                // artikel lain>" — SERING nyambung jadi satu baris panjang tanpa newline di hasil
                // ekstraksi Jina, jadi TIDAK bisa disyaratkan baris pendek keseluruhan), ATAU
                // (c) total barisnya emang pendek (≤60) biarpun gak diikuti tanda baca tertentu.
                val rest = line.removePrefix(marker)
                rest.isEmpty() || rest.startsWith(":") || rest.startsWith("?") || line.length <= 60
            }
        }
        return if (idx < 0) text else lines.subList(0, idx).joinToString("\n")
    }

    // ── 0) Pengecualian per-sumber ───────────────────────────────────────────────────────────
    /**
     * Registry aturan KHUSUS per-domain — buat situs dengan struktur "bagian yang gak perlu"
     * yang KONSISTEN & DIKENAL, di mana heuristik umum di bawah (link-density dkk, yang harus
     * aman buat SEMUA situs tak dikenal) kurang presisi atau sengaja gak cukup agresif. Beda
     * dari heuristik umum, aturan di sini boleh SPESIFIK & "berani" karena cuma nyala kalau host
     * URL sumbernya eksplisit cocok.
     *
     * Tambahin domain baru + aturannya di sini kalau nemu situs lain yang heuristik umum kurang
     * pas buat itu — BUKAN dengan ngoprek lagi heuristik umum di bawah (itu yang bikin siklus
     * "benerin situs A, situs B ikut kesenggol", persis yang kejadian pas kbbi.web.id vs
     * Wikipedia kemarin — density-by-length pas buat satu, salah tembak di yang lain).
     */
    private fun applyDomainRules(text: String, sourceUrl: String?): String {
        val host = sourceUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.lowercase() ?: return text
        return when {
            // Wikipedia BUKAN cuma satu situs — Wiktionary/Wikibooks/Wikisource/dst adalah
            // "sodara"-nya, sama-sama jalan di software MediaWiki yang SAMA PERSIS, termasuk
            // konvensi sitasi "↑" yang sama. Wikibooks & Wikiversity malah relevan langsung buat
            // "materi" (isinya literally buku teks/modul pembelajaran terbuka).
            WIKIMEDIA_HOSTS.any { host.endsWith(it) } -> cutWikipediaReferences(text)
            // Brainly.co.id (Q&A tugas sekolah — sangat umum dipakai pelajar Indonesia).
            // Dikonfirmasi lewat pencarian beberapa halaman beneran (bukan tebakan): SETIAP
            // halaman konsisten punya (1) placeholder "Iklan" berulang nyempil di tengah konten
            // (slot iklan yang gak ke-filter readability), (2) section "Pertanyaan baru di
            // [Mapel]" di akhir yang isinya daftar pertanyaan LAIN yang GAK ADA HUBUNGANNYA —
            // widget "mungkin kamu suka", bukan bagian dari jawaban yang lagi diimpor.
            host.endsWith("brainly.co.id") -> cleanBrainly(text)
            else -> text
        }
    }

    private val WIKIMEDIA_HOSTS = listOf(
        "wikipedia.org", "wiktionary.org", "wikibooks.org",
        "wikisource.org", "wikiquote.org", "wikiversity.org", "wikinews.org"
    )

    // Konvensi MediaWiki Cite extension (SAMA di semua edisi bahasa Wikipedia, bukan cuma yang
    // Indonesia): tiap item daftar rujukan SELALU diawali link dengan teks PERSIS "↑" menuju
    // anchor "#cite_ref-.../#cite_note-...". Ini dipakai sebagai penanda, BUKAN judul section
    // ("Referensi"/"Catatan kaki"/dst) — dikonfirmasi lewat fetch langsung kalau judul section
    // itu SERING HILANG pas diekstrak (nyambung langsung dari paragraf terakhir ke daftar
    // rujukan tanpa heading pemisah), jadi gak reliable buat dijadiin patokan potong.
    private val WIKI_CITE_MARKER = Regex("\\[↑]\\([^)]*cite_(?:ref|note)-")

    private fun cutWikipediaReferences(text: String): String {
        val match = WIKI_CITE_MARKER.find(text) ?: return text
        // Potong di AWAL BARIS yang memuat marker (bukan persis di posisi match) — kalau
        // dipotong pas di tengah baris, nomor list-nya ("1. ") nyangkut jadi baris ganjil tanpa
        // isi di baliknya.
        val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it == -1) 0 else it + 1 }
        return text.substring(0, lineStart)
    }

    private fun cleanBrainly(text: String): String {
        val lines = text.lines()
        // "### Pertanyaan baru di [Mapel]" SELALU jadi widget "pertanyaan lain" di akhir
        // halaman — begitu ketemu, semua baris SETELAHNYA ikut kepotong (bukan cuma baris
        // heading-nya doang), karena isinya emang bukan bagian dari jawaban yang lagi diimpor.
        val cutIdx = lines.indexOfFirst {
            it.trim().trimStart('#').trim().startsWith("Pertanyaan baru di", ignoreCase = true)
        }
        val trimmed = if (cutIdx >= 0) lines.subList(0, cutIdx) else lines
        return trimmed
            .filterNot { it.trim().equals("Iklan", ignoreCase = true) }
            .filterNot { it.trim().equals("Sebelumnya", ignoreCase = true) || it.trim().equals("Berikutnya", ignoreCase = true) }
            .joinToString("\n")
    }

    // ── 1) Buang blok yang mayoritas isinya link (nav/daftar populer/dst) ──────────────────
    private fun stripLinkHeavyBlocks(text: String): String {
        val blocks = text.split(Regex("\n\\s*\n"))
        return blocks.filterNot { isLinkHeavy(it) }.joinToString("\n\n")
    }

    /**
     * [DIREVISI] Deteksi blok navigasi/daftar-rujukan pakai sinyal "rasio kata penghubung",
     * BUKAN lagi rasio panjang-teks-di-dalam-link. Versi SEBELUMNYA (density by char length)
     * ketauan SALAH TEMBAK pas dites pakai artikel Wikipedia asli: gaya tulisan Wikipedia itu
     * SENDIRI padat link internal di tengah prosa yang sah (mis. "Penerapan penting teori
     * kuantum termasuk [kimia kuantum](...), [optik kuantum](...), ... dan [laser](...)" —
     * rasio panjangnya ketauan 0.51, DI ATAS ambang density lama 0.5, jadi paragraf ISI ARTIKEL
     * ASLI ikut kebuang dikira navigasi. Dikonfirmasi lewat simulasi sebelum revisi ini ditulis.
     *
     * Sinyal barunya: buang SEMUA link (teks+url) sepenuhnya dari blok, sisain yang di LUAR
     * link doang, lalu hitung berapa banyak "kata" (>=2 huruf beruntun) yang TERSISA. Daftar
     * navigasi murni ("kata1, kata2, kata3, ...") nyaris NOL kata penghubung di luar link-nya
     * sendiri — cuma koma/pemisah. Prosa beneran (SEBERAPA PUN padat link-nya) SELALU punya
     * kata kerja/konjungsi/artikel penghubung ("termasuk", "dan", "seperti", "yang", "adalah")
     * yang jumlahnya sebanding sama jumlah link-nya. Rasio kata-per-link yang RENDAH → navigasi;
     * yang TINGGI (walau link-nya banyak) → prosa sah, dipertahankan.
     */
    private fun isLinkHeavy(block: String, minLinks: Int = 4, minWordRatio: Double = 0.25): Boolean {
        val links = MD_LINK.findAll(block).toList()
        if (links.size < minLinks) return false
        val nonLinkText = MD_LINK.replace(block, "")
        val words = WORD.findAll(nonLinkText).count()
        return words.toDouble() / links.size < minWordRatio
    }

    // ── 2) Rapikan penomoran + sub-penomoran dari indentasi ─────────────────────────────────
    /**
     * Indent-stack: pop dulu semua level yang LEBIH DALAM dari baris sekarang, baru cocokkan
     * level yang sama (lanjutin counter-nya) atau push level baru (mulai counter dari 1). Sub-
     * nomor ("1.1", "2.3") dibentuk dari GABUNGAN counter tiap level di stack, ditulis sebagai
     * AWALAN TEKS BIASA rata kiri (BUKAN pakai indentasi) — editor app ini cuma mendukung daftar
     * satu tingkat, jadi hierarki direpresentasikan lewat labelnya sendiri ("2.1. ..."), bukan
     * lewat indentasi/sub-daftar bersarang.
     *
     * Dua baris kosong berturut-turut (bukan cuma satu — satu baris kosong di antara item list
     * masih dianggap list yang sama) MENUTUP konteks list saat ini (counter di-reset), begitu
     * juga baris non-kosong yang BUKAN item bernomor.
     */
    fun fixListNumbering(text: String): String {
        val lines = text.split("\n")
        val out = StringBuilder()
        val stack = mutableListOf<IntArray>() // [indentWidth, counter] per level
        var blankRun = 0
        for (line in lines) {
            val m = NUMBERED_ITEM.matchEntire(line)
            if (m != null) {
                val indent = m.groupValues[1].length
                val content = m.groupValues[3]
                while (stack.isNotEmpty() && indent < stack.last()[0]) stack.removeAt(stack.lastIndex)
                if (stack.isNotEmpty() && stack.last()[0] == indent) {
                    stack.last()[1] += 1
                } else {
                    stack.add(intArrayOf(indent, 1))
                }
                val label = stack.joinToString(".") { it[1].toString() }
                out.append(label).append(". ").append(content).append('\n')
                blankRun = 0
            } else if (line.isBlank()) {
                blankRun++
                out.append(line).append('\n')
                if (blankRun >= 2) stack.clear()
            } else {
                stack.clear()
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    // ── 3) Rapikan baris kosong berlebih (sisa "lubang" dari blok yang dibuang, dst) ────────
    private fun collapseBlankLines(text: String): String =
        text.replace(Regex("\n{3,}"), "\n\n").lines().joinToString("\n") { it.trimEnd() }
}
