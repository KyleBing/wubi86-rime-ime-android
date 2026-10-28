package cn.kylebing.meifeng.wubi.data

/** 按幅度 5 或 10，算被拖动候选要写成的权重，与 macOS / iOS 同一套规则 */
object CandidateRankWeight {
    fun placed(weights: List<Int>, from: Int, to: Int, step: Int): Int? {
        if (from !in weights.indices || from == to || step <= 0) return null
        val others = weights.toMutableList().also { it.removeAt(from) }
        if (others.isEmpty()) return null
        val dest = to.coerceIn(0, others.size)
        if (dest == 0) return others[0] + step
        if (dest >= others.size) return others.last() - step
        val above = others[dest - 1]
        val below = others[dest]
        if (above - step > below) return above - step
        if (below + step < above) return below + step
        return if (to < from) below + step else above - step
    }

    fun index(weight: Int, others: List<Int>): Int = others.count { it > weight }
}
