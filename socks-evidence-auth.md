# SOCKS5 用户名/密码认证（RFC 1929）— 实现与验证证据

> 任务：task-11；执行人：teammate `socks-auth`；日期：2026-10-29（Asia/Shanghai）
> 分支：`feat/socks5-proxy`（PR #13），基线 HEAD：`fbfe291`；**未提交**（由 Lead 统一提交/推送）。
> 环境：Windows + `.local/dev/build.ps1`（JDK 17.0.20.1+1、Gradle 8.13、Android SDK 36、OkHttp 4.12.0、JUnit 4.13.2）。
> 构建全程持 `D:\deepseek\ZakoCoder\.local\build.lock`（`CreateNew` 独占，`finally` 删除）串行执行；日志见 `.local/logs/socks-auth-*.log`。

---

## 1. 改动范围（仅 task-11 允许的文件）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/dev/zakocoder/app/settings/NetworkProxy.kt` | 扩展 `Spec`（`username`/`password`/`hasCredentials`/`display`/`proxyUrl`）、扩展 `parse`、新增 `credentials()`/`withCredentials()`、guest env 使用 `proxyUrl`、全局 `Authenticator` 单例与 `configureSocksAuthentication()` |
| `app/src/main/java/dev/zakocoder/app/settings/ProxyCredentialsStore.kt` | **新增**：Android Keystore AES/GCM 加密保存 SOCKS5 凭据（独立 prefs + 独立 alias + endpoint AAD），`load`/`save`/`clear` |
| `app/src/main/java/dev/zakocoder/app/settings/SettingsRepository.kt` | `validate` 走扩展 parse；`save` 拆出 endpoint 与凭据、端点变化时清理凭据；新增 `loadProxyCredentials()`、`proxyForRuntime()`、`proxyDisplay()` |
| `app/src/main/java/dev/zakocoder/app/runtime/LinuxRuntime.kt` | 构造参数 `proxyCredentials: () -> NetworkProxy.Credentials?`；rootfs 下载前配置全局 Authenticator；guest `ALL_PROXY` 使用含凭据的 `proxyUrl` |
| `app/src/main/java/dev/zakocoder/app/ai/ChatTransport.kt` | `clientFor` 构造/复用派生 client 前调用 `configureSocksAuthentication`（OkHttp 的 SOCKS 认证走 JDK 全局认证器） |
| `app/src/main/java/dev/zakocoder/app/Workbench.kt` | `runtime` 注入凭据 provider；AI `proxy` 改为 `settingsRepository.proxyForRuntime()`；状态新增打码字段 `proxyDisplay`；`saveSettings` 只把 endpoint 写入状态/运行时 |
| `app/src/main/java/dev/zakocoder/app/ui/SettingsPage.kt` | 代理行显示 `state.proxyDisplay`（密码打码）；输入框仍为单字段、接受完整 URL |
| `app/src/main/res/values/strings.xml` | `proxy_label` / `invalid_proxy` / `proxy_help` 文案更新（可选 `用户名:密码@`、加密保存、界面打码、guest env 暴露面、HTTP 不支持凭据） |
| `app/src/test/java/dev/zakocoder/app/settings/NetworkProxyTest.kt` | 6 → 10 个用例：凭据解析/百分号编解码/打码/拒绝 HTTP 凭据与半对凭据/guest env 凭据/`withCredentials` |
| `app/src/test/java/dev/zakocoder/app/settings/SocksProxyTest.kt` | 3 → 6 个用例：fixture 支持 RFC 1929（协商 0x02、校验 user/pass），新增认证成功/错误凭据/无凭据+服务器要求认证 3 个用例并断言 ATYP=3 |

未改动：模板相关文件（`Workbench.create()` 等）、依赖、CI、lint 配置、README/docs（Lead 负责）。工作树中 `README.md`/`docs/PRODUCT.md`/`docs/RUNTIME.md` 的改动来自 Lead 的并行编辑，不属于本任务。

## 2. 行为

### 2.1 语法与规范化

