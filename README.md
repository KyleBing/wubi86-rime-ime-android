# 玫枫五笔（Android）

与 [iOS 版](../wubi86-rime-ime-ios) 同一套 `wubi86_jidian` 词库和交互：仿系统三页键盘、候选栏、造词、候选排序、标点方案、云端用户词同步、局域网配置文件管理。

Android 侧用码表引擎实现五笔检索（四码、唯一自动上屏、`z` 拼音反查、`date`/`time`/`week`），不嵌入 librime，避免键盘进程体积和内存超限。词库文件与 iOS `Resources/Rime` 同源。

## 结构

```
app/src/main/java/cn/kylebing/meifeng/wubi/
    ime/       输入法服务、仿系统键盘
    data/      词库、偏好、五笔引擎
    sync/      门户云同步
    ui/        设置 App（启用、外观、输入、词库、账号、方案）
app/src/main/assets/rime/   内置方案与词库
```

## 编译

需要本机 Android SDK（`compileSdk 35`）。

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleDebug
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

然后：系统设置 → 系统 → 语言和输入法 → 屏幕键盘 → 管理键盘 → 启用「玫枫五笔」，再在输入框切换到该键盘。

## 云端同步

与 macOS、Windows、iOS、码表助手共用门户：

```
https://kylebing.cn/wubi/portal/
```

邀请码注册：`https://kylebing.cn/wubi/manager/#/register`

## 局域网管理

设置 App → 词库管理 → WiFi 管理词库文件。保持该页打开，电脑与手机同一 Wi‑Fi，浏览器打开 `http://手机IP:8765/`，可上传、删除、部署词库。离开该页后服务关闭。

## 与 iOS 的对应关系

| iOS | Android |
| --- | --- |
| 键盘扩展 | `InputMethodService` |
| App Group 词库 | `files/rime/` |
| librime + 码表 | `DictIndex` + `WubiEngine` |
| 设置容器 App | Compose 设置页 |
| 局域网 HTTP 配置管理 | `RimeFileSyncServer` :8765 |
