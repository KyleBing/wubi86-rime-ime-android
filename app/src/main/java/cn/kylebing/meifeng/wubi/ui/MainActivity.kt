package cn.kylebing.meifeng.wubi.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.RimeResourceDeployer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super 前安装，才能接上主题里的居中 logo
        installSplashScreen()
        super.onCreate(savedInstanceState)
        AppPaths.init(this)
        RimeResourceDeployer.syncBundledIfNeeded(this)
        enableEdgeToEdge()
        setContent { MeifengTheme { SetupGuideScreen() } }
    }
}