- 设置值：空 = 关闭；`http://host:port`；`socks5://[username[:password]@]host:port`。
- 仅 SOCKS5 允许 userInfo；`http://` 带凭据一律拒绝。
- 用户名/密码用 URI 百分号编码（先按 **raw user info** 的第一个 `:` 切分再逐段解码，因此用户名里编码的 `:` 不会被误当作分隔符；`+` 保持字面量，不按表单语义转空格）。
- 两者必须同时存在且非空、长度 ≤ 255、不含 ISO 控制字符；否则 `IllegalArgumentException`，UI 显示本地化错误。
- `Spec.endpoint`（无凭据）= `socks5://host:port`，是**唯一持久化/写进状态**的形式。
- `Spec.display` = `socks5://user:***@host:port`（密码打码、用户名 URL 编码），只用于设置页展示。
- `Spec.proxyUrl` = 含 URL 编码凭据的完整 URL，只用于内部：JVM SOCKS 认证与 guest `ALL_PROXY`。
- `Spec.toString()` / `Credentials.toString()` 重写为不含用户名/密码（防止 data class 默认 `toString` 泄漏）。

### 2.2 持久化（加密）

`ProxyCredentialsStore`（独立 SharedPreferences `proxy-credentials`）：

- 独立 AndroidKeyStore alias `zakocoder-proxy`，AES/GCM/NoPadding（与 `AiConfigStore` 的写法一致，但 alias 独立）。
- 明文 payload = `{"endpoint","username","password"}`；GCM **AAD = endpoint**，同时 prefs 里另存 endpoint 用于比对。换绑端点/篡改数据都会导致 GCM 校验失败。
- `load(endpoint)`：端点不匹配、数据缺失、解密失败、Keystore 不可用（如锁屏变更导致 key 失效）一律返回 `null`（不崩溃、不打印凭据、不会把损坏数据发出去）。
- `save` 仅在 Keystore 不可用等无法加密时抛错（表现为保存设置的错误提示）。
- `SettingsRepository.save` 语义：
  - 输入含凭据 → prefs 存 endpoint，凭据加密写入 store；
  - 输入为空 / 端点与已存不同 → `clear()`（旧凭据绝不发给新端点）；
  - 端点相同且输入无凭据 → 保留已存凭据（点击字号等其它设置不会误删凭据）。
- `load()` 返回的 `proxy` 仍是 endpoint；`proxyForRuntime()` 才返回含凭据的完整 URL；`proxyDisplay()` 返回打码值。

### 2.3 运行时使用

- **AI 请求（OkHttp）与运行时下载（HttpURLConnection）**：两者都通过 JDK 全局 `java.net.Authenticator` 完成 RFC 1929 认证。`NetworkProxy` 内只有一个单例 authenticator；`ChatTransport.clientFor` 在返回（可能是缓存的）派生 client 前调用 `configureSocksAuthentication(credentials)`，`LinuxRuntime.install` 在打开镜像下载连接前调用。`configureSocksAuthentication(null)` 会清空当前凭据，避免旧凭据被发给新端点。
- **guest 环境**：SOCKS5 仍只导出 `ALL_PROXY`/`all_proxy`（不伪造 `http_proxy`），但 URL 改为 `proxyUrl`，例如
  `ALL_PROXY=socks5://user:pass@host:1080`（URL 编码）。
- **apt**：逻辑未变，SOCKS5 仍不配置（apt 不认 SOCKS5）。
- **UI**：设置行显示 `proxyDisplay`（密码 `***`）；编辑框仍是单字段，可直接输入含凭据的完整 URL，保存时解析并加密。

## 3. 安全模型与已知暴露面

