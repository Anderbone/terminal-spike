package com.yanjiyu.terminalspike.ui.settings

import androidx.annotation.StringRes
import com.yanjiyu.terminalspike.R
import java.text.Normalizer
import java.util.Locale

internal data class SettingsSearchEntry(
    val category: SettingsCategory,
    @StringRes val titleRes: Int,
    val aliases: String = "",
    @StringRes val anchorRes: Int? = titleRes,
) {
    val id: String get() = "${category.name}:$titleRes"
}

internal val settingsSearchEntries = listOf(
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_app_appearance_heading, "app theme light dark system 应用主题 浅色 深色 系统"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_codex_input_status, "codex context week quota usage input status 上下文 每周 额度 用量 输入框 状态"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_terminal_theme_heading, "terminal theme color 终端主题 颜色 配色"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_font_heading, "font import monospace 字体 导入 等宽"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_font_size, "font size 字号 字体大小"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_line_height, "line height 行高"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_letter_spacing, "letter spacing 字距"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_font_bold_rendering, "bold 粗体"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_font_ligatures, "ligatures 连字"),
    SettingsSearchEntry(SettingsCategory.APPEARANCE, R.string.settings_font_pinch_zoom, "pinch zoom 缩放 双指"),
    SettingsSearchEntry(SettingsCategory.TERMINAL, R.string.settings_terminal_copy_on_selection, "copy selection 选中 复制"),
    SettingsSearchEntry(SettingsCategory.TERMINAL, R.string.settings_osc52_heading, "osc52 remote clipboard 远程 剪贴板"),
    SettingsSearchEntry(SettingsCategory.TERMINAL, R.string.settings_multiline_paste_confirmation, "paste multiline confirm 多行 粘贴 确认"),
    SettingsSearchEntry(SettingsCategory.KEYBOARD, R.string.settings_keyboard_preset_heading, "keyboard keys preset 键盘 按键 预设"),
    SettingsSearchEntry(SettingsCategory.KEYBOARD, R.string.settings_edit_keyboard_keys, "keyboard keys shortcuts 按键 快捷键 编辑"),
    SettingsSearchEntry(SettingsCategory.KEYBOARD, R.string.settings_keyboard_layout_heading, "keyboard rows layout 单行 双行 布局"),
    SettingsSearchEntry(SettingsCategory.KEYBOARD, R.string.settings_input_buttons_right, "input buttons right 输入 按钮 右侧"),
    SettingsSearchEntry(SettingsCategory.KEYBOARD, R.string.settings_input_keyboard_edit, "input keyboard layout 输入键盘 布局 编辑"),
    SettingsSearchEntry(SettingsCategory.KEYBOARD, R.string.settings_voice_input_enabled, "voice speech microphone dictation 语音 输入 麦克风"),
    SettingsSearchEntry(SettingsCategory.KEYBOARD, R.string.settings_voice_input_heading, "voice recognition language 语音 识别 语言"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_auto_attach_herdr, "herdr auto attach 自动 进入 会话"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_tmux_session_selector, "tmux selector attach 会话 选择器"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_keepalive_interval, "ssh keepalive 保活"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_automatic_reconnect, "automatic reconnect 自动 重连"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_keep_cpu_awake, "cpu awake power 唤醒 电池"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_keep_screen_on, "screen awake 常亮 屏幕"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_notifications_status, "notification status 通知 状态"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_background_restriction, "background restriction 后台 限制"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_battery_optimisation, "battery optimisation optimization 电池 优化"),
    SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_data_saver, "data saver 流量 节省"),
    SettingsSearchEntry(SettingsCategory.NOTIFICATIONS, R.string.settings_notification_privacy, "notification privacy 隐私 通知"),
    SettingsSearchEntry(SettingsCategory.NOTIFICATIONS, R.string.settings_disconnect_notifications, "disconnect notification 断开 通知"),
    SettingsSearchEntry(SettingsCategory.NOTIFICATIONS, R.string.settings_reconnect_notifications, "reconnect notification 重连 通知"),
    SettingsSearchEntry(SettingsCategory.NOTIFICATIONS, R.string.settings_notification_permission, "notification permission 通知 权限"),
    SettingsSearchEntry(SettingsCategory.NOTIFICATIONS, R.string.settings_session_channel, "notification channel 会话 通知 渠道"),
    SettingsSearchEntry(SettingsCategory.SECURITY, R.string.settings_app_lock_authentication, "app lock authentication 应用锁 解锁 身份验证"),
    SettingsSearchEntry(SettingsCategory.SECURITY, R.string.settings_block_screenshots, "block screenshot 禁止 截图"),
    SettingsSearchEntry(SettingsCategory.SECURITY, R.string.settings_credential_storage, "credential passwords storage 凭据 密码 存储"),
    SettingsSearchEntry(SettingsCategory.SECURITY, R.string.settings_remote_clipboard, "remote clipboard 远程 剪贴板"),
    SettingsSearchEntry(SettingsCategory.SECURITY, R.string.settings_clipboard_clear_heading, "clipboard clear timeout 剪贴板 清除"),
    SettingsSearchEntry(SettingsCategory.SECURITY, R.string.settings_known_hosts_heading, "known hosts fingerprint 主机 指纹"),
    SettingsSearchEntry(SettingsCategory.SNIPPETS, R.string.settings_snippets_display, "snippet display 命令片段 显示", null),
    SettingsSearchEntry(SettingsCategory.BACKUP_RESTORE, R.string.settings_backup_save_action, "backup save export 保存 备份 导出"),
    SettingsSearchEntry(SettingsCategory.BACKUP_RESTORE, R.string.settings_backup_restore_action, "backup restore import 恢复 备份 导入"),
    SettingsSearchEntry(SettingsCategory.LOCAL_ARCH, R.string.local_arch_title, "local arch linux 本地", null),
    SettingsSearchEntry(SettingsCategory.MOSH, R.string.settings_category_mosh, "mosh transport 传输", null),
)

/** Search the displayed language plus English/Chinese labels; never require exact capitalization. */
internal fun searchSettings(
    query: String,
    entries: List<SettingsSearchEntry>,
    labels: (Int) -> List<String>,
): List<SettingsSearchEntry> {
    val terms = normalizeSettingsSearch(query).split(Regex("\\s+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        val title = normalizeSettingsSearch(labels(entry.titleRes).joinToString(" "))
        val category = normalizeSettingsSearch(labels(entry.category.titleRes).joinToString(" "))
        val searchable = "$title $category ${normalizeSettingsSearch(entry.aliases)}"
        if (!terms.all { it in searchable }) return@mapNotNull null
        val score = terms.sumOf { if (it in title) 3 else if (it in entry.aliases) 2 else 1 }
        entry to score
    }.sortedByDescending { it.second }.map { it.first }
}

private fun normalizeSettingsSearch(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
