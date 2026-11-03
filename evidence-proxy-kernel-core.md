# task-20 证据：v2ray/xray 内核集成核心（VMess/SS/Trojan + ProxyKernel 生命周期）

- 仓库：`D:\deepseek\ZakoCoder`，分支 `feat/proxy-kernel`（基线 `1efabc8`）
- 写入范围：`settings/ProxySubscription.kt`、`settings/ProxySubscriptionParser.kt`（解析器必须扩展才能产出 transport，属于同一 settings 包；见“偏差”）、新增 `settings/ProxyKernelConfig.kt`、`settings/ProxyKernel.kt`、`Workbench.kt` 内核区域、`app/src/test/java/dev/zakocoder/app/settings/`、本证据文件。未改 UI/strings/构建/CI/docs；未 commit。

## 1. API（与任务一致）

```kotlin
data class VmessTransport(uuid, alterId, security, network, tls, sni, host, path, headerType)
data class ShadowsocksTransport(method, password)
data class TrojanTransport(password, sni)
data class NodeTransport(vmess=null, shadowsocks=null, trojan=null)
// ProxyNode: + transport: NodeTransport? = null; + viaKernel: Boolean（transport 完整才算）
// supported = HTTP/SOCKS5 直连 或 viaKernel；proxyUrl 仅直连非空，内核节点为空

object ProxyKernelConfig { fun build(node: ProxyNode, listenPort: Int): String? }

sealed interface ProxyKernelState { Stopped / Starting(nodeId) / Running(nodeId,nodeName,port) / Failed(reason) }
enum class KernelFailure { BinaryMissing, UnsupportedNode, StartFailed, Timeout, PortUnavailable }
class ProxyKernel(context, executable = { File(nativeLibraryDir, "libzakocoder_xray.so") }) {
  val state: StateFlow<ProxyKernelState>
  suspend fun start(node: ProxyNode): ProxyKernelState
  fun stop()
}
// Workbench: val proxyKernel = ProxyKernel(context)
//            applyProxyNode(node) 扩展内核分支；新增 stopProxyKernel()
```

## 2. 生成的 Xray 配置（示例，凭据已脱敏）

```json
{
  "log": {"loglevel": "warning"},
  "inbounds": [{"listen":"127.0.0.1","port":10808,"protocol":"socks",
                "settings":{"auth":"noauth","udp":false},"tag":"socks-in"}],
  "outbounds": [
    {"protocol":"vmess","tag":"proxy",
     "settings":{"vnext":[{"address":"vm.example.com","port":443,
                            "users":[{"id":"<uuid>","alterId":0,"security":"auto"}]}]},
     "streamSettings":{"network":"ws","security":"tls",
                       "tlsSettings":{"serverName":"sni.example.com"},
                       "wsSettings":{"path":"/ws","headers":{"Host":"cdn.example.com"}}}}
  ]
}
```

- VMess：`network` 仅 tcp/ws/grpc（grpc 写入 `grpcSettings.serviceName`）；`security` 仅 auto/none/zero/aes-128-gcm/chacha20-poly1305；TLS 仅 `none`/`tls`（reality/xtls 返回 null）；tcp 无 tlsSettings。
- SS：`{"protocol":"shadowsocks","settings":{"servers":[{address,port,method,password}]}}`，method 限 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`。
- Trojan：`servers[{address,port,password}]` + `streamSettings{network:tcp,security:tls,tlsSettings.serverName}`，缺省 SNI=节点 host。
- 入站只监听 `127.0.0.1`、noauth、UDP=false；所有字符串做长度限制 + 拒绝 ISO 控制字符 + 转义 `"`/`\`，失败返回 null（fail closed）。
- 只有 proxy outbound（依赖 Xray 默认路由选择第一个 outbound），无 freedom/route 配置。

## 3. 生命周期（ProxyKernel）

