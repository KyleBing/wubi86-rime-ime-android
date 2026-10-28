package cn.kylebing.meifeng.wubi.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object UserDictWriter {
    const val NEEDS_DEPLOY_KEY = "rime.userdict.needsDeploy"
    const val UNNAMED = "未命名"

    val activeFile: File
        get() {
            val f = AppPaths.userDictFile
            if (!f.exists()) saveDocument(UserDictDocument.empty(), f)
            return f
        }

    fun loadActiveDocument() = loadDocument(activeFile)
    fun loadActiveEntries() = loadActiveDocument().allEntries

    fun saveActiveDocument(document: UserDictDocument) {
        saveDocument(document, activeFile)
    }

    fun loadDocument(file: File): UserDictDocument {
        if (!file.exists()) return UserDictDocument.empty()
        return parseDocument(file.readText())
    }

    // 把文档写成 yaml 文本，不落盘
    fun render(document: UserDictDocument): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return if (document.shouldPreserveGroupHeaders) {
            val groups = document.groups.ifEmpty { listOf(UserDictGroup(name = "")) }
            val body = groups.joinToString("\n\n") { group ->
                buildString {
                    append("## ${group.name}\n")
                    group.entries.forEach { append(entryLine(it)).append('\n') }
                    if (endsWith('\n')) setLength(length - 1)
                }
            }
            buildString {
                append("# Rime dictionary\n# encoding: utf-8\n---\n")
                append("name: wubi86_jidian_user\nversion: \"$iso\"\n")
                append("dict_grouped: true\nsort: by_weight\n...\n\n")
                if (body.isNotEmpty()) append(body).append('\n')
            }
        } else {
            buildString {
                append("# Rime dictionary\n# encoding: utf-8\n---\n")
                append("name: wubi86_jidian_user\nversion: \"$iso\"\n")
                append("dict_grouped: false\nsort: by_weight\n...\n\n")
                document.allEntries.forEach { append(entryLine(it)).append('\n') }
            }
        }
    }

    fun saveDocument(document: UserDictDocument, file: File, markDeploy: Boolean = true) {
        file.parentFile?.mkdirs()
        file.writeText(render(document))
        if (markDeploy) {
            markNeedsDeploy()
            // 本地词库有改动时排一次自动同步
            cn.kylebing.meifeng.wubi.sync.UserDictAutoSync.noteLocalEdit()
        }
    }

    fun parseDocument(fileContent: String): UserDictDocument {
        val normalized = fileContent.replace("\r\n", "\n")
        val headerEnd = normalized.indexOf("...")
        if (headerEnd < 0) {
            return if (normalized.contains("##")) {
                val groups = parseGroupModeBody(normalized)
                if (groups.isEmpty()) UserDictDocument.empty()
                else UserDictDocument(true, groups.toMutableList())
            } else {
                val entries = parseFlatBody(normalized)
                if (entries.isEmpty()) UserDictDocument.empty() else UserDictDocument.flat(entries)
            }
        }
        val header = normalized.substring(0, headerEnd + 3)
        val body = normalized.substring(headerEnd + 3)
        val isGroup = header.contains("dict_grouped: true") || body.contains("##")
        return if (isGroup) {
            val groups = parseGroupModeBody(body)
            UserDictDocument(true, (groups.ifEmpty { listOf(UserDictGroup(name = "")) }).toMutableList())
        } else {
            UserDictDocument.flat(parseFlatBody(body))
        }
    }

    private fun parseGroupModeBody(body: String): List<UserDictGroup> {
        val lines = body.replace("\r\n", "\n").split('\n')
        val wordsGroup = mutableListOf<UserDictGroup>()
        var temp: UserDictGroup? = null
        var lastEmpty = false
        for (item in lines) {
            when {
                item.startsWith("##") -> {
                    temp?.let { cur ->
                        when {
                            cur.name.isNotEmpty() -> wordsGroup.add(cur)
                            cur.entries.isNotEmpty() -> wordsGroup.add(cur.copy(name = UNNAMED))
                        }
                    }
                    temp = UserDictGroup(name = item.drop(2).trim())
                    lastEmpty = false
                }
                item.contains('\t') -> {
                    val entry = parseEntryLine(item) ?: continue
                    if (temp == null) temp = UserDictGroup(name = "")
                    temp!!.entries.add(entry)
                    lastEmpty = false
                }
                item.startsWith("#") -> lastEmpty = false
                else -> {
                    if (!lastEmpty && temp != null) {
                        val flushed = if (temp!!.name.isEmpty()) temp!!.copy(name = UNNAMED) else temp!!
                        wordsGroup.add(flushed)
                        temp = UserDictGroup(name = "")
                    }
                    lastEmpty = true
                }
            }
        }
        temp?.let { cur ->
            if (cur.name.isNotEmpty() || cur.entries.isNotEmpty()) wordsGroup.add(cur)
        }
        return wordsGroup
    }

    private fun parseFlatBody(body: String): List<UserDictEntry> =
        body.lineSequence().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) null else parseEntryLine(line)
        }.toList()

    fun parseEntryLine(line: String): UserDictEntry? {
        val parts = if (line.contains('\t')) line.split('\t') else line.split(Regex("\\s+"))
        if (parts.size < 2) return null
        var text = parts[0]
        var code = parts[1].replace("\r", "")
        val weight = if (parts.size >= 3) {
            val raw = parts[2].trim()
            if (raw.isEmpty()) null else raw.toIntOrNull()
        } else null
        val firstLooksLikeCode = text.all { it.isLetterOrDigit() }
        val secondHasCjk = code.any { it.code in 0x4E00..0x9FFF }
        val note = if (parts.size >= 4) parts.drop(3).joinToString("\t").replace("\r", "").trim() else ""
        if (firstLooksLikeCode && secondHasCjk) {
            val tmp = text; text = code; code = tmp
        }
        return UserDictEntry(code.lowercase(), text, weight, note)
    }

    private fun entryLine(entry: UserDictEntry): String {
        val note = entry.note.replace("\n", " ").replace("\r", "").trim()
        return if (note.isEmpty()) {
            if (entry.weight != null) "${entry.text}\t${entry.code}\t${entry.weight}"
            else "${entry.text}\t${entry.code}"
        } else {
            val w = entry.weight?.toString() ?: ""
            "${entry.text}\t${entry.code}\t$w\t$note"
        }
    }

    fun addEntry(entry: UserDictEntry) {
        val doc = loadActiveDocument()
        if (doc.groups.isEmpty()) doc.groups.add(UserDictGroup(name = ""))
        addEntry(entry, doc.groups[0].id, doc)
        saveActiveDocument(doc)
        DictIndex.shared.reloadUser()
    }

    fun addEntry(entry: UserDictEntry, toGroupId: UUID) {
        val doc = loadActiveDocument()
        addEntry(entry, toGroupId, doc)
        saveActiveDocument(doc)
        DictIndex.shared.reloadUser()
    }

    // 按分组下标添加，详情页用下标避免重新加载后 UUID 失效
    fun addEntryAt(entry: UserDictEntry, groupIndex: Int) {
        val doc = loadActiveDocument()
        if (groupIndex !in doc.groups.indices) error("找不到目标分组")
        addEntry(entry, doc.groups[groupIndex].id, doc)
        saveActiveDocument(doc)
        DictIndex.shared.reloadUser()
    }

    // 只删指定分组里的一条，空分组保留
    fun removeEntryAt(entry: UserDictEntry, groupIndex: Int) {
        val doc = loadActiveDocument()
        if (groupIndex !in doc.groups.indices) return
        doc.groups[groupIndex].entries.removeAll { it.code == entry.code && it.text == entry.text }
        saveActiveDocument(doc)
        DictIndex.shared.reloadUser()
    }

    // 把词条挪到目标分组末尾
    fun moveEntriesTo(entries: List<UserDictEntry>, groupIndex: Int) {
        val doc = loadActiveDocument()
        if (groupIndex !in doc.groups.indices) return
        doc.moveEntries(entries, doc.groups[groupIndex].id)
        saveActiveDocument(doc)
        DictIndex.shared.reloadUser()
    }

    private fun addEntry(entry: UserDictEntry, toGroupId: UUID, document: UserDictDocument) {
        val idx = document.groups.indexOfFirst { it.id == toGroupId }
        if (idx < 0) error("找不到目标分组")
        document.removeEntries { it.code == entry.code && it.text == entry.text }
        val list = document.groups[idx].entries
        var insertAt = list.size
        for (i in 0 until maxOf(0, list.size - 1)) {
            if (entry.code >= list[i].code && entry.code <= list[i + 1].code) {
                insertAt = i + 1
                break
            }
        }
        when {
            list.isEmpty() -> insertAt = 0
            entry.code < list.first().code -> insertAt = 0
            entry.code > list.last().code -> insertAt = list.size
        }
        list.add(insertAt, entry)
    }

    fun removeEntry(entry: UserDictEntry) {
        val doc = loadActiveDocument()
        doc.removeEntries { it.code == entry.code && it.text == entry.text }
        saveActiveDocument(doc)
        DictIndex.shared.reloadUser()
    }

    fun importText(text: String) {
        activeFile.writeText(text)
        markNeedsDeploy()
        DictIndex.shared.reloadUser()
        cn.kylebing.meifeng.wubi.sync.UserDictAutoSync.noteLocalEdit()
    }

    fun exportText(): String = if (activeFile.exists()) activeFile.readText() else ""

    fun markNeedsDeploy() {
        AppPaths.prefs.edit().putBoolean(NEEDS_DEPLOY_KEY, true).apply()
    }

    fun consumeNeedsDeploy(): Boolean {
        val needed = AppPaths.prefs.getBoolean(NEEDS_DEPLOY_KEY, false)
        if (needed) AppPaths.prefs.edit().putBoolean(NEEDS_DEPLOY_KEY, false).apply()
        return needed
    }
}
