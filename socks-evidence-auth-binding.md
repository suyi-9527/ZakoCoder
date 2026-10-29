# #19 审查意见修复：SOCKS5 认证器绑定代理端点（切换/禁用/在途隔离）— 实现与验证证据

> 任务：task-14；执行人：teammate `socks-evidence`；日期：2026-10-29（Asia/Shanghai）
> 仓库：`D:\deepseek\ZakoCoder`；分支：`feat/socks5-credentials`；HEAD：`5b20ecc`（基于最新 main `a7f9017`）
> 环境：Windows（LAPTOP-B1M1JKU6）+ `.local/dev/build.ps1`（JDK 17.0.20.1+1、Gradle 8.13、OkHttp 4.12.0、JUnit 4.13.2）
> 构建/测试全程持 `D:\deepseek\ZakoCoder\.local\build.lock`（`New-Item` 独占创建，`finally` 删除）串行执行；**未 commit**（由 Lead 统一提交/推送）。

---

## 1. 审查意见与根因

维护者指出（合并前必须修）：`NetworkProxy.kt` 的全局 `Authenticator` 只按 `RequestorType`/协议返回“当前凭据”，**没有绑定代理主机与端口**。导致：

1. 不相关地址的 SOCKS5 认证请求也能拿到当前 SOCKS5 凭据；
2. 另一个地址的 HTTP PROXY 认证请求（`RequestorType.PROXY`）也能拿到当前 SOCKS5 凭据（旧代码 `requestorType != PROXY && protocol != "SOCKS5"` 才拒绝，PROXY 分支直接放行）；
3. 切换代理/在途连接时，可能把新代理凭据交给其他代理。

根因是全局单例 `Authenticator` 与唯一一份 `@Volatile activeCredentials` 脱钩，凭据不知道“自己属于哪个端点”。

## 2. 实现（仅白名单文件）

### 2.1 `app/src/main/java/dev/zakocoder/app/settings/NetworkProxy.kt`

- 删除 `@Volatile activeCredentials: Credentials?`，改为**不可变端点绑定快照**：

```kotlin
data class AuthBinding(val host: String, val port: Int, val credentials: Credentials) {
    override fun toString() = "AuthBinding(host=$host, port=$port, credentials=<redacted>)"
}
@Volatile private var activeBinding: AuthBinding? = null   // 单个 volatile 引用，切换原子替换
```

- `configureSocksAuthentication(credentials: Credentials?)` 改为 `configureSocksAuthentication(raw: String?)`：
  - 解析 `raw`（可含百分号编码凭据）→ **仅当 socks5 且凭据成对存在**时构造 `AuthBinding(spec.host, spec.port, credentials)`；
  - `null`/空白/HTTP/无凭据/非法值 → `binding = null`（**fail closed，绝不保留上一对凭据**）；
  - 不抛异常（旧版本残留的坏值不会崩溃，也不会把旧凭据留在内存态）；有 binding 时安装单例 authenticator。
- 抽出纯决策函数（`internal`，单元测试矩阵直接覆盖）：

```kotlin
internal fun credentialFor(
    requestingHost: String?,
    requestingPort: Int,
    requestingProtocol: String?,
    requestorType: Authenticator.RequestorType?,
): Credentials?
```

规则（全部 AND）：
1. `activeBinding != null`；
2. `requestingProtocol.equals("SOCKS5", ignoreCase = true)` —— **HTTP 代理认证（`http`）一律返回 null**，即使 `requestorType == PROXY`；
3. `requestorType ∈ { PROXY, SERVER }` —— 兼容实测的 JDK/Android `SERVER` 回调（详见 §3）；
4. `requestingPort == binding.port`；
5. `requestingHost` 匹配 `binding.host`：先大小写不敏感精确比较；再对 IP 字面量做字节规范化比较（如 `::1` vs `0:0:0:0:0:0:0:1`）；只有一侧是 hostname、另一侧是字面 IP 时才解析该 hostname（正常路径 JDK 会原样传配置的 host 字符串，不会走 DNS，见 §3）；
6. 返回的 `Credentials` 内容不变（不复制、不改写）。

`getPasswordAuthentication()` 现在仅转调 `credentialFor(requestingHost, requestingPort, requestingProtocol, requestorType)`，不再有“当前凭据”这一全局回退路径。

### 2.2 调用点（绑定到端点）

