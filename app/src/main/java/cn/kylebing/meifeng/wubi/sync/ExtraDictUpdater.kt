package cn.kylebing.meifeng.wubi.sync

import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.RimeConfigDeploy
import cn.kylebing.meifeng.wubi.data.UserDictDocument
import cn.kylebing.meifeng.wubi.data.UserDictEntry
import cn.kylebing.meifeng.wubi.data.UserDictGroup
import cn.kylebing.meifeng.wubi.data.UserDictWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 线上扩展词库：拉取后覆盖本地，只拉不推
object ExtraDictUpdater {
    data class ApplyResult(val added: Int, val wordCount: Int, val groupCount: Int)

    fun loadLocalDocument(): UserDictDocument {
        val file = AppPaths.extraDictFile
        if (!file.exists()) return UserDictDocument(true, mutableListOf())
        return UserDictWriter.parseDocument(file.readText())
    }

    // 按分类分组后覆盖写入，并部署
    fun applyOnlineItems(items: List<WubiCloudApi.ExtraWord>): ApplyResult {
        val previous = localKeys()
        val document = buildDocument(items)
        val incoming = document.allEntries.map { "${it.text}\t${it.code}" }.toSet()
        val added = incoming.subtract(previous).size
        AppPaths.extraDictFile.parentFile?.mkdirs()
        AppPaths.extraDictFile.writeText(serialize(document))
        WubiCloudConfig.lastExtraPullAt = System.currentTimeMillis()
        val deploy = RimeConfigDeploy.deployNow()
        if (deploy.isFailure) {
            val msg = deploy.exceptionOrNull()?.message ?: "部署失败"
            error("已写入扩展词库，但部署失败：$msg")
        }
        return ApplyResult(added, document.entryCount, document.groups.size)
    }

    private fun localKeys(): Set<String> {
        val file = AppPaths.extraDictFile
        if (!file.exists()) return emptySet()
        return UserDictWriter.parseDocument(file.readText()).allEntries
            .map { "${it.text}\t${it.code}" }
            .toSet()
    }

    // 按分类 id 排序后按分类名切组
    fun buildDocument(items: List<WubiCloudApi.ExtraWord>): UserDictDocument {
        val sorted = items.sortedWith(compareBy<WubiCloudApi.ExtraWord> { it.categoryId }.thenBy { it.id })
        val groups = mutableListOf<UserDictGroup>()
        var lastName: String? = null
        for (item in sorted) {
            val weight = item.weight.takeIf { it > 0 }
            val entry = UserDictEntry(
                code = item.code.lowercase(),
                text = item.word,
                weight = weight,
                note = item.comment.trim()
            )
            if (lastName != item.categoryName) {
                groups.add(UserDictGroup(name = item.categoryName, entries = mutableListOf(entry)))
                lastName = item.categoryName
            } else if (groups.isNotEmpty()) {
                groups.last().entries.add(entry)
            }
        }
        if (groups.isEmpty()) return UserDictDocument(true, mutableListOf(UserDictGroup(name = "")))
        return UserDictDocument(true, groups)
    }

    private fun serialize(document: UserDictDocument): String {
        val version = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val groups = document.groups.ifEmpty { listOf(UserDictGroup(name = "")) }
        val body = groups.joinToString("\n\n") { group ->
            buildString {
                append("## ${group.name}\n")
                group.entries.forEach { append(line(it)).append('\n') }
                if (endsWith('\n')) setLength(length - 1)
            }
        }
        return buildString {
            append("# Rime dictionary\n# encoding: utf-8\n---\n")
            append("name: wubi86_jidian_extra\n")
            append("note: \"扩展词库\"\n")
            append("version: \"$version\"\n")
            append("dict_grouped: true\nsort: original\n...\n\n")
            if (body.isNotEmpty()) append(body).append('\n')
        }
    }

    private fun line(entry: UserDictEntry): String {
        val note = entry.note.replace("\n", " ").replace("\r", "").trim()
        if (note.isEmpty()) {
            val weight = entry.weight
            if (weight != null && weight > 0) return "${entry.text}\t${entry.code}\t$weight"
            return "${entry.text}\t${entry.code}"
        }
        val weightCol = entry.weight?.toString() ?: ""
        return "${entry.text}\t${entry.code}\t$weightCol\t$note"
    }
}
