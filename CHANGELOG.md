# Changelog RKR

Log perubahan kode per tanggal. Dibikin mulai audit 11 Okt 2026 — histori SEBELUM tanggal ini
TIDAK direkonstruksi di sini, tetap ada apa adanya di komentar inline masing-masing file
(`[FIX, tanggal]`, `[BARU]`, `[UBAH]`, dst.) dan di `app/src/main/kotlin/com/ruangkerja/companion/editor/ARSITEKTUR_EDITOR.md`
/ `.../util/MINDMAP.md` buat dua subsistem itu. File ini pelengkap, bukan pengganti, komentar
inline — komentar di titik perubahan tetap ditulis seperti biasa (alasan detail ada di sana); di
sini cuma ringkasan satu-dua baris per perubahan biar ada satu tempat buat liat histori sekilas.

Posisi file ini: ROOT project (sejajar `build.gradle`/`settings.gradle`), bukan di dalam source
`app/src/main/kotlin/...` — pindah ke sini 13 Okt setelah ketauan struktur root sebenarnya
(pengiriman 11 Okt sempat naruhnya salah tempat, di dalam folder source).

## 13 November 2026 — companion-v2.2.39-20261113

Redesain sistem + UI Diskusi — permintaan William: "ubah sistem sama UI diskusi, bisa pilih
level thinking, ada history message, sama kayak ai chat pada umumnya." Scope diklarifikasi lewat
3 pertanyaan sebelum dikerjakan: histori → **banyak thread bisa di-switch (kayak ChatGPT)**,
bukan cuma 1 percakapan permanen; level thinking → **reasoning effort 1 model (Gemini)**, bukan
pemilihan model/provider lain; server → **TIDAK disentuh**, cukup dilaporkan apa yang dibutuhkan.

1. **Histori multi-thread, persisten.** Diskusi dulu 1 percakapan per bab, murni di memori
   (`AppViewModel._chat`), ke-reset total tiap ganti bab/tutup app. Sekarang: entity baru
   `ChatThread` (judul otomatis dari pesan pertama) + `ChatMsg` diperluas (id/threadId/
   createdAt) — persisten via `LocalStorage` (`chat_threads.json`/`chat_messages.json`, pola
   sama seperti chunk RAG: lokal-only, SENGAJA tidak ikut `SyncManager`). Buka bab otomatis
   melanjutkan thread yang TERAKHIR dipakai (bukan selalu kosong). Thread baru baru benar-benar
   dibuat pas pesan pertama dikirim (lazy), bukan langsung pas tap "+ Percakapan baru" — biar
   gak numpuk thread kosong. Cascade delete (hapus bab/workspace) dan wipe-saat-ganti-akun ikut
   diperbarui supaya thread+pesan Diskusi tidak jadi yatim/bocor ke akun lain.
2. **Pilih level thinking Gemini (Default/Rendah/Sedang/Tinggi).** Tersimpan di SharedPreferences
   (`AiEngine.chatThinkingLevel`, lintas-sesi). Dialirkan ke `GeminiService` via
   `AiBackend.chat()`/`PromptedAiBackend.complete()` (parameter trailing opsional — 7 fitur AI
   lain sama sekali tidak berubah pemanggilannya). **HANYA berlaku ke model Gemini generasi 3+**
   (`thinkingConfig.thinkingLevel`) — dikonfirmasi langsung ke dokumentasi resmi
   `generateContent` (field ini eksplisit error kalau dikirim ke model Gemini 2.5), dan
   `GeminiService.MODELS`/ai-proxy `GEMINI_MODELS` mencampur generasi 2.5 & 3.x sebagai
   fallback satu sama lain — jadi SENGAJA dikosongkan tiap giliran fallback jatuh ke model 2.5,
   bukan dipaksa kirim & bikin request itu pasti error.
   - **Jalur BYOK (API key Gemini sendiri, Pengaturan > Lanjutan): LANGSUNG BERFUNGSI** —
     panggilan REST ke Gemini dari app ini sendiri yang membangun `thinkingConfig`.
   - **Jalur bawaan (lewat edge function `ai-proxy`, mayoritas pengguna): BELUM berfungsi.**
     Field `thinkingLevel` SUDAH dikirim ke server (`EdgeChatReq`), tapi `ai-proxy/index.ts`
     saat ini TIDAK membaca/meneruskannya ke Gemini sama sekali (dicek langsung ke source
     server yang live, bukan asumsi) — server SENGAJA TIDAK diubah sesuai permintaan eksplisit
     William ("jangan ubah kode server, beri tahu saja apa yang dibutuhkan"). Spek patch yang
     persis dibutuhkan (signature `callGemini`/`createProviderCalls`, dan catatan PENTING soal
     `generationConfig` yang harus dipindah ke DALAM loop model, bukan dibangun sekali di luar
     loop seperti sekarang) ditulis di `claude/keputusan-implementasi-ai-proxy-04nov2026.md` §16.
   - UI (chip "Thinking: …" di header Diskusi) tetap SELALU tampil apa adanya di kedua jalur —
     bukan disembunyikan pas mode bawaan aktif — disertai catatan kecil jujur soal cakupan ini
     di dropdown-nya (yang harus dihapus begitu server sudah diupdate).
3. **UI Diskusi**: baris header baru — kiri: dropdown riwayat thread (buka/pilih/hapus,
   + "Percakapan baru"), kanan: chip level thinking. `LazyColumn` pesan sekarang pakai
   `key = { it.id }` (sebelumnya tanpa key sama sekali).
4. **TIDAK dikerjakan di delivery ini** (sesuai scope yang dikonfirmasi): rename thread manual,
   model-picker (provider/model lain selain Gemini), patch server `ai-proxy`.

(server, bukan APK — TIDAK ada perubahan kode server; spek patch tertulis, belum diterapkan)

## 12 November 2026 — companion-v2.2.38-20261112 (2)

**Tahap 1 "REKOMENDASI FINAL ARSITEKTUR": tutup 4 titik yang nampilin Markdown mentah + 2 gap di renderer custom yang sudah ada.**

Audit jalur rendering materi (dipicu dokumen arsitektur 12 Nov) — RKR sudah punya renderer Markdown sendiri (`MarkdownText()` di `ui/components/MarkdownRenderer.kt`, dipakai di bubble chat Diskusi & Ringkasan bab), tapi 4 tempat lain masih nampilin konten mentah lewat `Text()` polos tanpa lewat renderer sama sekali:

- **`TabMateri.kt` — `materialDisplayTitle()`:** sebelumnya cuma strip `#`/`##`/`###` di depan + `**` kalau kebetulan nutup satu baris penuh — bold di tengah baris/list marker/dkk tetap lolos mentah. Sekarang pakai `markdownToPlainPreview()` (baru, `util/MarkdownPreview.kt`) yang nutup semua kasus itu.
- **`TabMateri.kt` — preview konten di kartu list materi:** sebelumnya `mat.content` ditampilin mentah (cuma newline→spasi). Sekarang lewat `markdownToPlainPreview()` juga sebelum `.take(90)`.
- **`TabFlashcard.kt` — front/back flashcard:** bisa berisi hasil "Bantu AI" (`generateFlashcards`). Sekarang lewat `MarkdownInlineText()` (baru, di file renderer yang sama) — render bold/italic/strikethrough/kode, bukan tampil mentah.
- **`TabMindMap.kt` — catatan node:** bisa berisi hasil "Buat/Lengkapi dengan AI". Sama, sekarang lewat `MarkdownInlineText()`.

`MarkdownInlineText()` dan `markdownToPlainPreview()` beda tujuan: yang pertama buat field teks pendek yang masih butuh styling (flashcard/mind map, pakai ulang `mdInline()` yang sudah ada), yang kedua buat preview/judul SATU BARIS yang cuma butuh String polos bersih (kartu materi). Dua-duanya SENGAJA tidak menangani struktur blok (heading/tabel/code-block/gambar) — bukan lupa, field-field ini memang tidak diharapkan punya struktur dokumen.

**2 gap ditambahin ke `mdInline()`/`MarkdownText()` (berlaku juga buat Diskusi & Ringkasan, bukan cuma 4 titik di atas):** link `[teks](url)` sebelumnya tidak ditangani sama sekali (tampil mentah persis) — sekarang teksnya ditampilkan bergaris-bawah + warna aksen (SENGAJA belum bisa diketuk buka URL, di luar scope perbaikan ini). Horizontal divider (`---`/`***`/`___`) sebelumnya jatuh ke paragraf biasa — sekarang dirender `HorizontalDivider`.

**Keputusan arsitektur (persetujuan William):** pertahankan & perluas renderer custom yang sudah ada, BUKAN ganti ke library `com.mikepenz:multiplatform-markdown-renderer` yang direkomendasikan dokumen arsitektur (proyek ini kompatibel — pure Android, ada modul `-m3`/`-coil3` — tapi dependency baru belum terbukti teruji di Kotlin 2.3.21 proyek ini; renderer custom sudah battle-tested). Opsi library tetap terbuka buat dipertimbangkan terpisah nanti.

**BELUM DIUJI** — sandbox sesi ini masih sama sekali tidak bisa jalanin `./gradlew` (lihat alasan di entri v2.2.37 di bawah). Kelima file (1 baru, 4 diubah) ditulis & ditelusuri manual tanpa compiler, dicek brace/paren/bracket balance via script — WAJIB `./gradlew :app:assembleDebug` dulu di AndroidIDE, terutama cek `mdInline()` (fungsi yang paling sering kepanggil, sekarang nambah 1 branch baru) dan `MarkdownInlineText()`/`markdownToPlainPreview()` (API baru, dipanggil dari 4 lokasi).

**[KOREKSI, 12 Nov — hasil `assembleDebug` William]** Build beneran dijalanin, 2 error compile di `MarkdownRenderer.kt` (baris `mdInline()`): branch link baru pakai `color = AppAccent`, padahal `AppAccent` itu `@Composable get()` (baca `MaterialTheme.colorScheme.primary`) — gak bisa dibaca dari `mdInline()` yang sengaja BUKAN `@Composable` (persis kenapa branch bold/italic/strikethrough/underline/code dari awal cuma pakai warna hardcode atau gak ada warna sama sekali). Diperbaiki: warna dibuang dari span link, sisa `textDecoration = TextDecoration.Underline` doang (garis-bawah aja udah cukup nunjukkin "ini link", konsisten sama pola underline/strikethrough yang juga gak pakai warna). `versionCode`/`versionName` TETAP 24/2.2.38 (bukan bump baru — ini nyelesaiin v2.2.38 yang sama, bukan kerjaan baru). Sisa 4 file lain gak disentuh sama sekali. Masih WAJIB di-build ulang buat mastiin bersih sekarang.

Item lain di Tahap 1 (dari 5 langkah di dokumen arsitektur) masih terbuka: integrasi renderer AST pihak ketiga (ditunda, lihat keputusan di atas), dan pengujian visual heading/tabel/list/code-block/gambar (butuh device asli).

## 12 November 2026 — companion-v2.2.37-20261112

**Perbaikan chunking RAG (paragraf panjang kepotong jadi 1 chunk raksasa) + validasi embedding sebelum simpan + skip RAG buat basa-basi.**

Ngerjain "PLANNING IMPLEMENTASI RKR: PERBAIKAN RAG, CHUNKING, DAN AUTOMATIC EMBEDDING" dari William — 3 perubahan, semuanya di klien Android (sesuai arahan eksplisit buat gak nyentuh Supabase/ai-proxy putaran ini):

