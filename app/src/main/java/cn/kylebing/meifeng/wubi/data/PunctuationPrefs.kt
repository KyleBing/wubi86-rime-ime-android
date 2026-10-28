package cn.kylebing.meifeng.wubi.data

/** 标点由键盘自己处理，符号与 iOS / macOS half_shape 一致 */
object PunctuationPrefs {
    enum class Mode(val raw: String, val title: String) {
        Direct("direct", "直接上屏"),
        Candidates("candidates", "候选列表");

        companion object {
            fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Direct
        }
    }

    data class Item(val key: String, val symbols: List<String>, val defaultMode: Mode = Mode.Direct)

    val items = listOf(
        Item(",", listOf("，")),
        Item(".", listOf("。")),
        Item("<", listOf("《")),
        Item(">", listOf("》")),
        Item("/", listOf("/")),
        Item("?", listOf("？")),
        Item(";", listOf("；")),
        Item(":", listOf("：")),
        Item("'", listOf("‘", "’")),
        Item("\"", listOf("“", "”")),
        Item("\\", listOf("、")),
        Item("|", listOf("|")),
        Item("`", listOf("`")),
        Item("~", listOf("~")),
        Item("!", listOf("！")),
        Item("@", listOf("@")),
        Item("#", listOf("#")),
        Item("%", listOf("%", "％", "°", "℃")),
        Item("$", listOf("￥")),
        Item("^", listOf("……")),
        Item("&", listOf("&")),
        Item("*", listOf("*")),
        Item("(", listOf("（")),
        Item(")", listOf("）")),
        Item("-", listOf("-")),
        Item("_", listOf("——")),
        Item("+", listOf("+")),
        Item("=", listOf("=")),
        Item("[", listOf("【")),
        Item("]", listOf("】")),
        Item("{", listOf("「")),
        Item("}", listOf("」"))
    )

    fun item(forKey: String) = items.firstOrNull { it.key == forKey }

    fun mode(key: String): Mode {
        val stored = AppPaths.prefs.getString("punct.mode.$key", null)
        return if (stored != null) Mode.from(stored) else item(key)?.defaultMode ?: Mode.Direct
    }

    fun setMode(mode: Mode, key: String) {
        AppPaths.prefs.edit().putString("punct.mode.$key", mode.raw).commit()
    }

    fun symbols(key: String): List<String> {
        val stored = AppPaths.prefs.getString("punct.symbols.$key", null)
        if (!stored.isNullOrBlank()) {
            val list = parseSymbols(stored)
            if (list.isNotEmpty()) return list
        }
        return item(key)?.symbols ?: listOf(key)
    }

    fun setSymbols(symbols: List<String>, key: String) {
        val cleaned = symbols.map { it.trim() }.filter { it.isNotEmpty() }
        val e = AppPaths.prefs.edit()
        if (cleaned.isEmpty()) e.remove("punct.symbols.$key")
        else e.putString("punct.symbols.$key", cleaned.joinToString(" "))
        e.commit()
    }

    fun restoreDefaults() {
        val e = AppPaths.prefs.edit()
        items.forEach {
            e.remove("punct.mode.${it.key}")
            e.remove("punct.symbols.${it.key}")
        }
        e.commit()
    }

    fun parseSymbols(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    class PairState {
        private val closingNext = mutableMapOf<String, Boolean>()
        fun reset() { closingNext.clear() }
        fun directSymbol(key: String, symbols: List<String>): String {
            val primary = symbols.firstOrNull() ?: key
            if (symbols.size < 2 || (key != "\"" && key != "'")) return primary
            val closing = closingNext[key] == true
            closingNext[key] = !closing
            return if (closing) symbols[1] else primary
        }
    }
}
