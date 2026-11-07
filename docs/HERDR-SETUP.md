# Recommended Herdr settings for Terminal Spike

Configure Herdr on the computer that runs its terminal UI. With a normal SSH/Mosh
connection from Terminal Spike, this is the connected computer, not the phone.
These settings are optional; Terminal Spike also works with an ordinary shell.

## Check the installation first

Inspect the executable your connection actually launches and its supported options:

```sh
command -v herdr
herdr --version
herdr --help
herdr --default-config
```

On Linux/macOS, the usual configuration file is `~/.config/herdr/config.toml`.
Respect an existing `HERDR_CONFIG_PATH` or `XDG_CONFIG_HOME` override, and use the
resolved path reported by the installed CLI. Other platforms can use different
paths. Back up the actual file before editing it.

Merge the following keys into the existing `[ui]` table. Keep other settings,
keybindings, connections and integrations. Do not append a second `[ui]` table or
redirect the complete default config over an existing file. See Herdr's
[configuration guide](https://herdr.dev/tui/docs/configuration/) for file discovery
and reload behavior.

## Phone and foldable layout

```toml
[ui]
mouse_capture = true
mobile_width_threshold = 64
status_indicators = "dots"

# Optional compact presentation:
pane_gaps = false
pane_outer_borders = false
pane_scrollbars = false
prompt_new_tab_name = false
```

| Setting | Purpose |
| --- | --- |
| `mouse_capture = true` | Let Herdr handle touch-derived mouse actions and menus. |
| `mobile_width_threshold = 64` | Use Switch on narrow terminals and the desktop sidebar above 64 columns, including unfolded screens with enough columns. |
| `status_indicators = "dots"` | Keep colored task status indicators (green idle, yellow working in the development grouped list). |
| `pane_gaps = false` | Reduce space between split panes. |
| `pane_outer_borders = false` | Remove the outside pane frame. |
| `pane_scrollbars = false` | Reclaim the remote pane scrollbar column. |
| `prompt_new_tab_name = false` | Open a tab without a naming prompt; this does not generate a task name. |

The threshold uses terminal columns, not screen pixels or device model. Larger
fonts reduce column count. A threshold of 160 also puts many unfolded screens
into Switch mode; use 64 when you want the sidebar and terminal together on a
wide screen. This setting also affects other Herdr clients using that configuration.

The four compact-presentation choices are preferences: keep borders, scrollbars or
the naming prompt if you prefer them. The installed version must support each
key. Upstream documents these options in its
[configuration reference](https://herdr.dev/tui/docs/config-reference/).

## Grouped sidebar on wide screens

Only with a Herdr build that supports it, merge this additional key into the same
existing `[ui]` table:

```toml
grouped_sidebar = true
```

The development client groups numbered tasks under each Space in the wide-screen
sidebar, matching its mobile list. Status dots remain at the left; the redundant
separator after each number and large indents are removed to leave more room for
names. This key does not replace `mobile_width_threshold = 64`, and is not a
configuration-only way to add the grouping patches to an upstream executable.

## Configuration versus development-build features

| Behavior | Requirement |
| --- | --- |
| Mobile layout at a chosen width; mouse capture; compact pane decoration | Supported Herdr configuration options above. |
| Space → task grouping, numbered top tabs, and Git beside grouped project headings | The development Herdr client used for these improvements. A width setting alone does not add this layout to upstream builds. |
| Automatic names from an existing Codex terminal title | The corresponding development Herdr server. Manual names remain authoritative; no extra AI request is made. |
| Git ahead/behind arrows | Cached tracking-branch information supplied by Herdr. |
| Clean, modified/untracked and conflict markers | Available `git_clean`, `git_dirty` and `git_conflict` workspace metadata from a status provider. |

The Android APK does not bundle these Herdr patches. Users of the same upgraded
Herdr installation share them; independently hosted installations need a
compatible Herdr build and configuration. Users do not need to edit the source
themselves. No patched Herdr download is linked in this guide: treat these as
development-build features unless a release explicitly supplies a compatible
artifact. Do not assume an upstream binary includes these changes,
or identify a patched build solely by its version number.

### Git indicators

`↑` and `↓` describe commit differences from the tracked branch. Worktree markers
such as `+`, `-`, `~` and `?` describe file state according to the installed status
provider; `✓` is an explicitly reported clean worktree, not a promise that every
commit has been pushed.

Keep an existing working Git-status provider. Adding token names to configuration
does not create that provider. If no provider is installed, report the missing
data source rather than showing a clean checkmark or silently installing a
background monitor. Missing data and zero ahead/behind counts do not prove a
clean worktree.

For an existing provider, the traditional sidebar can use the following row
configuration. Merge it with existing rows rather than replacing a customized
layout. The development grouped view reads these cached values directly.

```toml
[ui.sidebar.spaces]
rows = [
  ["state_icon", "workspace"],
  ["branch", "git_status", "$git_clean", "$git_dirty", "$git_conflict"],
]
```

## Apply and verify

Use Herdr's global menu → **reload config** to reload client and selected-server
settings. The CLI can also reload the server, in the intended session/socket
context:

```sh
herdr server reload-config
```

For a named session, use `herdr --session NAME server reload-config`. Check the
returned status and diagnostics, rather than treating a successful command exit
alone as proof that every setting applied.

On the phone, open Switch, check task selection and menus, then fold/unfold or
rotate and confirm that narrow terminals use Switch while wider terminals keep
the sidebar beside the terminal. With a compatible development build, confirm
that both layouts show grouped tasks and their colored status indicators.
Check a known dirty repository and a known clean one if Git metadata is available.
An agent without access to the phone should report those physical checks as
pending instead of claiming they passed.

After installing a new Herdr **executable**, an already running client must use
Herdr's **menu → detach**, then run `herdr` again (or reattach its named session).
Resuming the same Mosh transport can preserve the old client. Keep the server and
its tasks running; do not stop it or recreate workspaces for a UI configuration
change. To undo configuration changes, restore the backup and reload it.

These instructions are also available inside the Android app under
**Settings → User guide → Herdr setup for phones & foldables**, including
copyable settings and instructions to send to an agent; GitHub is not required.

## Ask an agent to configure it

Copy either message below. It authorizes configuration edits, not a Herdr binary
upgrade or source patch. A missing development build should be reported separately;
the supported settings can still be configured.

### English

```text
Configure Herdr for Terminal Spike on the computer my SSH/Mosh connection uses. Identify the host, executable/version and actual config path, respecting HERDR_CONFIG_PATH/XDG_CONFIG_HOME. Inspect supported keys, back up the file, and merge these [ui] values for the compact phone/foldable preset: mouse_capture=true, mobile_width_threshold=64, status_indicators="dots", pane_gaps=false, pane_outer_borders=false, pane_scrollbars=false, prompt_new_tab_name=false. Enable grouped_sidebar=true only if the installed build supports it. Preserve all other settings, keybindings, connections, integrations and running tasks; do not duplicate TOML tables. Distinguish upstream configuration from Terminal Spike's development Herdr client/server features: grouping, numbered top tabs, Git headings and automatic task names cannot be enabled on an unsupported binary by adding settings. Keep an existing Git-status provider; if it is missing, report that requirement instead of installing a monitor or claiming the worktree is clean. Do not download/replace binaries, patch source, stop servers, kill panes or recreate workspaces. Validate TOML, reload the intended session's config, inspect diagnostics and state which checks actually passed. Explain any needed client detach/reattach and phone fold/unfold checks, without claiming unperformed checks passed. Report the config path, minimal diff and rollback steps, omitting secrets.
```

### 中文：可直接转发

```text
请在我用 Terminal Spike 通过 SSH/Mosh 连接的电脑上配置 Herdr。先确认目标主机、实际启动的 Herdr 路径/版本及配置文件路径，尊重 HERDR_CONFIG_PATH、XDG_CONFIG_HOME 等已有设置。检查版本支持后，备份文件，并把紧凑手机/折叠屏预设合并进已有 [ui]：mouse_capture=true、mobile_width_threshold=64、status_indicators="dots"、pane_gaps=false、pane_outer_borders=false、pane_scrollbars=false、prompt_new_tab_name=false。仅在当前 Herdr 构建支持时添加 grouped_sidebar=true。保留其余配置、快捷键、连接、集成和运行任务，不要重复添加 TOML 表。明确区分上游配置项与 Terminal Spike 开发版 Herdr 才有的分组、数字顶栏、项目 Git 标记和自动任务命名；缺少兼容版本时说明限制，先完成可用的配置，不要假称全部功能已启用。保留现有 Git 状态数据源；缺少时说明，不擅自安装后台监控，也不把无数据当作干净。不要下载或替换二进制、改源码、停止服务端、杀 pane 或重建 workspace。校验 TOML，重载正确 session/socket 的配置并检查诊断；说明是否需要 detach 后重进客户端，以及手机折叠/展开还需验证哪些项目，没做的检查不能说通过。最后给出配置路径、最小 diff、实际验证结果和回滚方法，不输出密钥或密码。
```
