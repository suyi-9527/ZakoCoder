# UI 取证：网络代理与机场订阅页（PR #48，7 模板流程后）

> **重要标注：本页所有机场订阅内容均为本地注入的 fake 数据渲染，非真实机场订阅、非真实网络请求。** 设备为 x86_64 模拟器（非目标 ARM64 真机）。本文档仅用于布局/交互证据。
> 基线：worktree `D:\deepseek\ZakoCoder-proxyui`（detached HEAD `3a74204` feat(proxy): 重做网络代理页并支持机场订阅导入）。
> 取证时间：2026-11-03 12:18–12:41（Asia/Shanghai）。截图目录：`D:\deepseek\ZakoCoder\.local\dev\ui-proxy\`。

## 1. 临时 fake 注入（未提交，已还原）

目的：模拟器无法访问真实订阅（fetcher 仅 HTTPS 且无外网），仅在 **worktree 本地**注入 demo fetcher 渲染 UI。

- 新增（未跟踪，仅本地）`app/src/main/java/dev/zakocoder/app/settings/DemoProxySubscriptionFetcher.kt`：
  - 实现 `ProxySubscriptionFetcher`，按 URL 尾段返回固定 Clash YAML（`proxies:` 子集）：
    - `/slow` → 延迟 4s 后返回 7 节点（Socks5 带凭据、Socks5 无凭据、HTTP 带凭据、VMess、SS、Trojan、Hysteria2），用于捕获识别中状态与机场结果；
    - `/single` → 1 个可用 SOCKS5 节点（单节点结果）；
    - `/unsupported` → VMess/SS/Trojan 三个均“暂不支持”的节点；
    - `/error` → 抛 `ProxySubscriptionException(ProxySubscriptionError.Unreachable)`；
    - 其他 → 同 7 节点内容。
- 修改（仅一行）`app/src/main/java/dev/zakocoder/app/Workbench.kt:118`：
  `ProxyCatalog(HttpProxySubscriptionFetcher(), …)` → `ProxyCatalog(DemoProxySubscriptionFetcher(), …)`。
- 还原：`git -C D:\deepseek\ZakoCoder-proxyui checkout -- app/src/main/java/dev/zakocoder/app/Workbench.kt` + 删除 `DemoProxySubscriptionFetcher.kt`；收尾 `git status` 干净，**未 commit**。

`git diff` 摘要（注入时）：仅 `Workbench.kt` 1 行修改 + 1 个新增未跟踪文件；两处均已还原。

## 2. 构建与安装

- 模拟器：`Medium_Phone_API_37.0`（x86_64 / android-37.0 google_apis_playstore），headless 启动
  `emulator -avd Medium_Phone_API_37.0 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim -skip-adb-auth`。
- adbkey 坑（同前）：模拟器用 `ANDROID_USER_HOME=D:\Android\.android` 的 key，adb CLI 用 `%USERPROFILE%\.android`。临时用 D 盘 key 覆盖并重启 adb server，**收尾已还原**。
- 构建（持 `D:\deepseek\ZakoCoder\.local\build.lock`，finally 释放）：
  `.\gradlew.bat :app:assembleDebug -x :app:prepareRuntime "-PcompatibilityAbis=arm64-v8a,x86_64" --console=plain` → **BUILD SUCCESSFUL in 1m 17s**，APK 21,428,100 bytes。
  > 说明：该提交的 `app/build.gradle.kts` 已支持 `-PcompatibilityAbis`（逗号分隔，仅允许 arm64-v8a/x86_64），因此**无需临时修改 build.gradle.kts**，直接用属性注入 x86_64 即可，收尾无 build 配置改动。
- 安装：`adb -s emulator-5554 install -r app-debug.apk` → Success；启动 `am start -n dev.zakocoder.app/.MainActivity`。
- 导航：项目库 → 底部「设置」→ 列表「网络代理」（设置页需下滑到「终端/网络」区）→ 网络代理页。

## 3. 截图与观察（每张均经 read_image 检查）

设备参数：除非特别注明，均为 1080x2400 / 420dpi / font_scale 1.0 / night no。

| # | 文件 | 状态/参数 | 观察结论 |
|---|---|---|---|
| 01 | `01-status-direct.png` | 直连（默认）、空态 | 当前状态卡「直连 / 未配置代理」；连接方式三选一（直连/手动代理/机场订阅）选中直连；「使用直连」主按钮；无截断/重叠 |
| 02 | `02-manual.png` | 手动代理 + 输入框聚焦（键盘弹出） | 键盘占据下半屏时「保存并启用」CTA 仍完整可见且位于键盘上方，不被遮挡；输入框内容 `socks5://demo:secret@10.0.0.1:1080` 单行显示；说明文案折行正常 |
| 03 | `03-identify-loading.png` | 机场订阅「识别中」 | 识别按钮变为 spinner + 「正在识别」，粘贴按钮禁用；键盘弹出状态；底部「取消 / 保存并启用」仍可达。*局限：骨架卡片位于识别按钮下方、被键盘与底部操作栏挤压，截图中未完整露出（已尝试清空缓存后重截，受时间盒限制未成功），此图覆盖加载态而非骨架卡片态。* |
| 04 | `04-airport-light.png` | 机场结果（亮色） | 「机场订阅」badge + 「7 个节点 · 2 个可用」；节点列表 SOCKS/HTTP/「暂不支持」标记；默认选中「香港 01 · SOCKS5」；底部栏「将启用：香港 01 · SOCKS5」+「取消 / 保存并启用」；无截断/横向溢出 |
| 05 | `05-airport-dark.png` | 同上，night yes | 深色背景、卡片与文字对比正常，badge/选中态清晰；布局与亮色一致 |
| 06 | `06-airport-large-font.png` | font_scale 1.5 | 文字放大后各级标题/说明/节点行正常折行，无重叠；底部操作栏不压列表，内容区可滚动查看节点（列表在首屏以下） |
| 07 | `07-airport-narrow.png` | `wm size 720x1280` + `wm density 320`（≈360x640dp） | 自上滑后可见 HTTP/VMess/SS/Trojan/Hysteria 节点行与「移除订阅」，底部栏固定不遮挡列表内容；无横向溢出；节点名与 host 单行省略正常 |
| 08 | `08-airport-error.png` | 错误态（`https://demo.local/error`） | 红色边框错误块「无法连接订阅服务器，请检查网络后重试。」+「重新识别」按钮；无缓存订阅残留；底部栏 CTA 禁用 |
| 09 | `09-unsupported-only.png` | 全不支持（3 节点） | 「暂不支持」badge + 「共 3 个节点，当前版本仅支持 HTTP/SOCKS5 节点。」；三行节点均标「暂不支持」且不可选；底部「保存并启用」禁用 |
| 10 | `10-single-node.png` | 单节点结果 | 「单节点」badge + 节点卡（SOCKS / 香港 单节点 · SOCKS5 / host:port · SOCKS5 / 「识别到单个可用节点…」）；底部栏「将启用：…」+ 可用「保存并启用」；「移除订阅」可见 |

