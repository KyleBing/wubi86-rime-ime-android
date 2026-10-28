package cn.kylebing.meifeng.wubi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import cn.kylebing.meifeng.wubi.data.DictIndex
import cn.kylebing.meifeng.wubi.data.UserDictDocument
import cn.kylebing.meifeng.wubi.data.UserDictEntry
import cn.kylebing.meifeng.wubi.data.UserDictWriter
import cn.kylebing.meifeng.wubi.data.WubiDictEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private sealed interface LexPage {
    data object Root : LexPage
    data object MainDict : LexPage
    data object Lan : LexPage
    data class Group(val index: Int, val name: String) : LexPage
}

// 词库管理：主词库设置、分组、添词
@Composable
fun LexiconHost(navBack: NavBack, onTitle: (String?) -> Unit) {
    var page by remember { mutableStateOf<LexPage>(LexPage.Root) }
    var document by remember { mutableStateOf(UserDictDocument.empty()) }

    fun reload() {
        document = UserDictWriter.loadActiveDocument()
    }

    DisposableEffect(page) {
        navBack.pop = {
            if (page != LexPage.Root) {
                page = LexPage.Root
                reload()
                true
            } else {
                false
            }
        }
        onDispose { navBack.pop = null }
    }
    SideEffect {
        onTitle(
            when (val current = page) {
                LexPage.Root -> null
                LexPage.MainDict -> "主词库设置"
                LexPage.Lan -> "WiFi 管理词库文件"
                is LexPage.Group -> current.name
            }
        )
    }
    LaunchedEffect(Unit) { reload() }

    when (val current = page) {
        LexPage.Root -> LexiconHome(
            document = document,
            onReload = { reload() },
            onOpenMain = { page = LexPage.MainDict },
            onOpenLan = { page = LexPage.Lan },
            onOpenGroup = { index, name -> page = LexPage.Group(index, name) }
        )
        LexPage.MainDict -> MainDictScreen()
        LexPage.Lan -> LanPage()
        is LexPage.Group -> GroupDetailScreen(
            groupIndex = current.index,
            onTitle = { page = LexPage.Group(current.index, it) },
            onDeleted = {
                page = LexPage.Root
                reload()
            },
            onChanged = { reload() }
        )
    }
}

