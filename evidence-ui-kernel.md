# UI 取证：本地 Xray 内核节点（VMess/SS/Trojan）列表与运行状态

> 基线：worktree `D:\deepseek\ZakoCoder-kernelui`（detached HEAD `0d1937e` feat(proxy): 通过本地 Xray 内核支持 VMess／SS／Trojan 节点）。
> **标注：订阅内容为本地注入的 fake Clash YAML；VMess 服务端是主机自建的测试 xray（10.0.2.2:10086，测试 UUID），非真实机场订阅。设备为 x86_64 模拟器，非目标 ARM64 真机。**
> 取证时间：2026-11-03 12:55–13:16（Asia/Shanghai）。截图目录：`D:\deepseek\ZakoCoder\.local\dev\ui-kernel\`。

## 1. 临时 fake 注入（未提交，已还原）

- 新增（未跟踪，仅本地）`app/src/main/java/dev/zakocoder/app/settings/DemoProxySubscriptionFetcher.kt`：实现 `ProxySubscriptionFetcher`，返回固定 Clash YAML（`proxies:` 子集，忽略 URL）：
  - `VMess HK` → `10.0.2.2:10086`，uuid `11111111-2222-3333-4444-555555555555`，alterId 0，cipher auto，network tcp（主机真实 xray VMess inbound，可连通，用于 Running）；
  - `VMess 不可达` → `10.0.2.2:19999`（同 uuid，用于预期失败态）；
  - `SS 示例` → `10.0.2.2:8388`，cipher aes-256-gcm；
  - `Trojan 示例` → `10.0.2.2:443`，password demo-pass，sni demo.example；
  - `VMess 坏配置` → `10.0.2.2:10087`，uuid `INVALID-UUID-FOR-FAILURE`（尝试制造内核启动失败）；
  - `SOCKS5 直连` → `10.0.2.2:1080`（直连协议对照）。
- 修改（仅一行）`Workbench.kt:118`：`ProxyCatalog(HttpProxySubscriptionFetcher(), …)` → `ProxyCatalog(DemoProxySubscriptionFetcher(), …)`。
- 还原：`git -C D:\deepseek\ZakoCoder-kernelui checkout -- app/src/main/java/dev/zakocoder/app/Workbench.kt` + 删除 Demo 文件；收尾 `git status` 干净（`## HEAD (no branch)`），**未 commit**、未改主工作树。

## 2. 构建与安装

- 构建（持 `D:\deepseek\ZakoCoder\.local\build.lock`，finally 释放）：
  `.\gradlew.bat :app:assembleDebug -x :app:prepareRuntime "-PcompatibilityAbis=arm64-v8a,x86_64" --console=plain`
  → `:app:prepareProxyKernel` 校验 `arm64-v8a/libzakocoder_xray.so` + `x86_64/libzakocoder_xray.so`，**BUILD SUCCESSFUL**（首次 42s；加入失败测试节点后增量 6s），APK 46,586,679 bytes。
- `adb -s emulator-5554 install -r app-debug.apk` → Success（另执行一次 `pm clear` 清掉上一任务的旧缓存订阅）。
- 导航：项目库 → 底部「设置」→「网络代理」→「机场订阅」→ 输入 `https://demo.local/kernel` →「识别链接」→ 节点列表 → 选中节点 →「保存并启用」。

## 3. 截图与观察

设备参数：1080x2400 / 420dpi / font_scale 1.0；01–04 night no，05 night yes。

| # | 文件 | 状态 | 观察结论 |
|---|---|---|---|
| 01 | `01-list-with-kernel-nodes.png` | 机场结果列表（未运行内核） | 6 个节点全部「本地内核」可用：VMess HK、VMess 不可达、SS 示例、Trojan 示例、VMess 坏配置、SOCKS5 直连；每行有 VM/SS/TR/SOCKS 协议徽标 +「本地内核」标签；「6 个节点 · 6 个可用」；下方「本地内核 · 未运行（启用 VMess/SS/Trojan 节点时自动启动）」；底部「将启用：VMess HK」+ 取消/保存并启用 |
| 02 | `02-kernel-starting-or-running.png` | 内核运行中（选中 VMess HK，真实 xray 服务端） | 内核卡绿灯 + **「运行中 · VMess HK · 127.0.0.1:33321」**；「停止内核」按钮；底部「已启用（本地内核）」；列表含全部内核节点标签。内核日志确认 `Xray 26.3.27 ... linux/amd64 started` |
| 03 | `03-kernel-stopped-after.png` | 点「停止内核」后 | 内核卡回到「未运行（启用 VMess/SS/Trojan 节点时自动启动）」，无绿灯；节点列表与选择保持；底部不再显示已启用反馈 |
| 04 | `04-unreachable-node-still-running.png` | 选中「VMess 不可达」并「保存并启用」 | **预期失败未复现**：内核卡仍显示「运行中 · VMess 不可达 · 127.0.0.1:38573」+「已启用（本地内核）」。原因：内核就绪判定只探测本地 127.0.0.1 SOCKS 端口，远端 VMess 不可达不影响进程启动（详见问题 1） |
| 05 | `05-kernel-dark.png` | 运行中，夜间模式 | 深色下内核卡、节点行、标签对比正常；「运行中 · VMess 不可达 · 127.0.0.1:38573」清晰；底部 CTA 正常 |

截图缺失项：**`04-kernel-failed.png` 未取得**。尝试了两种失败触发方式（不可达远端端口、无效 UUID 的 VMess 节点），均被 Xray 26.3.27 接受并正常启动（本地入站可用 → UI 判 Running）；未修改/删除内核二进制或伪造进程故障。20 分钟时间盒内未找到稳定 UI 可触发的 Failed 路径，按任务要求如实记录、不伪造。

