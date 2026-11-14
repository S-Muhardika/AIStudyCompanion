# Mind Map RKR — pemetaan spek → implementasi

Sumber: dua dokumen spek dari William — "MIND MAP" (struktur & tampilan) dan "MIND MAP GENERATION — ADAPTIVE KNOWLEDGE
STRUCTURE" (aturan generasi), keduanya 5 Okt 2026. Model lama (root → cabang → anak, 5 tipe tetap) dihapus karena
bertentangan langsung dengan spek ("tidak ada jumlah level/cabang tetap", "kategori tidak boleh dikunci").

## Model (`data/db/MindMap.kt`)
`MindMapNode(id, label, category, children, ordered, relation, note)` — pohon BEBAS kedalaman.
`MindMapLink(from, to, relation)` — cross-link antar id node. `MindMapData(root, links)`.
Disimpan sebagai JSON v2 di `Chapter.mindMapJson` (kolom teks yang sama, tidak ada perubahan skema/sinkron).
Peta format lama tetap terbaca (`MindMapCodec.decode` mengubahnya ke pohon; tipe lama → kategori baru).

## Spek → kode
| Spek | Implementasi |
|---|---|
| Root = konsep utama; cabang utama = domain terbesar | prompt `AiPrompts.mindMap`; jalur algoritma: judul `#` = cabang utama |
| Kedalaman adaptif, tanpa angka tetap | pohon rekursif; parser menjaga hierarki sedalam teksnya; prompt melarang kedalaman tetap |
| Setiap tingkat memberi informasi baru; jangan tingkat palsu | prompt aturan 4; `sanitize(mergeRepeats)` menggabungkan anak berlabel sama dengan induknya (impor AI/parser) |
| Jumlah cabang adaptif; kelompokkan menurut makna | prompt aturan 3 (tanpa jumlah wajib) |
| Kategori = kosakata, bukan template | `MindMapCategories.VOCABULARY` (saran), teks bebas diterima, boleh kosong; legenda hanya memuat kategori yang dipakai |
| Hubungan antar-node (memiliki, menyebabkan, …) | `MindMapNode.relation` (label di garis) + `MindMapCategories.RELATIONS` |
| Cross-link yang bermakna | `MindMapData.links` — garis putus-putus berpanah + label; AI dibatasi `aiLinkCap`, edit manual tidak dibatasi |
| Contoh = node akhir; studi kasus = cabang sendiri | prompt; kategori "Contoh"/"Studi Kasus" |
| Proses: urutan lebih penting daripada kategori | `ordered` → anak bernomor; parser menandai daftar bernomor; tombol Naik/Turun di mode edit |
| Materi berbeda → struktur berbeda | tidak ada template; prompt melarangnya |
| Zoom, collapse/expand, fokus cabang, navigasi level, filter kategori, detail node | pinch-zoom + bar zoom; chevron ciut/buka + "Ciutkan/Buka semua" (hanya peta > 80 node yang otomatis diciutkan, mulai kedalaman 3); "Fokus ke cabang ini" + breadcrumb per tingkat; legenda (dropdown yang bisa dikecilkan) = filter; ketuk node = lembar detail |

## Spek generasi → kode (dokumen kedua)
| Aturan | Implementasi |
|---|---|
| Materi menentukan struktur; tanpa template | `AiPrompts.mindMap` (tiap materi dianalisis dulu; pola Definisi→Manfaat→Contoh→Risiko dilarang) |
| Kapan memperdalam / berhenti (ada sub-konsep penting di materi? makna berbeda? redundan?) | prompt bagian "KEDALAMAN & CABANG ADAPTIF" memuat kedua daftar kriteria |
| Kedalaman palsu / terlalu datar / terlalu dalam | prompt + `MindMapOps.mergeRepeatsDeep` (anak berlabel sama dengan induk naik; saudara kembar digabung) |
| Pengelompokan menurut hubungan makna | prompt (contoh Unsur Keuangan › Aset › Kas) |
| Kategori dinamis (kosakata 40 istilah, termasuk **Variabel**) | `MindMapCategories.VOCABULARY`; boleh kosong/bebas |
| Proses mempertahankan urutan; rumus; studi kasus | `ordered` + prompt (struktur Rumus › Variabel › Arti Variabel › Prosedur › Contoh › Interpretasi) |
| Hubungan eksplisit & linking phrase spesifik, bukan "terkait/berhubungan/link" | `MindMapCategories.RELATIONS` (tanpa frasa generik) + `cleanRelation` mengosongkan label generik dari AI |
| Cross-link lewat pemeriksaan kedua (6 pertanyaan) | prompt "HUBUNGAN"; hanya antar-cabang, didukung materi; AI dibatasi `aiLinkCap` |
| JANGAN membuat informasi baru | prompt "KESETIAAN KE SUMBER"; parser tidak mengarang (paragraf → `note`); "Lengkapi dengan AI" tidak mengubah label/hierarki |
| Materi panjang (domain → hierarki → cross-link) & pendek (jangan mengarang cabang) | prompt |
| Validasi 12 poin & 15 langkah | prompt "CARA BEKERJA" + "PEMERIKSAAN AKHIR" (dijalankan model); yang bisa deterministik dijaga `sanitize` |

## Batas teknis (bukan batas struktur pengetahuan)
`MAX_DEPTH = 12` (turunan di bawahnya dinaikkan sebagai anak langsung, tidak dibuang), `MAX_NODES = 400`,
label ≤ 160 huruf. Melindungi tampilan dari data patologis; materi yang lebih dalam dipecah per cabang.

## Alur data
- **Algoritma** (`MindMapParser`): ringkasan/outline → pohon, offline. Paragraf di bawah judul menjadi `note`, bukan node.
  Kategori hanya ditebak dari awalan label (`MindMapCategories.infer`) dan tidak pernah dipaksa.
- **AI penuh** (`generateMindMap`): bahan baku = ringkasan bab (kerangka) + materi sumber (rincian) lewat `MindMapSource`
  (±13 ribu huruf, jatah materi dibagi adil); lalu `MindMapCodec.decodeAi` (id sementara → UUID, link dipetakan; keluaran terpotong
  diselamatkan oleh `repairTruncated`). Batas keluaran 6000 token.
- **Lengkapi dengan AI** (`enrichMindMapWithAI`): struktur final, AI hanya menambah kategori/urutan/hubungan/link;
  digabung per id dan hanya mengisi yang kosong (hasil edit pengguna tidak ditimpa).

## Sub-node bersarang
Setiap node boleh punya sub-node, di tingkat berapa pun: ketuk node → "Sub-node" (di lembar detail maupun lembar edit, yang
sekarang menaruh blok Struktur di atas). Batas teknis 12 tingkat (turunan di bawahnya diratakan, tidak dibuang).

## Yang diverifikasi lewat simulasi Python (tanpa kompiler)
Parser (10 skenario), codec (v2, format lama, id & link AI, JSON terpotong di setiap titik potong, sanitasi),
operasi pohon (hapus, geser, link, filter, ciut otomatis), dan tata letak (3000 pohon acak: tanpa tumpang tindih).
