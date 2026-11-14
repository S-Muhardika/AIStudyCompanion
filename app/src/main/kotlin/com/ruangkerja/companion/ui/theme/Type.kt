package com.ruangkerja.companion.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
val AppTypography = Typography(
    headlineLarge  = TextStyle(fontFamily=FontFamily.Default, fontWeight=FontWeight.ExtraBold, fontSize=25.sp),
    headlineMedium = TextStyle(fontFamily=FontFamily.Default, fontWeight=FontWeight.Bold, fontSize=20.sp),
    titleLarge     = TextStyle(fontFamily=FontFamily.Default, fontWeight=FontWeight.Bold, fontSize=17.sp),
    bodyLarge      = TextStyle(fontFamily=FontFamily.Default, fontWeight=FontWeight.Normal, fontSize=14.sp, lineHeight=22.sp),
    bodyMedium     = TextStyle(fontFamily=FontFamily.Default, fontWeight=FontWeight.Normal, fontSize=13.sp, lineHeight=20.sp),
    labelSmall     = TextStyle(fontFamily=FontFamily.Default, fontWeight=FontWeight.Medium, fontSize=11.sp)
)