## 4. 发现的问题（按严重度）

1. **（中）内核 Running 不等于代理可用**：就绪探针仅检查本地 loopback SOCKS 端口，远端服务器不可达（如 `10.0.2.2:19999`）甚至无效 UUID 的 VMess 节点也会显示「运行中 / 已启用（本地内核）」。用户会误以为代理已生效，实际流量会失败。建议增加一次经内核的出站连通性探活（或把 Running 文案改为「内核已启动，连通性未知」）。
2. **（中）应用重启后残留死端点**：上次启用内核节点后设置保存为 `socks5://127.0.0.1:38573`；重启 app 后内核进程不存在，但「当前状态」仍显示「手动代理 / 已生效」并指向该 loopback 端口（本次导航中实际出现）。建议启动时校验 loopback 端点对应内核是否存活，否则回退为直连/提示。
3. **（轻）升级后旧缓存节点丢 transport**：用旧版本导入并持久化的订阅，在新版本（支持内核）下全部显示「暂不支持」（本次旧缓存 5 节点仅 1 个 SOCKS5 可用），需重新「识别」才恢复。建议缓存版本迁移/失效策略提示刷新。
4. **（轻）底部固定操作栏会遮挡「识别链接」按钮**：键盘弹出时「保存并启用」栏上移覆盖识别按钮区域，需先收键盘或滚动才能点到识别（本次实操遇到）。建议底部栏让出内容区或识别按钮保持可见。

## 5. 实际内核日志摘录

`adb -s emulator-5554 shell run-as dev.zakocoder.app cat cache/proxy-kernel/kernel.log`（最后一次启动，坏配置节点）：

```
Xray 26.3.27 (Xray, Penetrates Everything.) Custom (go1.26.4 linux/amd64)
2026/11/03 05:02:21.473888 [Info] infra/conf/serial: Reading config: &{Name:/data/user/0/dev.zakocoder.app/cache/proxy-kernel/config-208bc7b67a5deca4.json Format:json}
2026/11/03 05:02:21.474124 [Warning] common/errors: The feature VMess (with no Forward Secrecy, etc.) is deprecated, not recommended for using and might be removed. Please migrate to VLESS Encryption as soon as possible.
2026/11/03 05:02:21.473975 [Warning] core: Xray 26.3.27 started
```

内核配置文件落在 `cache/proxy-kernel/config-*.json`，日志在 `cache/proxy-kernel/kernel.log`（每次启动覆盖写）。

## 6. 真实局限性

- 订阅内容为 fake YAML；仅 VMess HK 指向主机自建测试 xray（10.0.2.2:10086，测试 UUID，非生产服务）。SS/Trojan 节点只有占位参数，未验证真实 SS/Trojan 服务端连通。
- 设备为 **x86_64 模拟器（Android 17/API 37）**，非目标 ARM64 真机；x86_64 内核为 `runtime-artifacts/jniLibs/x86_64/libzakocoder_xray.so`（32MB），通过 `-PcompatibilityAbis` 打包，未改 build.gradle.kts、未提交。
- 未验证：经内核的真实 HTTP 代理链路（本次 xray 入站可启动，但未通过 app 实际发起网页请求）、SS/Trojan 真实服务端、ARM64 真机内核启动、内核崩溃/超时后 UI 的 Failed 状态（未取得）。
- 截图未覆盖：Failed 状态（原因见 §3）、大字体/窄屏下的内核卡（未在时间盒内补）。

## 7. 可直接粘贴到 PR 的说明

```
UI 证据（本地 Xray 内核节点，基于 0d1937e；x86_64 模拟器 Android 17，非 ARM64 真机）：

⚠️ 订阅为本地注入的 fake Clash YAML；VMess HK 指向主机自建测试 xray（10.0.2.2:10086，测试 UUID），非真实机场订阅。

- 01-list-with-kernel-nodes：6 个节点（VMess×3 / SS / Trojan / SOCKS5）全部可用，VMess/SS/Trojan 行显示“本地内核”标签，内核区显示未运行。
- 02-kernel-starting-or-running：选中可连通 VMess HK → 保存并启用 → 内核卡显示“运行中 · VMess HK · 127.0.0.1:33321”，底部“已启用（本地内核）”；内核日志确认 Xray 26.3.27 (linux/amd64) started。
- 03-kernel-stopped-after：点“停止内核”后回到“未运行”，节点列表保持。
- 04-unreachable-node-still-running：远端不可达节点仍显示“运行中”（就绪探针只检查本地 SOCKS 端口）——建议产品上补出站连通性探活。
- 05-kernel-dark：夜间模式下运行中状态对比正常。

未取得 Failed 状态截图（不可达远端与无效 UUID 均被内核接受并判 Running，时间盒内未找到 UI 可触发的稳定失败路径，不伪造）。
另发现：应用重启后设置仍残留指向已停止内核的 socks5://127.0.0.1:旧端口，状态卡仍显示“已生效”；旧版本缓存订阅在新版本下全部“暂不支持”，需重新识别。
```

## 8. 收尾状态

- worktree `ZakoCoder-kernelui`：`git status` 干净，`Workbench.kt` 已还原、Demo fetcher 已删除；未 commit、未改主工作树。
- `D:\deepseek\ZakoCoder\.local\build.lock` 已释放（不存在）。
- **模拟器未关闭**（按任务要求，由 Lead 统一收尾）；**adbkey 未还原**（本会话已由 Lead 切换，按任务要求保持）。
- 设备仍是安装有 x86_64 debug APK 的状态（当前 UI 停在内核“运行中 · VMess 坏配置”页，可直接复看）。
