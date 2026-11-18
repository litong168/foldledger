package com.foldledger.ui.l10n

import androidx.compose.runtime.mutableStateOf

/**
 * 界面语言（v16.7.0 中英双语）。
 *
 * 只提供「简体中文 / English」两种，默认中文。偏好值存 DataStore（AppSettings.language）。
 *
 * ⚠️ [displayName] 是语言选择器里的**原始名字**，不能走 t()：它要恒定显示两种语言自己的写法，
 * 而且枚举初始化早于 L10n，调 t() 会踩初始化顺序的坑。
 */
enum class Language(val code: String, val displayName: String) {
    CHINESE("zh", "简体中文"),
    ENGLISH("en", "English");

    companion object {
        fun fromCode(code: String?): Language = if (code == "en") ENGLISH else CHINESE
    }
}

/**
 * 全局语言状态 + 翻译。
 *
 * [language] 用 `mutableStateOf` 包裹——**这是关键**：即使 [t] 是普通函数（非 @Composable），
 * 只要它在某个 Composable 的组合期被调用，读取 `language.value` 就会被 Compose 的 snapshot
 * 系统记录为该 Composable 的依赖；语言一变，所有调用过 [t] 的 Composable 自动重组并取到新文案。
 *
 * 非 Composable 侧（ViewModel / 纯函数）调用 [t] 时读到的也是当前值，只是不建立订阅。
 */
object L10n {
    val language = mutableStateOf(Language.CHINESE)

    var current: Language
        get() = language.value
        set(value) { language.value = value }

    /** 中文原文 -> 英文。未命中的词条回退中文，保证任何界面都不会出现空洞。 */
    fun translate(zh: String): String =
        if (language.value == Language.ENGLISH) (EnStrings.map[zh] ?: zh) else zh
}

/**
 * 翻译一段 UI 文案。[zh] 直接用中文原文作 key，避免引入额外的 key 命名负担。
 *
 * 故意做成**非 Composable**：这样批量替换可以覆盖 ViewModel 提示语、object 常量、when 分支等
 * 所有上下文。它在组合期读 [L10n.language]（Snapshot 状态），自动建立重组订阅。
 * 未翻译的词条原样返回中文，绝不返回空串。
 */
fun t(zh: String): String = L10n.translate(zh)
