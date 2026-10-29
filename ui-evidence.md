# UI 取证 v2：新建项目模板弹窗（7 个模板 · 窄屏 / 大字体 / 亮暗模式）

> 关联：PR #14（模板扩展为 7 类）。本版基于 **`0a07e5a` feat(projects): 新建项目模板支持 C++/Go/Rust**，在独立 worktree `D:\deepseek\ZakoCoder-ui2` 中取证，主工作树未改动、未提交。
> 上一版证据（v1）基于 `4dc2228`、仅 4 个模板（静态网页 / Node.js / Python / 其他）；本版截图**同名覆盖** `01-light-default.png`、`02-dark.png`、`03-font-large.png`、`04-narrow.png`，PR #14 正文引用的 raw URL 会自动更新。
> 取证时间：2026-10-29 20:50–21:00（Asia/Shanghai）。

## 1. 设备 / 镜像 / APK 来源

| 项 | 值 |
|---|---|
| 模拟器 | Android Emulator 37.2.12.0（WHPX 加速） |
| AVD | `Medium_Phone_API_37.0`（x86_64） |
| System image | `android-37.0/google_apis_playstore/x86_64` |
| 设备型号 / 系统 | `sdk_gphone64_x86_64`，Android 17（API 37） |
| 默认分辨率 / 密度 | 1080x2400 / 420dpi |
| 启动命令 | `emulator -avd Medium_Phone_API_37.0 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim -skip-adb-auth` |
| APK | `app/build/outputs/apk/debug/app-debug.apk`（18,269,204 bytes，`:app:assembleDebug` BUILD SUCCESSFUL in 28s） |

**APK 来源**：本机无 ARM64 真机，为装进 x86_64 模拟器，在 worktree 临时把 `app/build.gradle.kts` 的 `abiFilters += "arm64-v8a"` 改为 `listOf("arm64-v8a", "x86_64")`（仅此一处）。取证后已 `git checkout -- app/build.gradle.kts` 还原，`git status` 干净，**未提交**。APK 内 `lib/` 仍只有 `lib/arm64-v8a/*.so`（termux/proot/zakocoder）+ `lib/x86_64/libandroidx.graphics.path.so`：纯 Java/Kotlin UI 可在 x86_64 运行，终端/PRoot 运行时原生库仍为 ARM64（见局限）。

安装：`adb install -r app-debug.apk` → Success；启动 `adb shell am start -n dev.zakocoder.app/.MainActivity`。

## 2. 导航路径（可复现）

1. 启动进入「项目库」空态页；
2. 点中部蓝色「+ 添加项目」（1080x2400：`(541,1517)`；窄屏 720x1280：`(360,855)`；大字体 1.5x、1080x2400：`(541,1588)`）；
3. 底部弹层「添加项目」点「创建空项目」（1080x2400：`(541,1959)`；窄屏：`(360,952)`；大字体：`(541,1830)`）；
4. 进入「新建项目」弹窗：项目名称输入框 + 「初始化模板」分组，**7 个模板：静态网页 / Node.js / Python / C++ / Go / Rust / 其他**（默认选中「其他」，未填名称时「创建」为禁用态）。

截图方式：`adb shell screencap -p /sdcard/ui.png` + `adb pull`（PowerShell 下 `adb exec-out > file` 会损坏 PNG）。

## 3. 四张截图与观察结论

