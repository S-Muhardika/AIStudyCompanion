package com.ruangkerja.companion.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.ruangkerja.companion.data.db.*
import com.ruangkerja.companion.ui.components.*
import com.ruangkerja.companion.ui.theme.*
import com.ruangkerja.companion.viewmodel.AppViewModel

// ── TAB: CATATAN ──────────────────────────────────────────
@Composable
internal fun TabCatatan(ch: Chapter, vm: AppViewModel) {
    var notes by remember(ch.id) { mutableStateOf(ch.notes) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { kotlinx.coroutines.delay(2200); saved = false } }
    Column(Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OutlinedTextField(
            value = notes, onValueChange = { notes = it; saved = false },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Tulis catatan, pertanyaan, atau poin penting...", color = AppTextDim, fontSize = 12.sp) },
            shape = RoundedCornerShape(Radius.control),
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = AppText, lineHeight = 19.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppBorder, unfocusedBorderColor = AppBorder, focusedContainerColor = AppBgCard, unfocusedContainerColor = AppBgCard, focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = AppAccent)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PrimaryBtn(if (saved) "Tersimpan" else "Simpan", { vm.saveNotes(ch, notes); saved = true }, icon = if (saved) Icons.Default.Check else Icons.Default.Save)
        }
    }
}