## 4. 发现的问题（按严重度）

1. **（中）HTTP 节点只要带用户名/密码就会被判为「暂不支持」**：`ProxyNode` 只为无凭据 HTTP 生成可用的 `http://host:port`（见 `ProxySubscription.kt`），但页面文案写「支持 HTTP 与 SOCKS5；SOCKS5 可带凭据并加密保存」，用户容易误解。demo 数据中「新加坡 03 · HTTP（带凭据）」即显示「暂不支持」。建议文案明确「HTTP 代理暂不支持带凭据」，或在节点行展示原因。
2. **（轻）识别中骨架不易见**：加载态骨架卡片位于「识别链接」按钮下方，小屏/键盘弹出时被键盘与固定底部栏挤压，几乎看不到骨架；加载态主要靠按钮 spinner 表达。
3. **（信息）节点列表高度上限 420dp**：7 节点在默认尺寸下只露出约 3 行（需在列表内滚动），大字体/窄屏下首屏只能看到列表标题；列表自身可滚动，未见底部栏遮挡（07 已验证）。
4. 未发现：文字截断到不可用、元素重叠、亮/暗对比不足、窄屏横向溢出、底部 CTA 被列表/键盘遮挡（02 键盘态已验证 CTA 可见）。

## 5. 真实局限性