| 文件 | 改动 |
|---|---|
| `ai/ChatTransport.kt` | `clientFor(raw)`：**每次**调用（包括命中缓存）都 `configureSocksAuthentication(raw)`，绑定原始含凭据 URL 的端点；`raw` 为空时显式 `configureSocksAuthentication(null)` 先清绑定再返回基础 client（禁用代理立即失效） |
| `runtime/LinuxRuntime.kt` | rootfs 下载前：`rawProxy = withCredentials(settings.proxy, proxyCredentials())`，再用同一 `rawProxy` 建连接并 `configureSocksAuthentication(rawProxy)`，HTTP/SOCKS 两条路径绑定同一个端点；不再“端点与凭据分开传” |
| `settings/SettingsRepository.kt` | `save()` 持久化后立即 `configureSocksAuthentication(proxyForRuntime())`：切换端点=原子替换绑定，禁用=清空，凭据轮换立即生效，旧绑定不会跨设置变更存活 |

未改：`Workbench.create()`/模板/依赖/CI/lint/README/docs。

## 3. 真实探针：JDK 实际回调字段（不是想当然）

在 `SocksProxyTest` 内用**真实** JDK 路径采集 `getPasswordAuthentication()` 的 `requestorType` / `requestingHost` / `requestingPort` / `requestingProtocol` / `requestingScheme` / `requestingURL` / `requestingPrompt`：

- (a) OkHttp 经 `java.net.Proxy.Type.SOCKS`（生产 AI 路径）；
- (b) `HttpURLConnection` 经同一 SOCKS5 代理（生产 runtime 下载路径）；
- (c) `HttpURLConnection` 经本地 HTTP 代理，代理回 `407 Proxy Authentication Required`（维护者复现 2 的真实形态）。

真实输出（`TEST-...SocksProxyTest.xml` 的 `<system-out>` 原文，重跑结果一致）：

```text
AUTH_CALLBACK AuthCallback(requestorType=SERVER, requestingHost=127.0.0.1, requestingPort=55262, requestingProtocol=SOCKS5, requestingScheme=null, requestingURL=null, requestingPrompt=SOCKS authentication)
AUTH_CALLBACK AuthCallback(requestorType=SERVER, requestingHost=127.0.0.1, requestingPort=55262, requestingProtocol=SOCKS5, requestingScheme=null, requestingURL=null, requestingPrompt=SOCKS authentication)
HTTP_PROXY_AUTH_CALLBACK AuthCallback(requestorType=PROXY, requestingHost=127.0.0.1, requestingPort=55263, requestingProtocol=http, requestingScheme=basic, requestingURL=http://127.0.0.2/probe-http-proxy, requestingPrompt=probe)
HTTP_PROXY_AUTHORIZATION header=null
```

其中 `55262` 是 SOCKS5 fixture 的监听端口、`55263` 是 HTTP proxy fixture 的端口（测试内断言相等）。由此确定绑定规则的事实依据：

| 维度 | SOCKS5 认证回调（实测） | HTTP 代理 407 回调（实测） |
|---|---|---|
| `requestorType` | **SERVER**（JDK 把 SOCKS 请求类型重置为 SERVER） | **PROXY** |
| `requestingProtocol` | **SOCKS5** | **http** |
| `requestingHost` | **代理端点 host**（配置的 `127.0.0.1`，不是 CONNECT 目标） | 代理端点 host |
| `requestingPort` | **代理端点 port**（不是 CONNECT 目标端口） | 代理端点 port |
| `requestingScheme` / `URL` / `prompt` | null / null / "SOCKS authentication" | basic / 目标 URL / realm |

结论：
- host/port 绑定规则用**代理端点**（不是目标地址、不是目标端口）——与实测一致；
- `requestorType` 必须放行 `SERVER`（只认 PROXY 会让真实 SOCKS5 认证永远失败），但 SERVER 兼容由 host/port 与“仅 SOCKS5 协议”共同约束；
- HTTP 代理回调 `protocol=http` 被规则 2 直接拒绝，实测未发送任何 `Proxy-Authorization`（header=null）。

另：既有 `.local/dev/evidence-socks-auth.md` 的独立 Java 探针（`.local/logs/socks-auth-probe.log`）同样得到 `requestorType=SERVER protocol=SOCKS5 host=127.0.0.1 port=<proxy port>`，与本次测试内探针一致。

## 4. 切换 / 禁用 / 在途隔离语义

