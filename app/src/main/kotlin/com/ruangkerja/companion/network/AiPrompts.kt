package com.ruangkerja.companion.network

import com.ruangkerja.companion.util.MindMapCategories

/**
 * Prompt & instruksi sistem yang dipakai bersama oleh SEMUA backend AI (Gemini, Groq,
 * Cerebras, custom). Disatukan di sini supaya hasilnya — terutama bentuk JSON mind map
 * dan flashcard yang di-parse balik di AppViewModel — tetap konsisten nggak peduli
 * provider mana yang lagi aktif. Isi persis sama dengan prompt asli di GeminiService.
 */
object AiPrompts {
    // ── Aturan format markdown SPESIFIK app ini — dipisah jadi konstanta sendiri biar bisa
    // dipakai bareng di SYSTEM (summarize/chat) MAUPUN textAction (tombol Lanjutkan/Perluas/
    // dkk di toolbar), yang sebelumnya masing-masing punya system prompt SENDIRI-SENDIRI dan
    // textAction gak nyebut aturan markdown SAMA SEKALI — makanya hasilnya suka pakai "###"
    // (H3, gak didukung toolbar) atau sintaks lain yang gak match kemampuan editor beneran.
    // Isi di bawah ini DISESUAIN PERSIS ke apa yang parser editor Companion dukung (bukan
    // markdown umum) — kalau editor-nya berubah kemampuan, ini yang perlu diupdate bareng. ──
    private val MARKDOWN_RULES = """
Format Markdown yang didukung editor app ini (JANGAN pakai di luar daftar ini):
- Heading: HANYA "# " (utama) dan "## " (sub) — JANGAN PERNAH pakai "###" atau lebih dalam, editor cuma punya 2 level
- STRUKTUR heading harus BERJENJANG, bukan rata semua: kalau materi punya topik-topik besar yang masing-masing punya beberapa sub-bagian di dalamnya, topik besar itu "# ", dan sub-bagian DI DALAMNYA "## " — JANGAN jadiin semua section level yang sama (misal semua "# ", atau semua "## ") padahal sebagian sebenarnya anak dari sebagian yang lain
- Kalau materi ada penomoran/pelabelan section (1/2/3, A/B/C, dst), tulis label itu SEBAGAI BAGIAN TEKS heading-nya sendiri (contoh: "# 1. Gerak Lurus" atau "# A. Jarak dan Perpindahan") — bukan elemen terpisah, editor gak punya fitur penomoran otomatis buat heading
- Tebal: **teks**
- Miring: *teks*
- Garis bawah: <u>teks</u>
- Coret: ~~teks~~
- Kode PENDEK dalam kalimat: `teks` — JANGAN pakai blok kode multi-baris (```), tidak dirender rapi
- Daftar: "- item" (satu tingkat saja, JANGAN pakai sub-daftar/indentasi)
- Daftar bernomor: "1. item" dst (nomornya otomatis dirapikan aplikasi)
- JANGAN pakai sintaks gambar markdown ![]() — gambar di app ini cuma bisa disisipkan lewat pengambil gambar bawaan, bukan dari teks AI
    """.trimIndent()

    val SYSTEM = """
Kamu adalah alat bantu dalam aplikasi Ruang Kerja Ringkas.
Jawab dengan jelas, ringkas, dan akurat dalam Bahasa Indonesia.
$MARKDOWN_RULES
Tone: tenang dan informasional. Tidak perlu sapaan atau persona tertentu.
Jika ada konteks materi yang diberikan, gunakan sebagai referensi utama.
    """.trimIndent()

    fun summarize(text: String) = "Rangkum materi berikut dengan format Markdown terstruktur:\n\n$text"

    fun chatSystem(ctx: String?): String =
        if (ctx != null) "$SYSTEM\n\nKONTEKS MATERI BAB INI:\n${ctx.take(600)}" else SYSTEM

