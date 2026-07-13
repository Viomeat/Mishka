package top.yukonga.mishka.ui.platform

import androidx.compose.ui.platform.Clipboard

/**
 * 剪贴板纯文本读写助手。
 *
 * Compose 新 `Clipboard`（suspend）替代已弃用的 `ClipboardManager`，但 commonMain 未提供
 * 从文本构造 / 读取 `ClipEntry` 的工厂（`toClipEntry` 仅 Android），故按平台 expect/actual 实现。
 */
internal expect suspend fun Clipboard.setPlainText(text: String)

/** 读取剪贴板纯文本；空或非文本内容返回 null。 */
internal expect suspend fun Clipboard.getPlainText(): String?
