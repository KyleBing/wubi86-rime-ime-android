package cn.kylebing.meifeng.wubi.data

/** 主词库自动编码，对齐 iOS WubiDictEncoder / 码表助手 DictMap */
object WubiDictEncoder {
    const val MAIN_DICT_KEY = "wubi.mainDict.fileName"
    const val DEFAULT_MAIN = "wubi86_jidian.dict.yaml"
    const val CHAR_MAP_NAME = "wubi_charmap.tsv"

    var selectedMainDictName: String
        get() = AppPaths.prefs.getString(MAIN_DICT_KEY, DEFAULT_MAIN) ?: DEFAULT_MAIN
        set(v) { AppPaths.prefs.edit().putString(MAIN_DICT_KEY, v).apply() }

    private var cachedMap: Map<String, String>? = null

    fun isChineseHanzi(ch: Char): Boolean {
        val v = ch.code
        return v in 0x4E00..0x9FFF || v in 0x3400..0x4DBF
    }

    fun chineseCharacters(word: String) = word.filter(::isChineseHanzi).toList()

    fun encode(word: String): String {
        val map = loadCharMap()
        if (map.isEmpty()) return ""
        var chars = chineseCharacters(word)
        if (chars.isEmpty()) return ""
        if (chars.size == 1) return map[chars[0].toString()] ?: ""
        if (chars.size > 4) chars = listOf(chars[0], chars[1], chars[2], chars.last())
        val codes = chars.map { map[it.toString()] ?: "" }
        if (codes.any { it.isEmpty() }) return ""
        return when (codes.size) {
            2 -> codes[0].take(2) + codes[1].take(2)
            3 -> codes[0].take(1) + codes[1].take(1) + codes[2].take(2)
            else -> codes[0].take(1) + codes[1].take(1) + codes[2].take(1) + codes.last().take(1)
        }
    }

    // 用户目录里可选的码表（不含用户词文件）
    fun availableDictFileNames(): List<String> {
        val files = AppPaths.userDir.listFiles() ?: return emptyList()
        return files.map { it.name }
            .filter { it.endsWith(".dict.yaml") && !it.contains("user") }
            .sorted()
    }

    // 单字字典是否已生成
    fun charMapStatus(): CharMapStatus {
        val file = AppPaths.charMapFile
        if (!file.exists()) return CharMapStatus(false, 0, null)
        val count = file.readLines().count { it.contains('\t') }
        return CharMapStatus(true, count, file.lastModified())
    }

    data class CharMapStatus(val exists: Boolean, val count: Int, val modified: Long?)

    fun rebuildCharMap(): Int {
        val file = AppPaths.mainDictFile
        if (!file.exists()) error("找不到主词库文件：$selectedMainDictName")
        val content = file.readText()
        val map = extractCharacterMap(content)
        AppPaths.charMapFile.writeText(map.entries.sortedBy { it.key }.joinToString("") { "${it.key}\t${it.value}\n" })
        cachedMap = map
        return map.size
    }

    fun clearCache() { cachedMap = null }

    fun loadCharMap(): Map<String, String> {
        cachedMap?.let { return it }
        if (!AppPaths.charMapFile.exists()) {
            rebuildCharMap()
        }
        val text = AppPaths.charMapFile.takeIf { it.exists() }?.readText() ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2 && parts[0].length == 1) map[parts[0]] = parts[1].lowercase()
        }
        cachedMap = map
        return map
    }

    fun extractCharacterMap(fileContent: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var inBody = false
        fileContent.replace("\r\n", "\n").split('\n').forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed == "...") { inBody = true; return@forEach }
            if (!inBody && trimmed.startsWith("---")) return@forEach
            if (!inBody && raw.contains('\t') && !trimmed.startsWith("#") && !trimmed.startsWith("name:")) {
                inBody = true
            }
            if (!inBody) return@forEach
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = raw.split('\t', ' ').filter { it.isNotEmpty() }
            if (parts.size < 2) return@forEach
            val text = parts[0]
            val code = parts[1].lowercase()
            if (text.length == 1 && code.length >= 2 && map[text] == null) map[text] = code
        }
        return map
    }
}
