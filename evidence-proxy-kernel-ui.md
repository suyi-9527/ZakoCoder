# task-21 证据：机场页接入本地内核 UI（VMess/SS/Trojan 可选 + 内核状态/停止/启动反馈）

日期：2026-11-03 · 仓库 `D:\deepseek\ZakoCoder` · 分支 `feat/proxy-kernel`（基于 PR #48 `1efabc8`）
执行者：teammate `project-templates` · 未 commit（Lead 统一提交推送）。
并行依赖：`settings/ProxyKernel.kt`、`ProxyKernelConfig.kt`、`ProxySubscription.kt`（viaKernel/transport）与 `Workbench.kt`（`proxyKernel`/`applyProxyNode`/`stopProxyKernel`）由 net-proxy 同时实现；本记录基于当前工作树合并状态，UI 只调用既定 API，不改核心文件。

## 1. 改动文件（仅 UI + strings）

| 文件 | 说明 |
| --- | --- |
| `app/src/main/java/dev/zakocoder/app/ui/ProxySettingsPage.kt` | 节点行支持 `viaKernel`（可选 +「本地内核」标签）、新增本地内核状态卡（Stopped/Starting/Running/Failed + 停止/重试）、内核异步启动反馈（LaunchedEffect）、状态卡识别本地内核、刷新/移除前停止内核 |
| `app/src/main/res/values/strings_proxy.xml` | 新增 15 条内核文案；更新 3 条既有文案（不支持提示、仅不支持结果、移除说明）；当前共 100 条 |
| `.local/dev/evidence-proxy-kernel-ui.md` | 本证据 |

未改 `settings/`、`Workbench.kt`、构建/CI/docs/依赖。

## 2. 页面变化

### 2.1 节点行（VMess/SS/Trojan）
- `node.supported` 现在包含 `viaKernel`（核心规则：参数完整的 VMess/SS/Trojan）。因此这类节点：
  - 整行可点（`selectable` + `Role.RadioButton`），右侧显示 `RadioButton`；
  - 协议短标旁显示「本地内核」小标签（`KernelTag`，primaryContainer 底 + primary 字）；
  - 可被选中后由底部「保存并启用」启用。
- 真正不支持的节点（参数缺失/未知协议/`https://` TLS-to-proxy 等）保持灰化 +「暂不支持」，点击显示更新后的内联说明：「该节点缺少必要参数或协议暂不支持。」
- 「暂不支持」结果区文案同步为「共 N 个节点，但都缺少必要参数或协议暂不支持。」（`UnsupportedOnly` 现在只包含确实不可用的节点）。

### 2.2 本地内核状态卡（仅机场模式，位于节点列表下方/移除入口上方）
复用现有卡片体系（12dp 圆角、surface 底、`cardBorder`/`cardElevation`、`StatusDot`），状态机 UI：

| `ProxyKernelState` | 状态点 | 文案 | 操作 |
| --- | --- | --- | --- |
| `Stopped` | 次要灰 | 「未运行（启用 VMess/SS/Trojan 节点时自动启动）」 | 无 |
| `Starting` | 警告黄 | 「正在启动…」 | 无（避免与启动竞争） |
| `Running` | 成功绿 | 「运行中 · <节点名> · 127.0.0.1:<端口>」 | 「停止内核」48dp → `workbench.stopProxyKernel()` |
| `Failed(reason)` | 错误红 | 按 `KernelFailure` 中文分类：缺少内核文件 / 参数不完整或内核不支持 / 启动失败 / 启动超时 / 本地端口不可用 | 「重试启动」（已知上次内核节点时）+「停止内核」 |

- 失败原因不做原始日志/配置/凭据展示，只用分类文案。
- 重试逻辑：记住最后一次请求启动的内核节点（`lastKernelNode`），`Failed` 时「重试启动」重新调用 `applyProxyNode`；没有节点上下文时不显示重试（只有停止），避免无意义按钮。

### 2.3 启用流程与异步反馈
- 底部「保存并启用」→ `applyNode(selectedNode)`：
  - 普通 HTTP/SOCKS5 节点：沿用原同步逻辑（`applyProxyNode` true/false → 已提交/不支持/失败）。
  - `viaKernel` 节点：记录 `pendingKernelApply` + 「正在启动本地内核…」，调用 `applyProxyNode`（核心立即返回、内核在后台启动）。
- `LaunchedEffect(kernel, pendingKernelApply)` 统一收尾：
  - `Running` 且 nodeId 匹配 → 页内/底栏显示「已启用（本地内核）」；
  - `Failed` → 显示对应失败分类（错误色）；
  - `Starting` → 保持「正在启动本地内核…」；
  - `Stopped`（1.5s 内没有进入 Starting，例如 Workbench busy/请求被拒）→ 显示「当前无法保存，请稍后重试」并把 pending 清空，避免永久“正在启动”。
- 反馈颜色：成功绿色、启动中次要色、失败错误色（`feedbackColor`），出现位置为底部固定区（可滚动区外，始终可见）。

### 2.4 状态卡“已生效”判定扩展
- 新增：当 `settings.proxy == socks5://127.0.0.1:<Running.port>` 且内核 `Running` 时：
  - 模式显示「机场节点（本地内核）」（`proxy_status_airport_kernel`）；
  - 来源显示「来自机场 · <Running.nodeName>」。
- 若内核已停止/切换为直连或普通节点，回落到原有「机场节点 / 手动代理 / 直连」判定；底部/状态卡均不宣称成功。
- 页面重新打开且内核仍在服务当前代理时，自动落到机场 tab（用户手动切过 tab 则不覆盖）。

