package cn.kylebing.meifeng.wubi.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.kylebing.meifeng.wubi.data.RimeConfigDeploy
import cn.kylebing.meifeng.wubi.data.UserDictDocument
import cn.kylebing.meifeng.wubi.data.UserDictWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 用户词库云端同步：对比后选择覆盖云端、覆盖本地或增量合并
class CloudDictSyncController {
    enum class DiffTab(val title: String) { OnlyLocal("仅本地"), OnlyRemote("仅云端"), Changed("内容不同") }
    enum class SyncAction { Upload, Download, Merge }

    var isComparing by mutableStateOf(false)
    var isApplying by mutableStateOf(false)
    var statusMessage by mutableStateOf<String?>(null)
    var statusIsError by mutableStateOf(false)
    var diffResult by mutableStateOf<DictSyncDiff.Result?>(null)
    var selectedTab by mutableStateOf(DiffTab.OnlyLocal)
    var showDiffSheet by mutableStateOf(false)
    var requiresManualChoice by mutableStateOf(false)

    private var remoteYaml: String? = null
    private var remoteDocument: UserDictDocument? = null

    fun showTip(message: String, isError: Boolean = false) {
        statusMessage = message
        statusIsError = isError
    }

    // 拉取云端并打开差异面板
    suspend fun compareWithRemote() {
        if (!refreshDiff(showSheetAlways = true)) return
        val result = diffResult ?: return
        when {
            result.isIdentical -> showTip("本地与云端完全一致")
            !result.hasRemote -> showTip("云端尚无该文件备份，可将本地上传")
        }
    }

    // 列表上的快捷同步。差异很大时先打开面板
    suspend fun requestSync(action: SyncAction) {
        if (!WubiCloudConfig.isLoggedIn) {
            showTip("未登录，请先登录", true)
            return
        }
        if (!refreshDiff(showSheetAlways = false)) return
        val result = diffResult ?: return
        if (result.isIdentical) {
            showTip("本地与云端完全一致，无需同步")
            return
        }
        if (result.isLargeDivergence || isDestructive(action, result)) {
            requiresManualChoice = true
            showDiffSheet = true
            showTip(result.largeDivergenceHint)
            return
        }
        apply(action)
    }

    // 面板里点选后直接执行
    suspend fun confirmSync(action: SyncAction) {
        apply(action)
    }

    fun previewWords(tab: DiffTab): List<DictSyncDiff.WordItem> {
        val result = diffResult ?: return emptyList()
        return when (tab) {
            DiffTab.OnlyLocal -> result.onlyLocal
            DiffTab.OnlyRemote -> result.onlyRemote
            DiffTab.Changed -> emptyList()
        }
    }

    fun previewChanged(): List<Pair<DictSyncDiff.WordItem, DictSyncDiff.WordItem>> =
        if (selectedTab == DiffTab.Changed) diffResult?.changed.orEmpty() else emptyList()

    private suspend fun refreshDiff(showSheetAlways: Boolean): Boolean {
        if (!WubiCloudConfig.isLoggedIn) {
            showTip("未登录，请先在同步设置中登录", true)
            return false
        }
        isComparing = true
        statusMessage = null
        statusIsError = false
        try {
            val local = UserDictWriter.loadActiveDocument()
            val pulled = withContext(Dispatchers.IO) { WubiCloudApi.pullDict() }
            if (pulled != null) {
                remoteYaml = pulled.yaml
                val remote = UserDictWriter.parseDocument(pulled.yaml)
                remoteDocument = remote
                val result = DictSyncDiff.compare(DictSyncDiff.flatten(local), DictSyncDiff.flatten(remote), true)
                diffResult = result
                pickDefaultTab(result)
                requiresManualChoice = result.isLargeDivergence
                if (showSheetAlways || result.isLargeDivergence) showDiffSheet = true
            } else {
                remoteYaml = null
                remoteDocument = null
                val result = DictSyncDiff.compare(DictSyncDiff.flatten(local), null, false)
                diffResult = result
                selectedTab = DiffTab.OnlyLocal
                requiresManualChoice = result.isLargeDivergence
                if (showSheetAlways || result.isLargeDivergence) showDiffSheet = true
            }
            return true
        } catch (e: Exception) {
            showTip(e.message ?: "对比失败", true)
            return false
        } finally {
            isComparing = false
        }
    }

    private fun isDestructive(action: SyncAction, result: DictSyncDiff.Result): Boolean = when (action) {
        SyncAction.Upload -> result.localCount == 0 && result.remoteCount > 0
        SyncAction.Download -> !result.hasRemote || (result.remoteCount == 0 && result.localCount > 0)
        SyncAction.Merge -> false
    }

