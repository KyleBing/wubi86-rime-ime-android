package cn.kylebing.meifeng.wubi.sync

import cn.kylebing.meifeng.wubi.data.UserDictDocument
import cn.kylebing.meifeng.wubi.data.UserDictEntry
import cn.kylebing.meifeng.wubi.data.UserDictGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class CloudException(message: String) : Exception(message)

object WubiCloudApi {
    data class PullResult(val title: String, val yaml: String, val revision: Int)
    data class DictMeta(val revision: Int)

    data class ExtraWord(
        val code: String,
        val word: String,
        val weight: Int,
        val comment: String,
        val categoryName: String,
        val categoryId: Int = 0,
        val id: Int = 0
    )

    fun login(email: String, password: String, baseURL: String = WubiCloudConfig.baseURL): WubiCloudUserInfo {
        val conn = request("POST", url(baseURL, "user/login"), json = JSONObject().apply {
            put("email", email)
            put("password", password)
        })
        val root = JSONObject(conn)
        if (!root.optBoolean("success")) {
            val data = root.optJSONObject("data")
            if (data?.optString("code") == "email_not_verified") {
                throw CloudException(root.optString("message", "邮箱尚未验证，请查收验证邮件后再登录"))
            }
            throw CloudException(root.optString("message", "登录失败"))
        }
        val payload = root.optJSONObject("data") ?: throw CloudException("登录失败")
        val token = payload.optString("token")
        if (token.isEmpty()) throw CloudException("登录失败：未返回 token")
        val info = WubiCloudUserInfo(
            uid = payload.optInt("uid"),
            email = payload.optString("email").ifEmpty { email },
            nickname = payload.optString("nickname"),
            token = token,
            avatar = payload.optString("avatar")
        )
        WubiCloudConfig.baseURL = baseURL
        WubiCloudConfig.userInfo = info
        return info
    }

    fun pullDict(
        title: String = WubiCloudConfig.SYNC_DICT,
        user: WubiCloudUserInfo? = WubiCloudConfig.userInfo,
        baseURL: String = WubiCloudConfig.baseURL
    ): PullResult? {
        val u = user ?: throw CloudException("未登录，请先登录")
        val raw = request("GET", url(baseURL, "dict/pull") + "?title=" + enc(title), user = u)
        val root = JSONObject(raw)
        if (!root.optBoolean("success")) {
            val msg = root.optString("message")
            if (msg.contains("不存在") || msg.contains("未") || msg.contains("not found", true)) return null
            throw CloudException(msg.ifEmpty { "拉取失败" })
        }
        val data = root.optJSONObject("data") ?: return null
        val yaml = data.optString("content")
        if (yaml.isEmpty()) return null
        return PullResult(data.optString("title", title), yaml, data.optInt("revision"))
    }

    // GET dict/meta。没有备份时返回 null
    fun fetchMeta(
        title: String = WubiCloudConfig.SYNC_DICT,
        user: WubiCloudUserInfo? = WubiCloudConfig.userInfo,
        baseURL: String = WubiCloudConfig.baseURL
    ): DictMeta? {
        val u = user ?: throw CloudException("未登录，请先登录")
        val raw = request("GET", url(baseURL, "dict/meta") + "?title=" + enc(title), user = u)
        val root = JSONObject(raw)
        if (!root.optBoolean("success")) {
            val msg = root.optString("message")
            if (msg.contains("不存在") || msg.contains("未")) return null
            throw CloudException(msg.ifEmpty { "读取版本失败" })
        }
        val data = root.optJSONObject("data") ?: return null
        val revision = data.optInt("revision")
        if (revision <= 0) return null
        return DictMeta(revision)
    }

    // GET user/avatar。失败时保留空串
    fun fetchAvatar(email: String, baseURL: String = WubiCloudConfig.baseURL): String {
        val mail = email.trim()
        if (mail.isEmpty()) return ""
        val raw = request("GET", url(baseURL, "user/avatar") + "?email=" + enc(mail))
        val root = JSONObject(raw)
        if (!root.optBoolean("success")) return ""
        val data = root.opt("data")
        return when (data) {
            is String -> data
            is JSONObject -> data.optString("avatar")
            else -> ""
        }
    }

