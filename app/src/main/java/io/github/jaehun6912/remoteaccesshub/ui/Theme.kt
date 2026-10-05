package io.github.jaehun6912.remoteaccesshub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import java.util.Locale

/** 화면 색 묶음. Windows 버전 UI/Theme.cs와 같은 값(글자·배경 명암비는 단위 검사로 확인). */
@Immutable
data class Palette(
    val name: String,
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val subText: Color,
    val muted: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val logBackground: Color,
    val logText: Color,
)

object Palettes {
    /** 어두운 테마. 공유기 관리 화면(어두운 바탕, 초록 강조)과 어울리게 맞췄다. */
    val Dark = Palette(
        "dark", true,
        background = Color(0xFF16181A), surface = Color(0xFF202326), surfaceAlt = Color(0xFF2A2E32), border = Color(0xFF3A3F44),
        text = Color(0xFFE9ECEF), subText = Color(0xFFB3BAC1), muted = Color(0xFF7E868E),
        accent = Color(0xFF7CB342), accentPressed = Color(0xFF689F38), onAccent = Color(0xFF0E1A04),
        success = Color(0xFF81C784), warning = Color(0xFFFFB74D), danger = Color(0xFFEF7B7B), info = Color(0xFF64B5F6),
        logBackground = Color(0xFF121416), logText = Color(0xFFC9CFD5),
    )

    val Light = Palette(
        "light", false,
        background = Color(0xFFF3F4F6), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFEEF0F3), border = Color(0xFFD5D9DE),
        text = Color(0xFF1D2125), subText = Color(0xFF525A63), muted = Color(0xFF9AA1A9),
        accent = Color(0xFF3F7D20), accentPressed = Color(0xFF336A19), onAccent = Color(0xFFFFFFFF),
        success = Color(0xFF2E7D32), warning = Color(0xFF9A5B00), danger = Color(0xFFC62828), info = Color(0xFF1565C0),
        logBackground = Color(0xFFFAFBFC), logText = Color(0xFF2B3137),
    )

    /** "system" | "dark" | "light" */
    fun resolve(setting: String?, systemDark: Boolean): Palette = when ((setting ?: "").trim().lowercase(Locale.ROOT)) {
        "dark" -> Dark
        "light" -> Light
        else -> if (systemDark) Dark else Light
    }

    /** WCAG 상대 명암비. */
    fun contrast(a: Color, b: Color): Double {
        fun lum(c: Color): Double {
            fun ch(v: Float): Double {
                val d = v.toDouble()
                return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val l1 = lum(a)
        val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}

val LocalPalette = staticCompositionLocalOf { Palettes.Dark }

@Composable
fun AppTheme(themeSetting: String, content: @Composable () -> Unit) {
    val p = Palettes.resolve(themeSetting, isSystemInDarkTheme())
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.accent, onSecondary = p.onAccent,
            background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.subText, outline = p.border, outlineVariant = p.border,
            error = p.danger, onError = Color(0xFF1A0505), surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceAlt,
            surfaceContainerHighest = p.surfaceAlt, surfaceContainerLow = p.surface,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.accent, onSecondary = p.onAccent,
            background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.subText, outline = p.border, outlineVariant = p.border,
            error = p.danger, onError = Color.White, surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceAlt,
            surfaceContainerHighest = p.surfaceAlt, surfaceContainerLow = p.surface,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 기본 아이콘 묶음에 없는 그림(선 아이콘, 24 단위). */
object AppIcons {
    private fun strokeIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** 전원 표시: 위쪽이 트인 원호 + 세로 막대(Windows 아이콘과 같은 도안). */
    val Power: ImageVector = strokeIcon("Power") {
        moveTo(15.9f, 6.6f)
        arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 8.1f, y1 = 6.6f)
        moveTo(12f, 3.5f)
        lineTo(12f, 11.5f)
    }

    /** 모니터(원격 접속). */
    val Monitor: ImageVector = strokeIcon("Monitor") {
        moveTo(5f, 4f); lineTo(19f, 4f)
        arcTo(2f, 2f, 0f, false, true, 21f, 6f); lineTo(21f, 14f)
        arcTo(2f, 2f, 0f, false, true, 19f, 16f); lineTo(5f, 16f)
        arcTo(2f, 2f, 0f, false, true, 3f, 14f); lineTo(3f, 6f)
        arcTo(2f, 2f, 0f, false, true, 5f, 4f); close()
        moveTo(12f, 16f); lineTo(12f, 20f)
        moveTo(8f, 20f); lineTo(16f, 20f)
    }

    /** 공유기. */
    val Router: ImageVector = strokeIcon("Router") {
        moveTo(5f, 13f); lineTo(19f, 13f)
        arcTo(2f, 2f, 0f, false, true, 21f, 15f); lineTo(21f, 18f)
        arcTo(2f, 2f, 0f, false, true, 19f, 20f); lineTo(5f, 20f)
        arcTo(2f, 2f, 0f, false, true, 3f, 18f); lineTo(3f, 15f)
        arcTo(2f, 2f, 0f, false, true, 5f, 13f); close()
        moveTo(7f, 16.5f); lineTo(7.01f, 16.5f)
        moveTo(11f, 16.5f); lineTo(11.01f, 16.5f)
        moveTo(17f, 13f); lineTo(17f, 5f)
        moveTo(14f, 7f)
        arcTo(4.2f, 4.2f, 0f, false, true, 20f, 7f)
    }

    /** 기록(줄 목록). */
    val Log: ImageVector = strokeIcon("Log") {
        moveTo(8f, 6f); lineTo(20f, 6f)
        moveTo(8f, 12f); lineTo(20f, 12f)
        moveTo(8f, 18f); lineTo(20f, 18f)
        moveTo(4f, 6f); lineTo(4.01f, 6f)
        moveTo(4f, 12f); lineTo(4.01f, 12f)
        moveTo(4f, 18f); lineTo(4.01f, 18f)
    }

    /** 지구본(일반 접속). */
    val Globe: ImageVector = strokeIcon("Globe") {
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f)
        arcTo(14f, 14f, 0f, false, true, 12f, 21f)
        arcTo(14f, 14f, 0f, false, true, 12f, 3f)
    }
}
