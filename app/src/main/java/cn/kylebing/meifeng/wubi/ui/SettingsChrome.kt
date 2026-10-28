package cn.kylebing.meifeng.wubi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 设置页底色：浅色用分组灰，深色用纯黑，色块用 surface
@Composable
fun settingsPageColor(): Color =
    if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)

// 一块功能区：标题在色块外面，内容坐在圆角色块上
@Composable
fun SettingsCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        if (!title.isNullOrEmpty()) {
            Text(
                title,
                Modifier.padding(start = 4.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

// 多行拼成同一块：首尾圆角，中间贴齐
@Composable
fun Modifier.groupedRow(index: Int, count: Int): Modifier {
    val shape = when {
        count <= 1 -> RoundedCornerShape(12.dp)
        index <= 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        index >= count - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RoundedCornerShape(0.dp)
    }
    return this
        .padding(horizontal = 16.dp)
        .background(MaterialTheme.colorScheme.surface, shape)
}