截图目录：`D:\deepseek\ZakoCoder\.local\dev\ui\`（均为本次 7 模板版本，同名覆盖 v1）

### 01-light-default.png — 默认亮色、默认分辨率（1080x2400 / 420dpi / font_scale 1.0 / night no）
- 弹窗自顶部展示：项目名称、初始化模板分组、7 个模板卡片**全部可见**（静态网页 / Node.js / Python / C++ / Go / Rust / 其他），「其他」蓝色选中态 + 勾选清晰；「创建」禁用态可见。
- 观察：无文字截断、无重叠，浅色对比正常；**「取消」位于首屏下方（需上滑一次约 700px 才可见）** —— 7 个模板使弹窗内容高度超过一屏，这是与 v1（4 模板，取消首屏可见）的主要布局差异。

### 02-dark.png — 暗色模式（`adb shell cmd uimode night yes` 后重启 app，其余同 01）
- 7 个模板全部可见，深色卡片 + 反白文字，选中项深蓝底 + 蓝边 + 勾选；层级对比正常，无暗底暗字、无截断/重叠。
- 同 01：「创建」可见，「取消」需上滑后出现（已验证可触达）。

### 03-font-large.png — 大字体（`adb shell settings put system font_scale 1.5` 后重启 app；1080x2400 / 420dpi / night no）
- 首屏（截图所示顶部状态）：标题 + 项目名称 + 分组标题与说明 + 前 4 项（静态网页 / Node.js / Python / C++）完整可见，**Go 卡片在屏幕底边被裁去一部分，Rust 与「其他」完全在首屏之外**；长描述（C++ / Go / Rust 的说明）按 1.5x 正常折行，无省略号截断、无文字重叠。
- 滚动可达性：连续 2 次上滑（每次约 1000px，合计约 2000px）后到达底部，Rust / 其他 / 禁用「创建」/「取消」全部完整可见，底部按钮可触达。内容区可滚动，功能可用。

### 04-narrow.png — 窄屏（`adb shell wm size 720x1280` + `wm density 320` ≈ 360x640dp；font_scale 1.0 / night no）
- 首屏（截图所示顶部状态）：标题 + 项目名称 + 分组说明 + 前 4 项（静态网页 / Node.js / Python / C++）可见；**Go / Rust / 其他 在首屏之外**；卡片与描述文案按窄宽度正常折行，无横向溢出、无文字截断/重叠。
- 滚动可达性：连续 2 次上滑（每次约 600px，合计约 1200px）后可见 Python / C++ / Go / Rust / 其他 + 「创建」+「取消」，底部按钮可触达。

## 4. 与上一版（v1，4 模板 @ 4dc2228）的差异

| 项 | v1（4 模板） | v2（7 模板） |
|---|---|---|
| 模板数量 | 静态网页 / Node.js / Python / 其他 | 静态网页 / Node.js / Python / **C++ / Go / Rust** / 其他 |
| 默认 1080x2400 | 4 项全可见且「取消」首屏可见 | 7 模板全可见，但「取消」被挤出首屏（上滑一次可达） |
| 大字体 1.5x | 4 模板全可见，仅「取消」被挤出 | 首屏仅前 4 项 + Go 半裁；Rust/其他在屏外，需约 2 次上滑才能看到全部与底部按钮 |
| 窄屏 720x1280 | 4 模板全可见，底部按钮需 1 次上滑 | 首屏仅 4 项；Go/Rust/其他在屏外，需约 2 次上滑 |
| 新增模板描述 | — | C++「CMake 与 C++17 入口源码…」、Go「go.mod 与入口源码…」、Rust「Cargo.toml 与入口源码…」，换行正常 |

## 5. 发现的 UI 问题（按严重度）

1. **（中等/可用性）7 个模板后主操作按钮不常驻可见**：即便默认 1080x2400/420dpi 亮色与暗色下，「取消」也落在首屏之外（「创建」尚可见）；大字体与窄屏下需滚动约 2 次才能同时看到全部模板与「创建/取消」。建议把「取消 / 创建」改为底部 sticky 操作栏（内容区独立滚动），保证任意字号/宽度下主 CTA 常驻可见。
2. **（轻微）大字体首屏出现半裁卡片**：1.5x 下 Go 卡片在首屏底边被裁切（可滚动，非布局破损），视觉上提示性不足；可在列表底部加渐隐/滚动提示。
3. 未发现：文字截断至不可用（无省略号）、文字重叠、亮/暗对比度不足、选中态不清晰、窄屏横向溢出 —— 4 张截图均正常。

## 6. 环境问题与处理（与 v1 相同）

- adb 首次连接仍为 `emulator-5554 unauthorized`：模拟器用 `ANDROID_USER_HOME=D:\Android\.android` 的 adbkey，而 adb CLI 用 `%USERPROFILE%\.android\adbkey`，两者不同。
- 处理：备份 `C:\Users\suyi\.android\adbkey{,.pub}` → 用 `D:\Android\.android\` 同名文件覆盖 → 重启 adb server（`adb devices` 变 `device`）。**取证结束后已还原原 key**；后续再次取证需重复此步骤。
- 构建前持有 `D:\deepseek\ZakoCoder\.local\build.lock`，构建结束释放；未改动主工作树。

## 7. 真实局限性

- 证据来自 **x86_64 模拟器（Android 17/API 37）**，不是目标 ARM64 真机；x86_64 ABI 为取证临时构建配置，已还原、未提交。
- APK 内 termux/proot/zakocoder 原生库仍 arm64-v8a，**终端、PRoot 运行时、环境配置/执行相关功能在本模拟器上未验证**；本次只覆盖新建项目弹窗的 Compose UI 渲染与交互。
- 字体缩放仅测 1.5x（未测 2.0x 上限）；暗色仅测系统夜间模式跟随；窄屏仅测 720x1280@320dpi（360x640dp）一种，未测折叠屏/平板/横屏。
- 滚动距离为按 1080x2400、720x1280 实测滑动估算（每次上滑约 1000px / 600px），非精确像素测量。
- debug APK（release 因 PRoot provenance 校验无法出包）。

## 8. 可直接粘贴到 PR 的说明

```
UI 验证（7 个模板，基于 0a07e5a；x86_64 模拟器 Android 17/API 37，非 ARM64 真机，仅验证 Compose UI）：