- 串行 start（Mutex）：先停旧进程→校验 `viaKernel`→校验二进制→`ServerSocket(0)` 取空闲 127.0.0.1 端口→生成配置到 `cacheDir/proxy-kernel/config-<safe-id>.json`→`ProcessBuilder(binary,"run","-c",config)`（cwd=cacheDir/proxy-kernel，stdout/stderr 重定向 cache 内 `kernel.log`）→最多 10s 轮询 TCP 连接判断 ready（300ms 超时、100ms 间隔）→Running。
- 失败/超时/进程提前退出：销毁进程、删配置、状态 `Failed(reason)`；`stop()` 立即置 Stopped 并异步 destroy 子进程（不阻塞主线程）。
- 日志：进程输出只进 cache 文件；停止/失败时把尾部 ≤64 KiB 归档到 `filesDir/kernel-logs/kernel.log`；`ProxyKernelState` 只有枚举，不含配置/凭据/原始日志行。
- 配置含密码，始终留在应用私有 cache，stop 时删除；UI 拿到的只有端口与状态。

## 4. Workbench 集成

- `applyProxyNode(node)`：
  - `node.viaKernel` → 若内核处于 Starting 返回 false（防重复启动）；否则 `scope.launch { proxyKernel.start(node) }`，Running 后 `saveSettings(settings.copy(proxy="socks5://127.0.0.1:<port>"))`；失败置 `state.message`（复用 `R.string.operation_failed`）并返回 true（已提交，结果看 state flow）。
  - 直连节点：先 `proxyKernel.stop()`，再保持原有 `saveSettings(settings.copy(proxy=node.proxyUrl))`。
  - unsupported / workbench busy/loading → false。
- `stopProxyKernel()`：记录 Running 端口→stop；若当前 `settings.proxy == socks5://127.0.0.1:<该端口>` 则 `saveSettings(proxy="")`。
- UI teammate 已并行接入：`workbench.proxyKernel.state.collectAsStateWithLifecycle()`、`ProxyKernelState`/`KernelFailure` 状态卡、`node.viaKernel` 标签、模式切换时 `stopProxyKernel()`；本仓编译通过，无 API 不匹配。

## 5. 测试（纯 JVM，真实结果）

`--tests 'dev.zakocoder.app.settings.*'`：**85 tests / 0 failures / 0 errors**（XML：`app/build/test-results/testDebugUnitTest/TEST-dev.zakocoder.app.settings.*.xml`）

| 测试类 | tests |
| --- | --- |
| `ProxyKernelConfigTest`（新增） | 8 |
| `ProxySubscriptionParserTest`（扩展 transport/Clash/viaKernel/脱敏） | 22 |
| `ProxyCatalogTest` | 18 |
| `ProxyProbeClassificationTest` | 8 |
| `NetworkProxyTest` | 10 |
| `SocksAuthBindingTest` | 7 |
| `SocksProxyTest` | 12 |

覆盖：vmess tcp / ws+tls（sni/path/host header）/ grpc 字段存在性、ss method+password、trojan 默认/覆盖 SNI、listenPort 注入、引号/反斜杠转义、换行等控制字符拒绝、缺失 transport/关键字段→null、错误协议→null、reality/未知 network/未知加密→null、端口越界→null；解析侧 vmess base64（含 URL-safe/无 padding 路径沿用既有实现）、SIP002 ss、trojan `?sni=`、Clash YAML vmess/ss/trojan 字段、`viaKernel`/`supported`/`proxyUrl` 语义、transport toString 脱敏。未启动任何真实进程/网络。

## 6. 构建验证

