package cn.kylebing.meifeng.wubi.sync

import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.RimeConfigDeploy
import cn.kylebing.meifeng.wubi.data.UserDictDocument
import cn.kylebing.meifeng.wubi.data.UserDictWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// 用户词自动同步。打开键盘或本 App 后约 2 秒、改词约 3 秒、前台每 10 分钟
object UserDictAutoSync {
    private const val ENABLED = "wubi.autoSync.enabled"
    private const val LAST_TEXT = "wubi.autoSync.lastText"
    private const val ATTEMPT = "wubi.autoSync.lastAttempt"
    private const val REVISION = "wubi.autoSync.revision"
    private const val LOCK = "wubi.autoSync.lock"
    private const val INTERVAL_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var tickerStarted = false
    private var debounceJob: kotlinx.coroutines.Job? = null

    @Volatile
    var applying: Boolean = false

    private val prefs get() = AppPaths.prefs

    var isEnabled: Boolean
        get() = prefs.getBoolean(ENABLED, false)
        set(value) {
            if (isEnabled == value) return
            prefs.edit().putBoolean(ENABLED, value).apply()
            if (value) {
                start(300)
            } else {
                prefs.edit().remove(LAST_TEXT).apply()
                debounceJob?.cancel()
            }
        }

    val lastResultText: String?
        get() = if (isEnabled) prefs.getString(LAST_TEXT, null) else null

    // 进程起来后装上 10 分钟定时器
    fun install() {
        synchronized(this) {
            if (tickerStarted) return
            tickerStarted = true
        }
        scope.launch {
            while (isActive) {
                delay(INTERVAL_MS)
                start(0)
            }
        }
    }

    // 打开键盘或 App 时，距上次尝试超过间隔就补一轮
    fun activate() {
        install()
        val last = prefs.getLong(ATTEMPT, 0L)
        if (System.currentTimeMillis() - last < INTERVAL_MS) return
        start(2000)
    }

    // 登录成功后马上补一轮
    fun syncSoon() {
        install()
        start(300)
    }

