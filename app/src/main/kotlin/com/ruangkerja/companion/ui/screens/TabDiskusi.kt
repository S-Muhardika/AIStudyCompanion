package com.ruangkerja.companion.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruangkerja.companion.data.db.*
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel

// ── TAB: DISKUSI ──────────────────────────────────────────
// [BARU, 13 Nov] Label tampilan level thinking — dipisah dari nilai yang dikirim ke server
// ("low"/"medium"/"high", lihat AiEngine.chatThinkingLevel) biar teks Indonesia di sini bebas
// diubah kapan aja tanpa nyentuh kontrak nilai yang disimpan/dikirim.
private fun thinkingLevelLabel(level: String?): String = when (level) {
    "low" -> "Thinking: Rendah"
    "medium" -> "Thinking: Sedang"
    "high" -> "Thinking: Tinggi"
    else -> "Thinking: Default"
}

// [BARU, 16 Nov — permintaan William: animasi streaming "jangan tiba-tiba banyak"] Reveal
// bertahap PURE TAMPILAN — data `msg.text` di StateFlow/penyimpanan SELALU utuh & akurat
// persis seperti sebelumnya (tidak disentuh sama sekali di sini), cuma versi yang DITAMPILKAN
// yang "mengejar" secara animasi tiap kali teksnya tumbuh. Ini sengaja ditaruh di layer UI
// doang (TabDiskusi.kt) — TIDAK ada perubahan apa pun ke AppViewModel/AiEngine/GeminiService/
// ChatStreamEvent (kontrak streaming yang baru selesai TAHAP 2 kemarin tidak disentuh).
// Kenapa perlu: provider yang BENERAN native-streaming (Gemini/Cloudflare/SiliconFlow/
// OpenRouter) kirim delta kecil2 jadi sudah kelihatan nyicil dari sendirinya — TAPI provider
// fallback yang buffered (groq/nvidia/zai/cohere/aionlabs, lihat §3.1 audit streaming 14 Nov)
// kirim SATU delta gede isi jawaban lengkap sekaligus, yang kalau ditampilkan apa adanya ya
// persis keluhan "tiba-tiba banyak". Animatable dipilih (bukan loop delay manual) karena bisa
// "mengejar" target yang terus berubah (delta baru datang sebelum animasi lama kelar) tanpa
// perlu nulis ulang logic cancel/restart sendiri. Reset checkpoint (teks mengecil — lihat
// AppViewModel.sendChat, strategi restart_from_beginning) SENGAJA snap langsung (bukan
// animasi mundur, yang bakal aneh dilihat) baru lanjut animasi maju lagi dari situ. Begitu
// status bukan STREAMING lagi (Done/ServerError), fungsi ini dilewati total — pesan lama di
// riwayat tetap tampil instan, nol biaya animasi.
@Composable
private fun rememberRevealedText(fullText: String, isStreaming: Boolean): String {
    if (!isStreaming) return fullText
    val revealed = remember { Animatable(0f) }
    LaunchedEffect(fullText) {
        val target = fullText.length.toFloat()
        if (target <= revealed.value) {
            revealed.snapTo(target)
        } else {
            // ~12ms/karakter buat delta kecil terasa halus, dibatasi 80ms–1200ms biar delta
            // RAKSASA (provider buffered di atas) tetap selesai kebaca cepat, bukan nunggu lama.
            val durasiMs = ((target - revealed.value) * 12).toInt().coerceIn(80, 1200)
            revealed.animateTo(target, tween(durationMillis = durasiMs, easing = LinearEasing))
        }
    }
    val count = revealed.value.toInt().coerceIn(0, fullText.length)
    return fullText.take(count)
}

