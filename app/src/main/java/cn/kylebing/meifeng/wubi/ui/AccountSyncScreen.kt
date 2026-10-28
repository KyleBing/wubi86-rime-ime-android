package cn.kylebing.meifeng.wubi.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kylebing.meifeng.wubi.data.UserDictDocument
import cn.kylebing.meifeng.wubi.data.UserDictGroup
import cn.kylebing.meifeng.wubi.data.UserDictWriter
import cn.kylebing.meifeng.wubi.sync.CloudDictSyncController
import cn.kylebing.meifeng.wubi.sync.DictSyncDiff
import cn.kylebing.meifeng.wubi.sync.ExtraDictUpdater
import cn.kylebing.meifeng.wubi.sync.UserDictAutoSync
import cn.kylebing.meifeng.wubi.sync.WubiCloudApi
import cn.kylebing.meifeng.wubi.sync.WubiCloudConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private val categoryColors = listOf(
    Color(0xFF007AFF), Color(0xFF30B0C7), Color(0xFF5856D6), Color(0xFFAF52DE),
    Color(0xFFFF2D55), Color(0xFFFF9500), Color(0xFF00C7BE), Color(0xFF32ADE6),
    Color(0xFF34C759), Color(0xFFA2845E)
)

private enum class AccountConfirm { ClearSync, DestroyAccount }

