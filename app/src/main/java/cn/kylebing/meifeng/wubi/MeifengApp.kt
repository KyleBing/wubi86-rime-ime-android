package cn.kylebing.meifeng.wubi

import android.app.Application
import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.DictIndex
import cn.kylebing.meifeng.wubi.sync.UserDictAutoSync

class MeifengApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        UserDictAutoSync.install()
        UserDictAutoSync.activate()
        // 主进程提前解压词库，键盘进程仍会自己加载
        Thread({ DictIndex.shared.ensureLoaded() }, "dict-preload").start()
    }
}
