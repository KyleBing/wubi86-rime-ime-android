package cn.kylebing.meifeng.wubi.data

import android.content.Context
import java.io.File

/** 词库与偏好的本地目录，对齐 iOS App Group 用户数据目录 */
object AppPaths {
    lateinit var filesDir: File
        private set
    lateinit var prefs: android.content.SharedPreferences
        private set
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        filesDir = appContext.filesDir
        prefs = appContext.getSharedPreferences("meifeng_wubi", Context.MODE_PRIVATE)
        userDir.mkdirs()
        RimeResourceDeployer.syncBundledIfNeeded(appContext)
    }

    val context: Context get() = appContext

    val userDir: File get() = File(filesDir, "rime")

    val userDictFile: File get() = File(userDir, "wubi86_jidian_user.dict.yaml")
    val extraDictFile: File get() = File(userDir, "wubi86_jidian_extra.dict.yaml")
    val mainDictFile: File get() = File(userDir, WubiDictEncoder.selectedMainDictName)
    val charMapFile: File get() = File(userDir, WubiDictEncoder.CHAR_MAP_NAME)
}

/** 首次启动把内置方案拷到用户目录 */
object RimeResourceDeployer {
    private const val STAMP = "rime.bundled.stamp"
    private const val STAMP_VALUE = 1

    fun syncBundledIfNeeded(context: Context): Boolean {
        val prefs = AppPaths.prefs
        val dest = AppPaths.userDir
        dest.mkdirs()
        val need = prefs.getInt(STAMP, 0) != STAMP_VALUE || !File(dest, "wubi86_jidian.dict.yaml").exists()
        if (!need) return false
        val names = context.assets.list("rime") ?: emptyArray()
        for (name in names) {
            context.assets.open("rime/$name").use { input ->
                File(dest, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        prefs.edit().putInt(STAMP, STAMP_VALUE).apply()
        return true
    }

    fun resetToFactory(context: Context) {
        AppPaths.userDir.deleteRecursively()
        AppPaths.userDir.mkdirs()
        AppPaths.prefs.edit().remove(STAMP).apply()
        syncBundledIfNeeded(context)
        UserDictWriter.markNeedsDeploy()
        DictIndex.shared.reload()
        WubiDictEncoder.clearCache()
    }
}