    private suspend fun apply(action: SyncAction) {
        when (action) {
            SyncAction.Upload -> performUpload()
            SyncAction.Download -> performDownload()
            SyncAction.Merge -> performMerge()
        }
    }

    private suspend fun performUpload() {
        if (!WubiCloudConfig.isLoggedIn) {
            showTip("未登录，请先登录", true)
            return
        }
        isApplying = true
        try {
            val yaml = UserDictWriter.exportText()
            val count = UserDictWriter.loadActiveDocument().entryCount
            val revision = withContext(Dispatchers.IO) { WubiCloudApi.pushDict(yaml = yaml, wordCount = count) }
            UserDictAutoSync.noteCloudRevision(revision, UserDictWriter.loadActiveDocument())
            showDiffSheet = false
            requiresManualChoice = false
            showTip("已上传覆盖云端（$count 条）")
        } catch (e: Exception) {
            showTip(e.message ?: "上传失败", true)
        } finally {
            isApplying = false
        }
    }

    private suspend fun performDownload() {
        if (!WubiCloudConfig.isLoggedIn) {
            showTip("未登录，请先登录", true)
            return
        }
        if (diffResult?.hasRemote == false) {
            showTip("云端尚无备份，无法下载", true)
            return
        }
        isApplying = true
        try {
            val yaml = remoteYaml ?: withContext(Dispatchers.IO) { WubiCloudApi.pullDict() }?.yaml
            if (yaml == null) {
                showTip("云端尚无备份，无法下载", true)
                return
            }
            UserDictAutoSync.markApplying { UserDictWriter.importText(yaml) }
            val meta = withContext(Dispatchers.IO) { runCatching { WubiCloudApi.fetchMeta() }.getOrNull() }
            if (meta != null) UserDictAutoSync.noteCloudRevision(meta.revision, UserDictWriter.loadActiveDocument())
            WubiCloudConfig.lastSyncAt = System.currentTimeMillis()
            val deploy = RimeConfigDeploy.deployNow()
            showDiffSheet = false
            requiresManualChoice = false
            if (deploy.isSuccess) showTip("已用云端覆盖本地并部署")
            else showTip("已覆盖本地，但部署失败：${deploy.exceptionOrNull()?.message}", true)
        } catch (e: Exception) {
            showTip(e.message ?: "下载失败", true)
        } finally {
            isApplying = false
        }
    }

    private suspend fun performMerge() {
        if (!WubiCloudConfig.isLoggedIn) {
            showTip("未登录，请先登录", true)
            return
        }
        if (diffResult?.hasRemote == false) {
            performUpload()
            return
        }
        isApplying = true
        try {
            val remoteDoc = remoteDocument ?: run {
                val pulled = withContext(Dispatchers.IO) { WubiCloudApi.pullDict() }
                if (pulled == null) {
                    isApplying = false
                    performUpload()
                    return
                }
                remoteYaml = pulled.yaml
                UserDictWriter.parseDocument(pulled.yaml).also { remoteDocument = it }
            }
            val local = UserDictWriter.loadActiveDocument()
            val (merged, added) = DictSyncDiff.mergeRemoteIntoLocal(local, remoteDoc)
            UserDictAutoSync.markApplying {
                UserDictWriter.saveActiveDocument(merged)
            }
            val yaml = UserDictWriter.exportText()
            val revision = withContext(Dispatchers.IO) {
                WubiCloudApi.pushDict(yaml = yaml, wordCount = merged.entryCount)
            }
            UserDictAutoSync.noteCloudRevision(revision, merged)
            val deploy = RimeConfigDeploy.deployNow()
            showDiffSheet = false
            requiresManualChoice = false
            val tip = "已合并（本地新增 $added 条）并上传"
            if (deploy.isFailure) showTip("$tip；部署失败：${deploy.exceptionOrNull()?.message}", true)
            else showTip(tip)
        } catch (e: Exception) {
            showTip(e.message ?: "合并失败", true)
        } finally {
            isApplying = false
        }
    }

    private fun pickDefaultTab(result: DictSyncDiff.Result) {
        selectedTab = when {
            result.onlyLocal.isNotEmpty() -> DiffTab.OnlyLocal
            result.onlyRemote.isNotEmpty() -> DiffTab.OnlyRemote
            result.changed.isNotEmpty() -> DiffTab.Changed
            else -> DiffTab.OnlyLocal
        }
    }

    companion object {
        const val PREVIEW_LIMIT = 80
    }
}