    // 本地词库刚写入。自动同步自己的写回不再从这里排
    fun noteLocalEdit() {
        if (applying || !isEnabled || !WubiCloudConfig.isLoggedIn) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(3000)
            start(0)
        }
    }

    // 手动同步成功后记下版本和基准，避免下一轮整份重拉
    fun noteCloudRevision(revision: Int, snapshot: UserDictDocument) {
        if (revision <= 0) return
        prefs.edit().putInt(REVISION, revision).apply()
        saveBase(snapshot)
    }

    // 包住自动同步的写回，避免触发下一轮
    fun markApplying(block: () -> Unit) {
        applying = true
        try {
            block()
        } finally {
            applying = false
        }
    }

    private fun start(delayMs: Long) {
        if (!isEnabled || !WubiCloudConfig.isLoggedIn) return
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!running.compareAndSet(false, true)) return@launch
            try {
                runOnce()
            } finally {
                running.set(false)
            }
        }
    }

    private fun runOnce() {
        if (!isEnabled || !WubiCloudConfig.isLoggedIn) return
        if (!tryLock()) return
        try {
            prefs.edit().putLong(ATTEMPT, System.currentTimeMillis()).apply()
            val outcome = syncBody()
            if (isEnabled) record(outcome)
        } catch (e: Exception) {
            if (isEnabled) record("自动同步失败：${e.message ?: "失败"}")
        } finally {
            unlock()
        }
    }

    private fun syncBody(): String {
        val meta = WubiCloudApi.fetchMeta()
        val savedRev = prefs.getInt(REVISION, 0)
        val base = loadBase()
        val local = UserDictWriter.loadActiveDocument()
        val localDirty = base?.let { !DictSyncDiff.sameSnapshot(local, it) } ?: true
        if (meta != null && meta.revision == savedRev && savedRev > 0 && !localDirty) {
            return "自动同步：本地与云端一致"
        }
        if (meta != null && meta.revision == savedRev && savedRev > 0 && base != null && localDirty) {
            return mergeAndPush(base, savedRev, base, allowRetry = true)
        }
        return pullMergeAndPush(allowRetry = true)
    }

    private fun pullMergeAndPush(allowRetry: Boolean): String {
        val pulled = WubiCloudApi.pullDict()
        val base = loadBase()
        if (pulled == null) {
            val local = UserDictWriter.loadActiveDocument()
            val revision = WubiCloudApi.pushDict(
                yaml = UserDictWriter.exportText(),
                wordCount = local.entryCount,
                baseRevision = 0
            )
            saveBase(local)
            prefs.edit().putInt(REVISION, revision).apply()
            return "自动同步：云端还没有备份，已上传本地 ${local.entryCount} 条"
        }
        val remote = UserDictWriter.parseDocument(pulled.yaml)
        return mergeAndPush(remote, pulled.revision, base, allowRetry)
    }

    private fun mergeAndPush(
        remote: UserDictDocument,
        remoteRevision: Int,
        base: UserDictDocument?,
        allowRetry: Boolean
    ): String {
        val local = UserDictWriter.loadActiveDocument()
        val probe = DictSyncDiff.compare(DictSyncDiff.flatten(local), DictSyncDiff.flatten(remote), true)
        if (probe.isLargeDivergence) {
            return "自动同步已暂停：${probe.largeDivergenceHint}请到「账户同步」对比后手动选择同步方式。"
        }
        val merged = DictSyncDiff.threeWayMerge(base, local, remote)
        if (merged.localChanged) {
            markApplying { UserDictWriter.saveActiveDocument(merged.merged) }
            RimeConfigDeploy.deployNow()
        }
        var revision = remoteRevision
        if (merged.needsUpload) {
            val fresh = UserDictWriter.loadActiveDocument()
            if (!DictSyncDiff.sameSnapshot(fresh, merged.merged)) {
                noteLocalEdit()
                return "自动同步：本地又有新改动，稍后重试"
            }
            try {
                revision = WubiCloudApi.pushDict(
                    yaml = UserDictWriter.exportText(),
                    wordCount = merged.merged.entryCount,
                    baseRevision = remoteRevision
                )
            } catch (e: CloudException) {
                if (allowRetry && (e.message ?: "").contains("版本已变化")) {
                    return pullMergeAndPush(allowRetry = false)
                }
                throw e
            }
        }
        saveBase(merged.nextBase)
        if (revision > 0) prefs.edit().putInt(REVISION, revision).apply()
        return message(merged)
    }

    private fun message(merged: DictSyncDiff.ThreeWayMerge): String {
        if (merged.conflicts.isNotEmpty()) {
            val names = merged.conflicts.take(3).joinToString("、") { it.word }
            val suffix = if (merged.conflicts.size > 3) " 等 ${merged.conflicts.size} 条" else ""
            return "自动同步：$names$suffix 两边都改过，已保留本地，未上传"
        }
        if (merged.needsUpload && merged.tookRemote > 0) {
            return "自动同步：已收下云端 ${merged.tookRemote} 条，并上传本地改动"
        }
        if (merged.needsUpload) return "自动同步：已上传本地改动"
        if (merged.tookRemote > 0 || merged.dropped > 0) {
            return "自动同步：已更新本地，收下 ${merged.tookRemote} 条，删除 ${merged.dropped} 条"
        }
        return "自动同步：本地与云端一致"
    }

    private fun record(text: String) {
        if (!isEnabled) return
        prefs.edit().putString(LAST_TEXT, text).apply()
    }

    private fun baseFile() = File(AppPaths.filesDir, "wubi-sync-base.yaml")

    private fun loadBase(): UserDictDocument? {
        val file = baseFile()
        if (!file.exists()) return null
        return runCatching { UserDictWriter.parseDocument(file.readText()) }.getOrNull()
    }

    private fun saveBase(document: UserDictDocument) {
        baseFile().writeText(UserDictWriter.render(document))
    }

    // 跨进程互斥，避免键盘和 App 同时推一份
    private fun tryLock(): Boolean {
        val now = System.currentTimeMillis()
        val held = prefs.getLong(LOCK, 0L)
        if (held > now) return false
        return prefs.edit().putLong(LOCK, now + 45_000).commit()
    }

    private fun unlock() {
        prefs.edit().putLong(LOCK, 0L).commit()
    }
}
