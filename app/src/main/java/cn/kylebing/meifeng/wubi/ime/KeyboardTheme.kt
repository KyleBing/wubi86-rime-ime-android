package cn.kylebing.meifeng.wubi.ime

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import cn.kylebing.meifeng.wubi.data.KeyboardPrefs

/** 对齐 iOS KeyboardTheme：浅色白键 / 灰功能键，暗色 systemGray2/3 */
data class KeyboardTheme(
    val background: Int,
    val keyFill: Int,
    val specialFill: Int,
    val keyPressed: Int,
    val specialPressed: Int,
    val title: Int,
    val accent: Int,
    val separator: Int,
    val panelFill: Int,
    val hint: Int,
    val keyShadow: Int,
    val popupFill: Int
) {
    companion object {
        fun current(context: Context): KeyboardTheme {
            val night = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            // iOS：亮色字母键 systemBackground 白，功能键 systemGray3；暗色字母 systemGray2，功能键更深一档
            return if (night) {
                KeyboardTheme(
                    background = Color.parseColor("#1C1C1E"),
                    keyFill = Color.parseColor("#636366"),       // systemGray2 近似
                    specialFill = Color.parseColor("#48484A"),  // systemGray3 近似
                    keyPressed = Color.parseColor("#48484A"),
                    specialPressed = Color.parseColor("#636366"),
                    title = Color.WHITE,
                    accent = KeyboardPrefs.accentColor,
                    separator = Color.parseColor("#3A3A3C"),
                    panelFill = Color.parseColor("#2C2C2E"),
                    hint = Color.parseColor("#8E8E93"),
                    keyShadow = Color.argb(90, 0, 0, 0),
                    popupFill = Color.parseColor("#636366")
                )
            } else {
                KeyboardTheme(
                    // iOS 键盘面板底色（系统材质近似）
                    background = Color.parseColor("#D1D5DB"),
                    keyFill = Color.WHITE,
                    specialFill = Color.parseColor("#AEB3BE"),
                    keyPressed = Color.parseColor("#AEB3BE"),
                    specialPressed = Color.WHITE,
                    title = Color.parseColor("#000000"),
                    accent = KeyboardPrefs.accentColor,
                    separator = Color.parseColor("#C6C6C8"),
                    panelFill = Color.parseColor("#E5E5EA"),
                    hint = Color.parseColor("#6C6C70"),
                    keyShadow = Color.argb(40, 0, 0, 0),
                    popupFill = Color.WHITE
                )
            }
        }

        fun dp(context: Context, v: Float) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
    }
}