- **`ChunkRetrieval.kt` — `chunkText()` sekarang mecah paragraf yang sendirian > 900 karakter.** Sebelumnya: chunking cuma mecah per-baris-kosong lalu gabung-sampe-pas, TANPA pernah ngecek apakah 1 paragraf sendirian udah lebih gede dari target — paragraf 5.000 karakter tanpa baris kosong di tengah bisa lolos jadi 1 chunk raksasa, bikin embedding-nya "rata-rata" kebanyakan topik sekaligus (similarity search jadi kurang presisi). Sekarang: `splitOversizedParagraph()` + `findBreakPoint()` (prioritas titik potong: baris kosong > baris baru > akhir kalimat > spasi, baru jatuh ke potong-per-karakter kalau beneran gak ada titik potong layak) motong paragraf kepanjangan DULU sebelum ikut logika gabung yang lama — invariant "setiap chunk <= 900 karakter" sekarang selalu kejaga. `safeCharCut()` jaga surrogate pair emoji/Unicode astral biar gak kepotong di tengah jadi karakter korup. `cosineSimilarity()`/`topKSimilar()` TIDAK disentuh sama sekali.
- **`ChunkRetrievalTest.kt` — BARU, 10 unit test** persis sesuai 10 skenario di dokumen planning (kosong, 1 paragraf pendek, gabung paragraf pendek, paragraf panjang kepecah, gak ada chunk > 900, gak ada kata hilang, urutan kepertahanin, teks spasi doang, Unicode/emoji, 1 kata raksasa tanpa spasi). Cek "kata hilang" pakai tokenisasi + exact-match set, bukan `.contains()` mentah (`.contains()` bisa salah lolos — "kata1" itu substring dari "kata10").
- **`AppViewModel.kt` — validasi embedding sebelum disimpan + skip RAG buat obrolan basa-basi.** `reindexMaterial()`: sekarang nolak nyimpen kalau jumlah vector embedding yang balik gak sama dengan jumlah chunk, atau ada embedding yang dimensinya bukan 768, atau ada nilai non-finite (NaN/Infinity) — sebelumnya hasil yang cacat bisa aja ikut kesimpen tanpa ketahuan. `sendChat()`: pertanyaan "basa-basi" (salam, makasih, konfirmasi pendek, pertanyaan identitas — daftar tertutup di `SIMPLE_CHAT_PATTERNS`) sekarang skip retrieval RAG sama sekali (`isSimpleChatTurn()`, baru, `internal` biar gampang dicek kalau perlu) — sebelumnya tiap giliran chat selalu manggil `buildRagContext()` walau pertanyaannya cuma "halo"/"makasih", buang-buang panggilan embedding.

**BELUM DIUJI — PENTING:** sandbox sesi ini gak bisa jalanin `./gradlew` sama sekali (dicoba 2 cara: wrapper minta Gradle 9.0.0 yang gak ke-cache dan network ke-block 403; fallback ke Gradle 8.14.3 bawaan sandbox juga gagal karena Android Gradle Plugin 8.13.2 gak ketemu di repo manapun, semua butuh network yang diblokir). Ketiga file di atas ditulis & ditelusuri manual tanpa compiler sama sekali, sama kayak putaran audit-audit sebelumnya — WAJIB dijalanin `./gradlew :app:testDebugUnitTest` dulu di AndroidIDE sebelum dipercaya beneran lulus.

`versionCode`/`versionName` dibump (22→23, 2.2.36→2.2.37) walau belum tervalidasi build — konsisten sama konvensi satu versi per pengiriman di proyek ini (lihat entri 29 Okt v2.2.35 di bawah, test-only tapi tetap dibump).

## 4-5 November 2026 — companion-v2.2.36-20261105

**Routing ai-proxy diubah total dari 1 rantai fallback generik jadi provider utama+cadangan PER
FITUR ("bagi rata fungsinya") + Cerebras diganti Aion Labs + beberapa koreksi & fitur debug.**

