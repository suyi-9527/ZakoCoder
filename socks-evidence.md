# SOCKS5 DNS 语义修正 + 转发/不回退 JVM fixture 验证证据

- 仓库：`D:\deepseek\ZakoCoder`
- 分支：`feat/proxy-project-templates`，HEAD `4dc2228`
- 任务：task-7（PR #12 审查意见：修正 SOCKS5 DNS 说明 + 补验证证据）
- 环境：Windows（LAPTOP-B1M1JKU6），JDK 17.0.20.1（`.local/tools/jdk-17.0.20.1+1`），Gradle wrapper，OkHttp 4.12.0，JUnit 4.13.2
- 构建/测试全部经 `.local/dev/build.ps1`，并严格遵守 `.local/build.lock` 互斥（另两个 teammate 并行构建）

## 1. 代码改动（只改注释，行为零变更）

### 1.1 `app/src/main/java/dev/zakocoder/app/settings/NetworkProxy.kt`

`javaProxy` 的 KDoc 原文是错误结论：

> Note that the JDK resolves the target hostname locally for SOCKS instead of sending it to the
> proxy (`socks5h` is not available through java.net.Proxy).

改为准确表述（实现行 `parse` / 映射 / `javaProxy` 函数体一行未动）：

> DNS semantics for application requests: the target hostname is sent to the SOCKS5 proxy as
> the CONNECT address and resolved by the proxy, not by the device. An independent on-device
> probe of `HttpURLConnection` and of this project's OkHttp client observed the SOCKS5
> request arriving with ATYP=3 (domain name), so no `socks5h`-style switch is needed for
> application traffic. Guest tools differ: see [guestEnvironment].

并在 `guestEnvironment` KDoc 增加「应用请求 vs guest 工具」的区分：

> DNS for guest tools is up to the tool parsing the variable, unlike this app's own requests
> (see [javaProxy]). Only the `socks5h://` URL scheme asks the proxy to resolve the target
> hostname; with the `socks5://` scheme this project exports, curl/git resolve it locally and
> send the resulting IP over SOCKS5. This project deliberately does not export `socks5h`.

`git diff`（该文件）只有两段注释增删，`+13/-2`，无任何逻辑行变化：

```diff
@@ -55,8 +55,12 @@ object NetworkProxy {
      * Java/OkHttp proxy handle. HTTP uses [Proxy.Type.HTTP]; SOCKS5 uses [Proxy.Type.SOCKS].
-     * Note that the JDK resolves the target hostname locally for SOCKS instead of sending it to
-     * the proxy (`socks5h` is not available through java.net.Proxy).
+     *
+     * DNS semantics for application requests: ... ATYP=3 (domain name) ... Guest tools differ: see [guestEnvironment].
@@ -69,6 +73,11 @@ object NetworkProxy {
      * ... intentionally fall back to a direct connection rather than mis-speaking to the proxy.
+     *
+     * DNS for guest tools is up to the tool parsing the variable ... does not export `socks5h`.
```

### 1.2 `runtime/LinuxRuntime.kt`、`ai/ChatTransport.kt` 检查结果

逐个 grep/通读了两处所有 SOCKS5 相关注释：

- `LinuxRuntime.kt:105`「SOCKS5 uses Proxy.Type.SOCKS; plain HTTP keeps the previous HttpURLConnection proxy path.」→ 只描述映射方式，无 DNS 结论，**无需修改**。
- `LinuxRuntime.kt:174/221` apt workaround / guest env 说明 → 无 DNS 结论，**无需修改**。
- `ChatTransport.kt:32-36`「carrying the HTTP/SOCKS5 proxy … callers map it to a network failure instead of silently going direct」→ 与实测一致（失败不回退），**无需修改**。

结论：仅 `NetworkProxy.kt` 存在错误的 DNS 注释，已改；其余文件未改动（`git status` 中这两文件无变更）。

## 2. 新增 JVM 单测：`app/src/test/java/dev/zakocoder/app/settings/SocksProxyTest.kt`

纯 JVM：无 Android `Context`、无外部网络、无 mock server 依赖，只用 JDK `ServerSocket` + 项目已有 OkHttp（`implementation(libs.okhttp)`，单测 classpath 可见）。

### fixture 设计

1. **SOCKS5 fixture**（`Socks5Fixture`，`ServerSocket` 绑定 `127.0.0.1:0`）：
   - RFC 1928 方法协商：读 `VER=5`、`NMETHODS`、methods → 回 `05 00`（无需认证）；
   - 读 CONNECT：`VER, CMD=1, RSV, ATYP`，按 ATYP 解析地址（`1`=IPv4、`3`=域名、`4`=IPv6）与端口；
   - **记录** `ObservedConnect(atyp, host, port)`（`Collections.synchronizedList`），供断言读取；
   - 与本地 HTTP fixture 的 `host:port` 建 TCP 连接，成功后回 RFC1928 成功应答（`05 00 00 01 00 00 00 00 00 00`），然后用两个守护线程双向转发字节；
   - 支持 `REJECT_METHODS` 模式：方法协商阶段回 `05 FF`（no acceptable methods），用于「握手被拒」用例。