- `:app:testDebugUnitTest --tests 'dev.zakocoder.app.settings.*'` → BUILD SUCCESSFUL，85/0/0。
- `:app:assembleDebug :app:lintDebug -x :app:prepareRuntime` → BUILD SUCCESSFUL；`:app:prepareProxyKernel` 实际执行并输出 `Verified arm64-v8a/libzakocoder_xray.so` / `Verified x86_64/libzakocoder_xray.so`（hash 与 `native-kernel/artifacts.lock.json` 一致）。
- 双 ABI 打包：`:app:assembleDebug -PcompatibilityAbis=arm64-v8a,x86_64 -x :app:prepareRuntime` → BUILD SUCCESSFUL；检查 `app-debug.apk`（46,586,211 B）内包含 `lib/arm64-v8a/libzakocoder_xray.so`(30,277,794 B) 与 `lib/x86_64/libzakocoder_xray.so`(32,379,042 B)。（注：本机 `runtime-artifacts/jniLibs/x86_64` 只有 xray、没有 x86_64 PRoot，因为 prepareRuntime 在 Windows 被跳过；由 Lead/CI 的 prepare 脚本负责补齐，不影响本任务内核代码。）
- `PYTHONUTF8=1 python3 scripts/check_repository.py` → `OK`，exit 0。
- 构建锁：`.local/build.lock` 原子抢占 + `finally` 删除；等待 UI teammate 构建，无清理他人锁。
- 说明：Windows 本机 PRoot 仍需 `-x :app:prepareRuntime`（main 要求 Linux host 自编译，`fcntl`/`linux-x86_64` NDK；本机制品此前已由 WSL 真实自编译并校验，见 `.local/dev/evidence-proxy-airport-core.md`）。本任务的 xray 二进制由 `:app:prepareProxyKernel` 正常校验（未跳过）。

## 7. 已知限制 / 风险

1. **API 偏差**：为产出 transport，必须修改 `settings/ProxySubscriptionParser.kt`（任务文字只列了 `ProxySubscription.kt`，但解析器同在 settings 包且无法在不改它的情况下实现需求）。`ProxyProbe.UnsupportedOnly` 在本分支是 `nodes: List<ProxyNode>`（PR #48 既有形状，含 `total` getter），未按任务文字改为 `total: Int`，避免破坏已合并的 UI。
2. **Windows 无 Go 构建路径不涉及**：`prepareProxyKernel` 在 Windows 用本机 Go 1.26.4 仅做 hash 校验（正常通过）；真正自编译由 CI/Linux 完成。jniLibs 中 xray 二进制为本地临时产物（gitignored），最终由 prepare 脚本自编译。
3. **进程生命周期**：内核为应用子进程。应用被系统杀死/崩溃时子进程退出行为依赖 Android；无 watchdog/自动重启（ready 后不再监控），下次 start 会先 stop。切换节点、清空订阅、切回直连由 UI 调 `stopProxyKernel()`/`applyProxyNode` 处理；若 UI 忘记调用，进程可能存留到进程死亡。
4. **Android exec 约束**：只能从 `nativeLibraryDir` 执行（assets/data 目录 exec 受限），因此二进制以 `libzakocoder_xray.so` 形式随 jniLibs 打包；`prepareProxyKernel` 负责 hash 校验。
5. **协议覆盖**：VMess（tcp/ws/grpc + tls/none）、SS（方法+密码，无 plugin）、Trojan（tcp+tls）。不支持 VLESS/REALITY/XTLS/Hysteria/TUIC/SS 插件；VMess tcp headerType 未写入 `tcpSettings`（当前机场常用 ws/tls）；grpc 无 multiMode。
6. **无真实订阅/服务器 E2E**：本任务仅纯 JVM 测试；真实 VMess 端到端已由 Lead 在 WSL 用同一 xray 版本验证（server+client+socks，curl 200），应用内进程启动/端口联动未在真机跑仪器测试。
7. **保存时序**：内核 Running 后调用 `saveSettings`；若此时 workbench busy，`run()` 会静默跳过保存（既有语义），UI 会看到内核 Running 但设置未切换——小概率，需真机验证；可在后续让 apply 重试或排队。
8. 内核 warning 日志可能包含目标 host/连接错误，但不含配置/密码；归档日志不展示给用户。
