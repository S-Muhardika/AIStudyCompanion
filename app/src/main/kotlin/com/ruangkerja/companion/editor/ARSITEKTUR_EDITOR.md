# Arsitektur Editor RKR — kenapa ada dua editor

Hasil audit item 5 (work order "Advanced Refactoring & Architecture", 1 Okt 2026).

## Keputusan
- **Notebook Editor** (`ui/components/NotebookEditor.kt` + paket `editor/`) dan **Draft Editor** (`ui/screens/DraftEditor.kt`) **sengaja dibiarkan sebagai dua sistem terpisah**.
- Hanya duplikat murni (kelas **A**) yang disatukan: grid tabel (`TableGrid`) dan `lineBoundsAt`. Sisanya kelas B/C: jangan digabung.
- Jangan membuat `BaseEditor`, model blok bersama, atau mesin editor bersama. Mesin teksnya berbeda (EditText native vs `BasicTextField` Compose), jadi abstraksinya akan bocor di semua sisi.

## Klasifikasi
A = duplikat murni · B = mirip tetapi kebutuhan berbeda · C = independen

| Dimensi | Notebook | Draft | Kelas | Keputusan |
|---|---|---|---|---|
| Mesin teks | EditText native (`NativeTextState`); Quote: `RichTextState` | `BasicTextField` + `TextFieldValue` | C | tidak disentuh |
| Model blok | `Block` (Text/Quote/Table), `BlockId` UUID | `DraftBlock` (Txt/Tbl), id Int hanya saat runtime | B | terpisah: Draft tak butuh Quote, command, atau id tetap |
| Parser | `parseBlocks`: tabel = baris header + baris pemisah; kutipan `> `; ada ambang pemecahan paragraf panjang | `parseDraftBlocks`: rangkaian baris `\|…\|` apa pun = tabel | B | terpisah: predikat tabel beda (Notebook ketat, Draft longgar demi draf lama). Isi sel sudah dibagi lewat `parseTableLines` |
| Serializer | `blocksToText` (wajib `.toMarkdown()`, bukan `.text`) | `serializeDraftBlocks` | B | terpisah. `buildTableMarkdown` sudah dibagi |
| Seleksi / kursor | seleksi EditText + `focusedBlockIndex` | `TextFieldValue.selection` + `FocusRequester` per blok | C | tidak disentuh |
| Undo / redo | `HistoryManager` (snapshot markdown seluruh dokumen, maks 100) | tidak ada | C | tidak ditambah (bukan duplikasi) |
| Format inline | gaya & judul disimpan sebagai rentang/penanda, bukan karakter markdown di teks | markdown ringan terlihat (`**`, `# `, `☐/☑`, `---`) + `VisualTransformation` | C | beda filosofi produk (lihat catatan 1) |
| Checklist | `- [ ]` / `- [x]` | `☐` / `☑` | B | beda format simpan. Belum ada alur Draft→Notebook; kalau nanti dibuat, wajib ada konversi |
| Tabel (UI) | `TableGrid` | `TableGrid` | **A → digabung** | `ui/components/TableEditor.kt`; `onFocus` opsional |
| Autosave | polling 300 ms; setelah 2 tick stabil: simpan + push history | `LaunchedEffect(title, flatContent)` + debounce 700 ms + guard `lastSaved` | B | terpisah: sumber state berbeda |
| Persistensi | `onSave(title, content)` lewat `TabMateri` | autosave lewat `AppViewModel` ke entitas `Draft` | C | entitas & siklus hidup berbeda |
| State | `SnapshotStateList<Block>` + `BlockManager` + `CommandDispatcher` | `SnapshotStateList<DraftBlock>` + fungsi lokal di `DraftEditorOverlay` | B | terpisah: pola command hanya berguna untuk toolbar kaya milik Notebook |
| Siklus hidup | tertanam di tab Materi | overlay layar penuh (`Dialog`), `commitAndClose` | C | tidak disentuh |
| Performa | polling 300 ms memanggil `blocksToText` seluruh dokumen | `VisualTransformation` + `serializeDraftBlocks` dihitung ulang tiap recomposition | B | aman untuk ukuran sekarang; pantau kalau draf bisa sangat panjang |
| `lineBoundsAt` | `editor/core/NativeFormatting.kt` | salinan pribadi | **A → digabung** | satu fungsi di `editor/core`; bug posisi 0 diperbaiki |
| Tombol toolbar | `ToolbarBtn` (30 dp, status aktif) | `DraftToolbarBtn` (`IconButton` 38 dp) | B | tampilan memang berbeda |
| Hitung kata, snippet "tampilkan keyboard" | 1 baris / 3 baris | sama | A mikro | dibiarkan: terlalu kecil untuk layak abstraksi |

## Titik temu yang sah (satu-satunya yang dibagi)
- `TableGrid`, `parseTableLines`, `buildTableMarkdown` di `ui/components/TableEditor.kt`
- `lineBoundsAt` di `editor/core/NativeFormatting.kt` (fungsi murni tanpa state)

## Catatan (bukan duplikasi, tidak diubah)
1. Prinsip "markdown tidak terlihat" berlaku di Notebook tetapi belum di Draft: pengguna Draft melihat `# ` dan `**` apa adanya (hanya digayakan). Itu keputusan produk, bukan bug teknis; menyeragamkannya adalah pekerjaan fitur terpisah.
2. Toolbar format Draft sengaja no-op saat fokus di sel tabel, jadi Draft memakai `TableGrid` tanpa `onFocus`.
3. Loop polling Notebook O(n) per tick. Dibiarkan: itu workaround reaktivitas yang sudah terdokumentasi di `NotebookEditor.kt`.

## Aturan menjaga pemisahan
- Jangan menyatukan model blok, parser, atau serializer hanya karena terlihat mirip.
- Fungsi baru yang benar-benar identik di kedua editor boleh dipindah ke titik temu di atas. Yang perilakunya berbeda, biarkan terpisah.