2. **HTTP fixture**（`HttpFixture`，`ServerSocket` 绑定 `127.0.0.1:0`）：
   - 每 accept 一个连接 `accepted` 计数 +1；
   - 读完请求头后固定返回 `HTTP/1.1 200 OK` + `Content-Length: 5` + body `hello`，随后关闭；
   - `accepted` 计数是「是否发生直连回退」的判据。
3. 客户端：`OkHttpClient` 精确使用应用同一映射 `NetworkProxy.javaProxy("socks5://127.0.0.1:<socksPort>")`（类型断言为 `Proxy.Type.SOCKS`），`retryOnConnectionFailure(false)`，connect/read 2s、callTimeout 4s，每个用例 `@Test(timeout = 5_000)`，可重复、快速。

### 用例与断言

| 用例 | 场景 | 关键断言 |
|---|---|---|
| A `forwardsApplicationRequestThroughSocks5AndRecordsTarget` | 代理指向 SOCKS fixture，目标为 HTTP fixture | HTTP 200 + body=`hello`；fixture 记录到 CONNECT 目标端口 == HTTP fixture 端口；ATYP ∈ {1,3}；HTTP fixture `accepted == 1`（确实经代理隧道到达）；`println` 记录真实 ATYP |
| B `refusesDirectFallbackWhenProxyPortIsClosed` | 代理端口预先 bind 后关闭（真实连接拒绝），直连 HTTP fixture 本身可达 | 请求抛 `IOException`；HTTP fixture `accepted == 0`（**没有静默直连**） |
| C `refusesDirectFallbackWhenSocksHandshakeIsRejected` | SOCKS fixture 回 `05 FF` 拒绝握手 | 请求抛 `IOException`；HTTP fixture `accepted == 0`（**没有静默直连**） |

ATYP 故意不断言平台相关的单值（JDK/SOCKS 实现可能发域名或已解析 IP），只断言 ∈ {1,3}，真实观察值写入 system-out（见下）。

## 3. 执行命令与真实结果

统一入口（PowerShell，`.local/dev/build.ps1` 需要 `-ExecutionPolicy Bypass` 子进程调用；并先抢 `.local/build.lock`，`finally` 释放）：

```powershell
$lock='D:\deepseek\ZakoCoder\.local\build.lock'
# 抢锁：New-Item 不带 -Force，已存在则等待，finally Remove-Item
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .local\dev\build.ps1 `
    :app:testDebugUnitTest --tests 'dev.zakocoder.app.settings.*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:assembleDebug
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:lintDebug
```

结果（同一持锁会话，最终一次全量复跑）：

```
TEST_EXIT=0 ASSEMBLE_EXIT=0 LINT_EXIT=0
> Task :app:testDebugUnitTest        BUILD SUCCESSFUL in 3s
> Task :app:assembleDebug            BUILD SUCCESSFUL in 1s
> Task :app:lintDebug                BUILD SUCCESSFUL in 2s
```

### 3.1 测试 XML 计数（`app/build/test-results/testDebugUnitTest/`，已原样复制到 `.local/logs/socks-test-results/`）

| 测试类 | tests | failures | errors | skipped | time |
|---|---|---|---|---|---|
| `NetworkProxyTest`（既有） | 6 | 0 | 0 | 0 | 0.061s |
| `SocksProxyTest`（新增） | 3 | 0 | 0 | 0 | 0.273s |
| **合计** | **9** | **0** | **0** | **0** | — |

### 3.2 实际 ATYP 观察值（真实输出，非推断）

`TEST-dev.zakocoder.app.settings.SocksProxyTest.xml` 的 `<system-out>` 原样内容：

```
SOCKS5 fixture observed ATYP=3 (domain name) target=127.0.0.1:50907 (HTTP fixture port=50907)
```

即：**Windows JVM + OkHttp 4.12.0 经 `java.net.Proxy.Type.SOCKS` 也观察到 ATYP=3（域名）**，CONNECT 目标端口与 HTTP fixture 端口完全一致（50907），由 fixture 侧解析并建隧道。这说明「Java/OkHttp 会把目标域名交给 SOCKS5 代理解析」的结论不是 Android 特例。

补充（来自维护者的 Android 真机独立探针，非本次本地执行）：Android 上 `HttpURLConnection` 与本项目 OkHttp（`java.net.Proxy.Type.SOCKS`）同样发送 ATYP=3，由代理侧解析。两者结论一致。

### 3.3 「失败不回退直连」的证明方式

- 用例 B：代理端口已关闭，HTTP fixture 自身 `127.0.0.1:<port>` 可直连（用例内未主动直连，但端口可连通性由 fixture 正常 accept 能力保证）；断言请求抛 `IOException` 且 `accepted == 0`。若 OkHttp 静默回退直连，`accepted` 会 ≥1，测试必失败。
- 用例 C：代理在线但显式拒绝 SOCKS5 方法协商（`05 FF`），同样断言 `IOException` + `accepted == 0`，排除「握手失败就直连」这一更隐蔽的回退路径。

### 3.4 assemble / lint

- `:app:assembleDebug`：`BUILD SUCCESSFUL`。
- `:app:lintDebug`：`BUILD SUCCESSFUL`（`lint { warningsAsErrors = true }` 全绿，0 error）。
- 过程说明：本轮首次跑时 lint 曾因**并行 teammate 的 template 功能**报 `R.string.project_template_invalid` UnusedResources 失败；该问题不属于本任务文件，已由 `template-writer` 在其写作用域内修复（`ProjectRepository.create` 引用该资源），复跑后 lint 全绿。本任务未改该文件。

## 4. 发现的其他问题 / 备注

1. `NetworkProxy.parse` 明确拒绝 `socks5h://`（有既有测试固化），而 `guestEnvironment` 只导出 `socks5://`；因此 guest 工具（curl/git）在该 scheme 下本机解析 DNS、发 IP；只有外部手动使用 `socks5h://` 才会代理解析。此语义已写入注释，文档由 Lead 统一修改。
2. `LinuxRuntime.kt` / `ChatTransport.kt` 无同类错误注释，未改（见 1.2）。
3. 测试 fixture 只用 `127.0.0.1` 绑定端口，Windows 防火墙不弹窗、无外部网络依赖；连续两次全量复跑结果一致（9/9 通过，ATYP 均为 3）。