### 2.5 刷新 / 移除订阅
- 「刷新订阅」：若内核非 `Stopped`，先 `workbench.stopProxyKernel()`（核心会在代理指向本地内核时同时清空设置），再 `proxyCatalog.refresh()`。
- 「移除订阅」二次确认：文案更新为「……并停止本地内核；当前已生效的代理不会被自动关闭。此操作无法撤销。」确认后先停止内核，再 `proxyCatalog.clear()`，并清空页内 pending/反馈。

## 3. 新增/更新文案（全部在 strings_proxy.xml）

新增：`proxy_status_airport_kernel`、`proxy_kernel_section`、`proxy_kernel_badge`、`proxy_kernel_stopped`、`proxy_kernel_starting`、`proxy_kernel_running`、`proxy_kernel_stop`、`proxy_kernel_retry`、`proxy_kernel_failure_binary_missing`、`proxy_kernel_failure_unsupported_node`、`proxy_kernel_failure_start_failed`、`proxy_kernel_failure_timeout`、`proxy_kernel_failure_port_unavailable`、`proxy_feedback_kernel_starting`、`proxy_feedback_kernel_running`。

更新：`proxy_node_unsupported_hint`、`proxy_result_unsupported_only`、`proxy_remove_message`。

设计保持：沿用状态卡/页内反馈/48dp 触控/现有字阶与亮暗语义色；内核状态文字同时给出图标+文字，不只靠颜色。

## 4. 验证（真实执行）

Windows 环境按 Lead 指示跳过 Linux-only PRoot 自编译（`-x :app:prepareRuntime`），未改构建文件、未伪造制品；内核制品任务 `:app:prepareProxyKernel` 正常执行：

```text
> Task :app:prepareProxyKernel
Verified arm64-v8a/libzakocoder_xray.so
Verified x86_64/libzakocoder_xray.so
```

| 命令 | 结果 |
| --- | --- |
| `.local\dev\build.ps1 :app:assembleDebug :app:lintDebug -x :app:prepareRuntime`（抢 `.local/build.lock`，finally 释放） | **BUILD SUCCESSFUL**（最终一次 2m9s；前一次同样 SUCCESS） |
| `:app:lintDebug`（同一命令内） | **通过**（0 error / 0 warning，仅既有 informational hints） |
| `PYTHONUTF8=1 python3 scripts/check_repository.py` | `Repository structure, XML, local documentation links and ignore rules: OK`（exit 0） |
| APK 内容抽查 | `app-debug.apk` 含 `lib/arm64-v8a/libzakocoder_xray.so`（另有 proot/loader） |

未执行：真机/模拟器运行、真实 VMess/SS/Trojan 节点端到端代理、内核进程生命周期（启动等待/端口占用/超时）实机验证；这些依赖设备与可用订阅，按 Lead 安排由 QA 后续验证。本次仅为 UI 接入与编译/lint 证据。

## 5. 限制与偏差（如实）

1. **未真机验证**：内核启动异步状态、`Running/Failed` UI 切换、停止后设置清空均按核心状态机实现，但未在设备上实际触发；内核进程行为与真实节点连通性未验证。
2. **双击/并发**：`applyProxyNode` 在内核 `Starting` 时返回 false，UI 仍保持「正在启动」并等待状态机；不会重复启动进程（核心串行），但按钮在 `state.busy||catalog.busy` 下才禁用，短暂二次点击不会造成双进程。
3. **失败重试**：`ProxyKernelState.Failed(reason)` 不带 nodeId，UI 仅记住本页面最后一次请求的节点；若失败发生时页面已重建（remember 丢失）则只显示「停止内核」，不显示重试，属信息缺失下的保守设计。
4. **页内反馈位置**：内核启动结果同时出现在底部固定区；`Failed` 详情同时显示在内核状态卡（分类原因 + 重试/停止）。未新增 Snackbar 机制，避免与既有 message 浮层重叠。
5. **未做 130%/暗色截图**：布局沿用既有可伸缩布局与语义色，未实机复核；截图由 QA 补充。

## 6. 可直接粘贴 PR 的段落（中文）

> ### 机场页接入本地 Xray 内核（VMess / SS / Trojan）
>
> - 节点列表：参数完整的 VMess/SS/Trojan（`ProxyNode.viaKernel`）不再灰化，可单选并在协议短标旁显示「本地内核」标签；真正不安全/参数缺失的节点继续显示「暂不支持」，提示改为「该节点缺少必要参数或协议暂不支持」。
> - 新增「本地内核」状态卡（机场模式）：未运行 / 正在启动 / 运行中（节点名 · 127.0.0.1:端口）/ 启动失败（按 KernelFailure 分类为缺少内核文件、节点不支持、启动失败、超时、端口不可用），运行中可停止，失败可重试或停止。
> - 「保存并启用」对内核节点异步启动：先显示「正在启动本地内核…」，由内核状态机在 Running/Failed 时更新为「已启用（本地内核）」或分类失败原因；不会把启动中误报为成功。
> - 状态卡：当设置中的代理为内核的本地 socks5 回环且内核 Running 时，显示「机场节点（本地内核）」及节点来源；刷新订阅与移除订阅前会先停止内核（移除确认文案已说明）。
> - 验证：`:app:assembleDebug`、`:app:lintDebug` 通过（Windows 本地按约定 `-x :app:prepareRuntime`；`:app:prepareProxyKernel` 已验证 arm64/x86_64 内核制品），`scripts/check_repository.py` 通过，APK 内含 `libzakocoder_xray.so`。未做真机内核实跑，截图与设备验证由 QA 后续补充。