    // 已登录时刷新本地头像地址，失败则保留原来的
    fun refreshStoredAvatar(): String {
        val info = WubiCloudConfig.userInfo ?: return ""
        if (!info.isLoggedIn || info.email.isEmpty()) return ""
        val avatar = runCatching { fetchAvatar(info.email) }.getOrDefault("")
        if (avatar.isEmpty()) return WubiCloudConfig.userInfo?.avatar.orEmpty()
        val latest = WubiCloudConfig.userInfo ?: return ""
        if (!latest.isLoggedIn || latest.uid != info.uid) return ""
        if (latest.avatar != avatar) WubiCloudConfig.userInfo = latest.copy(avatar = avatar)
        return avatar
    }

    fun pushDict(
        title: String = WubiCloudConfig.SYNC_DICT,
        yaml: String,
        wordCount: Int,
        baseRevision: Int? = null,
        user: WubiCloudUserInfo? = WubiCloudConfig.userInfo,
        baseURL: String = WubiCloudConfig.baseURL
    ): Int {
        val u = user ?: throw CloudException("未登录，请先登录")
        if (yaml.length >= WubiCloudConfig.SYNC_MAX) {
            throw CloudException("同步内容超过 ${WubiCloudConfig.SYNC_MAX} 字")
        }
        val body = JSONObject().apply {
            put("title", title)
            put("content", yaml)
            put("contentSize", yaml.length)
            put("wordCount", wordCount)
            if (baseRevision != null) put("baseRevision", baseRevision)
        }
        val raw = request("PUT", url(baseURL, "dict/push"), json = body, user = u)
        val root = JSONObject(raw)
        val data = root.optJSONObject("data")
        if (data?.optBoolean("conflict") == true || root.optString("message").contains("版本已变化")) {
            throw CloudException("版本已变化")
        }
        if (!root.optBoolean("success")) throw CloudException(root.optString("message", "上传失败"))
        WubiCloudConfig.lastSyncAt = System.currentTimeMillis()
        return data?.optInt("revision") ?: 0
    }

    fun pullExtraDict(
        user: WubiCloudUserInfo? = WubiCloudConfig.userInfo,
        baseURL: String = WubiCloudConfig.baseURL
    ): List<ExtraWord> {
        val raw = request("POST", url(baseURL, "word/export-extra"), json = JSONObject(), user = user)
        val root = JSONObject(raw)
        if (!root.optBoolean("success")) throw CloudException(root.optString("message", "拉取扩展词库失败"))
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ExtraWord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val priority = o.opt("priority")
            val weight = when (priority) {
                is Number -> priority.toInt()
                is String -> priority.trim().toIntOrNull() ?: 0
                else -> o.optInt("priority")
            }
            out.add(
                ExtraWord(
                    code = o.optString("code"),
                    word = o.optString("word"),
                    weight = weight,
                    comment = o.optString("comment"),
                    categoryName = o.optString("category_name"),
                    categoryId = o.optInt("category_id"),
                    id = o.optInt("id")
                )
            )
        }
        return out
    }

    fun clearSyncData(user: WubiCloudUserInfo? = WubiCloudConfig.userInfo): String {
        val u = user ?: throw CloudException("未登录，请先登录")
        val raw = request("DELETE", url(WubiCloudConfig.baseURL, "user/clear-sync-data"), user = u)
        val root = JSONObject(raw)
        if (!root.optBoolean("success")) throw CloudException(root.optString("message", "删除失败"))
        WubiCloudConfig.lastSyncAt = 0L
        return root.optString("message", "线上同步数据已删除")
    }

    fun destroyAccount(user: WubiCloudUserInfo? = WubiCloudConfig.userInfo): String {
        val u = user ?: throw CloudException("未登录，请先登录")
        val raw = request("DELETE", url(WubiCloudConfig.baseURL, "user/destroy-account"), user = u)
        val root = JSONObject(raw)
        if (!root.optBoolean("success")) throw CloudException(root.optString("message", "注销失败"))
        return root.optString("message", "账号已注销")
    }

    private fun url(base: String, path: String) = WubiCloudConfig.resolveBaseURL(base) + path.trimStart('/')

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun request(
        method: String,
        urlStr: String,
        json: JSONObject? = null,
        user: WubiCloudUserInfo? = null
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            if (user != null) setRequestProperty("Authorization", "Bearer ${user.token}")
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(json.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
        conn.getHeaderField("X-Access-Token")?.takeIf { it.isNotEmpty() }?.let {
            WubiCloudConfig.updateTokenIfNeeded(it)
        }
        if (code !in 200..299) throw CloudException("网络错误（HTTP $code）")
        return body
    }
}