1. **静态存储**：磁盘上只有 endpoint、IV、GCM 密文；key 在 AndroidKeyStore，不可导出；与 AI key 的 alias 独立。
2. **界面**：只显示打码形式；输入框回填的是无凭据 endpoint（避免把 `***` 当真实密码回写）。
3. **日志/异常**：不打印凭据。解析错误统一为通用/本地化消息（不把原始 URL 放进异常消息）；`Spec`/`Credentials` 的 `toString` 打码；`AiRequestLog`/诊断只记录异常类型。
4. **全局 Authenticator 的边界**：该认证器是进程级单例，只在 `RequestorType.PROXY` 或 `requestingProtocol == "SOCKS5"` 且当前凭据非空时返回密码；对普通 HTTPS 源站认证一律返回 null。因此不会把 SOCKS 凭据交给源站。副作用：任何使用同一 JDK 全局认证器的 SOCKS5 请求都会拿到当前凭据（本应用只有一处代理设置，且每次构造/使用前由 `proxyForRuntime()` 同步）。
5. **guest env 暴露面（明确说明）**：配置认证后，凭据以**明文**出现在每个 guest 进程的 `ALL_PROXY`/`all_proxy` 环境变量中；guest 内任何进程（包括用户项目脚本、AI 执行的命令）都能读取 `/proc/self/environ` 或 `env`。若命令把 env 打印到终端/日志，凭据也会进入该输出（以及 AI 命令日志）。这是为了兼容 curl/git/Go 等只认 URL 凭据的工具而做的取舍，设置页帮助文案已提示。
6. **解密失败**：返回 `null` → 请求不使用凭据（认证失败），绝不静默回退到直连。

### 3.1 与任务设计的一处偏差（已实测确认，非静默修改）

任务要求「`getPasswordAuthentication` 只在 `RequestorType.PROXY` 且凭据非空时返回」。但实测 JDK 17 与 AOSP（android-37 源码）的 SOCKS 实现都调用旧版
`Authenticator.requestPasswordAuthentication(host, addr, port, "SOCKS5", ...)`，而 `Authenticator.reset()` 会把 `requestingAuthType` 置为 **SERVER**：

```text
=== JDK 17 SOCKS5 authenticator probe ===
AUTH_CALLED requestorType=SERVER protocol=SOCKS5 host=127.0.0.1 port=<proxy port> scheme=null site=/127.0.0.1
CONNECT_SUCCEEDED
--- fixture ---
GREETING version=5 methods=[0, 2]
AUTH user=b'probe-user' pass=b'probe-pass'
CONNECT atyp=3 host=b'fixture.test' port=80
```

（原始输出：`.local/logs/socks-auth-probe.log`；探针源码：`.local/dev/probe-auth/Probe.java`、`fixture.py`。）
若严格只认 `PROXY`，SOCKS5 用户名密码认证将永远拿不到凭据（JDK 会退化为 `user.name` + 空密码并被服务器拒绝）。因此实现改为：
`requestorType == PROXY || requestingProtocol == "SOCKS5"` 时返回当前凭据。源站（SERVER/非 SOCKS5）认证仍然拿不到凭据；HTTP 代理凭据本来就不支持，故 `PROXY` 分支不会把 SOCKS 凭据交给 HTTP 代理之外的任何服务。

另：Android 编译桩不暴露 `Authenticator.getDefault()`（JDK 有、AOSP 旧 API 无），生产代码不依赖它；单测用反射读取原 default，在 `finally` 精确恢复。

## 4. 测试与验证（真实执行结果）

### 4.1 目标测试（持锁，`.local/dev/build.ps1`）

```powershell
powershell -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:testDebugUnitTest --tests 'dev.zakocoder.app.settings.*'
```

结果：`BUILD SUCCESSFUL`（`GRADLE_EXIT=0`），日志 `.local/logs/socks-auth-tests.log`。XML 计数（`.local/logs/socks-auth-test-results/`）：

| 测试类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `dev.zakocoder.app.settings.NetworkProxyTest` | **10** | 0 | 0 | 0 |
| `dev.zakocoder.app.settings.SocksProxyTest` | **6** | 0 | 0 | 0 |

合计 **16/16 通过**（既有 6+3 = 9，新增 7：NetworkProxyTest +4、SocksProxyTest +3）。

`SocksProxyTest` 的 fixture 认证证据（XML `<system-out>` 原文）：

```text
SOCKS5 fixture observed ATYP=3 (domain name) target=127.0.0.1:52891 (HTTP fixture port=52891)
RFC1929 fixture accepted user=user@corp (password redacted), ATYP=3 target=127.0.0.1:52898
```

用例覆盖（fixture 支持协商 0x02、读取 RFC 1929 `VER/ULEN/UNAME/PLEN/PASSWD`、校验后 CONNECT）：