- **切换（A→B）**：`configureSocksAuthentication(rawB)` 在 `authenticationLock` 内对 `@Volatile activeBinding` 做一次引用替换，是一次原子状态切换。之后：
  - 对 B 端点：回调匹配 host+port+SOCKS5 → 返回 B 凭据；
  - 对 A 端点：回调 host 可能相同但 **port 不匹配**（或 host 不匹配）→ 返回 null，JDK 认证失败（表现为 `SocketException: SOCKS : authentication failed`），**绝不会拿到 B 的新凭据**。
  - 测试：`switchingAndDisablingFailClosedForTheOldEndpoint` 用两个不同凭据的 SOCKS fixture 端到端验证（A 首次成功；切 B 后 A 失败且 A 从未收到 B 的凭据；B 成功）。
- **禁用/清空**：`configureSocksAuthentication(null)` 或 `""` → `activeBinding = null`，所有端口/协议一律 null；`ChatTransport.clientFor("")` 也显式清绑定；`SettingsRepository.save()` 在禁用后立即同步清空。测试：单元 `disablingClearsTheBinding` + 集成 `switchingAndDisablingFailClosedForTheOldEndpoint` 末段。
- **在途隔离**：
  - authenticator 只在**建立连接阶段**被 JDK 调用一次；已建立的连接（认证已完成）不会再次回调，因此切换/禁用不影响已完成认证的在途连接（连接池里的 socket 继续可用）。
  - 对于“切换发生后才发起认证回调”的连接（如慢连接）：回调读取的是切换后的快照，旧端点 host/port 不再匹配 → 返回 null 失败关闭，而不是把新凭据交出去。
  - 测试：`establishedConnectionIsUnaffectedByLaterBindingSwitch` 先用真实 `Socket(Proxy.Type.SOCKS)` 完成认证并建立隧道，再切换绑定 A→B、随后禁用，隧道内继续请求仍返回 `200 hello`。`switchingAndDisablingFailClosedForTheOldEndpoint` 则验证旧端点**新连接**在切换后失败且拿不到新凭据。
  - 全局 `Authenticator` 仍是进程级单例（JDK 的限制），但返回内容被端点快照严格约束；应用只有一处代理设置，语义上等价于“当前唯一代理端点”。

## 5. 测试（真实执行结果）

### 5.1 settings 单测（持锁，`.local/dev/build.ps1`）

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .local\dev\build.ps1 `
    :app:testDebugUnitTest --tests 'dev.zakocoder.app.settings.*'
# -> BUILD SUCCESSFUL，TEST_EXIT=0
```

XML 计数（`.local/logs/socks-auth-binding-test-results/`，原样复制自 `app/build/test-results/testDebugUnitTest/`）：

| 测试类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `dev.zakocoder.app.settings.NetworkProxyTest`（既有 10 个，未削弱） | 10 | 0 | 0 | 0 |
| `dev.zakocoder.app.settings.SocksAuthBindingTest`（**新增** 决策矩阵） | 7 | 0 | 0 | 0 |
| `dev.zakocoder.app.settings.SocksProxyTest`（既有 6 个全保留 + 新增 6 个） | 12 | 0 | 0 | 0 |
| **合计** | **29** | **0** | **0** | **0** |

现有 16 个 settings 测试全部保留并通过（`NetworkProxyTest` 10 个 + `SocksProxyTest` 原 6 个），没有任何削弱或删除。

### 5.2 覆盖矩阵

**`SocksAuthBindingTest`（纯决策函数，无网络）**

| 用例 | 覆盖点 |
|---|---|
| `noBindingNeverReturnsCredentials` | 无绑定、空串、HTTP 端点、无凭据 SOCKS5 → 全 null |
| `returnsCredentialsOnlyForTheBoundSocks5Endpoint` | 匹配 host+port+SOCKS5（SERVER 与 PROXY 两种类型、协议大小写）→ 凭据；**维护者复现 1**：其他 host、其他 port、不相关 SOCKS5 端点、缺/空 host → null；**维护者复现 2**：`protocol=http, requestorType=PROXY` → null、SERVER+http → null、null 协议/类型 → null |
| `hostComparisonIsCaseInsensitiveAndCanonicalForIpLiterals` | host 大小写不敏感；IPv6 字面量 `::1` vs `0:0:0:0:0:0:0:1` 规范化；另一地址 → null |
| `switchingEndpointsInvalidatesTheOldEndpointAtomically` | A→B 后 A=null、B=凭据；同端点换凭据整体替换 |
| `disablingClearsTheBinding` | null/空串清空，之后所有请求 null |
| `invalidConfigurationFailsClosedAndClearsThePreviousPair` | 非法值/HTTP 带凭据不抛异常且清掉上一对 |
| `authBindingAndCredentialsNeverPrintTheSecret` | `AuthBinding.toString()` 与 `Credentials.toString()` 不含用户名/密码 |