    // Kosakata kategori & hubungan diambil dari MindMapCategories — satu sumber untuk prompt, pemilih kategori, dan warna.
    private val VOCAB_TEXT: String get() = MindMapCategories.VOCABULARY.joinToString(", ")
    private val RELATION_TEXT: String get() = MindMapCategories.RELATIONS.joinToString(", ")

    /**
     * [DITULIS ULANG sesuai dokumen spek "Mind Map Generation — Adaptive Knowledge Structure"] Dulu prompt ini memaksa 3 tingkat
     * (root → branch → children) dan 5 kategori tetap. Sekarang AI diminta membangun STRUKTUR PENGETAHUAN materi: kedalaman &
     * jumlah cabang adaptif (tanpa angka tetap), kriteria kapan sebuah node diperdalam/dihentikan, tanpa kedalaman palsu/terlalu
     * datar/terlalu dalam, pengelompokan menurut makna, kategori dari kosakata (bukan template), urutan untuk proses, rumus,
     * studi kasus, linking phrase spesifik, pemeriksaan kedua khusus cross-link, KESETIAAN KE SUMBER (tidak mengarang), dan
     * pemeriksaan akhir. Setiap node membawa id pendek ("n0", "n1", …) hanya supaya cross-link bisa menunjuknya; id itu diganti
     * UUID saat dibaca (MindMapCodec.decodeAi). Aturan yang bisa ditegakkan tanpa AI (node berulang digabung, label hubungan
     * generik dikosongkan) dijaga lagi di MindMapOps.sanitize / MindMapCategories.cleanRelation.
     */
    fun mindMap(text: String) = """
Bangun mind map dari materi di bawah. Tujuannya BUKAN ringkasan berbentuk pohon, melainkan representasi visual STRUKTUR PENGETAHUAN materi: konsep utama, konsep turunan, hierarki, komponen, karakteristik, proses, hubungan antar-konsep, contoh, penerapan, sebab-akibat, dan hubungan lintas cabang. Materi yang menentukan strukturnya — JANGAN memakai template yang sama untuk semua materi.

CARA BEKERJA (di dalam kepala, jangan ditulis): pahami seluruh materi → tentukan konsep utama dan kelompok konsep → bangun hierarki dari umum ke spesifik → untuk SETIAP node tanyakan "masih punya sub-konsep penting yang ADA di materi?" (ya → pecah, tidak → node akhir) → tandai proses/urutan, sebab-akibat, klasifikasi, dan komponen → taruh contoh dan studi kasus → lakukan pemeriksaan KEDUA khusus untuk mencari cross-link → buang node redundan → validasi.

KEDALAMAN & CABANG ADAPTIF
- Tidak ada batas kedalaman atau jumlah cabang yang universal. Dilarang memakai aturan seperti "selalu 3 level", "maksimal 5 level", atau "tiap cabang sama banyak anaknya". Satu node boleh punya 1, 2, 5, 10 anak atau lebih; kedalaman tiap cabang boleh berbeda (jangan memaksa "Definisi" sedalam "Persamaan Akuntansi").
- Perdalam sebuah node hanya jika turunannya bermakna berbeda, memperjelas induknya, ADA di materi, dan penting untuk memahami induk (bagian, jenis, karakteristik, proses, akibat, penerapan, atau rincian penting). Berhenti jika konsep sudah cukup spesifik, tidak ada sub-konsep penting, informasi berikutnya hanya kalimat penjelasan, atau pemecahan lanjut hanya menghasilkan node redundan. JANGAN berhenti hanya karena sudah mencapai jumlah level tertentu.
- JANGAN membuat kedalaman palsu (Akuntansi › Pengertian › Definisi › Makna › Penjelasan): node yang hanya mengulang informasi digabung.
- JANGAN terlalu datar (Root berisi 8 topik tanpa turunan padahal tiap topik punya konsep penting) dan JANGAN terlalu dalam (jangan memecah kalimat menjadi rantai node seperti "Pendapatan › merupakan › peningkatan › manfaat…").
- Bila banyak konsep berada langsung di bawah satu node, KELOMPOKKAN menurut hubungan makna (mis. Akuntansi › Unsur Keuangan › Aset › Kas; Pencatatan › Jurnal; Pelaporan), bukan sekadar membatasi jumlah.
- KELENGKAPAN: teks di bawah bisa berisi RINGKASAN (kerangka) dan MATERI SUMBER (rincian) — keduanya sumber yang sah; utamakan rincian dari materi sumber. Uraikan SETIAP istilah, komponen, jenis, klasifikasi, tahap, rumus/variabel, sebab-akibat, dan contoh yang DISEBUT materi sebagai node di bawah konsep yang tepat; jangan berhenti di tingkat atas selama materi masih menyebut rinciannya (tetap: jangan mengarang, jangan memecah kalimat).
- Materi panjang: identifikasi domain utama → bangun hierarki tiap domain sesuai kompleksitasnya → cari cross-link antar-domain; jangan diratakan menjadi satu hierarki dangkal. Materi pendek: struktur sederhana dan JANGAN mengarang cabang tambahan.

KATEGORI (field "category", opsional)
Pilihan: $VOCAB_TEXT. Ini pilihan, bukan kewajiban: jangan membuat semua materi berpola Definisi → Manfaat → Contoh → Risiko; pilih menurut sifat materi, dan kosongkan bila tidak ada yang cocok.

JENIS BAGIAN
- Konsep (gagasan utama, boleh punya turunan) ≠ detail (karakteristik, komponen, jenis, proses, sifat) ≠ contoh (kasus konkret, biasanya node akhir, taruh pada konsep yang diperjelasnya).
- PROSES/PROSEDUR: pertahankan urutan aslinya dan beri "ordered": true pada node induknya — jangan diubah menjadi daftar kategori biasa.
- RUMUS/PERHITUNGAN: Konsep › Rumus, Variabel/Komponen, Arti Variabel, Prosedur Perhitungan, Contoh, Interpretasi Hasil. Rumus yang menjadi konsep utama jangan dijadikan teks biasa.
- STUDI KASUS: node khusus "Studi Kasus" yang menghubungkan konsep abstrak dengan situasi konkret.

HUBUNGAN
- Hubungan induk-anak hanya untuk: bagian dari, jenis dari, rincian dari, tahap dari. Hubungan bermakna lain → tulis di "relation" node (hubungan ke induknya, opsional) atau jadikan cross-link.
- Linking phrase harus spesifik, mis.: $RELATION_TEXT. JANGAN memakai label generik ("terkait", "berhubungan", "link") bila hubungannya bisa dijelaskan lebih spesifik.
- "links" = CROSS-LINK {"from":id,"to":id,"relation":"..."} antar-cabang BERBEDA; hanya bila valid, bermakna, membantu pemahaman, dan DIDUKUNG materi. Periksa: konsep di cabang A menjelaskan / menyebabkan perubahan pada / menjadi dasar bagi konsep di cabang B? Proses memakai konsep dari cabang lain? Contoh di satu cabang memperjelas konsep cabang lain? Ada sebab-akibat lintas cabang? JANGAN membuat link demi menambah jumlah.

KESETIAAN KE SUMBER
JANGAN mengarang definisi, fakta, contoh, kategori, klasifikasi, atau hubungan sebab-akibat yang tidak ada atau tidak dijelaskan di materi. Hubungan yang masuk akal tetapi tidak didukung materi jangan dimasukkan sebagai fakta.

PRIORITAS
Sederhana tetapi akurat > kompleks dengan node buatan. Pertahankan kedalaman bila materi memang membutuhkannya. Lebih sedikit node dengan hubungan bermakna > banyak node tanpa hubungan. JANGAN mengoptimalkan jumlah node, level, cabang, kategori, atau cross-link; optimalkan ketepatan, kelengkapan, struktur, kedalaman yang relevan, hubungan antar-konsep, dan keterbacaan. Cakup semua konsep utama dan konsep pendukung penting materi, bukan hanya bagian awal.

PEMERIKSAAN AKHIR (di dalam kepala): hierarki umum → spesifik? ada konsep penting yang berhenti terlalu cepat? ada tingkat yang hanya mengulang? rincian yang banyak sudah dikelompokkan? kategori sesuai sifat materi? hubungan masuk akal? ada cross-link penting yang terlewat? contoh di konsep yang tepat? semua node didukung materi? ada node redundan? struktur masih terbaca saat divisualisasikan? konsep utama dan pendukung sudah terwakili?

FORMAT: balas HANYA JSON valid (tanpa markdown atau teks lain). Label node = istilah/frasa singkat (maksimal ±6 kata), BUKAN kalimat; penjelasan 1 kalimat yang berasal dari materi boleh ditaruh di "note". Setiap node punya "id" unik pendek ("n0", "n1", ...). Field selain id, label, dan children boleh dihilangkan bila kosong:
{"root":{"id":"n0","label":"Judul","children":[{"id":"n1","label":"Cabang","category":"Definisi","relation":"","ordered":false,"note":"","children":[]}]},"links":[{"from":"n3","to":"n9","relation":"memengaruhi"}]}

Materi: ${text.take(15000)}
    """.trimIndent()
    const val MINDMAP_SYS = "Respond only with valid JSON, no markdown."