- **数据全部为本地 fake**：`DemoProxySubscriptionFetcher` 返回固定 YAML，未发生任何真实 HTTPS 请求；节点均为 `*.demo.example` 占位域名。截图不代表真实机场解析结果。
- 设备为 **x86_64 模拟器（Android 17/API 37）**，非目标 ARM64 真机；x86_64 通过 `-PcompatibilityAbis` 临时构建（未修改 build 文件、未提交）。
- 未验证：真实订阅网络链路（HTTPS/重定向/体积限制/超时）、`EncryptedProxySubscriptionStore` 在真机 Keystore 上的持久化与解密、导入后的代理实际连通性（应用节点后真实代理请求）、系统 VPN/网络层行为。
- 字体仅测 1.5x、窄屏仅 720x1280@320dpi 一种；未测 2.0x、横屏、折叠屏。
- 骨架卡片态截图未取得（见 03 说明）；错误态仅覆盖 `Unreachable`，其它错误分类（HTTP 状态/URL 非法/内容过大等）未逐一截图。

## 6. 可直接粘贴到 PR 的说明

```
UI 证据（网络代理与机场订阅页，基于 3a74204；x86_64 模拟器 Android 17，非 ARM64 真机）：

⚠️ 机场订阅内容为本地注入的 fake 数据渲染（DemoProxySubscriptionFetcher，固定 Clash YAML，*.demo.example 占位），非真实订阅、未发起真实网络请求；用于验证布局与状态机。

- 01-status-direct：默认直连 + 空态，三模式选择器与「使用直连」CTA 正常。
- 02-manual：手动模式输入框聚焦、键盘弹出时「保存并启用」仍完整可见可点。
- 03-identify-loading：识别中（按钮 spinner + 「正在识别」）；骨架卡片在此视口下不完整可见。
- 04-airport-light：7 节点机场结果（2 个可用 SOCKS5），默认选中、底部「将启用/取消/保存并启用」正常。
- 05-airport-dark：夜间模式对比度正常，布局一致。
- 06-airport-large-font：1.5x 字体无重叠/截断，底部操作栏不压列表。
- 07-airport-narrow：720x1280/320dpi ≈360x640dp，滚动后节点行与「移除订阅」可见，底部栏固定不遮挡。
- 08-airport-error：不可达错误块 + 「重新识别」重试，无缓存残留。
- 09-unsupported-only：全不支持（VMess/SS/Trojan）提示与禁用 CTA 正常。
- 10-single-node：单节点识别结果 + 可保存启用。

已知问题：带凭据的 HTTP 节点被判「暂不支持」，与页面「支持 HTTP」文案存在理解落差，建议补充说明；识别中骨架在键盘/固定底栏下不易见。
局限：fake 数据 + x86_64 模拟器；真实订阅网络、真实 Keystore 持久化、应用节点后的实际代理连通性均未验证。
```

## 7. 产出清单

- 截图：`D:\deepseek\ZakoCoder\.local\dev\ui-proxy\01-status-direct.png` … `10-single-node.png`（共 10 张）
- 构建日志：`D:\deepseek\ZakoCoder\.local\logs\build-proxyui.log`
- 模拟器日志：`D:\deepseek\ZakoCoder\.local\logs\emulator-proxyui.log`
- 收尾：模拟器已 kill；`Workbench.kt` 与临时 Demo fetcher 已还原（`git status` 干净）；adbkey 已还原；未 commit、未改主工作树。
