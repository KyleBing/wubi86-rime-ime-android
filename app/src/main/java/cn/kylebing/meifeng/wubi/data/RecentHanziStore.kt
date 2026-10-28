package cn.kylebing.meifeng.wubi.data

object RecentHanziStore {
    const val CAPACITY = 32
    private const val KEY = "keyboard.recentHanzi"

    val characters: List<Char>
        get() {
            val stored = AppPaths.prefs.getString(KEY, "") ?: ""
            return stored.filter(WubiDictEncoder::isChineseHanzi).takeLast(CAPACITY).toList()
        }

    fun append(text: String) {
        if (text.isEmpty()) return
        val stored = AppPaths.prefs.getString(KEY, "") ?: ""
        val chars = stored.filter(WubiDictEncoder::isChineseHanzi).toMutableList()
        var changed = false
        text.forEach { ch ->
            if (WubiDictEncoder.isChineseHanzi(ch)) {
                chars.add(ch)
                changed = true
            }
        }
        if (!changed) return
        val kept = if (chars.size > CAPACITY) chars.takeLast(CAPACITY) else chars
        AppPaths.prefs.edit().putString(KEY, kept.joinToString("")).apply()
    }
}
