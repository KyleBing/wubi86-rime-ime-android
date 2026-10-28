package cn.kylebing.meifeng.wubi.data

import java.util.UUID

data class UserDictEntry(
    val code: String,
    val text: String,
    val weight: Int? = null,
    val note: String = ""
) {
    val id: String get() = "$code|$text"
}

data class UserDictGroup(
    val id: UUID = UUID.randomUUID(),
    var name: String,
    val entries: MutableList<UserDictEntry> = mutableListOf()
) {
    val displayName: String
        get() = name.trim().ifEmpty { "- 未命名 -" }

    // 没有任何词条
    val isEmpty: Boolean get() = entries.isEmpty()
}

data class UserDictDocument(
    var isGroupMode: Boolean,
    val groups: MutableList<UserDictGroup>
) {
    val allEntries: List<UserDictEntry> get() = groups.flatMap { it.entries }
    val entryCount: Int get() = allEntries.size
    val shouldPreserveGroupHeaders: Boolean
        get() = isGroupMode || groups.size > 1 || groups.any { it.name.trim().isNotEmpty() }

    companion object {
        fun empty() = UserDictDocument(true, mutableListOf(UserDictGroup(name = "")))
        fun flat(entries: List<UserDictEntry>) =
            UserDictDocument(false, mutableListOf(UserDictGroup(name = "", entries = entries.toMutableList())))
    }

    // 复制分组和词条列表，避免合并时改到正在用的文档
    fun deepCopy(): UserDictDocument = UserDictDocument(
        isGroupMode,
        groups.map { g ->
            UserDictGroup(id = g.id, name = g.name, entries = g.entries.toMutableList())
        }.toMutableList()
    )

    // 在指定位置插入空分组
    fun insertEmptyGroup(before: Int): UserDictGroup {
        val group = UserDictGroup(name = "")
        groups.add(before.coerceIn(0, groups.size), group)
        return group
    }

    fun removeEntries(predicate: (UserDictEntry) -> Boolean) {
        groups.forEach { g -> g.entries.removeAll(predicate) }
    }

    fun moveEntries(entries: List<UserDictEntry>, toGroupId: UUID) {
        val ids = entries.map { it.id }.toSet()
        removeEntries { it.id in ids }
        val dest = groups.indexOfFirst { it.id == toGroupId }
        if (dest < 0) return
        for (entry in entries) {
            groups[dest].entries.removeAll { it.code == entry.code && it.text == entry.text }
            groups[dest].entries.add(entry)
        }
    }
}

data class DictHit(
    val text: String,
    val code: String,
    val weight: Int,
    val note: String,
    val fileName: String,
    val lineIndex: Int
)