@Composable
internal fun TabDiskusi(ch: Chapter, vm: AppViewModel) {
    val msgs by vm.chat.collectAsStateWithLifecycle()
    val loading by vm.chatLoading.collectAsStateWithLifecycle()
    // [BARU, 13 Nov — permintaan William: histori multi-thread + pilih level thinking]
    val threads by vm.chatThreads.collectAsStateWithLifecycle()
    val activeThreadId by vm.activeThreadId.collectAsStateWithLifecycle()
    val thinkingLevel by vm.chatThinkingLevel.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }
    var showThinkingMenu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }
    // [BARU, 16 Nov — permintaan William: "layarnya ikut turun"] Efek di atas cuma jalan pas
    // JUMLAH pesan berubah (placeholder STREAMING baru dibuat) — begitu placeholder itu ADA,
    // ukurannya terus membesar tiap delta (lihat AppViewModel.sendChat, updateChatMessage) tanpa
    // msgs.size berubah lagi, jadi list TIDAK ikut turun lagi selama sisa streaming. Efek baru
    // ini yang nutup itu — keyed ke PANJANG teks pesan yang lagi STREAMING, bukan ke isi
    // persisnya (biar gak retrigger kalau cuma status yang berubah tanpa teks berubah).
    // "isNearBottom" sengaja dicek dulu: kalau user lagi scroll ke atas baca riwayat lama,
    // jangan dipaksa balik ke bawah tiap delta masuk — pola umum di aplikasi chat.
    val streamingMsg = msgs.lastOrNull { it.status == "STREAMING" }
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(streamingMsg?.text?.length) {
        if (streamingMsg != null && isNearBottom) listState.animateScrollToItem(msgs.size - 1)
    }

    // .imePadding() di sini yang bikin SELURUH kolom (daftar pesan + input) ikut naik
    // pas keyboard muncul, bukan ketutup diem di tempat kayak sebelumnya.
    Column(Modifier.fillMaxSize().imePadding()) {
        // [BARU, 13 Nov] Header: riwayat percakapan (kiri) + pilih level thinking (kanan).
        // CATATAN SKOPE SENGAJA: tombol thinking SELALU ditampilkan (gak disembunyikan pas
        // provider BYOK non-Gemini aktif di Pengaturan > Lanjutan) — menghindari plumbing
        // reaktif baru dari situ ke sini. Note kecil di bawah dropdown yang menjelaskan cakupan,
        // bukan menyembunyikan kontrolnya. Loading mid-switch-thread BUKAN per-thread (lihat
        // AppViewModel.sendChat) — edge case kecil yang sengaja diterima apa adanya, bukan bug.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box {
                TextButton(onClick = { showHistory = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(Icons.Outlined.History, null, modifier = Modifier.size(15.dp), tint = AppTextMuted)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        threads.find { it.id == activeThreadId }?.title ?: "Percakapan baru",
                        color = AppTextMuted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp)
                    )
                    Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp), tint = AppTextDim)
                }
                DropdownMenu(expanded = showHistory, onDismissRequest = { showHistory = false }) {
                    DropdownMenuItem(
                        text = { Text("+ Percakapan baru") },
                        onClick = { vm.newChatThread(); showHistory = false }
                    )
                    if (threads.isNotEmpty()) HorizontalDivider()
                    threads.forEach { t ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (t.id == activeThreadId) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            onClick = { vm.selectChatThread(t.id); showHistory = false },
                            trailingIcon = {
                                IconButton(onClick = { vm.deleteChatThread(t.id) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Outlined.Delete, "Hapus percakapan", modifier = Modifier.size(15.dp), tint = AppTextDim)
                                }
                            }
                        )
                    }
                }
            }
            Box {
                AssistChip(
                    onClick = { showThinkingMenu = true },
                    label = { Text(thinkingLevelLabel(thinkingLevel), fontSize = 11.5.sp) },
                    // [CATATAN] AutoAwesome dipilih (bukan ikon "otak"/psikologi yang kurang
                    // pasti ada di paket material-icons-extended) — juga konsisten sama ikon
                    // tombol "Bantu" AI yang sudah ada di TabFlashcard/TabRingkasan.
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp)) }
                )
                DropdownMenu(expanded = showThinkingMenu, onDismissRequest = { showThinkingMenu = false }) {
                    listOf(null, "low", "medium", "high").forEach { lvl ->
                        DropdownMenuItem(
                            text = { Text(thinkingLevelLabel(lvl)) },
                            onClick = { vm.setChatThinkingLevel(lvl); showThinkingMenu = false },
                            trailingIcon = if (lvl == thinkingLevel) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(15.dp), tint = AppAccent) }
                            } else null
                        )
                    }
                    HorizontalDivider()
                    // [CATATAN JUJUR, 13 Nov — lihat keputusan-implementasi-ai-proxy §16] Berlaku
                    // nyata HANYA kalau: (a) pakai API key Gemini sendiri di Pengaturan > Lanjutan
                    // (langsung berfungsi), ATAU (b) mode bawaan SETELAH server ai-proxy diupdate
                    // (BELUM — field terkirim tapi diabaikan server saat ini). HAPUS paragraf ini
                    // begitu ai-proxy sudah forward field ini ke Gemini.
                    Text(
                        "Berlaku utk Gemini. Mode bawaan (tanpa API key sendiri): menyusul, menunggu update server.",
                        color = AppTextDim, fontSize = 10.5.sp, lineHeight = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 220.dp)
                    )
                }
            }
        }
        HorizontalDivider(color = AppBorder)
        if (msgs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp).emptyEntrance()) {
                    EmptyIconBadge(Icons.Outlined.ChatBubbleOutline, size = 56)
                    Spacer(Modifier.height(14.dp))
                    Text("Tanyakan sesuatu tentang ${ch.name}", color = AppText, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    if (ch.summary != null) Text("Ringkasan bab digunakan sebagai konteks jawaban.", color = AppTextMuted, fontSize = 12.5.sp, textAlign = TextAlign.Center)
                    else Text("Buat ringkasan terlebih dahulu untuk jawaban yang lebih kontekstual.", color = AppTextMuted, fontSize = 12.5.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(msgs, key = { it.id }) { msg ->
                    // BoxWithConstraints buat tau lebar layar yang tersedia, jadi 85% itu jadi
                    // BATAS MAKSIMAL doang (buat pesan panjang) — bubble pendek kayak "halo"/"test"
                    // sekarang ngikutin lebar teksnya sendiri (wrapContentWidth), nggak dipaksa lebar.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val bubbleMaxWidth = maxWidth * 0.85f
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.role == "user") Arrangement.End else Arrangement.Start) {
                            Column(
                                Modifier.widthIn(max = bubbleMaxWidth)
                                    // [BARU, 16 Nov] Bubble assistant yang lagi STREAMING tumbuh
                                    // tinggi tiap delta — animateContentSize() bikin pertambahan
                                    // tingginya ikut dianimasikan (gak loncat kotak), konsisten
                                    // sama reveal teks di atas. Aman dipasang buat SEMUA bubble
                                    // (bukan cuma yang streaming) — bubble yang sudah selesai
                                    // ukurannya statis, jadi efeknya nol ongkos buat riwayat lama.
                                    .animateContentSize()
                                    .clip(if (msg.role == "user") RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                                    .background(if (msg.role == "user") AppAccent else AppBgMuted)
                                    .padding(Spacing.md)
                            ) {
                                if (msg.role == "assistant") {
                                    // [BARU, 16 Nov] Lihat rememberRevealedText() di atas — animasi
                                    // reveal PURE tampilan, msg.text aslinya tidak disentuh.
                                    val shown = rememberRevealedText(msg.text, msg.status == "STREAMING")
                                    MarkdownText(shown)
                                } else {
                                    Text(msg.text, color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                                }
                                // [BARU, 14-15 Nov — streaming native Diskusi] Pesan assistant
                                // dengan status STREAMING SEKARANG sudah "ada" di msgs sejak
                                // awal (lihat AppViewModel.sendChat), teksnya tumbuh pelan-pelan
                                // lewat item yang SAMA (key=msg.id tidak berubah) — bukan lagi
                                // nunggu item loading terpisah di bawah baru muncul di akhir.
                                // Indikator kecil ini cuma penanda "masih nulis", bukan pengganti
                                // bubble kosong seperti dulu.
                                if (msg.status == "STREAMING") {
                                    Spacer(Modifier.height(4.dp))
                                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 2.dp, color = AppAccent)
                                }
                            }
                        }
                    }
                }
                // [UBAH, 14-15 Nov] Spinner mandiri ini SEKARANG hanya tampil di jeda SANGAT
                // singkat antara tap kirim dan placeholder pesan assistant (status STREAMING)
                // benar-benar masuk ke msgs (lihat AppViewModel.sendChat) — begitu placeholder
                // itu ada, indikator di DALAM bubble-nya sendiri (di atas) yang mengambil alih.
                // Tanpa syarat msgs.none{...} ini, dua indikator bakal tampil berbarengan
                // (bubble kosong + spinner mandiri) begitu placeholder muncul.
                if (loading && msgs.none { it.status == "STREAMING" }) {
                    item {
                        Row { Box(Modifier.clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)).background(AppBgMuted).padding(Spacing.md)) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppAccent) } }
                    }
                }
            }
        }
        HorizontalDivider(color = AppBorder)
        Row(Modifier.fillMaxWidth().background(AppBg).padding(horizontal = Spacing.md, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AppInput(input, { input = it }, "Tulis pertanyaan…", modifier = Modifier.weight(1f))
            // [BARU, 14-15 Nov — streaming native Diskusi] Dulu tombol ini SELALU tombol kirim
            // (cuma redup/nonaktif pas loading, tidak bisa dipakai apa-apa). Sekarang, pas
            // loading, tombol yang SAMA jadi tombol stop (vm.cancelChat()) — dikonfirmasi lewat
            // audit 14 Nov bahwa TIDAK ADA cara apa pun membatalkan chat yang sedang berjalan
            // sebelum ini (Job-nya dulu tidak pernah disimpan sama sekali).
            if (loading) {
                IconButton(
                    onClick = { vm.cancelChat() },
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(AppBgMuted)
                ) {
                    Icon(Icons.Filled.Stop, "Hentikan", tint = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                IconButton(
                    onClick = { if (input.isNotBlank()) { vm.sendChat(input.trim(), ch.id, ch.summary); input = "" } },
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (input.isNotBlank()) AppAccent else AppBgMuted)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Kirim", tint = if (input.isNotBlank()) MaterialTheme.colorScheme.onPrimary else AppTextDim)
                }
            }
        }
    }
}
