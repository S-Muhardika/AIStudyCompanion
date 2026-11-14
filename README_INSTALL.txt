RKR LAUNCHER ICON RESOURCES

Paket ini sudah disesuaikan dengan struktur resource RKR.

ISI:
- app/src/main/res/mipmap-*/ic_launcher.webp
- app/src/main/res/mipmap-*/ic_launcher_round.webp
- app/src/main/res/mipmap-xxxhdpi/ic_launcher_{background,foreground,monochrome}.png
- app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
- app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
- store_listing/play_store_icon_512x512.png (aset terpisah untuk listing Play Store)

CARA PASANG:
1. Cadangkan folder app/src/main/res milik proyek RKR.
2. Ekstrak isi ZIP ke ROOT proyek RKR (folder yang berisi app/).
3. Izinkan file ic_launcher.webp dan ic_launcher_round.webp yang ada ditimpa.
4. Pastikan AndroidManifest.xml menggunakan:
   android:icon="@mipmap/ic_launcher"
   android:roundIcon="@mipmap/ic_launcher_round"
5. Sync Gradle, lalu build dan instal APK.

CATATAN:
- Ikon legacy disimpan sebagai WEBP lossless dengan transparansi.
- Adaptive icon memakai foreground/background/monochrome terpisah.
- XML adaptive icon mereferensikan layer melalui @mipmap dan layer tersebut
  berada di mipmap-xxxhdpi.
- Jangan menyalin play_store_icon_512x512.png ke folder res.