- (a) 正确凭据（`socks5://user%40corp:p%40ss%3Aword@…`，验证百分号解码 + 全局 Authenticator）→ HTTP 200/`hello`，HTTP fixture `accepted == 1`，fixture 记录的 ATYP=**3**、CONNECT 端口 = HTTP fixture 端口；
- (b) 错误凭据 → 请求抛 `IOException`，HTTP fixture `accepted == 0`，fixture 记录到认证尝试但从未接受，且未收到 CONNECT（不回退直连）；
- (c) 服务器要求认证但客户端无凭据 → 请求抛 `IOException`，`accepted == 0`，无 CONNECT（不回退直连）；
- (d) 认证成功路径的 CONNECT 仍记录 **ATYP=3**（域名由代理解析，行为与无认证时一致）；
- 既有 3 个用例（正常转发、代理端口关闭、协商被拒）全部保留通过。
- 每个认证用例用 `withGlobalAuthenticator` 在 `finally` 恢复原 default `Authenticator`（反射取回），并清空 `NetworkProxy` 当前凭据，避免污染其它用例。

### 4.2 构建 / lint / 结构检查

```powershell
powershell -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:assembleDebug :app:lintDebug
# -> BUILD SUCCESSFUL in 1m 14s（GRADLE_EXIT=0），日志 .local/logs/socks-auth-build-lint.log

PYTHONUTF8=1 python3 scripts/check_repository.py
# -> Repository structure, XML, local documentation links and ignore rules: OK
#    CHECK_EXIT=0
```

补充（非验收必需）：`:app:compileDebugAndroidTestKotlin` 成功（`GRADLE_EXIT=0`，`.local/logs/socks-auth-androidtest-compile.log`），确认 `LinuxRuntime`/`SettingsRepository` 变动没有破坏现有仪器测试调用点。

### 4.3 全量单测（含与本任务无关的既有失败）

```powershell
powershell -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:testDebugUnitTest
# -> 102 tests completed, 7 failed（GRADLE_EXIT=1），日志 .local/logs/socks-auth-fulltests.log
```

失败的 7 个用例与本任务无关，且与基线 `.local/logs/baseline-app-tests-rerun.log` **完全一致**（同为 7 个、同类名同方法名，基线 86 tests / 7 failed，本次 102 tests / 7 failed，差值 16 = 本次 settings 新增用例数）：

- `AiProjectFilesTest` ×2
- `ArchiveExtractorTest` ×3
- `EditorFileStoreTest` ×2

（这些是本机 Windows 文件系统/权限相关的既有环境失败，基线已记录；`dev.zakocoder.app.settings.*` 全绿。）

## 5. 限制与风险

1. **凭据字符集**：JDK/Android 的 SOCKS 实现按 ISO-8859-1 写出用户名/密码（`userName.getBytes("ISO-8859-1")`），非 Latin-1 字符（如中文）会在网络上变成 `?`。建议使用 ASCII/Latin-1 凭据；解析器按任务设计只校验长度 ≤255 与无控制字符，未额外拒绝 Unicode。
2. **删除凭据的语义**：按设计「同端点 + 输入无凭据 = 保留已存凭据」，所以仅清空输入框不会删除凭据；要移除凭据需清空整个代理或先改成其它端点再改回。这是为了避免误删，但 UI 上没有独立的「删除凭据」按钮。
3. **guest env 暴露**：见 §3.5；guest 工具是否支持 URL 内嵌凭据取决于工具（curl/git/Go 支持 `ALL_PROXY` 的 userinfo；部分工具只读 `http_proxy`，本项目仍不会为 SOCKS5 伪造它，它们会直连失败而不是误连）。凭据可能随终端输出进入 AI 命令日志。
4. **apt 不走 SOCKS5**：与改动前一致（apt 不认 SOCKS5，`aptAddrConfigWorkaround` 仍只对 HTTP 生效）。
5. **HTTP 代理凭据仍不支持**：`http://` 带 userInfo 被拒绝；UI 文案已说明。
6. **全局认证器是进程级**：同一时刻只服务一个已配置代理（应用也只有一处代理设置）；凭据更新后下一次 `ChatTransport.clientFor`/`install` 会同步。已建立的复用连接不会再触发认证（与既有连接池语义一致）。
7. **AI 请求路径在读取凭据时会做一次 Keystore 解密**（`proxyForRuntime()` 在 `AiController` 调用 `proxy()` 的时机执行）；只在配置了代理且再次发起请求时发生，常规解密为毫秒级，未额外做内存缓存。
8. 未在真实 ARM64 设备上做端到端代理联调；fixture 为本地纯 JVM 端到端（真实 `java.net.Proxy.Type.SOCKS` + 全局 Authenticator + RFC 1928/1929 线协议）。