object DictSyncDiff {
    data class WordItem(
        val word: String,
        val code: String,
        val priority: String,
        val note: String,
        val groupName: String
    ) {
        val key get() = "$groupName\t$word\t$code"
        val brief get() = "$word  $code"
    }

    data class Result(
        val localCount: Int,
        val remoteCount: Int,
        val onlyLocal: List<WordItem>,
        val onlyRemote: List<WordItem>,
        val changed: List<Pair<WordItem, WordItem>>,
        val sameCount: Int,
        val hasRemote: Boolean
    ) {
        val isIdentical get() = hasRemote && onlyLocal.isEmpty() && onlyRemote.isEmpty() && changed.isEmpty()
        val divergentCount get() = onlyLocal.size + onlyRemote.size + changed.size
        val isLargeDivergence: Boolean
            get() {
                if (isIdentical) return false
                if (localCount == 0 && remoteCount >= 5) return true
                if (localCount <= 2 && remoteCount >= 20) return true
                if (hasRemote && remoteCount == 0 && localCount >= 5) return true
                if (hasRemote && remoteCount <= 2 && localCount >= 20) return true
                if (divergentCount >= 30) return true
                val total = maxOf(localCount, remoteCount, 1)
                return divergentCount >= 10 && divergentCount.toDouble() / total >= 0.5
            }

        // 差异过大时给用户看的说明
        val largeDivergenceHint: String
            get() = when {
                localCount == 0 && remoteCount > 0 ->
                    "本地词库为空，云端有 $remoteCount 条。若选「本地 → 云」会清空云端。"
                hasRemote && remoteCount == 0 && localCount > 0 ->
                    "云端无备份或为空，本地有 $localCount 条。若选「本地 ← 云」会清空本地。"
                localCount <= 2 && remoteCount >= 20 ->
                    "本地仅 $localCount 条，云端有 $remoteCount 条，差异很大。"
                hasRemote && remoteCount <= 2 && localCount >= 20 ->
                    "云端仅 $remoteCount 条，本地有 $localCount 条，差异很大。"
                else -> "本地 $localCount 条、云端 $remoteCount 条，共 $divergentCount 条不一致，请确认同步方式。"
            }
    }

    fun flatten(doc: cn.kylebing.meifeng.wubi.data.UserDictDocument): List<WordItem> {
        val list = mutableListOf<WordItem>()
        for (g in doc.groups) {
            for (e in g.entries) {
                list.add(
                    WordItem(
                        word = e.text,
                        code = e.code,
                        priority = e.weight?.toString() ?: "",
                        note = e.note,
                        // 用原始分组名做身份，空名不要换成展示名
                        groupName = g.name
                    )
                )
            }
        }
        return list
    }

    fun compare(local: List<WordItem>, remote: List<WordItem>?, hasRemote: Boolean): Result {
        val remoteList = remote ?: emptyList()
        val localMap = local.associateBy { it.key }
        val remoteMap = remoteList.associateBy { it.key }
        val onlyLocal = local.filter { it.key !in remoteMap }
        val onlyRemote = remoteList.filter { it.key !in localMap }
        val changed = local.mapNotNull { l ->
            val r = remoteMap[l.key] ?: return@mapNotNull null
            if (l.priority != r.priority || l.note != r.note) l to r else null
        }
        val same = local.count { l ->
            val r = remoteMap[l.key]
            r != null && l.priority == r.priority && l.note == r.note
        }
        return Result(local.size, remoteList.size, onlyLocal, onlyRemote, changed, same, hasRemote)
    }