## 5. 可直接粘贴到 PR 的「验证证据」段落

> ### SOCKS5 DNS 语义修正与验证证据
>
> **修正**：原注释称「JDK 对 SOCKS 在本地解析目标域名 / 无法使用 socks5h」，与实测不符，已改为：应用请求（`HttpURLConnection`、OkHttp 经 `java.net.Proxy.Type.SOCKS`）把**目标域名**作为 SOCKS5 CONNECT 地址发给代理，由代理侧解析（ATYP=3），因此应用侧不需要 socks5h 开关。guest 工具另行说明：本项目 guest 只导出 `ALL_PROXY=socks5://…`，curl/git 在该 scheme 下本机解析、发 IP；只有 `socks5h://` 才由代理解析，本项目不导出 socks5h。仅改注释，`parse`/映射行为未动。
>
> **验证方式**：新增纯 JVM 单测 `SocksProxyTest`（无 Android Context、无外部网络、单用例 `timeout=5s`）：
> - 自实现最小 SOCKS5 fixture（RFC 1928：方法协商→无认证→CONNECT，解析并记录 ATYP/目标地址/端口，然后与本地 HTTP `ServerSocket` 建立隧道并双向转发）；
> - 用例 A：OkHttp 经 `NetworkProxy.javaProxy("socks5://127.0.0.1:<port>")`（断言类型 `SOCKS`）请求本地 HTTP fixture，返回 200/`hello`；fixture 记录 CONNECT 端口 == HTTP fixture 端口，ATYP ∈ {1,3}；实测 **ATYP=3（域名）**，`target=127.0.0.1:<同一端口>`；
> - 用例 B：直连 HTTP fixture 可用但代理端口关闭 → 请求抛 `IOException`，HTTP fixture `accepted == 0`；
> - 用例 C：SOCKS 方法协商被拒（`05 FF`）→ 请求抛 `IOException`，HTTP fixture `accepted == 0`。
>   两个失败用例的 `accepted == 0` 直接证明「代理失败不会静默回退直连」。
>
> **结果**：`:app:testDebugUnitTest --tests 'dev.zakocoder.app.settings.*'` → 9 tests / 0 failures（既有 `NetworkProxyTest` 6 + 新增 `SocksProxyTest` 3）；`:app:assembleDebug` 成功；`:app:lintDebug` 成功（`warningsAsErrors=true`）。Android 真机独立探针同样观察到 ATYP=3，与本地 JVM 结论一致。

## 6. 证据文件清单

| 文件 | 内容 |
|---|---|
| `.local/dev/evidence-socks.md` | 本文件 |
| `.local/logs/socks-test.log` | settings 单测全量运行日志（BUILD SUCCESSFUL） |
| `.local/logs/socks-assemble.log` | `:app:assembleDebug` 日志 |
| `.local/logs/socks-lint.log` | `:app:lintDebug` 日志 |
| `.local/logs/socks-test-results/TEST-dev.zakocoder.app.settings.NetworkProxyTest.xml` | 6 tests / 0 failures |
| `.local/logs/socks-test-results/TEST-dev.zakocoder.app.settings.SocksProxyTest.xml` | 3 tests / 0 failures + 真实 ATYP=3 system-out |
| `app/src/test/java/dev/zakocoder/app/settings/SocksProxyTest.kt` | 新增测试源码 |
| `app/src/main/java/dev/zakocoder/app/settings/NetworkProxy.kt` | 仅注释修正（`git diff` 无逻辑变更） |
