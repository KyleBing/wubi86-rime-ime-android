package cn.kylebing.meifeng.wubi.data

data class EngineCandidate(val text: String, val comment: String)

/** 对齐 Rime wubi86_jidian：四码、唯一自动上屏、编码补全、z 拼音反查 */
class WubiEngine {
    private val composition = StringBuilder()
    private var pendingCommit: String? = null
    @Volatile var isReady = false
        private set

    fun start() {
        DictIndex.shared.ensureLoaded()
        isReady = true
        clearComposition()
    }

    fun stop() {
        isReady = false
        composition.clear()
        pendingCommit = null
    }

    fun processLetter(ch: Char): Boolean {
        if (!isReady) return false
        val c = ch.lowercaseChar()
        if (c !in 'a'..'z') return false
        // 对齐 Rime max_code_length:4 — 第五码顶首候选上屏，再用新字母起笔
        if (composition.length >= 4 && !composition.startsWith("z")) {
            val first = currentCandidates().firstOrNull()?.text
            if (first != null) pendingCommit = first
            composition.clear()
            composition.append(c)
            tryAutoSelect()
            return true
        }
        composition.append(c)
        tryAutoSelect()
        return true
    }

    fun processSpace(): Boolean {
        val cands = currentCandidates()
        if (cands.isNotEmpty()) {
            pendingCommit = cands[0].text
            composition.clear()
            return true
        }
        return false
    }

    fun processDelete(): Boolean {
        if (composition.isEmpty()) return false
        composition.deleteCharAt(composition.lastIndex)
        return true
    }

    fun select(index: Int): Boolean {
        val cands = currentCandidates()
        if (index !in cands.indices) return false
        pendingCommit = cands[index].text
        composition.clear()
        return true
    }

    fun commitText(): String? {
        val t = pendingCommit
        pendingCommit = null
        return t
    }

    fun clearComposition() {
        composition.clear()
        pendingCommit = null
    }

    fun preedit(): String = composition.toString()
    fun rawInput(): String = composition.toString()
    val isComposing: Boolean get() = composition.isNotEmpty()

    fun allCandidates(): List<EngineCandidate> = currentCandidates()

    fun lookupTexts(code: String): List<String> =
        DictIndex.shared.exact(code).map { it.text }.distinct()

    fun restore(code: String) {
        composition.clear()
        code.forEach { if (it in 'a'..'z') composition.append(it) }
    }

    private fun tryAutoSelect() {
        if (composition.startsWith("z")) return
        if (composition.length != 4) return
        val exact = DictIndex.shared.exact(composition.toString())
        if (exact.size == 1) {
            pendingCommit = exact[0].text
            composition.clear()
        }
    }

    fun shouldClearEmptyCode(): Boolean {
        if (!KeyboardPrefs.clearEmptyCode) return false
        if (composition.length != 4 || composition.startsWith("z")) return false
        if (DateTimeCandidates.candidates(composition.toString()) != null) return false
        return currentRimeCandidates().isEmpty()
    }

    private fun currentCandidates(): List<EngineCandidate> {
        val pre = composition.toString()
        if (pre.isEmpty()) return emptyList()
        DateTimeCandidates.candidates(pre)?.let { special ->
            val rest = currentRimeCandidates()
            return special.map { EngineCandidate(it, "") } + rest
        }
        return currentRimeCandidates()
    }

    private fun currentRimeCandidates(): List<EngineCandidate> {
        val pre = composition.toString()
        val hits = DictIndex.shared.lookup(pre)
        // 对齐 iOS：备注优先码表第四列；不再把补全编码塞进 comment（schema 默认也抹掉了提示码）
        val showNote = KeyboardPrefs.showCommentInPanel
        return hits.map { hit ->
            EngineCandidate(hit.text, if (showNote) hit.note else "")
        }
    }
}
