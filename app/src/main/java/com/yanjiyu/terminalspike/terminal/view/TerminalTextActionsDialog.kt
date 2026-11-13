package com.yanjiyu.terminalspike.terminal.view

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.terminal.selection.terminalTextAsSingleLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkPolicy
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkTarget
import com.yanjiyu.terminalspike.terminal.selection.TerminalTextExtraction

/** Optional text workspace. The terminal's floating selection toolbar remains the primary UI. */
internal fun showTerminalTextActions(
    context: Context,
    extraction: TerminalTextExtraction,
    link: TerminalLinkTarget?,
    copy: (String, TerminalClipboardContentKind) -> Boolean,
    openLink: (TerminalLinkTarget) -> Unit,
): AlertDialog {
    val padding = (12 * context.resources.displayMetrics.density).toInt()
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }
    val editor = EditText(context).apply {
        id = R.id.terminal_extracted_text
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(android.text.InputFilter.LengthFilter(65_536))
        typeface = Typeface.MONOSPACE
        textSize = 13f
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setText(extraction.text)
        setTextIsSelectable(true)
        setSelectAllOnFocus(false)
        setHorizontallyScrolling(false)
        showSoftInputOnFocus = false
    }
    val header = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
    header.addView(TextView(context).apply {
        setText(R.string.terminal_text_tools)
        textSize = 18f
    }, LinearLayout.LayoutParams(0, -2, 1f))
    val more = android.widget.Button(context).apply {
        text = "⋮"
        contentDescription = context.getString(R.string.terminal_text_more)
    }
    header.addView(more, LinearLayout.LayoutParams((48 * context.resources.displayMetrics.density).toInt(), -2))
    content.addView(header)
    content.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
    val dialog = AlertDialog.Builder(context)
        .setView(content)
        .setPositiveButton(R.string.terminal_action_copy, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    more.setOnClickListener {
        PopupMenu(context, more).apply {
            fun item(label: Int, action: () -> Unit) {
                menu.add(label).setOnMenuItemClickListener { action(); true }
            }
            item(R.string.terminal_copy_single_line) {
                val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
                val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
                val text = terminalTextAsSingleLine(editor.text.substring(start, end))
                if (text.isNotEmpty() && copy(text, TerminalClipboardContentKind.SELECTION)) dialog.dismiss()
            }
            item(R.string.terminal_select_logical_line) {
                val text = editor.text.toString()
                val hit = editor.selectionStart.coerceAtLeast(0)
                val start = if (hit == 0) 0 else text.lastIndexOf('\n', hit - 1) + 1
                val end = text.indexOf('\n', hit).takeIf { it >= 0 } ?: text.length
                editor.setSelection(start, end)
            }
            item(R.string.terminal_edit_extracted_text) {
                editor.showSoftInputOnFocus = true
                editor.requestFocus()
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            item(R.string.terminal_join_selected_lines) {
                val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
                val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
                val joined = editor.text.substring(start, end).replace("\n", "")
                editor.text.replace(start, end, joined)
                editor.setSelection(start, start + joined.length)
            }
            item(R.string.terminal_restore_extracted_text) {
                editor.setText(extraction.text)
                editor.setSelection(extraction.selectionStart, extraction.selectionEnd)
            }
            link?.let { target ->
                item(R.string.terminal_action_copy_link) {
                    if (copy(target.uri, TerminalClipboardContentKind.LINK)) dialog.dismiss()
                }
                if (TerminalLinkPolicy.canOpen(target.uri)) item(R.string.terminal_action_open_link) {
                    openLink(target)
                    dialog.dismiss()
                }
            }
            show()
        }
    }
    dialog.setOnShowListener {
        content.layoutParams = content.layoutParams.apply {
            height = (context.resources.displayMetrics.heightPixels * 0.8f).toInt()
        }
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editor.requestFocus()
        editor.setSelection(extraction.selectionStart, extraction.selectionEnd)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
            if (end > start && copy(editor.text.substring(start, end), TerminalClipboardContentKind.SELECTION)) dialog.dismiss()
        }
    }
    dialog.show()
    return dialog
}
