#!/usr/bin/env bash
# Prints fixtures only. No network requests or commands from the examples are executed.
set -eu
cat <<'TEXT'
长按选择与复制 — 手动试用
长按先显示选择手柄和系统小工具栏；需要整理时再点「文本工具」。

1. 短链接：长按后应能复制链接、打开链接，也能选择附近文字。
https://example.com

2. 长链接：屏幕自动折行后，长按中间或最后一行，复制链接应无空格、无换行。
https://play.google.com/store/apps/details?id=de.astubenbord.paperless_mobile

3. 括号和中文标点：复制链接应不包含外面的括号和句号。
应用说明（https://example.com/docs?name=hello%20world&mode=read#section）。

4. 单行命令：下面只是文字；选择整行并复制，不应改变引号、空格或反斜杠。
printf '%s\n' 'hello world' '中文 🚀' 'https://example.com/?a=1&b=2'

5. 多行命令：真实换行默认保留。移除换行只作用于选区，可点恢复原文撤销。
printf '%s\n' \
  'first line' \
  '第二行 🚀'

6. 中文选择：可以扩大或缩小选区，只复制「中文 🚀 hello world」，再试编辑文字。
前面的说明 中文 🚀 hello world 后面的说明

7. 在 HERDR/Codex 的历史输出中也试长按；关闭面板后，应仍停在原阅读位置。
TEXT