    /**
     * Tombol "Lengkapi dengan AI": struktur (label & hierarki) sudah FINAL di sisi Kotlin — dari algoritma, AI, atau hasil edit
     * pengguna — dan AI hanya melengkapi METADATA: kategori, urutan, hubungan, dan cross-link, tanpa mengarang. Hasil digabung
     * lewat id (bukan indeks) di MindMapOps.enrich, jadi tidak ada risiko salah pasang kalau urutan balasan berbeda.
     */
    fun enrichMindMap(structure: String, maxLinks: Int) = """
Berikut struktur mind map yang SUDAH FINAL (tiap baris "id: label", indentasi = tingkat, [kategori] = sudah ada). JANGAN mengubah label, hierarki, atau jumlah node, dan JANGAN mengarang: kamu hanya melengkapi metadata yang bisa dipastikan dari label dan strukturnya.
1. "category" (opsional) untuk node yang belum punya, dari pilihan: $VOCAB_TEXT. Ini pilihan, bukan kewajiban: tidak harus dipakai semuanya, kosongkan bila tidak ada yang cocok.
2. "ordered": true pada node yang anak-anaknya adalah langkah/tahapan yang urutannya penting.
3. "relation" (opsional): hubungan node ke induknya bila BUKAN sekadar bagian dari / jenis dari / rincian dari / tahap dari. Pakai linking phrase spesifik (mis. $RELATION_TEXT); JANGAN label generik ("terkait", "berhubungan", "link").
4. "links": cross-link antara dua node dari cabang BERBEDA, format {"from":id,"to":id,"relation":"memengaruhi"}; hanya bila valid, bermakna, membantu pemahaman, dan nyata terbaca dari struktur. Periksa: konsep di cabang A menjelaskan / menyebabkan perubahan pada / menjadi dasar bagi konsep di cabang B? Proses memakai konsep cabang lain? Contoh memperjelas konsep cabang lain? Boleh kosong, paling banyak $maxLinks, dan jangan membuat link demi menambah jumlah.
Balas HANYA JSON valid: {"nodes":[{"id":"n3","category":"Definisi","relation":"","ordered":false}],"links":[{"from":"n5","to":"n9","relation":"memengaruhi"}]}
Cantumkan hanya node yang kamu beri metadata.

STRUKTUR:
$structure
    """.trimIndent()
    const val ENRICH_SYS = "Respond only with valid JSON, no markdown. Jangan ubah label, hierarki, atau jumlah node."

