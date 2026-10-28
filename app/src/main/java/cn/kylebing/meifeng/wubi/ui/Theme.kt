package cn.kylebing.meifeng.wubi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFF95353)

@Composable
fun MeifengTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 页面灰底、功能区白底，和 iOS 分组列表一样把区块分开
    val colors = if (dark) {
        darkColorScheme(
            primary = Accent,
            background = Color.Black,
            surface = Color(0xFF1C1C1E)
        )
    } else {
        lightColorScheme(
            primary = Accent,
            background = Color(0xFFF2F2F7),
            surface = Color.White
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