- 亮色默认（1080x2400/420dpi）：7 个模板（静态网页/Node.js/Python/C++/Go/Rust/其他）全部可见，选中态与禁用「创建」正确，无截断/重叠；「取消」位于首屏下方，需上滑一次（约 700px）。截图：01-light-default.png
- 暗色模式（cmd uimode night yes）：7 个模板全可见，深色卡片/文字对比正常，布局与亮色一致；「取消」同样需上滑。截图：02-dark.png
- 大字体（font_scale 1.5）：首屏显示前 4 项（静态网页/Node.js/Python/C++），Go 在底边被半裁，Rust/其他在屏外；描述正常折行无截断/重叠；连续 2 次上滑约 2000px 后 Rust/其他/创建/取消 全部可见可触达。截图：03-font-large.png
- 窄屏（720x1280/320dpi ≈ 360x640dp）：首屏显示前 4 项，Go/Rust/其他在屏外，无横向溢出/截断；2 次上滑约 1200px 后全部模板与「创建/取消」可见。截图：04-narrow.png

说明：本机无 ARM64 真机，以上为临时 x86_64 ABI 构建的 debug APK 取证（构建配置已还原、未提交）；终端/PRoot 等 ARM64-only 运行时功能未在模拟器上验证。建议后续将「取消/创建」改为底部固定操作栏，避免 7 个模板/大字体/窄屏下主 CTA 需滚动才能触达。
```

## 9. 产出文件清单

- `D:\deepseek\ZakoCoder\.local\dev\ui\01-light-default.png`（1080x2400，覆盖 v1）
- `D:\deepseek\ZakoCoder\.local\dev\ui\02-dark.png`（1080x2400，覆盖 v1）
- `D:\deepseek\ZakoCoder\.local\dev\ui\03-font-large.png`（1080x2400，覆盖 v1）
- `D:\deepseek\ZakoCoder\.local\dev\ui\04-narrow.png`（720x1280，覆盖 v1）
- 日志：`D:\deepseek\ZakoCoder\.local\logs\build-ui-v2.log`、`emulator-ui2-v2.log`