**`SocksProxyTest`（真实 JDK 回调 + RFC 1928/1929 fixture 端到端）**

| 用例 | 覆盖点 |
|---|---|
| `jdkAuthenticationCallbacksAreBoundToTheConfiguredSocks5Endpoint` | 真实采集 OkHttp SOCKS5、HUC SOCKS5、HUC HTTP 代理三条回调路径的字段；断言 SOCKS5 回调=`SERVER/SOCKS5/代理 host/代理 port`，HTTP 代理回调=`PROXY/http/代理 host/代理 port`；把**真实回调值**回放给 `credentialFor`：SOCKS5 全部命中，HTTP 代理全部拒绝；打印原始值作证据 |
| `doesNotSendCredentialsToAnUnrelatedSocks5Endpoint` | **维护者复现 1 端到端**：绑定 A，请求 B 端点 → `IOException`；B 的认证尝试中没有任何 accepted，且从未出现 A 的凭据；A 端点未被联系；直连 HTTP fixture accepted=0 |
| `doesNotSendCredentialsToAnHttpProxyAuthenticationChallenge` | **维护者复现 2 端到端**：绑定 SOCKS5 A，HTTP 代理 407 → 请求失败；fixture 侧所有 `Proxy-Authorization` 均为 null（未泄露任何凭据） |
| `authenticatesHttpUrlConnectionThroughSocks5` | 真实 HUC（runtime 下载路径）经新绑定认证成功，返回 200/`hello`，fixture 记录 accepted 认证 |
| `switchingAndDisablingFailClosedForTheOldEndpoint` | A→B 切换后旧端点新连接失败且从未收到 B 凭据；B 正常工作；禁用后新连接不再认证 |
| `establishedConnectionIsUnaffectedByLaterBindingSwitch` | **在途隔离**：手动建立并完成 SOCKS5 认证的隧道，在切换 A→B 并随后禁用绑定后，继续发送 HTTP 请求仍得到 `200 hello`（已建立连接不因设置切换失败） |
| 既有 6 个（转发/ATYP、RFC1929 成功、错误凭据、服务器要求认证但无凭据、代理端口关闭、握手被拒） | 全部保留通过；认证成功路径记录 ATYP=3，失败场景均“不回退直连”（HTTP fixture accepted=0） |

### 5.3 构建 / lint / 结构检查

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:assembleDebug :app:lintDebug
# -> BUILD SUCCESSFUL in 1m 10s，BUILD_LINT_EXIT=0（lint warningsAsErrors=true，0 error）