    // 增量合并：保留本地已有词，补上云端独有词
    fun mergeRemoteIntoLocal(local: UserDictDocument, remote: UserDictDocument): Pair<UserDictDocument, Int> {
        val result = local.deepCopy()
        var added = 0
        if (result.isGroupMode || remote.isGroupMode || result.shouldPreserveGroupHeaders) {
            val indexByName = result.groups.mapIndexed { i, g -> g.name to i }.toMap().toMutableMap()
            for (remoteGroup in remote.groups) {
                val idx = indexByName[remoteGroup.name]
                if (idx != null) {
                    val existing = result.groups[idx].entries
                        .map { "${result.groups[idx].name}\t${it.text}\t${it.code}" }
                        .toMutableSet()
                    for (entry in remoteGroup.entries) {
                        val key = "${remoteGroup.name}\t${entry.text}\t${entry.code}"
                        if (key in existing) continue
                        result.groups[idx].entries.add(entry)
                        existing.add(key)
                        added++
                    }
                } else {
                    result.groups.add(
                        UserDictGroup(name = remoteGroup.name, entries = remoteGroup.entries.toMutableList())
                    )
                    indexByName[remoteGroup.name] = result.groups.lastIndex
                    added += remoteGroup.entries.size
                }
            }
            if (!result.isGroupMode && result.shouldPreserveGroupHeaders) result.isGroupMode = true
        } else {
            val existing = result.allEntries.map { "\t${it.text}\t${it.code}" }.toMutableSet()
            if (result.groups.isEmpty()) result.groups.add(UserDictGroup(name = ""))
            for (entry in remote.allEntries) {
                val key = "\t${entry.text}\t${entry.code}"
                if (key in existing) continue
                result.groups[0].entries.add(entry)
                existing.add(key)
                added++
            }
        }
        return result to added
    }

    data class ThreeWayMerge(
        val merged: UserDictDocument,
        val nextBase: UserDictDocument,
        val conflicts: List<WordItem>,
        val tookRemote: Int,
        val dropped: Int,
        val needsUpload: Boolean,
        val localChanged: Boolean
    )

    fun sameSnapshot(lhs: UserDictDocument, rhs: UserDictDocument): Boolean =
        mapsEqual(indexMap(lhs), indexMap(rhs))

    // 以上次同步为基准，本地和云端各自的改动都保留；两边都改过的留本地
    fun threeWayMerge(base: UserDictDocument?, local: UserDictDocument, remote: UserDictDocument): ThreeWayMerge {
        val baseMap = base?.let { indexMap(it) } ?: emptyMap()
        val localMap = indexMap(local)
        val remoteMap = indexMap(remote)
        val keys = (baseMap.keys + localMap.keys + remoteMap.keys).sorted()
        val chosen = LinkedHashMap<String, WordItem>()
        val conflictKeys = HashSet<String>()
        val conflicts = mutableListOf<WordItem>()
        var tookRemote = 0
        var dropped = 0
        for (key in keys) {
            when (val decision = pick(baseMap[key], localMap[key], remoteMap[key])) {
                is Pick.Use -> {
                    chosen[key] = decision.item
                    val remoteItem = remoteMap[key]
                    if (remoteItem != null && sameContent(decision.item, remoteItem)) {
                        val localItem = localMap[key]
                        if (localItem == null || !sameContent(localItem, remoteItem)) tookRemote++
                    }
                }
                Pick.Drop -> if (localMap[key] != null) dropped++
                is Pick.Conflict -> {
                    conflicts.add(decision.item)
                    conflictKeys.add(key)
                    localMap[key]?.let { chosen[key] = it }
                }
            }
        }
        val merged = documentFrom(chosen, local)
        val mergedMap = indexMap(merged)
        val baseNext = LinkedHashMap<String, WordItem>()
        if (conflictKeys.isEmpty()) {
            baseNext.putAll(mergedMap)
        } else {
            for ((key, item) in remoteMap) {
                if (key in conflictKeys) {
                    baseMap[key]?.let { baseNext[key] = it }
                    continue
                }
                val kept = chosen[key]
                if (kept != null && sameContent(kept, item)) baseNext[key] = item
            }
            for (key in conflictKeys) baseMap[key]?.let { baseNext[key] = it }
        }
        return ThreeWayMerge(
            merged = merged,
            nextBase = documentFrom(baseNext, local),
            conflicts = conflicts,
            tookRemote = tookRemote,
            dropped = dropped,
            needsUpload = conflictKeys.isEmpty() && !mapsEqual(mergedMap, remoteMap),
            localChanged = !mapsEqual(mergedMap, localMap)
        )
    }