Lanjutan dari riset `docs/AI_MODEL_RESEARCH.md` (10 provider) — diskusi interaktif dengan William
berujung ke desain ulang arsitektur, diimplementasikan atas instruksi eksplisit William ("coba
kita buat dulu sesuai keputusan ini... kalau ada yang kurang pas akan ku katakan"), lalu
dikoreksi lagi (5 Nov) setelah William review. **Ringkasan lengkap + tabel final ada di dokumen
project terpisah ("keputusan-implementasi-ai-proxy-04nov2026"), ini versi ringkas buat histori.**

**1. Routing per-fitur (`ai-proxy/index.ts`)**
- `FEATURE_PRIORITY` (provider utama+cadangan per 1 dari 8 fitur) + `BASE_ORDER` (fallback dalam,
  sama buat semua fitur) + `PROVIDER_CALLS`, gantikan array `providers[]` hardcoded + flag
  `forMindmap` yang lama.
- `mindmap` → OpenRouter (`qwen/qwen-2.5-72b-instruct:free`, model di-pin, dulu router otomatis
  `openrouter/free`) + SambaNova cadangan — **keputusan eksplisit William**, keduanya limit per
  REQUEST bukan per token, cocok buat mindmap yang token-nya paling gede.
- `chat` (Diskusi) → Gemini + Groq cadangan — **keputusan eksplisit William**. (Rencana lanjutan:
  user pilih tingkat *thinking* sebelum kirim pesan — UI BELUM dibuat, baru routing provider.)
- 6 pasangan sisanya (`enrichMindMap`→Cohere+Cloudflare, `summarize`→SiliconFlow+Cloudflare,
  `textAction`→Cloudflare+SiliconFlow, `flashcards`→Cohere+SiliconFlow,
  `chaptersFromTopic`→AionLabs+Cohere, `chaptersFromText`→Groq+AionLabs) — **asumsi Claude**,
  bukan hasil diskusi eksplisit, dirancang dari kategori tugas + kapasitas provider di laporan
  riset. Paling perlu direview.
- Z.ai & NVIDIA **dikeluarkan dari routing** (tidak dipakai fitur manapun) — **status belum
  final**, lihat poin 4 di bawah.

**2. Provider baru: Aion Labs (ganti Cerebras)**
- William tidak bisa menambah metode pembayaran di akun Cerebras-nya (disyaratkan walau buat
  trial gratis) — dialihkan ke Aion Labs, provider terpisah (BUKAN Cerebras ganti nama).
  `AIONLABS_API_KEY` secret BARU, **belum ada di Supabase sama sekali** — wajib ditambah sebelum
  provider ini berfungsi (fail-open ke cadangan selama belum ada).
- `AIONLABS_MODELS = ["aion-3.5-mini", "aion-3.0-mini"]` — ⚠️ **BELUM DIUJI**, format ID
  "telanjang" (tanpa prefix `aion-labs/`) cuma inferensi dari pola provider lain di file ini,
  belum dikonfirmasi dari dokumentasi resmi Aion Labs. Kalau `chaptersFromTopic`/
  `chaptersFromText` sering gagal 400, ini kemungkinan besar penyebabnya.
- Budget harian Aion Labs TOKEN-based (20.000 token/hari TOTAL), bukan request-based seperti
  provider lain — dikonversi kasar jadi `aionlabs: 7` di `PROVIDER_DAILY_BUDGET`.

**3. Model Gemini — direvisi 5 Nov**
- `GEMINI_MODELS` final: `["gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
  "gemini-2.5-flash-lite"]`. Awalnya (4 Nov) `gemini-3.8-flash` SENGAJA tidak dipasang (laporan
  riset cuma menyebutnya sekilas) — William tanya balik, riset ulang (datalearner.com +
  inside.com.tw) mengonfirmasi model ini nyata, resmi (rilis 2 Sep 2026), dan BENERAN lebih
  unggul dari 3.6-flash di 21/25 benchmark publik dengan harga identik. Dinaikkan ke prioritas 1.
  ⚠️ ID model `gemini-3.8-flash`/`gemini-3.6-flash` sendiri masih inferensi pola penamaan, belum
  100% dikonfirmasi dokumentasi resmi — kalau salah, otomatis lanjut ke model lain di list.
- `gemini-3.5-flash-lite` ditambahkan juga (GA resmi, sudah dipakai di jalur BYOK/
  `GeminiService.kt` — menutup selisih yang sempat tercatat antara BYOK & server).

**4. BELUM diimplementasikan — Z.ai "gpt-oss-120b"**
- William sempat minta Z.ai dipasang balik pakai model GPT-OSS-120B. Dicek ke 2 halaman
  dokumentasi resmi Z.ai (release notes + devpack overview) — katalog mereka CUMA model GLM
  (5.3, 5.3-Flash, dst), **tidak ditemukan** gpt-oss di manapun. Kemungkinan sumber kebingungan:
  array lama `CEREBRAS_MODELS = ["gpt-oss-120b", "zai-glm-4.7"]` — dua model itu berdampingan di
  Cerebras, bukan bukti Z.ai host gpt-oss. **Menunggu konfirmasi/arahan William** sebelum
  diimplementasikan apapun.

**5. Token mind map 6000→8000 (`AiEngine.kt`)**
- `MINDMAP_MAX_TOKENS` dinaikkan ke batas maksimum sistem (`MAX_ALLOWED_TOKENS`) — permintaan
  William, mind map paling sering kepotong. Dikonfirmasi (5 Nov) berlaku ke SEMUA provider yang
  dicoba request mindmap, termasuk kalau sampai jatuh ke fallback-dalam, bukan cuma OpenRouter/
  SambaNova — edge case token-tight provider (Aion Labs/Groq) kena 8000 juga kalau sampai situ,
  dibiarkan dulu (kejadian cuma kalau 2 provider utama gagal bareng).
- **Permintaan terpisah William ("hapus limit token khusus OpenRouter") DITOLAK**: dicek ke
  openrouter.ai, model yang dipin (`qwen/qwen-2.5-72b-instruct:free`) max output-nya sendiri
  cuma "up to 8K tokens" — persis di batas yang sudah ada, jadi menghapus/menaikkan limit RKR
  sendiri gak akan menghasilkan output lebih panjang (model-nya sendiri yang mentok), cuma
  membuka kembali celah yang ditutup perbaikan keamanan 30 Okt tanpa manfaat nyata.

**6. Debug provider & model — `AiUsageDebugActivity.kt` + `GeminiService.kt`**
- Permintaan William: bisa cek status provider/model dari app, karena belum semua provider
  dipasangi key. `usageStats()` (action="stats") diubah dari "cuma tampilin provider yang sudah
  pernah kepanggil" jadi "tampilin SEMUA provider yang dikenal sistem", ditambah per-provider:
  `configured` (key sudah/belum di-set di Supabase — dibaca server-side, TIDAK PERNAH expose isi
  key), `models` (model yang lagi dipasang), `activeInRouting` (masih dipakai fitur manapun).
  `ProviderUsage` (Kotlin) dapat 3 field baru itu; tiap provider di layar "Cek Limit AI" sekarang
  jadi kartu sendiri dengan indikator ●/○ + nama model + label buat yang tidak aktif di routing.

**7. Bug ketemu & diperbaiki: `AiUsageDebugActivity` TIDAK PERNAH terdaftar di manifest**
- William tanya "layar Cek Limit AI ada dimana, kok nggak ketemu" — dicek `AndroidManifest.xml`,
  ternyata cuma `.MainActivity` yang terdaftar sebagai `<activity>` dari awal. Jadi layar debug
  ini SEJAK 26 Okt tidak mungkin pernah muncul/bisa dibuka di HP manapun, APK manapun — bukan
  soal versi yang di-install. **Diperbaiki**: tambah `<activity android:name=".debug.
  AiUsageDebugActivity" android:label="Cek Limit AI" android:exported="true">` + intent-filter
  MAIN/LAUNCHER (persis pola MainActivity) di `AndroidManifest.xml`. Pakai ikon app yang sama,
  belum ada ikon debug terpisah — kebeda dari app utama lewat label di app drawer.

**File yang diubah:**
- `supabase/functions/ai-proxy/index.ts` (server, paste manual ke Supabase Dashboard atau
  `supabase functions deploy ai-proxy` kalau kerja dari CLI lokal — **BUKAN** bagian dari zip
  Android ini, dikirim terpisah)
- `app/src/main/kotlin/com/ruangkerja/companion/network/AiEngine.kt`
- `app/src/main/kotlin/com/ruangkerja/companion/network/GeminiService.kt`
- `app/src/main/kotlin/com/ruangkerja/companion/debug/AiUsageDebugActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle` (versionCode 21→22, versionName 2.2.35→2.2.36)

**Cara pasang:** timpa 4 file Kotlin/XML di atas ke lokasi yang sama di project, tempel
`index.ts` ke Supabase Dashboard (Edge Functions → ai-proxy → Code), tambah secret
`AIONLABS_API_KEY` (daftar dulu ke Aion Labs buat dapat key-nya), `supabase functions deploy
ai-proxy`, baru build & install APK baru.

**BELUM DIUJI sama sekali** (gak ada API key/Android SDK di sandbox) — semua di atas lolos
`tsc --noEmit` (TypeScript) dan review manual (Kotlin/XML), tapi panggilan API sungguhan ke Aion
Labs & 2 model Gemini baru, plus launcher activity baru, WAJIB dicek langsung setelah deploy +
install. Titik paling mungkin gagal: format ID model Aion Labs (poin 2).

**Sisa kerja William:** jawab soal Z.ai (poin 4), tambah `AIONLABS_API_KEY`, deploy ai-proxy,
install APK baru, tes terutama Aion Labs + 2 model Gemini baru + layar "Cek Limit AI".

## 30 Oktober 2026 — migration_ai_usage_daily.sql (server, bukan APK) (2)

**Tabel `ai_usage_daily` ternyata tidak ada di database live William — disusun ulang.**

Lanjutan verifikasi RLS di entri di bawah (yang sama hari ini): sambil ngecek RLS tabel
`ai_usage_*`, ketauan tabel `ai_usage_daily` (beda dari `ai_usage_account_daily` yang memang ada)
**tidak ada sama sekali** di project Supabase William — dikonfirmasi lewat
`select to_regclass('public.ai_usage_daily');` → hasilnya `null`.

**Dampak nyata (bukan cuma "tabel kosong"):** 3 dari 4 fungsi yang baca/tulis tabel ini di
`ai-proxy/index.ts` (`deviceUsageToday`, `providerUsageToday`, `trackUsage`) sengaja FAIL OPEN
by design kalau query ke tabel gagal — jadi tanpa tabel ini, `DAILY_DEVICE_CAP` dan **semua**
`PROVIDER_DAILY_BUDGET` (yang diminta eksplisit biar "jangan mepet sama limit dari key yang ada")
kemungkinan besar diam-diam gak pernah aktif sejak tabel ini pertama kali disebut dibuat (26 Okt).
Kuota UTAMA per-akun (`ai_usage_account_daily`, fail-closed) **tetap aktif** — jadi BUKAN "AI
tanpa batas total", tapi 2 lapisan pelindung tambahan kemungkinan gak pernah jalan dari awal.

**Perbaikan:** `supabase/migrations/migration_ai_usage_daily.sql` (file baru) — disusun ULANG dari
kontrak kolom yang kebaca jelas di `ai-proxy/index.ts` sendiri (`day`/`device_id`/`provider`/
`count`, granularitas 1 baris per kombinasi itu), bukan dari file migrasi asli yang sudah hilang:
- Tabel + PK komposit `(day, device_id, provider)`.
- RLS aktif tanpa policy tambahan — pola identik `ai_usage_account_daily` yang sudah
  terverifikasi benar hari ini juga.
- Fungsi `increment_ai_usage(p_device_id, p_provider)` — upsert **satu statement**
  (`INSERT ... ON CONFLICT ... DO UPDATE`), race-safe dari awal (belajar dari bug hitung-dobel
  yang pernah kejadian di `increment_ai_usage_account()` 27 Okt gara-gara pola 2-langkah).
- Execute permission dikunci ke `service_role` saja (default Postgres kalau gak di-revoke:
  kebuka ke semua role).

**Cara jalanin:** paste isi file ke Supabase Dashboard → SQL Editor → Run (bukan
`supabase functions deploy`, ini DDL murni bukan edge function).

**BELUM DIUJI:** ditulis & ditelusuri manual, belum pernah dijalankan ke Postgres sungguhan.
WAJIB dicek: tabel muncul di Table Editor, gak ada error pas run.

Detail lengkap: laporan audit project "Ruang Kerja Ringkas Tahap 2", §9 (temuan B5) + §13.

## 30 Oktober 2026 — ai-proxy/index.ts (server, bukan APK — tidak ada bump versionCode)

**Audit penuh `ai-proxy/index.ts` (Tahap 8/9 — akhirnya tersedia) + 1 temuan RENDAH diperbaiki.**

William kirim source `ai-proxy/index.ts` (739 baris) yang dari awal audit ini dimulai belum
pernah ada di zip manapun (file ini cuma hidup di Supabase Dashboard, bukan di repo Android).
Dibaca penuh, ditelusuri manual — **dikonfirmasi aman**: `user_id` selalu dari token
terverifikasi server (gak pernah dari body request), gerbang login fail-closed di semua action,
service-role key gak pernah bocor ke respons, dan `AiUsageDebugActivity` dikonfirmasi gak pernah
baca tabel `ai_usage_*` langsung (selalu lewat `action="stats"` yang sama-sama digembok login).

**[RENDAH] `maxTokens` dari client gak ada batas atas** — APK asli selalu kirim nilai wajar
(1500-6000), tapi endpoint bisa dipanggil langsung (APK dimodif / script luar pegang token+anon
key) dengan nilai berapa pun, sementara semua kuota yang ada ngitung per REQUEST bukan per
token. Butuh akun login sah + klien non-standar buat dieksploitasi (bukan cuma modal anon key),
makanya RENDAH bukan lebih tinggi. **Diperbaiki:** `maxTokens` sekarang diklem ke `[1, 8000]`
(8000 = di atas budget tertinggi yang dipakai fitur manapun sekarang, mind map=6000 + jarak
aman) — nilai wajar dari APK asli lewat tanpa berubah.

**Cara deploy BEDA dari Android — bukan rebuild APK:**
```
supabase functions deploy ai-proxy
```

**BELUM DIUJI:** sandbox sesi ini gak ada `deno` terinstal (beda dari audit 27 Okt yang sempat
jalanin `tsc --strict`), jadi perbaikan ini gak lewat pengecekan tipe/syntax otomatis apa pun —
murni review manual. Setelah deploy, WAJIB dicoba minimal 1 request chat/mindmap biasa buat
mastiin fitur asli gak kesenggol.

Detail lengkap + temuan "dikonfirmasi aman" lainnya: laporan audit project "Ruang Kerja Ringkas
Tahap 2", §9.

**Masih tertahan:** verifikasi RLS Supabase yang sesungguhnya (tabel `ai_usage_*` DAN tabel
sinkron workspaces/chapters/dkk yang dipakai `SyncManager` — yang terakhir ini gap LEBIH besar).
Cara tercepat kirim: jalankan 1 query di Supabase SQL Editor, lihat laporan audit §16 buat query
persisnya.

## 29 Oktober 2026 — companion-v2.2.35-20261029 (2)

**Unit test pertama di proyek ini (LocalStorageTest.kt) + setup test dependency.**

Setelah v2.2.34 (di bawah) dikonfirmasi build & jalan baik, lanjut ke salah satu gap TINGKAT
SEDANG dari laporan audit: nol unit test coverage sama sekali. Putaran pertama fokus ke
`LocalStorage` dulu — lapisan paling berisiko (satu-satunya jalur persistensi data user) dan yang
baru aja diubah di v2.2.34 (fix atomic write + quarantine file korup, temuan B1). 3 test:
roundtrip insert→restart→baca-lagi, atomic write gak nyisain file `.tmp`, dan file korup
beneran di-quarantine bukan ditimpa diam-diam.

Dependency baru (`testImplementation`, JVM-only, jalan lewat `./gradlew test` tanpa device/
emulator): JUnit4, Mockito + mockito-kotlin (buat mock `Context` — cuma `filesDir` yang perlu
di-stub, jadi Robolectric kelewat berat buat ini), `kotlinx-coroutines-test` (disamain versi sama
`kotlinx-coroutines-android` yang udah ada, 1.9.0). `versionCode`/`versionName` dibump lagi
(20→21, 2.2.34→2.2.35) walau ini test-only (gak ikut ke APK rilis) — biar konsisten sama
konvensi satu versi per pengiriman di proyek ini.

**BELUM DIUJI:** test ini sendiri belum pernah dijalankan (`./gradlew test`) — ditulis & ditelusuri
manual tanpa compiler, sama kayak semua kode lain dari sandbox audit ini. WAJIB dijalanin di
AndroidIDE buat mastiin beneran lulus.

## 29 Oktober 2026 — companion-v2.2.34-20261029

**Audit teknis menyeluruh (laporan lengkap ada di project "Ruang Kerja Ringkas Tahap 2") + perbaikan temuan TINGGI/SEDANG/RENDAH:**

Audit statis + manual dari sandbox (tanpa akses build/device — lihat laporan buat detail keterbatasan lingkungan) nemuin beberapa masalah nyata, langsung diperbaiki berurutan dari risiko tertinggi:

- **[TINGGI] `LocalStorage.kt`** — tulis file JSON SEBELUMNYA langsung (`writeText()` ke file asli); proses yang mati persis di tengah nulis bisa nyisain file korup/kepotong, dan `load()` yang gagal parse diam-diam nganggep datanya kosong (risiko kehilangan data beneran). Sekarang: tulis ke file `.tmp` dulu baru `rename()` (atomik di level OS — `writeAtomic()`), dan file yang gagal di-parse di-quarantine (`quarantineCorruptFile()`, di-rename ke `.corrupt-<timestamp>`, bukan ditimpa/ilang) sebelum dianggap kosong.
- **[TINGGI] `allowBackup`** — `backup_rules.xml`/`data_extraction_rules.xml` SEBELUMNYA ada tapi isinya template kosong & gak dihubungkan ke manifest, jadi Android Auto Backup default FULL yang jalan (termasuk SharedPreferences `app_prefs` yang nyimpen kunci BYOK — `ai_custom_key`, `gemini_api_key`, dkk). Sekarang dua file itu diisi exclude `app_prefs.xml` dan dihubungkan lewat `android:fullBackupContent`/`android:dataExtractionRules` di manifest. Lokasi sesi login Supabase BELUM terverifikasi ikut aman atau nggak (lihat komentar di `data_extraction_rules.xml`).
- **[SEDANG] `AppRepository.deleteWorkspace()`** — gak ikut bersihin `DraftSuggestion` yang `workspaceId`-nya nunjuk ke workspace itu (pola sama kayak bug `deleteDraft()` yang udah diperbaiki 16 Okt), bikin entri saran nyangkut di Notification Center selamanya. Sekarang dibersihin juga sebelum `db.deleteWorkspace()`, mirip persis pola `deleteDraft()`.
- **[SEDANG] `SyncManager.pushTombstones()`** — ditambahin filter `eq("user_id", userId)` sebagai lapisan pertahanan TAMBAHAN di atas RLS (bukan pengganti — RLS-nya sendiri masih belum bisa diverifikasi tanpa `schema.sql`, lihat laporan audit).
- **[RENDAH] `PdfTextExtractor`** — dimensi bitmap per halaman PDF sekarang di-clamp (`MAX_DIMENSION_PX = 3000`) sebelum upscale 2x, cegah OOM lokal pada PDF berdimensi ekstrem.
- **[RENDAH] `JinaExtractor.extract()`** — URL yang bikin `Request.Builder()` gagal (bukan cuma kegagalan HTTP) sekarang ketangkep jadi pesan error yang konsisten ("Tautan tidak valid..."), bukan exception mentah yang mungkin gak ketangkep di UI.
- **[Housekeeping]** `BuildConfig.GEMINI_API_KEY`/`GROQ_API_KEY`/`CEREBRAS_API_KEY` (sisa sebelum migrasi ke `ai-proxy`, nol referensi aktif — dicek grep) dihapus dari `build.gradle`.

**BELUM DIUJI (jujur — belum sempat divalidasi dari sandbox audit, WAJIB dicoba di AndroidIDE sebelum dipercaya):** build/compile v2.2.34 ini sama sekali, dan semua perilaku runtime dari perubahan di atas. Lihat laporan audit project buat detail lengkap tiap temuan, alasan, dan cara ngetesnya.

## 27 Oktober 2026 — companion-v2.2.33-20261027 (2)

**Fitur AI bawaan (jalur ai-proxy) SEKARANG WAJIB LOGIN — autentikasi + kuota per akun:**

Latar belakang: William kirim dua permintaan yang nyambung. Pertama, di chat: "Kita bikin limit
untuk RKR jangan mepet sama limit dari key yang ada kita tetapkan sendiri jangan ikut limit dari
provider" (sudah dikerjakan di `v2.2.32` di atas — `PROVIDER_DAILY_BUDGET`). Bareng itu William
kirim **dokumen planning terpisah** ("AUTENTIKASI, VERIFIKASI ACCESS TOKEN, DAN PEMBATASAN
PENGGUNAAN AI RKR") yang jauh lebih jauh: Supabase Auth login WAJIB buat SEMUA akses AI bawaan,
kuota dicatat per akun (bukan per device), verifikasi token di server, fail-closed kalau
verifikasi/kuota gagal dicek, dan larangan eksplisit menjadikan `device_id` sebagai identitas
utama.

**Konflik yang ditemukan & cara diselesaikan:** dokumen itu bentrok langsung sama prinsip yang
sudah tertulis eksplisit di `SupabaseAuthManager.kt` sejak awal: *"Login ini SEPENUHNYA opsional
— RuangKerja Ringkas tetap 100% jalan tanpa login. Jangan pernah gate fitur belajar inti
(materi/tugas/AI) di belakang status login ini."* Daripada diam-diam nurutin dokumen baru (ngelanggar
prinsip lama tanpa bilang) atau diam-diam nurutin prinsip lama (ngabaikan dokumen baru), konflik
ini ditanya eksplisit ke William lewat 3 opsi (hybrid anonim+boost-buat-yang-login / wajib login
semua sesuai dokumen / tunda dulu). **William pilih: wajib login semua.** Itu yang dikerjakan di
rilis ini.

**PENTING — batas scope, biar jelas apa yang BERUBAH dan apa yang TIDAK:** yang wajib login
HANYA jalur AI gratis bawaan (lewat `ai-proxy`, dibiayai key pribadi William yang dibagi ke semua
user). **TIDAK berubah:** materi/tugas/kalender/sinkronisasi (tetap 100% opsional, prinsip lama di
atas TETAP berlaku buat fitur-fitur itu), dan jalur **BYOK** (API key sendiri di Pengaturan >
Lanjutan) — itu manggil Gemini langsung dari Kotlin, nggak pernah lewat `ai-proxy`, jadi nggak
pernah butuh login juga.

**Desain kuota — TIGA lapisan sekarang (dulu dua):**
1. **Per akun (BARU, lapisan UTAMA)** — `DAILY_ACCOUNT_CAP = 60`/hari, dicek+dinaikkan ATOMIK
   lewat satu fungsi SQL (`increment_ai_usage_account`, tabel baru `ai_usage_account_daily`) biar
   race-safe (dua request barengan gak bisa dua-duanya lolos padahal gabungannya udah lewat cap —
   ini eksplisit diminta dokumen planning). **FAIL CLOSED**: gagal verifikasi login ATAU gagal cek
   kuota akun → request DITOLAK (503), bukan diloloskan.
2. **Per provider, global (sudah ada dari `v2.2.32`)** — `PROVIDER_DAILY_BUDGET`, TIDAK diubah,
   TETAP fail-open (hiccup DB kecil gak langsung matiin AI buat semua orang — ini lapisan
   tambahan yang sudah ada sebelum dokumen planning ini, bukan bagian yang diminta diubah).
3. **Per device (sudah ada dari `v2.2.31`)** — `DAILY_DEVICE_CAP`, TIDAK diubah, TETAP fail-open,
   sekarang TURUN jadi sinyal TAMBAHAN doang (bukan dihapus — sesuai batasan dokumen: "Device_id
   hanya boleh menjadi lapisan tambahan", bukan identitas utama).

Kenapa fail-closed cuma buat lapisan baru (akun) dan TETAP fail-open buat 2 lapisan lama (device +
provider): dua yang lama itu preseden terpisah yang udah ada sebelum dokumen ini — diubah jadi
fail-closed malah membuka risiko lama balik (1 hiccup DB = semua orang kehilangan akses AI). Tapi
verifikasi identitas + kuota akun ini GERBANG UTAMA yang diminta eksplisit (dokumen Section 6:
"Jika database kuota tidak tersedia... jangan membiarkan request AI berjalan tanpa batas") — kalau
gerbang utama ikut fail-open, seluruh fitur ini gak ada gunanya.

**Bug yang kena tangkep & diperbaiki SEBELUM dikirim** (dicatat biar jujur, bukan disembunyikan):
waktu nulis fungsi SQL `increment_ai_usage_account`, awalnya baris pertama tiap hari (INSERT
dengan count awal = 1, lalu langsung di-UPDATE +1 lagi) ketauan bakal KEHITUNG DUA KALI (count
jadi 2 padahal baru 1 pemakaian beneran). Ketauan lewat trace manual sebelum dikirim, diperbaiki
(count awal INSERT jadi 0, bukan 1) — detail lengkapnya ada di komentar `migration_ai_usage_account.sql`.

**File yang berubah:**

1. **`supabase/functions/ai-proxy/index.ts`** (dikirim terpisah) — `verifyRequestUser()` baru
   (gerbang wajib, jalan buat SEMUA action termasuk `"stats"`, sebelum logika lain apa pun):
   ambil token dari header `Authorization: Bearer <token>`, verifikasi lewat
   `supabaseAdmin.auth.getUser(token)` (kontak server Auth Supabase, bukan decode lokal — otomatis
   nolak token basi/salah-proyek/kedaluwarsa/palsu), `user_id` SELALU dari hasil verifikasi ini,
   TIDAK PERNAH dari body request. `checkAndIncrementAccountUsage()` + `refundAccountUsage()` baru
   (lapisan akun, atomik, fail-closed, refund best-effort kalau semua provider gagal setelah kuota
   kepotong). Deploy SEKARANG **TANPA** `--no-verify-jwt` (beda dari sebelumnya — lihat instruksi
   di bawah).
2. **`migration_ai_usage_account.sql`** (BARU, dikirim terpisah) — tabel `ai_usage_account_daily`
   (day, user_id → `auth.users`, count; RLS nyala tanpa policy = cuma service-role yang bisa
   akses), fungsi `increment_ai_usage_account` (atomik, dijelaskan di atas) dan
   `decrement_ai_usage_account` (refund). **TERPISAH** dari `migration_ai_usage_daily.sql` (26
   Okt) — itu TETAP ada & TETAP dipakai apa adanya, migrasi ini TAMBAHAN bukan pengganti.
3. **`auth/SupabaseAuthManager.kt`** — `AuthUser` dapat field baru `accessToken: String` (token
   sesi SAAT INI, diturunkan dari `sessionStatus` yang sama kayak field lain di situ — BUKAN
   accessor sinkron baru, sengaja, biar gak kena race yang sama yang pernah bikin bug auto-logout
   dulu, lihat komentar di file-nya). Komentar kelas di-update: prinsip "login opsional" TETAP
   berlaku buat fitur lain, dengan SATU pengecualian eksplisit (AI bawaan) yang dijelaskan alasannya.
4. **`network/GeminiService.kt`** — `edgePost()`: Authorization sekarang SELALU token sesi user
   (bukan anon/publishable key lagi — dulu nyaris gak pernah kekirim karena anon key baru bukan
   JWT). Gak ada sesi aktif → `AiException(AiFailure.LOGIN_REQUIRED, ...)` SEBELUM request
   dikirim (gagal cepat, pesannya nyebut cara login ATAU opsi BYOK). `edgeFailure()`'s 401/403:
   pesan dari server dipakai langsung (lebih jelas dari dulu). `AiUsageStatsResp` dapat field
   `account: AccountUsage?` baru.
5. **`network/AiErrors.kt`** — `AiFailure` dapat nilai baru `LOGIN_REQUIRED` (dipisah dari `AUTH`
   yang lama — beda kasus: ini ketauan di KLIEN sebelum request dikirim, `AUTH` ketauan SETELAH
   server nolak).
6. **`debug/AiUsageDebugActivity.kt`** — layar statistik sekarang nampilin kartu "Akun ini" (lapisan
   utama, di atas) + kartu "Perangkat ini (sinyal tambahan)" (di bawah, teks dikecilin) — dulu cuma
   ada device doang.
7. **`viewmodel/AppViewModel.kt`**, **`ui/screens/SettingsScreen.kt`** — komentar & teks UI
   di-update biar konsisten sama prinsip baru ini (desc tombol login sekarang nyebut AI, bukan
   cuma sync).

**Tambahan verifikasi:** `index.ts` berhasil di-type-check pakai `tsc --strict` (plus
`noUnusedLocals`/`noUnusedParameters`/`noImplicitReturns`/`noFallthroughCasesInSwitch`) dengan
stub tipe buat `Deno`/`npm:@supabase/supabase-js` — bersih, 0 error. Ini nangkep typo nama
variabel/fungsi, ketidakcocokan tipe, dan jalur kode yang gak lengkap return-nya — tapi TETAP
BUKAN pengganti pengujian live: `tsc` gak tau apakah `auth.getUser()` beneran berperilaku seperti
yang diasumsikan terhadap Supabase Auth server sungguhan, atau apakah nama kolom di RPC call cocok
PERSIS sama migration SQL-nya di database beneran (itu kenapa poin C/D/E/K di bawah tetap butuh
live test, bukan ketutup oleh type-check ini).

**Yang SUDAH diverifikasi lewat tinjauan kode (bukan cuma diasumsikan benar)** — mengacu ke 13 poin
pengujian wajib (A–M) di dokumen planning:
- **F** (ganti `user_id` di body gak ngubah identitas) — strukturnya MEMANG gak ada jalur baca
  `user_id` dari body sama sekali, di mana pun.
- **H** (satu akun gak bisa ganggu kuota akun lain) — tiap query/write kuota akun difilter
  `user_id` hasil verifikasi SAAT ITU doang, gak ada jalur baca/tulis baris akun lain.
- **I** (request bersamaan gak lewatin batas) — dijamin atomik (row-lock) oleh desain fungsi SQL
  `increment_ai_usage_account`, bukan dua-query terpisah yang rawan race.
- **L** (semua fitur tetap kirim nama fitur yang benar) — logika `feature`/`forMindmap` sama
  sekali gak disentuh perubahan ini.
- **M** (BYOK gak rusak) — `call()`/`embed()` BYOK branch gak disentuh, gak pernah manggil
  `edgePost()` sama sekali.

**Yang BUTUH pengujian LIVE William** (gak bisa disimulasikan dari sandbox ini — gak ada akses ke
project Supabase atau device Android beneran):
- **A, B** — kemungkinan besar benar (kode jelas nolak di depan kalau Authorization kosong), tapi
  paling murah dites: `curl` ke endpoint `ai-proxy` TANPA header Authorization (cuma `apikey`),
  harus balas 401.
- **C, D** (token palsu/kedaluwarsa) — ini yang PALING butuh live test, karena verifikasinya kontak
  server Auth Supabase beneran (gak bisa disimulasikan tanpa project aktif).
- **E, G, K** — jalur sukses penuh + kena cap + login Google beneran, perlu device + akun asli.
- **J** — paling murah dites dengan SENGAJA salah nama tabel/secret sebentar, cek request AI
  ditolak (bukan lolos tanpa batas), baru dibenerin lagi.

**Satu ketidakpastian teknis yang jujur perlu disebut:** field `session.accessToken` yang dipakai
di `SupabaseAuthManager.kt` (buat ambil JWT sesi user) dicocokkan ke dokumentasi resmi supabase-kt
(contoh kode `importSession(UserSession(accessToken = "token", ...))`) dan ke pola `.session.user`
yang SUDAH kebukti jalan di file yang sama — tapi BUKAN dicek langsung ke source code `UserSession`
di GitHub supabase-kt (gak ketemu link source-nya lewat pencarian). Kalau nama field ini ternyata
salah, Android Studio bakal langsung kasih error compile "unresolved reference: accessToken" —
gagalnya LANGSUNG KETAUAN pas build (bukan nyelip jadi bug diam-diam di runtime), dan perbaikannya
cuma ganti satu nama field itu. Disebut di sini biar kalau build gagal persis di titik itu, udah
tau penyebabnya dari awal.

**Langkah deploy — URUTAN PENTING (dokumen planning eksplisit: "Jangan langsung melakukan deploy
sebelum perubahan kode, migrasi database, dan pengujian diverifikasi"):**
1. Baca dulu `index.ts` & `migration_ai_usage_account.sql` yang dikirim terpisah — terutama
   komentar "AUTENTIKASI WAJIB" di awal `index.ts`.
2. Jalankan `migration_ai_usage_account.sql` di Supabase SQL Editor (aman diulang, idempotent).
3. Deploy ulang `index.ts`: **`supabase functions deploy ai-proxy`** — PERHATIKAN, TANPA flag
   `--no-verify-jwt` lagi (beda dari semua rilis sebelumnya).
4. **Sebelum install APK baru**, coba dulu tes A/B di atas (curl tanpa Authorization) ke endpoint
   yang baru di-deploy — harus 401. Kalau masih lolos, JANGAN lanjut, ada yang salah.
5. Install APK baru (version code 19 kalau APK device masih di bawah itu), login dengan Google,
   baru coba fitur AI apa pun (chat/mindmap/dst) — harus jalan normal. Coba juga tanpa login dulu
   (logout kalau perlu) — fitur AI harus nolak dengan pesan jelas minta login.
6. BYOK: isi API key sendiri di Pengaturan > Lanjutan TANPA login — fitur AI harus tetap jalan
   (jalur ini gak boleh ikut ke-gate).

## 27 Oktober 2026 — companion-v2.2.32-20261027

**Budget harian GLOBAL per-provider — "jangan mepet sama limit dari key yang ada, kita tetapkan sendiri":**

William minta batas RKR sendiri yang punya jarak aman dari limit asli provider, bukan ikut
mepet ke limit provider. Riset dulu (sub-agent) sebelum pasang angka — 2 temuan penting yang
mengubah rencana awal:

1. **Limit provider itu PER AKUN, bukan per API key** — dicek eksplisit di dokumentasi Gemini,
   Groq, Cerebras, OpenRouter, SiliconFlow: SEMUANYA bilang limit berlaku per akun/organisasi.
   Artinya kalau kamu punya 3 key dari AKUN YANG SAMA, kapasitasnya TETAP SAMA kayak 1 key —
   rotasi key cuma nolong kalau key-nya dari akun/signup yang BEDA. Karena ai-proxy nggak bisa
   tau dari kode itu akun yang sama atau beda, budget baru di bawah ini SENGAJA angka flat per
   provider (bukan dikali jumlah key kayak rencana awal).
2. **Cuma 2 dari 11 "provider" (termasuk gemini-embed) yang punya angka harian RESMI** — Groq
   (1000 RPD per-org) dan OpenRouter (50 RPD per-akun, sudah dipakai dari awal). Sisanya (Gemini,
   Cerebras, Cloudflare, NVIDIA, Z.ai, Cohere, SiliconFlow, SambaNova) nggak punya angka harian
   resmi buat model yang dipakai RKR — beberapa provider literally nggak publish angka tier
   gratisnya di manapun. **Catatan tambahan yang ketemu:** model `zai-glm-4.7` di Cerebras
   sepertinya nggak ada di tabel tier gratis resmi mereka — kemungkinan bukan gratis, perlu
   dicek ulang kalau sering gagal.

**File yang berubah:**

1. **`supabase/functions/ai-proxy/index.ts`** (dikirim terpisah) — `PROVIDER_DAILY_BUDGET`
   (constant baru): tiap provider dikasih budget harian sendiri, ditandai `[RESMI]` (≈70% dari
   angka resmi provider, "jarak aman 30%") atau `[GUESS]` (pagar pengaman kasar kita sendiri,
   buat provider yang nggak punya angka resmi — persis semangat "anggaran yang tersedia" yang
   diminta William). Dicek di dalam loop provider SEBELUM provider itu dicoba (skip ke fallback
   berikutnya kalau budgetnya abis, bukan tolak seluruh request) — beda dari `DAILY_DEVICE_CAP`
   yang nolak di depan sebelum coba provider manapun. Action `"stats"` sekarang ikut nampilin
   budget di samping jumlah pemakaian tiap provider.
2. **`network/GeminiService.kt`** — `ProviderUsage` DTO dapat field baru `budget: Int?`.
3. **`debug/AiUsageDebugActivity.kt`** — layar statistik sekarang nampilin "X / budget" per
   provider (bukan cuma angka mentah tanpa konteks).

Semua angka `[GUESS]`/`[RESMI]` gampang diubah langsung di `index.ts` — naikin kalau ternyata
kekecilan dari pemakaian wajar, turunin kalau provider itu sering gagal duluan sebelum budget RKR
sendiri kena.

## 26 Oktober 2026 — companion-v2.2.31-20261026 (2)

**Debug "Cek Limit AI" + batas pemakaian harian per device, biar endpoint nggak bisa dihabisin dari luar app:**

Latar belakang: William minta (1) fitur debug buat cek sisa limit tiap API, dan (2) proteksi
kalau APK didecompile terus endpoint `ai-proxy` dipakai dari luar app (nanya apa bisa
"dienkripsi"). Riset dulu (2 sub-agent, 26 Okt) sebelum nulis kode apapun — hasilnya ngubah
pendekatan dari yang kebayang awalnya:
- **8 dari 10 provider SAMA SEKALI gak expose cara cek sisa kuota lewat API** (Gemini, Cerebras,
  Cloudflare, NVIDIA, Z.ai, Cohere, SambaNova) — cuma lewat dashboard login manual. Groq punya
  response header tapi cuma kebaca lewat request chat ASLI (bukan "intip" gratis). SiliconFlow
  punya endpoint tapi itu SALDO UANG bukan jumlah request. Jadi **nggak mungkin** bikin "sisa
  limit" yang akurat buat semua provider — solusinya ai-proxy nyatet SENDIRI pemakaiannya.
- **"Enkripsi" endpoint/anon key TIDAK bisa nahan orang yang niat bongkar APK** — batasan
  fundamental (kunci buat "buka"-nya ikut keikut di APK juga), BUKAN soal kurang canggih
  implementasinya. Google Play Integrity API (mekanisme resmi Android) juga diriset — tapi
  verdict "app asli"-nya cuma valid kalau distribusi lewat Play Store, sedangkan RKR dikirim
  sebagai APK langsung, jadi BELUM kepake sekarang.
- Solusi yang REALISTIS dibangun: **device ID acak per-instalasi + batas pemakaian harian
  dihitung di server** — JUJUR ini deterrent/penghalang, BUKAN tembok sempurna, tapi nutup kasus
  paling umum (orang nyalin URL+anon key terus mukul endpoint langsung dari luar app).

**File yang berubah:**

1. **`network/GeminiService.kt`** — `deviceId()` baru (UUID acak dibikin sekali, disimpan di
   SharedPreferences `app_prefs`, reset kalau app di-uninstall). Dikirim di field `deviceId` baru
   pada `EdgeChatReq`/`EdgeEmbedReq` (sama-sama TANPA default, alasan sama kayak field `action`/
   `feature` sebelumnya). DTO baru `AiUsageReq`/`ProviderUsage`/`DeviceUsage`/`AiUsageStatsResp` +
   method baru `fetchUsageStats()` (debug-only, numpang `edgePost()`/`edgeMessage()` yang sudah
   ada). Juga perbaikan kecil: pesan error HTTP 429 sekarang pakai pesan dari server kalau ada
   (sebelumnya selalu teks generik "coba lagi beberapa menit" — sekarang bisa nampilin "coba lagi
   besok" buat kasus batas harian).
2. **`debug/AiUsageDebugActivity.kt` (BARU)** — activity terpisah, ikon launcher sendiri ("Cek
   Limit AI"), pola yang sama kayak `debug/PoeAuthTestActivity.kt` dulu (sudah dihapus 25 Okt).
   Nampilin: pemakaian+batas device ini hari ini, dan jumlah panggilan BERHASIL per provider hari
   ini (gabungan semua device) — BUKAN "sisa limit" (lihat alasan di atas).
3. **`AndroidManifest.xml`** — registrasi activity di atas.
4. **`supabase/functions/ai-proxy/index.ts`** (dikirim terpisah, bukan di zip ini) — tabel baru
   `ai_usage_daily` (lihat migrasi SQL, juga dikirim terpisah): dicatat tiap kali provider
   berhasil jawab, dicek sebelum nyoba provider manapun (device yang lewat `DAILY_DEVICE_CAP = 60`
   ditolak 429 duluan, hemat kuota provider juga). **FAIL OPEN** kalau tabelnya belum ada (migrasi
   SQL belum dijalankan) — AI tetap jalan tanpa batas sampai migrasinya kejalanin, urutan deploy
   bebas. Action baru `"stats"` buat baca statistiknya.

**PENTING buat kamu — 3 langkah di luar akses Claude:**
1. Jalankan `migration_ai_usage_daily.sql` (dikirim terpisah) di Supabase Dashboard > SQL Editor.
2. Ganti isi `ai-proxy/index.ts` yang sama di Dashboard, lalu `supabase functions deploy ai-proxy --no-verify-jwt` ulang.
3. Install APK v2.2.31 ini (zip cuma berisi file yang berubah — 3 file di atas + `build.gradle` +
   `CHANGELOG.md` ini, TIDAK ada folder `debug/` baru di zip karena itu 1 file baru, bukan
   perubahan — filenya ADA di zip, cuma foldernya belum ada di project kamu kalau belum pernah ada
   `debug/` sebelumnya, pastikan ke-extract ke tempat yang benar).

Angka `DAILY_DEVICE_CAP = 60` (gabungan semua fitur per device per hari) itu TEBAKAN WAJAR, bukan
hasil pengukuran — gampang diubah di `index.ts` kalau user asli kena batas padahal pemakaian wajar.

## 26 Oktober 2026 — companion-v2.2.30-20261026

**`ai-proxy`: deteksi fitur mind map dipindah dari tebakan `maxTokens` ke field `feature` eksplisit:**

1. **`network/AiEngine.kt`** — `PromptedAiBackend.complete()` (abstract) dapat parameter baru
   `feature: String`, SENGAJA tanpa nilai default (biar semua 8 pemanggil di bawahnya WAJIB
   diisi, bukan kelupaan). Kedelapan override (`summarize`, `chat`, `mindMap`, `enrichMindMap`,
   `textAction`, `flashcards`, `chaptersFromTopic`, `chaptersFromText`) masing-masing ngirim nama
   method-nya sendiri persis sebagai string (`"summarize"`, `"chat"`, `"mindmap"`, dst — `mindMap`
   Kotlin jadi `"mindmap"` huruf kecil semua biar konsisten sama pengecekan string di edge function).
2. **`network/GeminiService.kt`** — `EdgeChatReq` (DTO yang dikirim ke edge function) dapat field
   baru `feature: String`, juga TANPA default (kalau dikasih default, kotlinx.serialization
   `encodeDefaults=false` bakal diam-diam gak ngirim field ini pas isinya kebetulan sama kayak
   default — sama persis kasus field `action` yang pernah ketemu bug ini sebelumnya). `feature`
   dialirkan dari `complete()` → `call()` → `callEdgeProxy()`, termasuk di DUA pemanggilan retry
   rekursif `call()` (retry 429 & retry rotasi model 404/503) — kalau cuma salah satu yang
   diupdate, info fitur bisa hilang pas lagi retry.
3. **`network/OpenAiCompatibleService.kt`** — `complete()` override dapat parameter `feature`
   juga (wajib, biar cocok sama tanda tangan abstract-nya), tapi SENGAJA tidak dipakai di badan
   fungsinya — jalur BYOK manggil provider asli langsung pakai key user sendiri, bukan lewat
   `ai-proxy`, jadi info fitur ini gak relevan buat jalur ini.
4. **`supabase/functions/ai-proxy/index.ts`** (dikirim terpisah, bukan di zip ini — edge function
   bukan bagian dari project Android) — sekarang baca `payload.feature` langsung buat nentuin
   `forMindmap`, GANTI cara lama yang nebak dari `maxTokens >= 5000`. APK versi lama yang belum
   kirim field ini otomatis `feature=undefined` → `forMindmap=false` → tetap jalan normal kayak
   sebelum ada optimisasi OpenRouter-buat-mindmap ini (gak crash, cuma gak kebagian prioritas
   OpenRouter) — urutan deploy APK vs edge function BEBAS, dua-duanya aman.

**PENTING buat kamu:** zip ini cuma berisi 3 file Kotlin yang berubah + `build.gradle` +
`CHANGELOG.md` ini — TIDAK ada perubahan `AndroidManifest.xml` kali ini. `index.ts` edge
function dikirim terpisah (bukan bagian zip Android), ganti isi file yang sama di Supabase
Dashboard kamu, lalu `supabase functions deploy ai-proxy --no-verify-jwt` ulang biar perubahan
field `feature` ini aktif di server.

## 25 Oktober 2026 — companion-v2.2.29-20261025

**Rencana integrasi Poe (OAuth per-user) dibatalkan — kode uji Tahap 1 dicabut:**

1. **`debug/PoeAuthTestActivity.kt` (DIHAPUS)** — bersama package `debug/`-nya (sekarang kosong,
   dihapus juga). Ini activity uji OAuth+PKCE Poe dari companion-v2.2.28-20261024, tidak pernah
   menyentuh `AiEngine.kt`/fitur AI RKR yang sebenarnya. Dicabut karena rencana Poe (dan rencana
   penerusnya, Sign in with ChatGPT) sudah dibatalkan — arah baru: tambah provider lewat key
   bersama di edge function `ai-proxy` (lihat entri berikutnya saat sudah ada implementasinya).
2. **`AndroidManifest.xml`** — entry `<activity>` + komentarnya untuk activity di atas ikut dicabut.
   Tidak ada permission yang perlu dicabut (INTERNET tetap dipakai fitur lain).
3. **PENTING buat kamu:** zip pengiriman ini CUMA berisi file yang berubah (manifest, build.gradle,
   CHANGELOG ini) — zip gak bisa "mengirim" penghapusan file. Hapus manual folder
   `app/src/main/kotlin/com/ruangkerja/companion/debug/` (beserta isinya) di project kamu sebelum
   build ulang, kalau tidak manifest akan mengacu ke class yang sudah tidak ada di zip ini (tidak
   masalah kalau foldernya masih ada di project kamu saat ini — tetap harus dihapus manual).

## 24 Oktober 2026 — companion-v2.2.28-20261024

**Tahap 1 (Validasi Penghalang) dari integrasi Poe — kode uji OAuth terpisah, BELUM menyentuh fitur AI RKR:**

1. **`debug/PoeAuthTestActivity.kt` (BARU)** — activity terpisah dengan ikon launcher sendiri
   ("Tes OAuth Poe"), satu-satunya tujuan: memastikan alur OAuth 2.0 + PKCE Poe bisa diselesaikan
   dari Android sampai dapat `api_key`, sebelum provider Poe sungguhan ditulis ke `AiEngine.kt` di
   Tahap 2. Pakai redirect URI loopback (`http://localhost:<port>/callback`, server HTTP lokal
   sementara lewat `ServerSocket` biasa) karena dokumentasi resmi Poe bilang URI `localhost` "work
   automatically" tanpa perlu didaftarkan — bukan custom URI scheme (`rkr://...`), yang sama sekali
   tidak disebut dokumentasi mereka. Tidak ada dependency baru (pakai `Intent.ACTION_VIEW` ke
   browser default, bukan Custom Tabs/`androidx.browser`; token exchange pakai OkHttp yang sudah
   ada). Client ID diisi manual di layar (tersimpan di `SharedPreferences` terpisah
   `poe_oauth_test`, BUKAN prefs BYOK yang ada), bukan lewat `local.properties`, biar gampang
   dicoba ulang tanpa rebuild. Lihat komentar lengkap di file ini untuk cara pakai.
2. **`AndroidManifest.xml`** — satu `<activity>` baru buat activity di atas. Tidak ada permission
   baru (INTERNET sudah ada).
3. **PENTING:** package `debug/` ini + entrinya di manifest WAJIB dihapus sebelum rilis publik —
   token hasil tes sengaja cuma ditampilkan sekilas di layar (awal karakter saja), tidak disimpan
   permanen atau dienkripsi, karena ini murni alat verifikasi redirect URI, bukan fitur.

## 21 Oktober 2026 — companion-v2.2.27-20261021

**Masukan William atas popup "Saran Ruang Kerja" hasil redesign ronde 4/4, + 1 pertanyaan soal warna dark mode:**

1. **Tombol "Batal" diperbaiki** — di redesign ronde 4/4 sebelumnya, "Batal" SALAH diam-diam
   memanggil `dismissDraftMatch()` (nandain status "Diabaikan" permanen di Notification Center),
   padahal maksudnya cuma cancel/tutup pop-up biasa. William konfirmasi ini salah. Sekarang
   "Batal" memanggil `clearDraftMatchPopup()` — sama persis seperti tombol X/tap-di-luar dialog,
   tidak mengubah status apa pun.
2. **Tombol "Abaikan" dikembalikan** — di redesign ronde 4/4, aksi `dismissDraftMatch()` ikut
   melebur ke tombol "Batal" di atas, jadi tombol "Abaikan" sendiri hilang dari tampilan (ini yang
   ditanyakan William: "tombol abaikan nya kemana?"). Sekarang "Batal" dan "Abaikan" jadi 2 tombol
   teks terpisah, sejajar, di bawah garis pemisah yang sama.
3. **Baris "Ingatkan nanti" disederhanakan** — ikon kotak centang (`CheckBoxOutlineBlank`) di
   sebelah kiri dihapus; baris kustom (border + `clickable` manual) diganti pemanggilan `GhostBtn`
   biasa (komponen yang sama dengan tombol "Lihat"), ikon lonceng tetap ada di kiri teks. Aksinya
   (`snoozeDraftMatch()`) tidak berubah.
4. **Warna latar dark mode dinetralkan** — William menanyakan kenapa warna latar dark mode kerasa
   "warm" alih-alih hitam/abu-abu biasa. Sebelumnya memang sengaja pakai palet "Stone" (abu-abu
   condong hangat) untuk menghindari kesan "hitam pekat" yang tidak nyaman di layar AMOLED untuk
   sesi belajar lama. William pilih opsi netralkan rona hangatnya TANPA mengubah tingkat gelap/
   kontrasnya (dihitung dari rata-rata R/G/B tiap warna lama, jadi R=G=B di tingkat keterangan yang
   sama persis). Diubah di `ui/theme/Color.kt` (`DarkBg`, `DarkBgCard`, `DarkBgMuted`,
   `DarkBgAccent`, `DarkBorder` — `DarkText`/`DarkTextMuted` SENGAJA tidak disentuh, itu warna teks
   bukan "aksen background"), `ui/theme/Theme.kt` (`onPrimary`/`onError` di `darkColorScheme`, dulu
   hardcode warna stone yang sama), dan `res/values/themes.xml` (warna window/status/navigation bar
   SEBELUM Compose sempat render, biar tidak ada kedipan warm→netral saat app baru dibuka). Mode
   terang (light mode) dan warna aksen (`AccentChoice` — Indigo/Teal/Blue/Violet/Emerald/
   Terracotta) TIDAK disentuh, keduanya di luar yang ditanyakan.

## 19 Oktober 2026 — companion-v2.2.26-20261019

**4 perbaikan/penambahan di luar 4 ronde "ekosistem draft" yang sudah disepakati — masukan baru William setelah ronde 4/4 selesai:**

1. **Bahasa baku di seluruh teks UI** — William menunjuk `"Gak ada saran..."` di halaman Notifikasi
   sebagai contoh, tapi aturan yang diminta berlaku untuk SELURUH proyek: bahasa informal (gak/nggak/
   kamu/buat-sebagai-"untuk"/dulu-sebagai-"terlebih dahulu"/biar/dst.) hanya boleh dipakai di chat
   dengan Claude, BUKAN di teks yang dilihat pengguna aplikasi. Diaudit ulang seluruh `app/src/main/
   kotlin/**/*.kt` (bukan cuma file yang disentuh ronde 1-4) — ditemukan pelanggaran di 11 file,
   termasuk beberapa yang SUDAH ADA sebelum proyek ini dimulai: `NotificationCenterScreen.kt`,
   `notification/DailyReminderReceiver.kt`, `SearchScreen.kt`, `TaskScreen.kt`, `WorkspaceListScreen.
   kt`, `TabMateri.kt`, `TabFlashcard.kt`, `TabMindMap.kt`, `TabRingkasan.kt`,
   `WorkspaceDetailScreen.kt`, `viewmodel/AppViewModel.kt` (1 pesan error). Komentar kode (`//`, `/**
   */`) TIDAK disentuh — itu memang sengaja boleh informal, beda dari teks UI.
2. **Swipe antar-tab di halaman Notifikasi** (`NotificationCenterScreen.kt`) — tab status (Belum
   Direspon/Nanti/Dilihat/Diabaikan/Dipindahkan) sekarang bisa digeser, bukan cuma ditekan, pola
   `HorizontalPager` + `ScrollableTabRow` yang sama persis dengan halaman Bab (`ChapterDetailScreen.
   kt`). State tab lokal (`selectedTab`) diganti `pagerState.currentPage` sebagai sumber kebenaran
   tunggal, konten tiap tab (filter+grouping) dipindah ke dalam isi `HorizontalPager` per halaman.
3. **Cuplikan isi untuk draft tanpa judul** — helper baru `draftDisplayTitle()` (`util/ChapterNamer.
   kt`, 2 overload: `(Draft)` dan `(title, content)`) diekstrak dari logic yang sebelumnya cuma ada
   di `HomeDrawerRow`. Dipakai di 4 tempat sekarang: drawer (`HomeDrawer.kt`, diretrofit), popup saran
   & dialog "Nama Bab Baru" (keduanya pindah ke `MainActivity.kt`, lihat poin 4), dan
   `SuggestionRow` di `NotificationCenterScreen.kt` (snapshot, bukan `Draft` hidup — field baru
   `draftContentSnapshot: String = ""` ditambahkan ke `DraftSuggestion` di `Entities.kt`, default
   kosong biar data lama tetap kebaca, diisi di `upsertSuggestionStatus()`/`AppViewModel.kt`).
4. **Popup saran tetap di halaman Notifikasi, tidak usah pindah ke Beranda** — akar masalah: popup
   `draftMatch` + lanjutannya (`chapterPicker`, `newChapterPrompt`) dan overlay `draftEditTarget`
   dulu CUMA dirender di dalam `WorkspaceListScreen.kt` (Beranda); dipicu dari halaman lain
   (`NotificationCenterScreen`), popup itu tidak pernah muncul karena `NavHost` cuma meng-compose
   satu rute aktif. Keempatnya dipindah ke level `RuangKerjaApp`/`MainActivity.kt` (sejajar
   `ModalNavigationDrawer`, di luar `NavHost`) — sekarang selalu ter-compose di rute manapun.
   `SuggestionRow`'s `onClick` di `NotificationCenterScreen.kt` tidak lagi memanggil `onBack()`
   setelah `reopenSuggestion()` (baris itu sendiri yang dulu memaksa pindah ke Beranda supaya
   popup kelihatan — sekarang sudah tidak perlu).

## 19 Oktober 2026 (ronde 4/4) — companion-v2.2.25-20261019

**Ekosistem draft, ronde 4/4 (TERAKHIR dari 4 ronde yang disepakati 17 Okt): redesign popup "Saran Ruang Kerja"**
- William konfirmasi companion-v2.2.24-20261018.zip aman di-build, kasih SATU screenshot referensi
  (mockup dari popup draft yang sama, teks "ada kaitan dengan" dari ronde 2 kebawa — bukan dialog
  lain) buat acuan desain ulang popup ini, lalu minta lanjut.
- Isi & SEMUA 4 aksi yang udah ada (Lihat/Pindahkan/snooze/dismiss) TETAP SAMA PERSIS — murni
  reskin visual, BUKAN fitur baru. Pemetaan dari layout lama ke referensi:
  - Badge ikon dokumen (`Icons.Outlined.Description`, ikon yang sama yang udah dipakai buat
    "Draft" di menu FAB) di atas judul dialog.
  - "Lihat"/"Pindahkan" tetap 2 tombol sejajar; "Pindahkan" nambah ikon panah di KANAN teks.
  - "Nanti" (dulu `GhostBtn` berdiri sendiri) jadi satu baris bergaris + ikon centang-kosong di
    kiri + ikon lonceng di kanan ("Ingatkan nanti") — aksinya SAMA PERSIS (`snoozeDraftMatch()`),
    seluruh baris itu sendiri jadi area tap-nya, cuma gaya tombolnya yang beda.
  - "Abaikan" (dulu `GhostBtn` berdiri sendiri) jadi teks polos "Batal" di bawah garis pemisah.
    **[ASUMSI, perlu dikonfirmasi William]**: aksinya DIPERTAHANKAN sama kayak "Abaikan" lama
    (`dismissDraftMatch()` — nandain status DISMISSED permanen), BUKAN sekadar tutup-dialog-tanpa-
    aksi, soalnya cuma INI satu-satunya tombol di seluruh app yang manggil fungsi itu — kalau
    diganti jadi tutup-doang, kategori "Diabaikan" di Notification Center gak akan pernah keisi
    lagi. Gampang ditukar ke `clearDraftMatchPopup()` aja kalau ternyata maksud "Batal" di
    referensi itu emang cuma Batal/Cancel biasa.
- `ui/components/CommonComponents.kt` — 2 perluasan BACKWARD-COMPATIBLE ke komponen yang dipakai
  luas di app ini (dicek semua titik pemanggil, 1:1 aman):
  1. `PrimaryBtn` — parameter baru `trailingIcon: Boolean = false` (default = PERSIS perilaku
     lama, ikon di kiri teks kalau `icon` diisi). Ditaruh di AKHIR daftar parameter.
  2. `AppDialog` — parameter baru `icon: ImageVector? = null` (default = PERSIS perilaku lama,
     tanpa badge), nampilin `IconBox` (komponen yang udah ada) di atas judul kalau diisi. Ditaruh
     SEBELUM `content` tapi tetap aman: dicek seluruh 18 titik pemanggilan `AppDialog` di app
     ini, semuanya cuma lewatin 2 argumen posisi (title, onDismiss) + trailing lambda, gak ada
     yang lewatin argumen posisi ke-3 — jadi `icon` otomatis default null, trailing lambda tetap
     ke-bind ke `content` (parameter terakhir) seperti biasa.
- `ui/screens/WorkspaceListScreen.kt` — dialog `draftMatch` (popup "Saran Ruang Kerja") ditulis
  ulang total pakai 2 komponen di atas + elemen baru (baris centang+lonceng, teks "Batal"
  standalone pakai `Box` + `clickable`, bukan `TextButton` Material — ngikutin idiom app ini yang
  lain, mis. `HomeDrawerContent`). Ikon panah (`Icons.Default.ArrowForward`, BUKAN varian
  `AutoMirrored` — dihindarin sesuai catatan kehati-hatian ronde 3 soal resiko mismatch namespace)
  dan ikon centang/lonceng (`Icons.Outlined.CheckBoxOutlineBlank`/`Icons.Outlined.Notifications`)
  semuanya dari `material-icons-extended` yang udah jadi dependency, nggak nambah library baru.
- versionCode/versionName → `11`/`"2.2.25"`.
- Ini menutup SEMUA 4 ronde dari urutan yang disepakati 17 Okt. Verifikasi masih statis (dibaca
  ulang + dicek 18 titik pemanggil `AppDialog` satu-satu buat mastiin perluasan parameternya aman),
  belum compile on-device.

## 18 Oktober 2026 (ronde 3/4) — companion-v2.2.24-20261018

**Ekosistem draft, ronde 3/4: pemindahan manual + nama Bab baru gak hardcode lagi**
- `util/ChapterNamer.kt` (BARU) — `generateChapterName(draft)`: heuristik NON-AI (konsisten sama
  filosofi `DraftMatcher` "gratis & instan > akurat"; William sendiri milih opsi non-AI). Draft
  berjudul → judul itu sendiri. Draft tanpa judul → baris pertama isinya + prefix ACAK dari
  beberapa pilihan (bukan satu kata tetap). Draft kosong total → nama bertanggal. Hasilnya di sini
  CUMA SARAN awal, bukan final.
- `viewmodel/AppViewModel.kt`:
  1. State/fungsi pemindahan MANUAL baru: `startManualMove`/`cancelManualMove`/
     `pickWorkspaceForManualMove` — user bisa milih SENDIRI Ruang Kerja tujuan draft kapan aja
     (gak nunggu auto-match), lalu funnel ke `_chapterPicker` yang SAMA persis kayak alur
     auto-match (reuse penuh, gak ada dialog/logic Bab terpisah).
  2. State/fungsi baru `_newChapterPrompt`/`promptNewChapterName`/`confirmNewChapterName`/
     `cancelNewChapterPrompt` — `moveDraftToWorkspace()` (kasus workspace masih 0 bab) dan
     `moveDraftToNewChapter()` DULU langsung bikin Bab nama hardcode "Catatan Cepat". SEKARANG
     dua-duanya mampir ke `promptNewChapterName()` dulu — nyodorin saran dari `generateChapterName`
     tapi NUNGGU user konfirmasi/edit nama lewat dialog baru sebelum Bab-nya beneran dibikin.
  3. `NewChapterPrompt` — data class BARU, sengaja top-level (bukan nested di `AppViewModel`)
     karena perlu diakses dari `WorkspaceListScreen.kt`; StateFlow publik gak boleh "bocorin" tipe
     private (ini kesalahan desain yang sempat kebuat lalu dibenerin sebelum sempat di-build).
  4. [FIX self-audit] `@OptIn(ExperimentalCoroutinesApi::class)` yang tadinya nempel langsung di
     atas `class AppViewModel` (dipakai `flatMapLatest` di dua tempat) sempat ke-geser nangkring
     di atas `NewChapterPrompt` pas data class itu disisipkan di antaranya — ketauan & dibenerin
     sebelum sempat ke-build, bukan dari error compile.
- `ui/screens/WorkspaceListScreen.kt` — tombol "+ Buat Bab Baru: Catatan Cepat" di dialog pilih Bab
  diganti "+ Buat Bab Baru" (nama hardcode-nya emang udah gak ada lagi). Dua dialog baru: pilih
  Ruang Kerja tujuan (pemindahan manual) & konfirmasi/edit nama Bab baru (prefilled dari saran
  heuristik, pola input sama kayak dialog ubah-nama Ruang Kerja yang udah ada).
- `ui/screens/HomeDrawer.kt` — tiap baris draft di drawer sekarang punya aksi "Pindahkan" baru di
  menu titik tiga (sebelum "Hapus", soalnya non-destruktif) — ini entry point pemindahan manual.
  Ikon `Icons.Default.Folder` dipilih (bukan ikon panah/"move") buat jaga-jaga dari resiko
  mismatch namespace `AutoMirrored` kayak yang sempat bikin build gagal ronde sebelumnya.
- `MainActivity.kt` — sambungin aksi "Pindahkan" drawer ke `vm.startManualMove(d)`.
- versionCode/versionName → `10`/`"2.2.24"`.
- Verifikasi masih statis (dibaca ulang tiap file abis edit, dicek konsistensi lintas-file) — belum
  ada compile/uji on-device buat ronde ini.

## 18 Oktober 2026 (ronde 2/4) — companion-v2.2.23-20261018

**Ekosistem draft, ronde 2/4: teks saran gak hedging lagi + kategori status di Notification Center**
- `ui/screens/WorkspaceListScreen.kt` — teks popup saran: "kemungkinan besar berkaitan dengan"
  (HIGH) / "mungkin berkaitan dengan" (MEDIUM) diganti "cocok dengan" / "ada kaitan dengan".
  Masukan William: "jangan 'kemungkinan cocok', rasanya plin-plan" — kata kunci yang nyantol itu
  FAKTA (hasil regex), yang gak pasti cuma relevan-nggaknya buat user, bukan ketemunya.
- `ui/screens/NotificationCenterScreen.kt` — dua perubahan:
  1. `SuggestionRow` — teks saran (kasus DISMISSED/SNOOZED/default) ikut diganti sama kayak di
     atas, sekarang pakai `s.confidence` (field yang udah ada, sebelumnya gak dipakai teksnya)
     buat milih "cocok dengan" vs "ada kaitan dengan".
  2. Layar ini sekarang punya TAB status di atas (Belum Direspon/Nanti/Dilihat/Diabaikan/
     Dipindahkan — urut dari paling perlu aksi ke paling "kelar"), masing-masing nunjukkin
     jumlah. Di DALAM tab yang dipilih, tetap dikelompokkan per tanggal kayak sebelumnya
     (`groupSuggestionsByDay` gak diubah sama sekali — cuma dipanggil di list yang udah
     difilter). Default buka di tab "Belum Direspon". Pesan kosong dibedain: kosong total
     (belum pernah ada saran) vs kosong di tab yang lagi dipilih doang.
- versionCode/versionName → `9`/`"2.2.23"`.
- Verifikasi masih statis — dibaca ulang full-file abis edit buat jaga-jaga setelah build ronde
  sebelumnya sempat gagal gara-gara gap file, tapi tetap belum ada compile/uji on-device.

## 18 Oktober 2026 (lengkap) — companion-v2.2.22-20261018

**Build pertama gagal — bukan bug kode baru, file dari ronde v2.2.20/v2.2.21 kayaknya belum
semua ketaruh di project William**
- Error compile yang dilaporkan: `DraftMatcher.kt:62` & `AppViewModel.kt:498` — dua-duanya
  "Unresolved reference / No parameter with name 'dismissedMatchWorkspaceIds'". Field ini
  dideklarasikan di `Entities.kt` (ditambahin 16 Okt, v2.2.20) — errornya CUMA mungkin muncul
  kalau `Entities.kt` di project William gak punya field ini, padahal dua file yang error (plus
  `DraftEditor.kt`/`LocalStorage.kt`) semuanya udah lama (sejak v2.2.20/21) ngandelin field itu
  ada. Jadi bukan regresi dari kode ronde 1 (v2.2.22) kemarin — kemungkinan besar `Entities.kt`
  (dan mungkin file lain di ronde v2.2.20/21) belum sempat ke-apply ke project sebelum ronde ini
  ditumpuk di atasnya (pola sama kayak gap version 12-13 Okt).
- Gak nebak lebih jauh soal sebabnya (gak ada akses ke device) — solusinya: kirim ULANG satu zip
  LENGKAP semua file yang ngandelin/nyentuh `dismissedMatchWorkspaceIds` dari v2.2.20 s.d. ronde
  ini sekaligus (`Entities.kt`, `AppRepository.kt`, `DraftEditor.kt`, `LocalStorage.kt`,
  `DraftMatcher.kt`, `AppViewModel.kt`, `DraftSuggestionCheckReceiver.kt`), biar apa pun yang
  kelewat sebelumnya ketutup sekarang — nimpa file yang udah benar itu aman (isinya sama).
- versionCode/versionName TETAP `8`/`"2.2.22"` — ini bukan kerjaan baru, cuma nyelesaiin
  pengiriman v2.2.22 yang kemarin ternyata belum lengkap ke-apply.

## 17 Oktober 2026 — companion-v2.2.22-20261017

**Ekosistem draft, ronde 1/4: tunda pop-up & notifikasi sampai draft "didiemin" ~seminggu**
- William klarifikasi "sistem deteksinya agak aneh" jadi 6 poin konkret (dicatat lengkap di
  memory project). Disepakati dicicil 4 ronde; ini ronde pertama: cuma soal TIMING, belum
  nyentuh teks/kategori Notification Center, pindah manual, atau desain pop-up (ronde 2-4).
- `util/DraftMatcher.kt` — tambah `DRAFT_DORMANT_MS` (konstanta 7 hari, dipakai BERSAMA oleh
  dua titik di bawah biar gak ada risiko dua sisi diam-diam beda nilai).
- `viewmodel/AppViewModel.kt` — `checkDraftMatch()` (dipanggil tiap draft ditutup) sekarang
  CUMA nyatet match jadi status PENDING, gak peduli HIGH/MEDIUM — pop-up instan buat HIGH
  dihapus total. Pop-up beneran baru nongol lewat `checkDormantMatches()` (fungsi baru, jalan
  SEKALI tiap app dibuka lewat `init{}` baru, nunggu `repo.isReady()` dulu): nyari suggestion
  PENDING yang draft-nya udah `updatedAt` >=7 hari DAN gak lagi di-snooze, baru TRANSISI ke SEEN
  + tampilin pop-up (satu per app-open, pola sama kayak pop-up modal lain — satu dalam satu
  waktu). "Didiemin" diukur dari `Draft.updatedAt` yang udah ada, BUKAN field timestamp baru —
  draft yang masih aktif diedit otomatis nunda terus karena `updatedAt`-nya ikut maju.
- `notification/DraftSuggestionCheckReceiver.kt` — temuan tambahan pas ngecek ini: notif
  Android harian (jam 19:00) ternyata SAMA SEKALI gak peka umur draft, nagih dari hari pertama
  bahkan untuk MEDIUM yang pop-up-nya sendiri gak pernah muncul — dan pencet "Nanti" gak
  ngaruh ke notif ini sama sekali (cuma ngecek status, bukan `snoozedMatchUntil`). Diperbaiki
  sekalian: receiver ini sekarang ikut baca `drafts.json` (masih langsung dari disk, filosofi
  ringan tetap dipegang) buat cross-check umur & status snooze pakai `DRAFT_DORMANT_MS` yang
  sama dengan di atas.
- versionCode/versionName → `8`/`"2.2.22"`.
- Verifikasi masih statis (baca kode), belum ada compile/uji on-device.

## 16 Oktober 2026 (koreksi) — companion-v2.2.21-20261016

**Migrasi otomatis dismissedMatchWorkspaceId → dismissedMatchWorkspaceIds**
- William menyoroti entri v2.2.20 di bawah: "efek samping" (satu saran lama BISA nongol sekali
  lagi abis update, baru permanen ke-dismiss abis di-"Abaikan" ulang) itu tambal, bukan beresan
  tuntas — benar, dan migrasi otomatisnya genuinely gampang, cuma belum sempat dikerjain ronde
  sebelumnya.
- `data/db/LocalStorage.kt` — `coreJob` (satu-satunya titik `drafts.json` dibaca dari disk pas app
  kebuka) sekarang langsung GABUNGIN isi `dismissedMatchWorkspaceId` (field lama) ke
  `dismissedMatchWorkspaceIds` (field baru) begitu ke-load, lalu ngosongin field lama (null) di
  memori. Jalan tiap kali app dibuka (bukan migrasi sekali-terus-berharap-kesave), jadi draft yang
  gak pernah disentuh lagi pun tetap ke-migrasi tiap load. Hasilnya: dismissal lama GAK PERNAH
  ilang (digabung, bukan dibuang) DAN gak ada lagi skenario "bisa nongol sekali lagi" — state yang
  dipakai DraftMatcher/UI selalu udah termigrasi sejak app kebuka, titik.
- `data/db/Entities.kt` — komentar field lama diperbarui: dia bukan dibiarin nganggur permanen,
  tapi aktif dipakai LocalStorage.kt sebagai jembatan baca migrasi satu-arah (baca sekali, kosongin,
  gak pernah dibaca app logic lain). Satu-satunya sisa: draft yang gak pernah disave ulang abis
  migrasi bakal tetap nyimpen key lama itu di `drafts.json` DI DISK (bukan di memori/perilaku) —
  harmless, gak pernah kebaca/kepake lagi dari titik manapun.
- `app/build.gradle` — versionCode/versionName → `7`/`"2.2.21"`.

## 16 Oktober 2026 (lanjutan) — companion-v2.2.20-20261016

**Audit sistem notifikasi — fix temuan #2**
- `data/AppRepository.kt` — `deleteDraft()` sekarang cascade-delete semua `DraftSuggestion` yang
  `draftId`-nya cocok SEBELUM hapus draftnya. Dulu gak ada sama sekali — `deleteSuggestion()`
  fungsinya udah ada dari awal tapi gak pernah kepanggil dari manapun (kebuat, lupa disambungin).
  Akibatnya entri basi nyangkut status SEEN/PENDING/SNOOZED selamanya begitu draft sumbernya hilang
  (baik hapus manual maupun abis "Pindahkan", dua-duanya lewat fungsi ini), nge-inflate badge
  Notification Center & notifikasi harian ~19:00 tanpa cara ngilanginnya dari UI. Satu draft bisa
  punya lebih dari satu entri suggestion (kalau pernah cocok ke beberapa workspace beda di waktu
  beda) jadi semua yang draftId-nya cocok dibersihin, bukan cuma satu.
- `data/db/Entities.kt` — field `Draft.dismissedMatchWorkspaceId` (tunggal, `String?`) diganti
  `dismissedMatchWorkspaceIds` (`Set<String>`). Field lama DIBIARIN ADA (gak dihapus/ganti nama)
  biar `drafts.json` lama tetap kebaca aman, cuma gak dipakai logic lagi. Alasan: field tunggal
  bikin draft yang di-"Abaikan" ke workspace A lalu belakangan ke workspace B (konten draftnya
  berubah, match baru) NIMPA dismissal A — penolakan ke A kelupaan, bisa disaranin lagi padahal
  user udah bilang nggak. Efek samping yang disadari: dismissal LAMA di field lama gak otomatis
  "pindah" ke field baru, jadi ada kemungkinan satu suggestion yang kebetulan lagi nyimpen dismissal
  lama nongol sekali lagi abis update — begitu di-"Abaikan" ulang, permanen kesimpen di field baru.
- `util/DraftMatcher.kt` — filter kecocokan ikut pindah ke field baru (`!in` Set, bukan `!=` String).
- `viewmodel/AppViewModel.kt` (`dismissDraftMatch`) — nulis ke field baru dengan NAMBAH ke Set
  (`draft.dismissedMatchWorkspaceIds + match.workspace.id`), bukan nimpa.
- `ui/screens/DraftEditor.kt` — DUA tempat yang konstruksi `Draft(...)` baru dari draft lama
  (`commitAndClose` & autosave debounced) ikut dibawain field baru dari `initial`, persis kayak
  `dismissedMatchWorkspaceId`/`snoozedMatchUntil` yang udah lebih dulu butuh perlakuan ini — kalau
  kelewat, field barunya ke-reset ke kosong TIAP draft disimpan ulang (bukan cuma sekali pas migrasi).
- `app/build.gradle` — versionCode/versionName → `6`/`"2.2.20"`.

## 16 Oktober 2026 — companion-v2.2.19-20261016

**Audit sistem notifikasi (permintaan William) — fix temuan #1**
- `BootReceiver.kt` — ketemu lewat audit: receiver ini (nyalain ulang alarm AlarmManager abis HP
  reboot, soalnya semua alarm kehapus tiap restart) cuma re-arm `scheduleDailyCheck` (Pengingat
  Harian). `scheduleSuggestionCheck` (notifikasi Android buat saran draft→workspace, ekosistem
  draft Fase 3) KELEWAT, padahal polanya persis sama (komentar `AppViewModel.setDraftSuggestionNotifEnabled`
  sendiri bilang "pola sama persis kayak dailyReminderEnabled"). Akibatnya: toggle "Saran Draft"
  yang aktif bakal berhenti notif SELAMANYA abis HP di-restart, sampai togglenya dimatiin-nyalain
  manual. Diperbaiki: ditambah blok re-arm `scheduleSuggestionCheck` (gated `draft_suggestion_notif_enabled`,
  sama persis strukturnya kayak blok daily yang udah ada di atasnya).
- `app/build.gradle` — versionCode/versionName dinaikin ke `5`/`"2.2.19"`. Sekalian komentar
  histori version bump yang numpuk di `defaultConfig` (13 & 15 Okt) dipindah/diringkas jadi satu
  pointer ke file ini, biar gak nambah panjang tiap rilis — histori lengkapnya tetap ada, di sini.
- Temuan #2 audit notifikasi (entri `DraftSuggestion` basi gak pernah dibersihkan) BELUM disentuh
  — nunggu giliran, William minta mulai dari #1 dulu.

## 15 Oktober 2026 — companion-v2.2.18-20261015

**Fix bug FAB "+" Beranda "loncat" pas app baru dibuka**
- `MainActivity.kt` — William lapor: pas Beranda baru kebuka, 3 menu bottom nav bar sempet nggak
  langsung muncul, tombol "+" (FAB) kelihatan lebih turun terus TIBA-TIBA naik — klik ke tombol itu
  jadi sering gak kena/kerasa gak responsif. Akar masalah: `currentBackStackEntryAsState()` masih
  null di FRAME PERTAMA (NavHost baru nempelin graph & pindah ke startDestination belakangan di
  composition pass yang sama, state-nya baru ke-update di recomposition berikutnya) — `currentRoute`
  jadi null sesaat → `isHome` false → `NavigationBar` (bottom nav) SAMA SEKALI gak dirender di frame
  itu → tinggi yang kesedia buat NavHost (lewat padding Scaffold) sementara lebih tinggi dari
  harusnya → FAB (nempatin diri lewat `Alignment.BottomEnd`, ngisi tinggi NavHost itu) kerender
  lebih turun, lalu "loncat" naik begitu NavigationBar nongol di frame berikutnya & Scaffold ngitung
  ulang padding-nya — pas-pasan sama momen diklik. Diperbaiki: `currentRoute` sekarang fallback ke
  `"home"` (persis startDestination NavHost, satu-satunya rute yang mungkin aktif sebelum graph-nya
  nempel) alih-alih `null`, jadi bottom bar & FAB langsung di posisi final dari frame pertama, gak
  ada lagi loncatan. Verifikasi masih statis (baca & telusur kode) — belum ke-compile+jalan beneran
  di sandbox ini, tunggu konfirmasi build dari device.

## 13 Oktober 2026 — companion-v2.2.17-20261013

**Nutup gap version**
- `app/build.gradle` — `versionCode`/`versionName` ketauan ketinggalan jauh (`2`/`"2.1.2"`) dari
  urutan zip pengiriman (udah sampe v2.2.16) — tanda `WorkspaceListScreen.kt` hasil Prioritas 1
  (12 Okt) belum sempat disalin balik ke project asli sebelum di-build & di-zip ulang. Dinaikin
  ke `3`/`"2.2.17"` biar nerusin urutan. versionCode SEKEDAR +1 dari yang kebaca di sini — BUKAN
  dicocokkan ke angka asli di device, cek manual kalau ternyata udah lebih tinggi.
- `ui/screens/WorkspaceListScreen.kt` — fix Prioritas 1 (11 Okt, lihat entri di bawah) diterapkan
  ULANG di sini, di tree root yang benar. Dicek dulu: file lain yang disentuh audit 10-11 Okt
  (`Entities.kt`, `AppViewModel.kt`, `TabMateri.kt`, `MarkdownStructureCleaner.kt`, `AiEngine.kt`)
  SEMUANYA udah versi terbaru di tree ini — cuma file ini doang yang ketinggalan, bukan seluruh
  project.

**Dependency — centralisasi versi Kotlin**
- `gradle/libs.versions.toml` + `app/build.gradle` — plugin Compose & serialization compiler
  (`org.jetbrains.kotlin.plugin.compose`/`.serialization`) dulu hardcode versi string `'2.3.21'`
  sendiri-sendiri di `app/build.gradle`, terpisah dari `kotlin-android` yang ambil dari
  `version.ref "kotlin"` di version catalog walau nilainya sama persis. Drift risk kalau salah
  satu diupdate lupa yang lain (pola sama kayak token-budget `AiEngine.kt`). Sekarang dua-duanya
  juga lewat `alias(libs.plugins.kotlin.compose/.serialization)`, satu sumber kebenaran.

## 11 Oktober 2026 — companion-v2.2.16-20261011

**Prioritas 1 (standardisasi waktu) — SELESAI**
- `ui/screens/WorkspaceListScreen.kt` — `Calendar.getInstance()` (ikut timezone device) diganti
  `DateUtils.currentZoned()` (userZoneId terpusat, Asia/Jakarta) buat nentuin jam salam Beranda.
  Pelanggaran prinsip Date & Time Standardization yang kelewat pas migrasi 9 file 26-27 Sep (baris
  ini baru ada di file ini belakangan, abis split 28 Sep). Import `java.util.*` yang jadi gak
  kepake ikut dibuang. Perilaku berubah dikit DENGAN SENGAJA: salam sekarang tetap ngikutin WIB
  walau timezone device beda — sesuai keputusan "zona waktu aplikasi" di dokumen prioritas audit.

**Prioritas 2 (FIX15) — diverifikasi statis**
- Gak ada file yang diubah. Ditelusuri ulang logic round-trip parse → live-edit sync (dua arah) →
  toggle toolbar → serialize di `NativeTextField.kt`, `NativeTextState.kt`, `NativeLiveStyle.kt`,
  `MarkdownStyleCodec.kt`, `NativeFormatting.kt`, `CommandDispatcher.kt`, dan bagian toolbar
  `NotebookEditor.kt` — gak ketemu kejanggalan referensi/logic.

**Prioritas 3 (GoogleCalendarAuth) — status gak berubah**
- Gak ada file yang diubah. `AuthorizationRequest.builder()` (huruf kecil) masih belum pernah
  kebukti compile beneran atau nggak.

**Prioritas 4 (audit config Android) — SELESAI (13 Okt, lihat `technical-principles.md`)**
- Permission (`READ_CALENDAR`/`WRITE_CALENDAR`/dst.) cocok persis kebutuhan kode, gak ada yang
  nganggur/kurang. `FileProvider`/`file_paths.xml` valid, cocok sama path yang dipakai kode.
  `minifyEnabled false` di release (sengaja/default, gak diubah). `play-services-auth:21.2.0`
  sudah ada (setup eksternal Google Calendar selesai).

**Prioritas 5 (bersih-bersih struktur) — SELESAI**
- Dihapus 3 folder kosong nyasar di root project: `{data`, `{data/db,network,viewmodel,ui`,
  `{data/db,network,viewmodel,ui/{screens,components,theme}}` — sisa `mkdir` brace-expansion yang
  kebuka literal (bukan ke-expand), dikonfirmasi kosong (0 file) sebelum dihapus.