## 6. 可直接粘贴到 PR 的段落

> **SOCKS5 用户名/密码认证（RFC 1929）**
>
> - 设置语法扩展为 `socks5://[username:password@]host:port`（仅 SOCKS5，HTTP 仍不接受凭据）；用户名/密码支持百分号编码，必须同时提供、非空、≤255 且无控制字符。
> - 凭据不在设置字符串中持久化：`AppSettings.proxy`、SharedPreferences、UI 状态只保存无凭据的 `socks5://host:port`；用户名/密码用独立 AndroidKeyStore alias（`zakocoder-proxy`，AES/GCM，AAD=endpoint）加密存入独立 prefs，解密失败按「无凭据」处理，不崩溃也不回退直连。
> - 设置页只显示打码形式（`socks5://user:***@host:port`），编辑框仍为单字段，保存时解析加密；更换端点会清除旧凭据。
> - 运行时认证通过 JDK 全局 `java.net.Authenticator` 单例：OkHttp（AI 请求）与 `HttpURLConnection`（运行时镜像下载）的 SOCKS5 认证都经过它；`getPasswordAuthentication()` 仅对代理/SOCKS5 请求返回当前凭据，源站认证不受影响，任何日志/异常/`toString` 都不包含密码。
> - guest 环境：SOCKS5 的 `ALL_PROXY`/`all_proxy` 使用含 URL 编码凭据的完整 URL（这是 guest 工具能使用认证代理的前提）；凭据因此对 guest 进程环境可见，设置页帮助文案已明确提示。apt 仍不走 SOCKS5。
> - **实测偏差**：JDK 17/AOSP 的 SOCKS 客户端用旧版 `requestPasswordAuthentication`，`RequestorType` 被框架重置为 SERVER（探针输出 `requestorType=SERVER protocol=SOCKS5`），故认证器在 `PROXY` **或** 协议为 `SOCKS5` 时返回凭据；若只认 `PROXY`，认证将永远不生效。
> - **验证**：`settings.*` 单测 16/16（`NetworkProxyTest` 10、`SocksProxyTest` 6，fixture 支持 RFC 1929 协商 0x02 并校验 user/pass）；认证成功路径记录 **ATYP=3**，错误凭据/无凭据场景均 `IOException` 且本地 HTTP fixture `accepted=0`（不回退直连）；`:app:assembleDebug`、`:app:lintDebug` 成功；`scripts/check_repository.py` exit 0。

## 7. 证据文件清单

| 路径 | 内容 |
|---|---|
| `.local/dev/evidence-socks-auth.md` | 本文件 |
| `.local/logs/socks-auth-tests.log` | `settings.*` 单测成功日志 |
| `.local/logs/socks-auth-test-results/TEST-dev.zakocoder.app.settings.NetworkProxyTest.xml` | 10 tests / 0 failures |
| `.local/logs/socks-auth-test-results/TEST-dev.zakocoder.app.settings.SocksProxyTest.xml` | 6 tests / 0 failures + 真实 ATYP/RFC1929 `<system-out>` |
| `.local/logs/socks-auth-build-lint.log` | `assembleDebug` + `lintDebug` 成功日志 |
| `.local/logs/socks-auth-androidtest-compile.log` | `compileDebugAndroidTestKotlin` 成功日志 |
| `.local/logs/socks-auth-fulltests.log` | 全量单测 102/7（7 个为基线既有失败） |
| `.local/logs/socks-auth-probe.log` | JDK 17 SOCKS5 全局 Authenticator 探针原始输出（`requestorType=SERVER protocol=SOCKS5`、ATYP=3） |
| `.local/dev/probe-auth/Probe.java`、`.local/dev/probe-auth/fixture.py` | 上述探针源码 |