    private sealed class Pick {
        data class Use(val item: WordItem) : Pick()
        data object Drop : Pick()
        data class Conflict(val item: WordItem) : Pick()
    }

    private fun pick(base: WordItem?, local: WordItem?, remote: WordItem?): Pick {
        if (local != null && remote != null && sameContent(local, remote)) return Pick.Use(local)
        if (base == null && local != null && remote != null) return Pick.Conflict(local)
        if (base != null && local != null && remote != null) {
            val localChanged = !sameContent(local, base)
            val remoteChanged = !sameContent(remote, base)
            if (localChanged && remoteChanged) return Pick.Conflict(local)
            if (remoteChanged) return Pick.Use(remote)
            return Pick.Use(local)
        }
        if (base != null && local != null && remote == null) {
            if (sameContent(local, base)) return Pick.Drop
            return Pick.Conflict(local)
        }
        if (base != null && local == null && remote != null) {
            if (sameContent(remote, base)) return Pick.Drop
            return Pick.Conflict(remote)
        }
        if (local != null) return Pick.Use(local)
        if (remote != null) return Pick.Use(remote)
        return Pick.Drop
    }

    private fun sameContent(lhs: WordItem, rhs: WordItem): Boolean =
        lhs.priority == rhs.priority && lhs.note == rhs.note && lhs.groupName == rhs.groupName

    private fun indexMap(document: UserDictDocument): Map<String, WordItem> =
        flatten(document).associateBy { it.key }

    private fun mapsEqual(lhs: Map<String, WordItem>, rhs: Map<String, WordItem>): Boolean {
        if (lhs.size != rhs.size) return false
        for ((key, item) in lhs) {
            val other = rhs[key] ?: return false
            if (!sameContent(item, other)) return false
        }
        return true
    }

    private fun weightValue(priority: String): Int? {
        val raw = priority.trim()
        if (raw.isEmpty()) return null
        return raw.toIntOrNull()
    }

    private fun documentFrom(chosen: Map<String, WordItem>, template: UserDictDocument): UserDictDocument {
        val result = template.deepCopy()
        val used = HashSet<String>()
        for (group in result.groups) {
            val kept = mutableListOf<UserDictEntry>()
            for (entry in group.entries) {
                val key = "${group.name}\t${entry.text}\t${entry.code}"
                val item = chosen[key] ?: continue
                kept.add(entry.copy(weight = weightValue(item.priority), note = item.note))
                used.add(key)
            }
            group.entries.clear()
            group.entries.addAll(kept)
        }
        for (key in chosen.keys.filter { it !in used }.sorted()) {
            val item = chosen[key] ?: continue
            val entry = UserDictEntry(item.code, item.word, weightValue(item.priority), item.note)
            val index = result.groups.indexOfFirst { it.name == item.groupName }
            if (index >= 0) {
                result.groups[index].entries.add(entry)
            } else {
                result.groups.add(UserDictGroup(name = item.groupName, entries = mutableListOf(entry)))
                if (item.groupName.isNotEmpty()) result.isGroupMode = true
            }
        }
        return result
    }
}