    /** Return: prompt ke user + instruksi sistem + budget maxTokens (dipisah karena tiap backend
     *  beda cara nyisipin system prompt & parameter call-nya). maxTokens SENGAJA disatuin di sini
     *  (bukan di-hardcode masing-masing di GeminiService/OpenAiCompatibleService) — pelajaran
     *  yang sama kayak kenapa MARKDOWN_RULES disatuin: dulu tiap backend punya aturan/parameter
     *  sendiri-sendiri yang gampang saling gak sinkron. */
    fun textAction(action: String, text: String): Triple<String, String, Int> {
        val instruction = when (action) {
            "continue" -> "Lanjutkan tulisan berikut dengan gaya dan konteks yang sama, tambahkan sekitar 2-4 kalimat baru. Balas HANYA lanjutannya saja, JANGAN mengulang teks asli."
            "rewrite" -> "Tulis ulang teks berikut supaya lebih jelas dan enak dibaca, makna & panjang kira-kira tetap sama."
            "expand" -> "Perluas teks berikut dengan detail dan contoh tambahan yang relevan."
            "shorten" -> "Ringkas teks berikut jadi lebih singkat, pertahankan poin-poin pentingnya."
            "improve" -> "Perbaiki teks berikut supaya lebih jelas, rapi, dan enak dibaca."
            "tone" -> "Tulis ulang teks berikut dengan nada yang lebih formal/profesional."
            // [BARU] Dipakai AppViewModel.importFromUrl() — rapiin STRUKTUR hasil ekstraksi Jina
            // Reader doang, BUKAN ringkasan/parafrase. Instruksinya SENGAJA dibatasi ke DUA hal
            // konkret (nomor/sub-nomor, tabel) dan eksplisit melarang perubahan isi, biar hasil
            // impor URL tetap presisi ke sumber aslinya — bukan versi "ditulis ulang AI".
            "structureCleanup" -> "Rapikan STRUKTUR teks hasil ekstraksi halaman web berikut, TANPA mengubah isi/detail/kata-katanya sama sekali. Yang BOLEH kamu lakukan HANYA dua hal: (1) tambahkan atau rapikan penomoran & sub-penomoran kalau ada bagian yang berjenjang/berurutan — tulis pakai format \"1. \", \"1.1. \", \"2. \" dst sebagai AWALAN TEKS BIASA di masing-masing baris (JANGAN pakai indentasi atau sub-daftar, editor cuma mendukung daftar satu tingkat rata kiri); (2) kalau ada data yang jelas-jelas berbentuk baris & kolom tapi saat ini tergencet jadi paragraf biasa, ubah jadi tabel Markdown (\"| Kolom1 | Kolom2 |\" diikuti baris pemisah \"|---|---|\"). JANGAN meringkas, JANGAN memparafrase kalimat, JANGAN menghapus atau menambahkan informasi apa pun, JANGAN menyisipkan komentar/interpretasi tambahan. Kalau strukturnya sudah rapi dan tidak ada yang perlu diubah, balas teksnya apa adanya."
            else -> "Perbaiki teks berikut."
        }
        // structureCleanup beroperasi di HASIL IMPOR UTUH (bisa satu artikel penuh) — beda dari
        // action lain di atas yang beroperasi di potongan teks yang diseleksi user lewat toolbar
        // editor (biasanya jauh lebih pendek dari 3000 karakter). Kalau dipaksa pakai limit yang
        // sama, artikel panjang bakal keptotong SEBELUM sempat dirapikan — bertentangan langsung
        // sama permintaan "jangan banyak ubah detail" (detail ilang duluan kalau separuh dokumen
        // gak pernah kelihat AI). 12000 dipilih lebih longgar dari baseline 6000 (mindMap/
        // flashcards) karena tugas ini FULL-FIDELITY (bukan meringkas), jadi makin banyak yang
        // "dilihat" AI makin presisi hasilnya — tapi tetap dibatasi (bukan tanpa batas) buat jaga
        // durasi request & kuota API key tetap wajar.
        val limit = if (action == "structureCleanup") 12000 else 3000
        val prompt = "$instruction\n\nTeks:\n${text.take(limit)}"
        val sys = "Balas HANYA dengan hasil teksnya saja — tanpa basa-basi pembuka/penutup, tanpa tanda kutip pembungkus, tanpa markdown code fence.\n$MARKDOWN_RULES"
        // [FIX NYATA] SEBELUMNYA maxTokens buat SEMUA action di-hardcode 2000 di kedua backend
        // (GeminiService & OpenAiCompatibleService), gak berubah pas structureCleanup ditambah.
        // Padahal structureCleanup HARUS ngebalikin isi HAMPIR SEPANJANG input (instruksinya
        // eksplisit "jangan hapus isi", beda dari continue/shorten/dkk yang emang boleh lebih
        // pendek dari input) — buat dokumen hasil impor yang lumayan panjang, 2000 token gak
        // cukup, balasan AI-nya kepotong DI TENGAH KALIMAT sebelum sempat nyelesein. Ini PERSIS
        // gejala yang dilaporin: hasil impor kepotong "...dik" di tengah kata. 8000 dipilih biar
        // ada headroom yang cukup lebar di atas limit input 12000 char (~3-4 karakter per token
        // buat teks Indonesia, jadi 12000 char kira-kira butuh ~3000-4000 token buat direproduksi
        // utuh + tambahan karakter dari sintaks nomor/tabel yang ditambahkan).
        val maxTokens = if (action == "structureCleanup") 8000 else 2000
        return Triple(prompt, sys, maxTokens)
    }

