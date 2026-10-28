package cn.kylebing.meifeng.wubi.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.RimeResourceDeployer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPaths.init(this)
        RimeResourceDeployer.syncBundledIfNeeded(this)
        enableEdgeToEdge()
        setContent { MeifengTheme { SetupGuideScreen() } }
    }
}