// 账户同步：账号入口、用户词双向同步、扩展词库只拉
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountHost(controller: CloudDictSyncController, navBack: NavBack, onTitle: (String?) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    var userDoc by remember { mutableStateOf(UserDictDocument.empty()) }
    var extraDoc by remember { mutableStateOf(UserDictDocument(true, mutableListOf())) }
    var autoOn by remember { mutableStateOf(UserDictAutoSync.isEnabled) }
    var autoText by remember { mutableStateOf(UserDictAutoSync.lastResultText) }
    var logged by remember { mutableStateOf(WubiCloudConfig.isLoggedIn) }
    var avatar by remember { mutableStateOf(WubiCloudConfig.userInfo?.avatar.orEmpty()) }
    var pullingExtra by remember { mutableStateOf(false) }
    var extraMessage by remember { mutableStateOf<String?>(null) }
    var extraError by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun reload() {
        userDoc = UserDictWriter.loadActiveDocument()
        extraDoc = ExtraDictUpdater.loadLocalDocument()
        autoOn = UserDictAutoSync.isEnabled
        autoText = UserDictAutoSync.lastResultText
        logged = WubiCloudConfig.isLoggedIn
        avatar = if (logged) WubiCloudConfig.userInfo?.avatar.orEmpty() else ""
    }

    DisposableEffect(settings, controller.showDiffSheet) {
        navBack.pop = {
            when {
                controller.showDiffSheet -> {
                    controller.showDiffSheet = false
                    true
                }
                settings -> {
                    settings = false
                    true
                }
                else -> false
            }
        }
        onDispose { navBack.pop = null }
    }
    SideEffect {
        onTitle(
            when {
                controller.showDiffSheet -> "线上 / 线下差异"
                settings -> "同步账号"
                else -> null
            }
        )
    }

    LaunchedEffect(tick, controller.isApplying, controller.showDiffSheet) {
        if (!controller.isApplying) reload()
    }
    LaunchedEffect(logged) {
        if (!logged) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) { runCatching { WubiCloudApi.refreshStoredAvatar() }.getOrDefault("") }
        if (WubiCloudConfig.isLoggedIn && url.isNotEmpty()) avatar = url
    }

    if (controller.showDiffSheet) {
        SyncDiffScreen(controller)
        return
    }
    if (settings) {
        SyncAccountScreen(onChanged = {
            logged = WubiCloudConfig.isLoggedIn
            avatar = WubiCloudConfig.userInfo?.avatar.orEmpty()
            tick++
        })
        return
    }

    val userLabel = WubiCloudConfig.userInfo?.let { u ->
        u.nickname.ifEmpty { u.email.ifEmpty { "已登录" } }
    } ?: "未登录"

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SettingsCard(title = "账号") {
            ListItem(
                headlineContent = { Text(if (logged) userLabel else "API 与登录") },
                supportingContent = { Text(if (logged) "已登录" else "未登录") },
                leadingContent = { AccountAvatar(if (logged) avatar else "", 36.dp) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { settings = true }
            )
            }
        }
        item {
            SectionTitle("用户词库", Color(0xFF30B0C7))
            SettingsCard {
            DictOverview(
                entryCount = userDoc.entryCount,
                groups = userDoc.groups.filter { it.entries.isNotEmpty() },
                groupUnit = "个分组",
                accent = Color(0xFF30B0C7),
                secondary = Color(0xFF007AFF),
                emptyText = "本地还没有用户词，可在词库页添加或从云端拉取。",
                lastAt = WubiCloudConfig.lastSyncAt,
                lastLabel = "上次同步"
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("自动同步")
                Switch(autoOn, {
                    autoOn = it
                    UserDictAutoSync.isEnabled = it
                    autoText = UserDictAutoSync.lastResultText
                })
            }
            if (autoOn && !autoText.isNullOrEmpty()) {
                Text(
                    autoText!!,
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SyncActionButton(
                label = if (controller.isComparing) "对比中…" else "对比差异后同步",
                icon = Icons.Outlined.Sync,
                enabled = logged && !controller.isComparing && !controller.isApplying,
                onClick = { scope.launch { controller.compareWithRemote() } }
            )
            SyncActionButton(
                label = "本地 + 云：增量合并",
                icon = Icons.Outlined.Sync,
                enabled = logged && !controller.isComparing && !controller.isApplying,
                onClick = { scope.launch { controller.requestSync(CloudDictSyncController.SyncAction.Merge) } }
            )
            SyncActionButton(
                label = "本地 → 云：覆盖云端",
                icon = Icons.Outlined.CloudUpload,
                enabled = logged && !controller.isComparing && !controller.isApplying,
                onClick = { scope.launch { controller.requestSync(CloudDictSyncController.SyncAction.Upload) } }
            )
            SyncActionButton(
                label = "本地 ← 云：覆盖本地",
                icon = Icons.Outlined.CloudDownload,
                enabled = logged && !controller.isComparing && !controller.isApplying,
                onClick = { scope.launch { controller.requestSync(CloudDictSyncController.SyncAction.Download) } }
            )
            controller.statusMessage?.let { msg ->
                Text(
                    msg,
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = if (controller.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "自动同步与 iOS 相同：打开键盘或本 App 后约 2 秒、改词约 3 秒、前台每 10 分钟。键盘收起后系统不能保证仍每 10 分钟跑，下次打开键盘会补上。需登录。对象：${WubiCloudConfig.SYNC_DICT}。分组名称颜色不同，字号随词条多少变化。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
        }
        item {
            SectionTitle("扩展词库", Color(0xFF5856D6))
            SettingsCard {
            DictOverview(
                entryCount = extraDoc.entryCount,
                groups = extraDoc.groups.filter { it.entries.isNotEmpty() },
                groupUnit = "个分类",
                accent = Color(0xFF007AFF),
                secondary = Color(0xFF30B0C7),
                emptyText = "本地还没有扩展词库，可从线上拉取。",
                lastAt = WubiCloudConfig.lastExtraPullAt,
                lastLabel = "上次更新"
            )
            SyncActionButton(
                label = if (pullingExtra) "正在拉取…" else "从线上更新扩展词库",
                icon = Icons.Outlined.CloudDownload,
                enabled = !pullingExtra && !controller.isApplying,
                onClick = {
                    scope.launch {
                        pullingExtra = true
                        extraMessage = null
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val items = WubiCloudApi.pullExtraDict()
                                ExtraDictUpdater.applyOnlineItems(items)
                            }
                        }
                        pullingExtra = false
                        result.onSuccess {
                            extraError = false
                            extraMessage = "新增 ${it.added} 条，扩展词库共 ${it.wordCount} 条"
                            tick++
                        }.onFailure {
                            extraError = true
                            extraMessage = it.message ?: "拉取失败"
                        }
                    }
                }
            )
            extraMessage?.let { msg ->
                Text(
                    msg,
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = if (extraError) MaterialTheme.colorScheme.error else Color(0xFF34C759),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "${WubiCloudConfig.EXTRA_DICT} 是线上数据库的镜像，直接覆盖本地后部署，不做双向同步；无需登录即可拉取。分类名称的颜色不同，字号随词条多少变化。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
        }
    }

    if (controller.isApplying) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("处理中…", Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        color = color,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    )
}

@Composable
private fun SyncActionButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(horizontal = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictOverview(
    entryCount: Int,
    groups: List<UserDictGroup>,
    groupUnit: String,
    accent: Color,
    secondary: Color,
    emptyText: String,
    lastAt: Long,
    lastLabel: String
) {
    val maxCount = groups.maxOfOrNull { it.entries.size } ?: 0
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            ColoredStat(entryCount, "条", accent, 32)
            Spacer(Modifier.width(18.dp))
            ColoredStat(groups.size, groupUnit, secondary, 22)
        }
        if (lastAt > 0L) {
            Text(
                "$lastLabel ${relativeTime(lastAt)}",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (groups.isEmpty()) {
            Text(emptyText, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEachIndexed { index, group ->
                    val color = categoryColors[index % categoryColors.size]
                    val nameSize = categoryFontSize(group.entries.size, maxCount)
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(color.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(group.displayName, color = color, fontSize = nameSize.sp, fontWeight = if (nameSize >= 17) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                        Text(
                            " ${group.entries.size}",
                            color = color.copy(alpha = 0.72f),
                            fontSize = (nameSize - 3).coerceAtLeast(11).sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColoredStat(value: Int, unit: String, color: Color, size: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$value", color = color, fontSize = size.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = color.copy(alpha = 0.8f), fontSize = (size * 0.42f).coerceAtLeast(12f).sp, fontWeight = FontWeight.Medium)
    }
}

private fun categoryFontSize(count: Int, maxCount: Int): Int {
    if (maxCount <= 0 || count <= 0) return 13
    val ratio = count.toDouble() / maxCount
    return when {
        ratio >= 0.45 -> 19
        ratio >= 0.18 -> 17
        ratio >= 0.06 -> 15
        else -> 13
    }
}

private fun relativeTime(at: Long): String {
    val sec = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
    return when {
        sec < 60 -> "刚刚"
        sec < 3600 -> "${sec / 60} 分钟前"
        sec < 86400 -> "${sec / 3600} 小时前"
        else -> "${sec / 86400} 天前"
    }
}

@Composable
fun AccountAvatar(urlString: String, size: androidx.compose.ui.unit.Dp) {
    var bitmap by remember(urlString) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(urlString) {
        val display = avatarDisplayUrl(urlString) ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeStream(URL(display).openStream()) }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    } else {
        Icon(Icons.Outlined.AccountCircle, null, Modifier.size(size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// 七牛原图地址拼缩略图，并把 http 换成 https
private fun avatarDisplayUrl(stored: String): String? {
    var raw = stored.trim()
    if (raw.isEmpty()) return null
    if (raw.startsWith("http://")) raw = "https://" + raw.removePrefix("http://")
    if (!raw.contains("thumbnail_")) raw += "-thumbnail_200px"
    if (!raw.startsWith("https://") && !raw.startsWith("http://")) return null
    return raw
}

// 同步账号：请求地址、登录、注册、删除云端数据、注销
@Composable
fun SyncAccountScreen(onChanged: () -> Unit) {
    var baseURL by remember { mutableStateOf(WubiCloudConfig.baseURL) }
    var email by remember { mutableStateOf(WubiCloudConfig.userInfo?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var statusScope by remember { mutableStateOf("account") }
    var account by remember { mutableStateOf(WubiCloudConfig.userInfo) }
    var avatar by remember { mutableStateOf(account?.avatar.orEmpty()) }
    var confirm by remember { mutableStateOf<AccountConfirm?>(null) }
    var confirmEmail by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    LaunchedEffect(Unit) {
        val url = withContext(Dispatchers.IO) { runCatching { WubiCloudApi.refreshStoredAvatar() }.getOrDefault("") }
        account = WubiCloudConfig.userInfo
        if (url.isNotEmpty()) avatar = url
    }

    fun showStatus(message: String, scopeName: String, isError: Boolean) {
        status = message
        statusScope = scopeName
        statusError = isError
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SettingsCard(title = "请求地址") {
            Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                baseURL,
                { baseURL = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                placeholder = { Text("https://kylebing.cn/wubi/portal/") }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    WubiCloudConfig.baseURL = baseURL
                    baseURL = WubiCloudConfig.baseURL
                    showStatus("已保存请求地址", "address", false)
                }) { Text("保存地址") }
                if (statusScope == "address" && status != null) {
                    Text(status!!, color = if (statusError) MaterialTheme.colorScheme.error else Color(0xFF34C759), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("五笔独立服务，默认 https://kylebing.cn/wubi/portal/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }

        item {
            SettingsCard {
            Column(Modifier.padding(16.dp)) {
            val user = account
            if (user != null && user.isLoggedIn) {
                Text("当前账号", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (statusScope == "account" && status != null) {
                    Text(status!!, color = if (statusError) MaterialTheme.colorScheme.error else Color(0xFF34C759), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(avatar, 52.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(user.nickname.ifEmpty { "已登录" }, style = MaterialTheme.typography.titleMedium)
                        Text(user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Labeled("昵称", user.nickname.ifEmpty { "—" })
                Labeled("邮箱", user.email)
                Text("JWT", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(user.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
                TextButton(onClick = {
                    WubiCloudConfig.logout()
                    account = null
                    password = ""
                    avatar = ""
                    showStatus("已退出登录", "account", false)
                    onChanged()
                }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
            } else {
                Text("登录", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(
                    password, { password = it }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy, onClick = {
                        val mail = email.trim()
                        if (mail.isEmpty() || password.isEmpty()) {
                            showStatus("请填写邮箱和密码", "account", true)
                            return@Button
                        }
                        WubiCloudConfig.baseURL = baseURL
                        baseURL = WubiCloudConfig.baseURL
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { WubiCloudApi.login(mail, password, baseURL) }
                            }
                            busy = false
                            result.onSuccess { info ->
                                account = info
                                password = ""
                                avatar = info.avatar
                                showStatus("登录成功", "account", false)
                                onChanged()
                                if (avatar.isEmpty()) {
                                    val url = withContext(Dispatchers.IO) { runCatching { WubiCloudApi.refreshStoredAvatar() }.getOrDefault("") }
                                    if (url.isNotEmpty()) avatar = url
                                }
                                UserDictAutoSync.syncSoon()
                            }.onFailure {
                                showStatus(it.message ?: "登录失败", "account", true)
                            }
                        }
                    }) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("登录中…")
                        } else Text("登录")
                    }
                    if (statusScope == "account" && status != null) {
                        Text(status!!, Modifier.padding(start = 8.dp), color = if (statusError) MaterialTheme.colorScheme.error else Color(0xFF34C759), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("账号需在浏览器中使用邀请码注册；注册并验证邮箱后，在此登录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
            }
        }

        item {
            SettingsCard(title = "注册账号") {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = { uri.openUri(WubiCloudConfig.registerUrl()) }) { Text("在浏览器中使用邀请码注册") }
            TextButton(onClick = { uri.openUri(WubiCloudConfig.registerApplyUrl()) }) { Text("在浏览器中申请注册") }
            Text("付费用户可先提交申请，审核通过后将收到邀请码邮件；已有邀请码可直接打开注册页。", Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }

        val user = account
        if (user != null && user.isLoggedIn) {
            item {
                SettingsCard(title = "账号数据") {
                TextButton(enabled = !busy, onClick = { confirmEmail = ""; confirm = AccountConfirm.ClearSync }) {
                    Text("删除线上同步数据", color = MaterialTheme.colorScheme.error)
                }
                TextButton(enabled = !busy, onClick = { confirmEmail = ""; confirm = AccountConfirm.DestroyAccount }) {
                    Text("注销账号", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "删除线上同步数据：清空云端备份的个人词库，同步次数归零；本机词库和账号资料不受影响。\n注销账号：永久删除账号及全部线上数据（个人词库备份、本人提交的词条、邀请码、邮箱验证记录、本人提交的注册申请），无法登录也无法恢复；本机词库仍保留在本地。\n两项操作都需要输入完整账号邮箱后才能执行。",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }
        }
    }

    val pending = confirm
    if (pending != null) {
        val destroy = pending == AccountConfirm.DestroyAccount
        AlertDialog(
            onDismissRequest = { confirm = null; confirmEmail = "" },
            title = { Text(if (destroy) "注销账号" else "删除线上同步数据") },
            text = {
                Column {
                    val mail = account?.email.orEmpty()
                    Text(
                        if (destroy) "账号和全部线上数据会被永久删除，无法恢复；本机词库仍保留在本地。\n请输入账号邮箱 $mail 以确认。"
                        else "云端备份的个人词库会被删除，本机词库不受影响。\n请输入账号邮箱 $mail 以确认。"
                    )
                    OutlinedTextField(
                        confirmEmail,
                        { confirmEmail = it },
                        label = { Text("输入账号邮箱确认") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val action = pending
                    val typed = confirmEmail.trim().lowercase()
                    confirm = null
                    confirmEmail = ""
                    val expected = (account?.email ?: WubiCloudConfig.userInfo?.email).orEmpty().trim().lowercase()
                    if (expected.isEmpty() || typed != expected) {
                        showStatus("邮箱不一致，操作已取消", "account", true)
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                if (action == AccountConfirm.DestroyAccount) WubiCloudApi.destroyAccount()
                                else WubiCloudApi.clearSyncData()
                            }
                        }
                        busy = false
                        result.onSuccess { message ->
                            if (action == AccountConfirm.DestroyAccount) {
                                WubiCloudConfig.logout()
                                account = null
                                password = ""
                                avatar = ""
                                onChanged()
                            }
                            showStatus(message, "account", false)
                        }.onFailure { showStatus(it.message ?: "失败", "account", true) }
                    }
                }) { Text(if (destroy) "永久注销" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null; confirmEmail = "" }) { Text("取消") } }
        )
    }
}

@Composable
private fun Labeled(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

// 线上 / 线下差异：数量、列表和三种同步方式
@Composable
fun SyncDiffScreen(controller: CloudDictSyncController) {
    val result = controller.diffResult
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 标题只留在顶栏，避免和页面里再写一遍「线上 / 线下差异」
        if (result == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("加载中…", Modifier.padding(top = 8.dp))
                }
            }
            return
        }
        if (controller.requiresManualChoice || result.isLargeDivergence) {
            Row(Modifier.fillMaxWidth().background(Color(0xFFFF9500).copy(alpha = 0.12f)).padding(12.dp)) {
                Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFF9500))
                Column(Modifier.padding(start = 8.dp)) {
                    Text("差异较大，请确认同步方式", fontWeight = FontWeight.SemiBold)
                    Text(result.largeDivergenceHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 12.dp)) {
            SummaryCell("本地", "${result.localCount} 条", Modifier.weight(1f))
            SummaryCell("云端", if (result.hasRemote) "${result.remoteCount} 条" else "无备份", Modifier.weight(1f))
            SummaryCell("相同", "${result.sameCount} 条", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiffChip(controller, CloudDictSyncController.DiffTab.OnlyLocal, result.onlyLocal.size, Color(0xFF007AFF), Modifier.weight(1f))
            DiffChip(controller, CloudDictSyncController.DiffTab.OnlyRemote, result.onlyRemote.size, Color(0xFF30B0C7), Modifier.weight(1f))
            DiffChip(controller, CloudDictSyncController.DiffTab.Changed, result.changed.size, Color(0xFFFF9500), Modifier.weight(1f))
        }
        val rows = when (controller.selectedTab) {
            CloudDictSyncController.DiffTab.Changed -> emptyList()
            else -> controller.previewWords(controller.selectedTab)
        }
        val changed = controller.previewChanged()
        val total = if (controller.selectedTab == CloudDictSyncController.DiffTab.Changed) changed.size else rows.size
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (total == 0) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(emptyTitle(result), style = MaterialTheme.typography.titleMedium)
                    Text(emptyDescription(result), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn {
                    if (controller.selectedTab == CloudDictSyncController.DiffTab.Changed) {
                        val shown = changed.take(CloudDictSyncController.PREVIEW_LIMIT)
                        items(shown, key = { it.first.key + "|" + it.second.key }) { (local, remote) ->
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("本地  ${local.word}  ${local.code}  ${metaLabel(local)}", style = MaterialTheme.typography.bodyMedium)
                                Text("云端  ${remote.word}  ${remote.code}  ${metaLabel(remote)}", style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                        if (changed.size > shown.size) {
                            item { Text("还有 ${changed.size - shown.size} 条未展示", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                        }
                    } else {
                        val shown = rows.take(CloudDictSyncController.PREVIEW_LIMIT)
                        items(shown, key = { it.key }) { item ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.word, fontWeight = FontWeight.Medium)
                                Text("  ${item.code}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.groupName.isNotEmpty()) {
                                    Text(
                                        item.groupName,
                                        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(metaLabel(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                        if (rows.size > shown.size) {
                            item { Text("还有 ${rows.size - shown.size} 条未展示", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val hint = when {
                result.isIdentical -> "本地与云端完全一致"
                !result.hasRemote -> "云端尚无该文件备份，可将本地上传"
                result.isLargeDivergence -> "请根据上方差异选择一种同步方式"
                else -> null
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { scope.launch { controller.confirmSync(CloudDictSyncController.SyncAction.Upload) } },
                enabled = !controller.isApplying,
                modifier = Modifier.fillMaxWidth()
            ) { Text("本地 → 云：覆盖云端\n用本地整份替换云端（本地为空会清空云端）") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { scope.launch { controller.confirmSync(CloudDictSyncController.SyncAction.Download) } },
                enabled = result.hasRemote && !controller.isApplying,
                modifier = Modifier.fillMaxWidth()
            ) { Text("本地 ← 云：覆盖本地\n用云端整份替换本地并部署") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { scope.launch { controller.confirmSync(CloudDictSyncController.SyncAction.Merge) } },
                enabled = !controller.isApplying,
                modifier = Modifier.fillMaxWidth()
            ) { Text("本地 + 云：增量合并\n保留两边独有词条后上传") }
            if (controller.isApplying) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun SummaryCell(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DiffChip(
    controller: CloudDictSyncController,
    tab: CloudDictSyncController.DiffTab,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    val selected = controller.selectedTab == tab
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (selected) 0.18f else 0.1f))
            .border(if (selected) 2.dp else 0.dp, if (selected) color else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable { controller.selectedTab = tab }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$count", color = color, fontWeight = FontWeight.SemiBold)
        Text(tab.title, style = MaterialTheme.typography.labelSmall, color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun metaLabel(item: DictSyncDiff.WordItem): String {
    val parts = mutableListOf<String>()
    if (item.priority.isNotEmpty()) parts.add(if (item.priority.isEmpty()) "—" else "w${item.priority}")
    if (item.note.isNotEmpty()) parts.add("备注 ${item.note}")
    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}

private fun emptyTitle(result: DictSyncDiff.Result): String = when {
    result.isIdentical -> "完全一致"
    !result.hasRemote -> "云端无备份"
    else -> "此分类无差异"
}

private fun emptyDescription(result: DictSyncDiff.Result): String = when {
    result.isIdentical -> "无需同步"
    !result.hasRemote -> "可点「本地 → 云」上传"
    else -> "切换其它分类查看"
}