    // Truncation SEBELUMNYA 800 char — jauh lebih kecil dari mindMap() (6000) padahal butuh
    // "melihat" materi yang sama, dan gak ada alasan token/budget di baliknya (maxOutputTokens/
    // max_tokens ngatur panjang JAWABAN, bukan input). Akibatnya materi >800 karakter (hampir
    // selalu, buat materi belajar beneran) bikin flashcard cuma nyerap bagian AWAL doang, 5
    // pertanyaan numpuk di situ-situ aja. Disamain ke 6000 kayak mindMap(), yang sama-sama butuh
    // nyakup SELURUH materi biar 5 flashcard-nya nyebar, bukan cuma dari paragraf pembuka.
    fun flashcards(text: String) = "Buat 5 flashcard yang menyebar mencakup keseluruhan materi (jangan cuma dari bagian awal). Balas HANYA JSON array:\n[{\"front\":\"pertanyaan\",\"back\":\"jawaban\"}]\nMateri: ${text.take(6000)}"
    const val FLASHCARDS_SYS = "Respond only with valid JSON array, no markdown."

    fun chaptersFromTopic(topic: String, count: Int?) = """
Susun struktur bab pembelajaran untuk topik berikut, dari dasar ke lanjut, urutan yang logis.
${if (count != null) "Buat TEPAT $count bab." else "Tentukan sendiri jumlah bab yang wajar untuk topik ini (biasanya 5-10)."}
Tiap bab: judul singkat dan jelas, plus deskripsi satu kalimat tentang cakupannya.
Balas HANYA JSON array, tanpa markdown, dengan format persis:
[{"title":"Judul Bab","description":"Deskripsi singkat"}]
Topik: ${topic.take(300)}
    """.trimIndent()

    fun chaptersFromText(text: String) = """
Teks berikut hasil scan/OCR dari daftar isi, silabus, atau kurikulum — mungkin berantakan atau ada typo karena OCR.
Ekstrak jadi daftar bab yang bersih dan berurutan sesuai urutan aslinya, perbaiki typo OCR yang jelas terlihat.
Balas HANYA JSON array, tanpa markdown, dengan format persis:
[{"title":"Judul Bab","description":"Deskripsi singkat kalau tersedia, string kosong kalau tidak"}]
Teks: ${text.take(4000)}
    """.trimIndent()

    const val CHAPTERS_SYS = "Respond only with valid JSON array, no markdown."
}