@Composable
private fun LexiconHome(
    document: UserDictDocument,
    onReload: () -> Unit,
    onOpenMain: () -> Unit,
    onOpenLan: () -> Unit,
    onOpenGroup: (Int, String) -> Unit
) {
    var status by remember { mutableStateOf<String?>(null) }
    var encodeHint by remember { mutableStateOf("") }
    var showAddGroup by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableIntStateOf(0) }
    var newCode by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }

    LaunchedEffect(document) {
        val st = WubiDictEncoder.charMapStatus()
        encodeHint = if (st.exists) "单字字典已就绪（${st.count} 字）。可在本页直接添加词条。"
        else "单字字典未生成，添加词条需手动填编码。"
        if (selectedGroup >= document.groups.size) selectedGroup = (document.groups.size - 1).coerceAtLeast(0)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        exportText = UserDictWriter.exportText()
                        showExport = true
                    }) { Text("导出") }
                    TextButton(onClick = {
                        newText = ""
                        newCode = ""
                        if (selectedGroup >= document.groups.size) selectedGroup = 0
                        showAdd = true
                    }) { Text("添加") }
                }
                status?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("主词库 / 自动编码") },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable(onClick = onOpenMain)
                )
                HorizontalDivider(Modifier.padding(start = 16.dp))
                ListItem(
                    headlineContent = { Text("WiFi 管理词库文件") },
                    supportingContent = { Text("同一 Wi‑Fi 下用电脑浏览器管理配置") },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable(onClick = onOpenLan)
                )
                Text(
                    encodeHint,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (document.isGroupMode) {
            item {
                Text(
                    "分组（${document.groups.size}）· 词条 ${document.entryCount}",
                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            itemsIndexed(document.groups, key = { _, g -> g.id }) { index, group ->
                Column(Modifier.groupedRow(index, document.groups.size)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (index <= 0) return@IconButton
                        val doc = UserDictWriter.loadActiveDocument()
                        if (index !in doc.groups.indices) return@IconButton
                        val item = doc.groups.removeAt(index)
                        doc.groups.add(index - 1, item)
                        UserDictWriter.saveActiveDocument(doc)
                        onReload()
                    }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                    IconButton(onClick = {
                        val doc = UserDictWriter.loadActiveDocument()
                        if (index >= doc.groups.lastIndex) return@IconButton
                        val item = doc.groups.removeAt(index)
                        doc.groups.add(index + 1, item)
                        UserDictWriter.saveActiveDocument(doc)
                        onReload()
                    }, enabled = index < document.groups.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                    Column(Modifier.weight(1f).clickable { onOpenGroup(index, group.displayName) }.padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(group.displayName, Modifier.weight(1f))
                            if (group.isEmpty) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                    Text("空", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text("${group.entries.size}", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                        }
                    }
                }
                if (index < document.groups.lastIndex) HorizontalDivider()
                }
            }
            item {
                TextButton(onClick = { newGroupName = ""; showAddGroup = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("添加分组") }
                Text(
                    "点右上角添加词条并选择分组；点击分组进入管理；用箭头调整顺序。",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (document.shouldPreserveGroupHeaders) {
            val rows = this
            document.groups.forEachIndexed { groupIndex, group ->
                rows.item(key = "h-${group.id}") {
                    Text(group.displayName, Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.labelLarge)
                    if (group.entries.isEmpty()) {
                        Text("（空分组）", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                group.entries.forEachIndexed { entryIndex, entry ->
                    rows.item(key = "${group.id}-${entry.id}") {
                        // 同一分组的词条拼成一块，每条只占一行
                        Column(Modifier.groupedRow(entryIndex, group.entries.size)) {
                            EntryRow(entry) {
                                UserDictWriter.removeEntryAt(entry, groupIndex)
                                onReload()
                            }
                            if (entryIndex < group.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    "用户词（${document.entryCount}）",
                    Modifier.padding(start = 16.dp, top = 12.dp),
                    style = MaterialTheme.typography.labelLarge
                )
                if (document.allEntries.isEmpty()) {
                    Text("暂无词条，点右上角添加", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(document.allEntries, key = { _, e -> e.id }) { index, entry ->
                Column(Modifier.groupedRow(index, document.allEntries.size)) {
                    EntryRow(entry) {
                        UserDictWriter.removeEntry(entry)
                        onReload()
                    }
                    if (index < document.allEntries.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }

    if (showAddGroup) {
        AlertDialog(
            onDismissRequest = { showAddGroup = false },
            title = { Text("添加分组") },
            text = {
                Column {
                    Text("空名称会显示为「- 未命名 -」。")
                    OutlinedTextField(newGroupName, { newGroupName = it }, label = { Text("分组名（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val doc = UserDictWriter.loadActiveDocument()
                    val group = doc.insertEmptyGroup(doc.groups.size)
                    group.name = newGroupName.trim()
                    doc.isGroupMode = true
                    UserDictWriter.saveActiveDocument(doc)
                    status = "已添加分组「${group.displayName}」"
                    showAddGroup = false
                    onReload()
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showAddGroup = false }) { Text("取消") } }
        )
    }

    if (showAdd) {
        AddEntryDialog(
            document = document,
            selectedGroup = selectedGroup,
            onGroup = { selectedGroup = it },
            text = newText,
            code = newCode,
            onText = {
                newText = it
                newCode = WubiDictEncoder.encode(it.trim())
            },
            onCode = { newCode = it },
            onDismiss = { showAdd = false },
            onSave = {
                val code = newCode.trim().lowercase()
                val text = newText.trim()
                if (code.isEmpty() || text.isEmpty()) return@AddEntryDialog
                val index = if (document.shouldPreserveGroupHeaders) selectedGroup else 0
                runCatching { UserDictWriter.addEntryAt(UserDictEntry(code, text), index) }
                    .onSuccess {
                        val hint = if (document.shouldPreserveGroupHeaders && index in document.groups.indices) {
                            "（${document.groups[index].displayName}）"
                        } else ""
                        status = "已保存$hint。回到键盘后会自动部署"
                        showAdd = false
                        newCode = ""
                        newText = ""
                        onReload()
                    }
                    .onFailure { status = "保存失败：${it.message}" }
            }
        )
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出 / 导入") },
            text = {
                OutlinedTextField(exportText, { exportText = it }, modifier = Modifier.fillMaxWidth(), minLines = 8)
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { UserDictWriter.importText(exportText) }
                        .onSuccess {
                            status = "已导入，打开键盘后将重新部署"
                            showExport = false
                            onReload()
                        }
                        .onFailure { status = "导入失败：${it.message}" }
                }) { Text("写回") }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("关闭") } }
        )
    }
}

// 词条一行：词、编码、权重、删除
@Composable
private fun EntryRow(entry: UserDictEntry, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(entry.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            entry.code,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            entry.weight?.let { "权重 $it" } ?: "权重 —",
            Modifier.padding(start = 8.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "删除",
            Modifier.padding(start = 12.dp).clickable(onClick = onDelete),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEntryDialog(
    document: UserDictDocument,
    selectedGroup: Int,
    onGroup: (Int) -> Unit,
    text: String,
    code: String,
    onText: (String) -> Unit,
    onCode: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加词条") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (document.shouldPreserveGroupHeaders && document.groups.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                        OutlinedTextField(
                            value = document.groups.getOrNull(selectedGroup)?.displayName ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("保存到分组") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                            modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            document.groups.forEachIndexed { idx, g ->
                                DropdownMenuItem(text = { Text(g.displayName) }, onClick = {
                                    onGroup(idx)
                                    menu = false
                                })
                            }
                        }
                    }
                } else {
                    Text("保存到  用户词库（非分组）")
                }
                OutlinedTextField(text, onText, label = { Text("词条") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    code, onCode, label = { Text("编码（可改）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)
                )
                if (code.isEmpty() && text.trim().isNotEmpty()) {
                    val hans = WubiDictEncoder.chineseCharacters(text)
                    Text(
                        if (hans.isEmpty()) "词条中没有汉字，无法自动编码，请手动填写。"
                        else "无法自动编码：请先生成单字字典，或手动填写编码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainDictScreen() {
    var names by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf(WubiDictEncoder.selectedMainDictName) }
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        val st = WubiDictEncoder.charMapStatus()
        status = if (st.exists) {
            val date = st.modified?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }.orEmpty()
            "已生成：${st.count} 个单字" + if (date.isEmpty()) "" else "（$date）"
        } else {
            "尚未生成。请先选择主词库并生成单字字典，否则添加词条无法自动编码。"
        }
    }

    LaunchedEffect(Unit) {
        names = WubiDictEncoder.availableDictFileNames()
        if (selected !in names && names.isNotEmpty()) {
            selected = names.first()
            WubiDictEncoder.selectedMainDictName = selected
        }
        refresh()
    }

    Column(Modifier.fillMaxSize()) {
        SettingsCard(title = "主词库") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text("主词库") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                names.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        selected = name
                        WubiDictEncoder.selectedMainDictName = name
                        WubiDictEncoder.clearCache()
                        menu = false
                        refresh()
                    })
                }
            }
        }
        Text(
            "用于从单字编码按五笔规则自动生成词组编码（AaAbBaBb / AaBaCaCb / AaBaCaZa）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("单字字典", style = MaterialTheme.typography.titleMedium)
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = !working && names.isNotEmpty(), onClick = {
            working = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { WubiDictEncoder.rebuildCharMap() } }
                working = false
                result.onSuccess { status = "已生成：$it 个单字" }
                    .onFailure { status = "生成失败：${it.message}" }
            }
        }) { Text(if (working) "正在生成…" else "从主词库生成单字字典") }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        }
    }
}

@Composable
private fun GroupDetailScreen(
    groupIndex: Int,
    onTitle: (String) -> Unit,
    onDeleted: () -> Unit,
    onChanged: () -> Unit
) {
    var document by remember { mutableStateOf(UserDictWriter.loadActiveDocument()) }
    var status by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    val group = document.groups.getOrNull(groupIndex)

    fun reload() {
        document = UserDictWriter.loadActiveDocument()
        val current = document.groups.getOrNull(groupIndex)
        if (current != null) onTitle(current.displayName)
    }

    LaunchedEffect(groupIndex) { reload() }

    if (group == null) {
        LaunchedEffect(Unit) { onDeleted() }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { newText = ""; newCode = ""; showAdd = true }) { Text("添加") }
                }
                status?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { rename = group.name; showRename = true }) { Text("重命名分组") }
                TextButton(onClick = {
                    val doc = UserDictWriter.loadActiveDocument()
                    if (groupIndex !in doc.groups.indices) return@TextButton
                    doc.groups.removeAt(groupIndex)
                    if (doc.groups.isEmpty()) doc.groups.add(cn.kylebing.meifeng.wubi.data.UserDictGroup(name = ""))
                    UserDictWriter.saveActiveDocument(doc)
                    DictIndex.shared.reloadUser()
                    onDeleted()
                }) { Text("删除分组", color = MaterialTheme.colorScheme.error) }
            }
            Text(
                "词条（${group.entries.size}）",
                Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (group.entries.isEmpty()) {
                Text("暂无词条，点右上角添加", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(group.entries, key = { _, e -> e.id }) { index, entry ->
            Column(Modifier.groupedRow(index, group.entries.size)) {
                EntryRow(entry) {
                    UserDictWriter.removeEntryAt(entry, groupIndex)
                    reload()
                    onChanged()
                }
                if (index < group.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名分组") },
            text = { OutlinedTextField(rename, { rename = it }, label = { Text("分组名") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val doc = UserDictWriter.loadActiveDocument()
                    if (groupIndex in doc.groups.indices) {
                        doc.groups[groupIndex].name = rename.trim()
                        UserDictWriter.saveActiveDocument(doc)
                        status = "已重命名为「${doc.groups[groupIndex].displayName}」"
                        reload()
                        onChanged()
                    }
                    showRename = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }

    if (showAdd) {
        AddEntryDialog(
            document = document,
            selectedGroup = groupIndex,
            onGroup = {},
            text = newText,
            code = newCode,
            onText = { newText = it; newCode = WubiDictEncoder.encode(it.trim()) },
            onCode = { newCode = it },
            onDismiss = { showAdd = false },
            onSave = {
                val code = newCode.trim().lowercase()
                val text = newText.trim()
                if (code.isEmpty() || text.isEmpty()) return@AddEntryDialog
                runCatching { UserDictWriter.addEntryAt(UserDictEntry(code, text), groupIndex) }
                    .onSuccess {
                        status = "已保存。回到键盘后会自动部署"
                        showAdd = false
                        newCode = ""
                        newText = ""
                        reload()
                        onChanged()
                    }
                    .onFailure { status = "保存失败：${it.message}" }
            }
        )
    }

}
