package cn.kylebing.meifeng.wubi.ime

enum class KeyAction {
    Character, Space, Return, Delete, Shift,
    SwitchLetters, SwitchNumbers, SwitchSymbols,
    ToggleLanguage, NextInput, Dismiss, Tab, Noop
}

enum class KeyboardPage { Letters, Numbers, Symbols }

data class KeySpec(
    val title: String,
    val action: KeyAction,
    val payload: String = "",
    val widthFactor: Float = 1f,
    val special: Boolean = false,
    val showsPopup: Boolean = true,
    val secondary: String? = null,
    val badge: String? = null,
    val bold: Boolean = false
)