$env:PYTHONUTF8='1'; python3 scripts/check_repository.py
# -> Repository structure, XML, local documentation links and ignore rules: OK，CHECK_EXIT=0
```

日志：`.local/logs/socks-auth-binding-tests.log`、`socks-auth-binding-build-lint.log`、`socks-auth-binding-check-repository.log`。

## 6. 可粘贴到 PR 的回复段落

> **已按审查意见修复：SOCKS5 凭据现在绑定代理端点，不再有进程级“当前凭据”泄漏面。**
>
> - `NetworkProxy` 的全局 `Authenticator` 不再保存裸 `Credentials`，而是单个 `@Volatile AuthBinding(host, port, credentials)` 不可变快照；`configureSocksAuthentication(raw)` 从含凭据的 SOCKS5 URL 绑定规范化端点，空/禁用/HTTP/无凭据/非法值一律 `binding=null`（fail closed，不保留旧值）。
> - 新增纯决策函数 `credentialFor(host, port, protocol, requestorType)`，只有同时满足 ① 有绑定 ② `protocol == "SOCKS5"`（**HTTP 代理认证 `http` 一律 null**）③ `requestorType ∈ {PROXY, SERVER}` ④ port == 绑定 port ⑤ host 匹配绑定 host 才返回凭据。host 匹配为大小写不敏感 + IP 字面量规范化；hostname 仅在失配兜底时解析。
> - **实测回调字段（JDK 17，OkHttp 与 HttpURLConnection 两条真实路径）**：SOCKS5 为 `requestorType=SERVER, requestingProtocol=SOCKS5, requestingHost/Port=代理端点`（不是 CONNECT 目标）；HTTP 代理 407 为 `requestorType=PROXY, requestingProtocol=http, host/port=HTTP 代理端点`。所以必须保留 SERVER 兼容（否则 SOCKS5 认证永远失败），但 SERVER 分支也被 host/port+协议严格约束。
> - 复现回归（均有端到端测试）：① 绑定 A 后请求另一个端口的 SOCKS5 代理 → 认证失败（`IOException`），该端点从未收到 A 的凭据、也未 accepted；② 绑定 SOCKS5 后遇到 HTTP 代理 407 → 认证失败，代理侧未收到任何 `Proxy-Authorization`。
> - 切换 A→B 为原子快照替换：旧端点后续认证返回 null（失败而非拿到新凭据），新端点正常；禁用/清空立即清除绑定；已建立连接（认证已完成）不受影响（JDK 只在连接阶段回调）。
> - 调用点已同步：`ChatTransport.clientFor`（AI/OkHttp）、`LinuxRuntime` rootfs 下载（HttpURLConnection）都用同一含凭据 raw URL 绑定端点；`SettingsRepository.save` 保存后立即同步/清除绑定。
> - **验证**：`--tests 'dev.zakocoder.app.settings.*'` → **29 tests / 0 failures**（既有 16 个全部保留：`NetworkProxyTest` 10 + `SocksProxyTest` 原 6；新增 `SocksAuthBindingTest` 7 与 `SocksProxyTest` 6）。`:app:assembleDebug`、`:app:lintDebug` 成功，`scripts/check_repository.py` exit 0。

## 7. 已知边界与风险

1. **全局 Authenticator 仍是进程级单例**（JDK API 限制）：应用只有一处代理设置，不存在同时服务多个代理端点的合法场景；即便如此，返回内容也被端点快照约束，跨端点只可能“认证失败”，不可能串用凭据。
2. **hostname 解析兜底**：仅当回调 host 与绑定 host 文本不一致且一方为字面 IP 时才做一次 `InetAddress.getByName`（正常路径不会触发，因为 JDK 原样传配置 host 字符串）。这是为“配置 `localhost`、JDK 传解析后 IP”一类平台差异保留的兼容路径；解析失败则安全拒绝。
3. **SERVER 兼容面**：保留 `requestorType=SERVER` 放行是实测必需（JDK/AOSP 把 SOCKS 请求类型重置为 SERVER）。该分支仍要求协议为 SOCKS5 且 host/port 完全匹配，因此不会把 SOCKS5 凭据交给源站（HTTPS 源站认证协议/类型均不匹配）。
4. **`SettingsRepository.save` 同步绑定**：保存时调用 `proxyForRuntime()`（含一次 Keystore 解密，约毫秒级）来立即同步绑定；解密失败按“无凭据”处理（fail closed），不崩溃。
5. **未在真实 ARM64 设备复跑**：认证与绑定逻辑为纯 JVM + `java.net` 代码，fixture 端到端使用真实 JDK `Proxy.Type.SOCKS` + 全局 Authenticator；Android 特有的 SERVER 回调形态由既有真机探针证据（`.local/dev/evidence-socks-auth.md`）与本次 JVM 实测共同覆盖。
6. `SocksProxyTest` 断言真实回调“恰好 2 次 SOCKS5 + 1 次 HTTP 代理”基于 JDK 17 固定行为；若未来 JDK 改变重试策略需同步调整该断言（其他行为断言不受影响）。

## 8. 证据文件清单

| 路径 | 内容 |
|---|---|
| `.local/dev/evidence-socks-auth-binding.md` | 本文件 |
| `.local/logs/socks-auth-binding-tests.log` | settings 单测运行日志（BUILD SUCCESSFUL / TEST_EXIT=0） |
| `.local/logs/socks-auth-binding-test-results/` | 3 个 XML 原始结果（29 tests / 0 failures；`SocksProxyTest.xml` 含真实回调字段 `<system-out>`） |
| `.local/logs/socks-auth-binding-build-lint.log` | `:app:assembleDebug` + `:app:lintDebug` 成功日志 |
| `.local/logs/socks-auth-binding-check-repository.log` | `check_repository.py` OK 日志 |
| `.local/dev/evidence-socks-auth.md`、`.local/logs/socks-auth-probe.log` | 既有独立 JDK/AOSP SOCKS 认证探针证据（SERVER 回调 quirk 的对照来源） |
