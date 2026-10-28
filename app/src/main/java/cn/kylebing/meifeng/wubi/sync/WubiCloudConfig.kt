package cn.kylebing.meifeng.wubi.sync

import cn.kylebing.meifeng.wubi.data.AppPaths
import org.json.JSONObject

data class WubiCloudUserInfo(
    val uid: Int,
    val email: String,
    val nickname: String,
    val token: String,
    val avatar: String = ""
) {
    val isLoggedIn: Boolean get() = uid > 0 && token.isNotEmpty()

    fun toJson(): String = JSONObject().apply {
        put("uid", uid)
        put("email", email)
        put("nickname", nickname)
        put("token", token)
        put("avatar", avatar)
    }.toString()

    companion object {
        fun fromJson(raw: String): WubiCloudUserInfo? = runCatching {
            val o = JSONObject(raw)
            val token = o.optString("token")
            WubiCloudUserInfo(
                uid = o.optInt("uid"),
                email = o.optString("email"),
                nickname = o.optString("nickname"),
                token = token,
                avatar = o.optString("avatar")
            ).takeIf { it.isLoggedIn }
        }.getOrNull()
    }
}

object WubiCloudConfig {
    const val DEFAULT_BASE = "https://kylebing.cn/wubi/portal/"
    const val SYNC_DICT = "wubi86_jidian_user.dict.yaml"
    const val EXTRA_DICT = "wubi86_jidian_extra.dict.yaml"
    const val SYNC_MAX = 40_000

    private val p get() = AppPaths.prefs

    fun resolveBaseURL(raw: String?): String {
        var base = (raw ?: "").trim().ifEmpty { DEFAULT_BASE }
        if (base.startsWith("http://kylebing.cn/")) {
            base = "https://" + base.removePrefix("http://")
        }
        if (base == "https://kylebing.cn/portal/" || base == "https://kylebing.cn/portal") {
            base = DEFAULT_BASE
        }
        if (!base.endsWith("/")) base += "/"
        return base
    }

    var baseURL: String
        get() = resolveBaseURL(p.getString("wubi.cloud.baseURL", DEFAULT_BASE))
        set(v) { p.edit().putString("wubi.cloud.baseURL", resolveBaseURL(v)).apply() }

    var userInfo: WubiCloudUserInfo?
        get() = p.getString("wubi.cloud.userInfo", null)?.let { WubiCloudUserInfo.fromJson(it) }
        set(v) {
            val e = p.edit()
            if (v != null) e.putString("wubi.cloud.userInfo", v.toJson())
            else e.remove("wubi.cloud.userInfo")
            e.apply()
        }

    val isLoggedIn: Boolean get() = userInfo?.isLoggedIn == true

    var lastSyncAt: Long
        get() = p.getLong("wubi.cloud.lastSyncAt", 0L)
        set(v) { p.edit().putLong("wubi.cloud.lastSyncAt", v).apply() }

    // 扩展词库上次从线上覆盖的时间，0 表示还没有
    var lastExtraPullAt: Long
        get() = p.getLong("wubi.cloud.lastExtraPullAt", 0L)
        set(v) {
            val e = p.edit()
            if (v <= 0L) e.remove("wubi.cloud.lastExtraPullAt") else e.putLong("wubi.cloud.lastExtraPullAt", v)
            e.apply()
        }

    fun managerPublicBase(): String {
        var base = baseURL.removeSuffix("/")
        return if (base.contains("/portal")) base.replace("/portal", "/manager") else "$base/wubi/manager"
    }

    fun registerUrl() = managerPublicBase() + "/#/register"
    fun registerApplyUrl() = managerPublicBase() + "/#/register-apply"

    fun updateTokenIfNeeded(token: String) {
        if (token.isEmpty()) return
        val info = userInfo ?: return
        if (info.token == token) return
        userInfo = info.copy(token = token)
    }

    fun logout() { userInfo = null }
}
