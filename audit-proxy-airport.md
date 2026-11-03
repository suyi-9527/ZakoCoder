# task-18 独立审计报告：网络代理与机场订阅（feat/proxy-airport WIP）

> 审计者：build-env（未参与本功能实现）｜审计时间：2026-11-03 11:06–11:40（Asia/Shanghai）
> 仓库：`D:\deepseek\ZakoCoder`｜分支 `feat/proxy-airport`｜基线 main `7c359c8`（0 ahead / 0 behind）
> **冻结审计目标：2026-11-03T11:30（Lead 确认）**；下表为冻结快照哈希，验证前后一致（见 §2）。
> 审计者未 commit、未切分支、未修改 `app/src/**`、`docs/**`、依赖、CI、lint 配置；只写 `.local/`。
> 结论速览：**代码/构建/lint/单测门禁通过；未发现纯代码 P0；有 2 项仓库流程门禁（PR 合并阻塞）与 7 项 P1 建议。**
> 判断：**可提交/推送 feature 分支；PR 合并前需处理 P1-1…P1-6（或 Lead 明确豁免）+ 补 UI 截图；证据文档 `evidence-proxy-airport-*.md` 已落后于冻结代码，必须更新。**

---

## 1. 审计对象与冻结快照

### 1.1 变更清单（`git status` + 未跟踪）

| 类型 | 文件 |
|---|---|
| 新增核心 | `settings/ProxySubscription.kt`、`ProxySubscriptionParser.kt`、`ProxySubscriptionFetcher.kt`、`ProxySubscriptionStore.kt`、`ProxyCatalog.kt` |
| 修改 | `Workbench.kt`（proxyCatalog / applyProxyNode）、`ui/SettingsPage.kt`（onProxy 入口）、`ui/ZakoCoderApp.kt`（独立页导航） |
| 新增 UI | `ui/ProxySettingsPage.kt`、`res/values/strings_proxy.xml`（81 条字符串） |
| 新增测试 | `test/.../settings/ProxySubscriptionParserTest.kt`、`ProxyProbeClassificationTest.kt`、`ProxyCatalogTest.kt`、`ProxyTestDoubles.kt` |
| 新增文档 | `docs/PROXY.md`（125 行 UI 设计方案） |
| 修改文档 | `README.md`（+1 行“连接网络”）、`docs/PRODUCT.md`（设置行重写）、`docs/RUNTIME.md`（+1 段订阅说明） |
| 参考（本功能前已存在） | `settings/NetworkProxy.kt`、`settings/ProxyCredentialsStore.kt`、`settings/SettingsRepository.kt`、`ChatTransport.kt`、`LinuxRuntime.kt` |

### 1.2 冻结快照（BEFORE == AFTER，验证期间未再变化）

| 文件 | mtime | bytes | sha256 前 16 |
|---|---|---|---|
| settings/ProxySubscription.kt | 10:37:21 | 6811 | f6a3218fff5d5217 |
| settings/ProxySubscriptionParser.kt | 11:28:15 | 24700 | e2338911fcfcf8a5 |
| settings/ProxySubscriptionFetcher.kt | 11:01:04 | 5623 | 9e8765c0830b55bf |
| settings/ProxySubscriptionStore.kt | 10:38:42 | 7883 | 3ca43c6f0169775b |
| settings/ProxyCatalog.kt | 11:28:20 | 11677 | 33b62eba5866fdef |
| ui/ProxySettingsPage.kt | 10:49:24 | 32904 | b78531f371c6144b |
| Workbench.kt | 10:39:16 | 49185 | 7c6995f3279b270d |
| ui/SettingsPage.kt | 10:38:20 | 27718 | 58d1c8dc2dde1af8 |
| ui/ZakoCoderApp.kt | 10:38:23 | 14322 | 9c07114a5aee0b60 |
| res/values/strings_proxy.xml | 10:38:18 | 6201 | 8aeb88b9481bb93c |
| test/.../ProxySubscriptionParserTest.kt | 11:28:32 | 11254 | b06d7d3d397fca97 |
| test/.../ProxyCatalogTest.kt | 11:28:39 | 13210 | 632cb733a2cd3b47 |
| test/.../ProxyProbeClassificationTest.kt | 10:49:23 | 3560 | 78e9b4197a9e3b3b |
| test/.../ProxyTestDoubles.kt | 11:28:33 | 1883 | 3dade887596fe5cc |
| docs/PROXY.md | 11:01:04 | 9812 | 39076a13abee338f |
| README.md | 11:01:10 | 18364 | fcde651ad1d6e79b |
| docs/PRODUCT.md | 11:01:20 | 18430 | b8fd0bd3b4d80f10 |
| docs/RUNTIME.md | 11:01:21 | 17837 | dea65a90b5cda0de |

### 1.3 审计期间发生的变更（已由 Lead 确认并入冻结目标）

11:28:15–11:28:39 有 5 个文件被 Lead 修复批次改写（parser/catalog/3 个测试）。审计者第一次强制验证因此采到“旧编译 + 新测试”混合快照，出现 1 个竞态失败（`expected 3 but was 4`）；**该结果不代表冻结树**。Lead 确认冻结后，审计者用同一套命令在冻结快照上重跑，全部通过（§2）。修复批次内容与复核见 §3.8。

---

## 2. 验证矩阵（冻结快照，真实执行，持 `.local/build.lock`）

环境：`.local\dev\build.ps1`（JDK 17.0.20.1 / Gradle 9.8.2 / AGP 9.5.0 / compileSdk 37）；所有 Gradle 命令带 `-x :app:prepareRuntime`（Windows 无法执行 Linux-only 的 native-runtime 自编译；制品已在 WSL 真实自编译，见 §5.4）。审计锁：`CreateNew` 独占，11:30:37 获取、11:33:40 释放；无并发 Gradle。

| # | 命令 | 退出码 | 结果 | 耗时 |
|---|---|---|---|---|
| 1 | `$env:PYTHONUTF8='1'; python3 scripts/check_repository.py` | **0** | `Repository structure, XML, local documentation links and ignore rules: OK` | 0.4 s |
| 2 | `.local\dev\build.ps1 :app:assembleDebug :app:lintDebug -x :app:prepareRuntime` | **0** | BUILD SUCCESSFUL；123 tasks（15 executed，含 fresh lint 分析） | 142.8 s |
| 3 | `.local\dev\build.ps1 :app:testDebugUnitTest --tests 'dev.zakocoder.app.settings.*' -x :app:prepareRuntime` | **0** | **66 tests / 0 failures / 0 errors** | 6.2 s |
| 3b | 同上 `--rerun-tasks`（强制全量重编译+重跑） | **0** | **66 tests / 0 failures**；`53 actionable tasks: 53 executed` | 33.5 s |
| 4 | `:app:assembleDebug :app:lintDebug --rerun-tasks -x :app:prepareRuntime`（审计者额外做的一次全量重建，11:23–11:27，旧快照） | 0 | BUILD SUCCESSFUL，`123 actionable tasks: 123 executed` | 206.6 s |
| 5 | `adb devices`（审计者环境） | 0 | 空列表，无设备（与 UI 未验证一致） | — |

**测试明细（XML，冻结快照 11:33:40）**：

| 测试类 | tests | failures | errors | 说明 |
|---|---|---|---|---|
| `NetworkProxyTest`（既有） | 10 | 0 | 0 | 代理解析/凭据/guest env/apt |
| `ProxyCatalogTest`（新增+11:28 增量） | **17** | 0 | 0 | 含新增“在途改链接丢弃结果”用例 |
| `ProxyProbeClassificationTest` | 7 | 0 | 0 | 四态分类 + 非 HTTPS 不联网 |
| `ProxySubscriptionParserTest` | 13 | 0 | 0 | 含 https→unsupported 新断言 |
| `SocksAuthBindingTest`（既有） | 7 | 0 | 0 | RFC1929 绑定 |
| `SocksProxyTest`（既有，本地 SOCKS5 fixture） | 12 | 0 | 0 | 真实 socket/OkHttp/HttpURLConnection 路径 |
| **合计** | **66** | **0** | **0** | Lead 预期 65+1=66 ✓ |

**Lint（冻结快照 fresh，`app/build/reports/lint-results-debug.xml` 11:33:00）**：14 条 issue，**全部 Hint、0 error/0 warning**：
`OldTargetApi`×1、`GradleDependency`×5、`NewerVersionAvailable`×6、`AutoboxingStateCreation`×1（既有 `AiUsageDashboard.kt:38`）、`NotShrinkingResources`×1（既有构建配置）。
→ `warningsAsErrors = true` 下通过；未改 lint 配置/基线。新页面未引入任何 lint 违规。

**产物**：`app/build/outputs/apk/debug/app-debug.apk` = **21,521,997 B @11:30:42**，1053 entries、17 dex。运行时制品打包：`libzakocoder_proot.so` 326,936 B、`libzakocoder_loader.so` 18,248 B。

**工作树**：`git status --porcelain` 恰为预期的 6 modified + 12 untracked（5 核心 + 1 UI + 1 strings + 4 测试 + 1 docs）；无 build 产物、无 `.local/`、无密钥。

---

## 3. 逐项审计结论

### 3.1 安全/隐私（结论：基本到位；2 个 P1 隐私/正确性问题）

**已核实为正向的实现**：

- **订阅仅 HTTPS**：`ProxySubscriptionFetcher.parseUrl`（Fetcher:64-70）在建连前拒绝非 HTTPS；重定向手工跟随且**每跳校验 HTTPS**（Fetcher:49-62），降级为 `InsecureScheme`；非法 URL/超长 → `InvalidLink`；最多 3 跳。
- **有界成本**：connect 15s / read 30s / call 45s（Fetcher:113-121）；响应 2 MiB 上限（Content-Length + 流式 1 字节越界探测，Fetcher:91-104）；协程取消 `call.cancel()`（Fetcher:79）。
- **Keystore**：独立 alias `zakocoder-proxy-subscription`、独立 SharedPreferences `proxy-subscription`、AES/GCM + 随机 IV + 常量 AAD `zakocoder-proxy-subscription-v1`、JSON `schemaVersion=1`（Store:39-167）；`load()` 对损坏/Key 失效/schema 不符返回 null 且不抛（Store:42-56）；`save()` 先加密后写偏好（失败保留旧值）；`clear()` 可修复“坏条目卡死”（Store:73-76）。
- **凭据回写路径唯一**：节点 `proxyUrl` 只经 `Workbench.applyProxyNode` → 既有 `saveSettings` → `SettingsRepository.save` 抽离凭据到 `ProxyCredentialsStore`、偏好只存无凭据 endpoint（Workbench:662-667、SettingsRepository:65-89）。审计未发现第二条写回路径。
- **日志/异常**：新增代码 grep 无 `Log.`/`println`/`printStackTrace`；`ProxySubscriptionException` message 仅错误枚举名且 `toString` 重写（ProxySubscription.kt:101-107）；catalog state 只暴露枚举错误；11:28 起 `ProxyCatalogState.toString` 已脱敏（Catalog:26-31）。
- **SSRF（低风险）**：允许订阅链接指向私网/回环 IP；因请求由用户显式输入并仅 HTTPS，视为可接受；如需收紧可拒绝私有网段（建议，不阻塞）。

**问题**：

- **P1-1（隐私）明文密码/订阅 token 进入可恢复页面状态，且保存后仍留在输入框** — `ProxySettingsPage.kt:68`（`manualDraft` rememberSaveable）、`:70`（`linkDraft` rememberSaveable）、`:162-181`（手动地址输入含 `user:password@` 明文）、`:332-340`（订阅链接输入含 token）、`:86-95`（保存成功后不清理草稿）。`docs/DESIGN.md:57` 明确“模型密钥不回显、不放入可恢复页面状态”；代理凭据/token 应同等对待。证据 UI 文档称“草稿用 rememberSaveable 返回/重组保留，不写磁盘”，但系统可保存/恢复该 Bundle（进程死亡场景），且保存后密码一直可见到离开页面。**修复**：仅保存非敏感草稿（或保存后清空/掩码）、保存成功把输入重置为无密码 endpoint、页面 `onDispose`/失焦清理敏感字段；不要对含 secret 的字符串使用 rememberSaveable。
- **P2（文档与实现措辞）**：`docs/RUNTIME.md:109` 称“界面与日志不显示完整链接和密码”，但输入框必然显示用户粘贴的完整链接、手动表单显示明文密码（上条）；建议改为“持久化/状态卡/日志不显示完整链接与密码”。
- **P3**：`ProxySubscriptionException(reason, cause)` 保留底层 `IOException` 作为 cause；OkHttp 的异常 message 可能含目标 host（不含 path/token）。当前无日志打印它，风险低；若未来接入崩溃上报应剥离 cause。

### 3.2 解析/分类逻辑边界（结论：覆盖面好；4 个 P2 边界 + 2 个 P3）

**已核实/测试覆盖**：plain links（含 `#名称` 空格、多链接行拆分）、Base64（标准/URL-safe/缺 padding/空白/换行）、Clash/Mihomo 子集（`proxies:` 下 inline `{...}` 与 block、引号内逗号/注释、跨行大括号、到下一个顶层 key 停止）、`vmess/ss/ssr/vless/trojan/hysteria(2)/tuic` 列出为暂不支持、无效行跳过、未知 scheme 忽略、512 截断、host/port 校验、id 稳定（含重复节点 index 区分、重启一致）、2 MiB 上限、名称 80 字符、凭据 255 + 控制字符拒绝、HTML/垃圾内容拒绝。`ParserTest` 13 例 + `ProbeClassificationTest` 7 例 + 只读 JDK17 探针（大写 scheme/IPv6/BOM）共同核对。

**11:28 修复复核（https 语义）**：

- `parseLink` 现在 `"https" -> authorityUnsupported(...)`（Parser:41-56），新增 `authorityUnsupported`（Parser:148-159）保留 host/port/姓名并标 `ProxyProtocol.Other`/unsupported；Clash `type: https -> Other`（Parser:323-327）。
- `ParserTest:50-59` 断言节点 3 为 `Other`、unsupported、`proxyUrl=""`、host/port/name 保留；`supportedCount=3`。
- 合理性：app 只实现明文 HTTP 代理（`NetworkProxy` 仅接受 `http`/`socks5`），旧逻辑 `https:// → Http` 会在写回时静默降级为 `http://`；改为 unsupported 是正确的安全/正确性修复。**复核通过。**

**问题**：

- **P1-6（静默降级凭据）**：`ProxyNode.supported` 对 SOCKS5 只要求 `username == null || (username 非空 && password 非空)`（ProxySubscription.kt:31-35），因此 **`password` 有值但 `username == null` 时 supported=true，而 `proxyUrl` 会丢弃 password**（ProxySubscription.kt:38-47），节点被当成无认证 SOCKS5 保存。Clash 条目可直接到达（Parser:314-316 独立读取 username/password）。`ParserTest` 只覆盖“只有 username”的拒绝（ParserTest:194-207），未覆盖“只有 password”。**修复**：`supported` 改为 `password == null || (username != null && password.isNotEmpty())`（并补测试）。
- **P2（unsupported 链接带路径时丢失 host/port/名称）**：`extractAuthorityServer` 只去 `?`，未去 `/`（Parser:185-191），而 supported 路径 `authorityNode` 有 `substringBefore('/')`（Parser:121-122）。真实链接 `trojan://pass@host:443/path`、`hy2://host:443/?insecure=1` 会因 port 解析失败退化为“未命名节点”（Parser:148-159、161-181）。建议与 `authorityNode` 一致地去路径。
- **P2（BOM 订阅解析失败）**：`parse()` 只用 `trim()`（Parser:60-70），U+FEFF 不是 whitespace（JDK 探针 `Character.isWhitespace(U+FEFF)=false` 已实测），Clash 的 `looksLikeClash`（Parser:252-253）与 Base64 字母表（Parser:495+）都会因 BOM 失败；带 BOM 的单节点/Clash 订阅会误报“无法识别”。建议 `trimStart('\uFEFF')` 并加测试。
- **P2（IDN/非 ASCII host 标为 supported 但无法写回）**：`validHost` 允许非 ASCII host（Parser:229-232），`ProxyNode.supported` 为 true 且 `proxyUrl` 生成 `socks5://例え.jp:1080`，但 `NetworkProxy.parse` 用 `URI.host`，对非 ASCII host 返回 null → 抛 `invalid proxy`（NetworkProxy.kt:95-99；JDK 探针实测 `URI("https://例え.jp/").getHost()==null`）。表现为“可用节点点保存后失败”。建议 `supported` 直接以 `NetworkProxy.parse(proxyUrl) != null` 判定，或 parser 拒绝非 ASCII host。
- **P3**：`Parser` 类注释仍写 “only `http`/`https`/`socks`/`socks5` produce usable nodes”（Parser:16-18），与 11:28 修复矛盾；建议改为 http/socks/socks5。
- **P3**：Clash `proxies: [...]`（整列表 inline flow）、YAML anchor/alias、未闭合括号名称等子集外输入静默不解析；文档只称“子集”，可接受但建议在 PROXY/PRODUCT 标注支持的 Clash 子集范围。`ParserTest` 未覆盖“Base64 解码出 Clash”与跨行 inline braces（证据声称支持）。
- **P3**：`socks4://` 等未列协议被静默丢弃（Parser:41-56 else→null），与“其他已知协议标注暂不支持”的产品语义有差异；`https` 已修复，socks4 可顺带补。

### 3.3 状态机/并发（结论：结构清晰；2 个 P1 + 若干 P2）

**正向**：所有网络调用经 `Mutex.tryLock` 串行，忙时后续调用 no-op（Catalog:70-116、119-148、154-…）；`busy` 在每条路径（成功/失败/取消/finally）复位；`persist` 使用 `NonCancellable + Dispatchers.IO`（Catalog:256-258）；`refresh` 失败保留旧 `subscription/updatedAt/selectedId` 且不写 store（已由测试 `refreshFailure...` 验证）；`loadCached` 只读不联网；`clear` 清 store+state；取消经 `CancellationException` 重抛 + finally 复位。

**11:28 修复复核（在途改链接）**：

- `probe()` 在 fetch 返回后比较 `mutable.value.link.trim() != link`，不一致则 `probed=null`、`busy=false`、`error=null`、`probe=null` 返回（Catalog:98-104）——不再把旧链接结果当成新链接分类。
- 新测试 `probeResultIsDiscardedWhenTheLinkChangesWhileTheRequestIsInFlight`（CatalogTest:297-322）用 `beforeFetch` 挂起（TestDoubles:8-20）在 Default 调度器并发测：发起 probe→改 link→放行→断言 probe/error 为 null、busy=false、store.saves=0、随后 `import()` 不落盘。**测试真实、断言到位；复核通过。**
- 残留（P3）：UI 输入框仍允许在识别中编辑（本修复保证状态正确，但用户会看到空结果且无提示）；建议 busy 时禁用输入或提示“链接已修改，请重新识别”。

**问题**：

- **P1-3（probe 结果不可选/导入失败死锁）**：`select()` 只接受 `state.subscription` 中的节点（Catalog:228-233），而“识别（probe）成功后自动 import”失败（Keystore/存储异常）时 `subscription` 为 null，页面仍展示 probe 的节点列表（UI:106-110），点击行 `select()` no-op → 底部 CTA 永远禁用，只能重新识别。**修复**：`select` 同时允许当前 `probe` 节点；或 import 失败时清空节点列表并显示可重试错误。
- **P1-4（主线程工作）**：`loadCached()`（Catalog:212-225）在 UI 线程做 Keystore 解密 + JSON 解析，UI 在 `LaunchedEffect(Unit)` 直接调用（UI:75，每次进页都重复解密）；`probe()`/`refresh()` 的 `parser.parse(...)`（≤2 MiB、Clash 正则）在调用者（Main）线程执行（Catalog:105、refresh 内），仅 fetch/persist 切到 IO。首次 Keystore 初始化与 2 MiB 解析都可能造成 jank/ANR。**修复**：`loadCached` 改 suspend+IO（或在首次成功后缓存），parse 移入 `withContext(Dispatchers.Default)`。
- **P2**：`clear()` 在请求在途时不停请求/不拒绝结果；移除按钮在 `busy` 时仍可点（UI:604-608、Catalog:236-244），in-flight fetch 结束后会写回 probe（不落盘）→ UI 在“已移除”后再次出现识别结果。建议 `clear` 递增 generation 或在 busy 时禁用移除。
- **P2**：`finally { mutex.unlock(); resetBusyAfterCancellation() }`（Catalog:112-115、144-147、261-263）在解锁后才清 busy；另一个操作可在两步之间取得锁并置 busy=true，随后被复位为 false → 理论竞态（UI 单线程下概率极低，但可改为先清 busy 再 unlock）。
- **P2**：`probe()` 只对 fetch 做 try/catch，`parser.parse`/`classify` 的意外 RuntimeException 会逃出（Catalog:105-111），UI 的 `scope.launch { probe(); ... }` 无捕获 → 可能崩溃而不是显示“无法识别”。建议把解析/分类纳入 try 并映射 `UnsupportedContent`。
- **P3**：`refresh` 失败时不清 `probed`；之后任何 `import()` 调用可用新时间戳把旧 probe 重新落盘（正常 UI 流程不会触发，但状态机不严密）。
- **P3**：`import()` 在 `probe` 不是 Airport/SingleNode 时静默 return（不设 error），配合 P1-3 会让 UI 无反馈。

### 3.4 UI/UX/Compose 规范（结论：整体符合设计；1 个 P1 + 4 个 P2）

**检查通过项**：使用 `collectAsStateWithLifecycle`（UI:56），**composition 内无 `StateFlow.value` 读取**（唯一 `.value` 在 `identify()` 的协程内 UI:101，非组合期；11:28 前 lint 曾报的 line 61 已修）；`LaunchedEffect(Unit)` 只做 `loadCached`（不联网）；`LaunchedEffect(catalog.subscription, modeTouched)` 用于自动切 tab；`LaunchedEffect(copied)` 2s 复位；`AiFullScreen` 自带 `windowInsetsPadding(systemBars)+imePadding()`（AiDiagnostics.kt:232），底部操作区随 IME 上移；底部操作区是滚动区外的兄弟节点（UI:208-217），不会压列表；列表使用 `LazyColumn` + `key=node.id` + `heightIn(max=420dp)`（UI:439-450），有界（页面级 `verticalScroll` 内嵌套滚动，是 PROXY.md“列表最多一屏内滚动、页面级滚动”的落地，但需真机手感验证）；触控目标均 ≥48dp（模式 48、行 56、按钮/水印链 48）；四态齐全（骨架/空/错误/结果，UI:389-415）；字符串全部进 `strings_proxy.xml`（81 条，脚本核对**全部有引用**，lint 未报 UnusedResources）；状态不只靠颜色（徽标有文字 + RadioButton）；协议图标用文字短标，无新图标库。

**问题**：

- **P1-2（状态卡把“草稿选择”误报为“已生效机场节点”）**：`currentModeRes` = `hasSubscription && selectedId != null ? 机场节点 : 手动代理`（UI:114-118），而 `selectedId` 是 catalog 草稿选择（`loadCached`/`import` 会自动选中首个可用节点，Catalog:265-267），并不表示该节点已写入设置。因此：手动代理 + 存在旧订阅时，状态卡/“来自机场 · 节点名”会显示机场节点；导入订阅但从未点 CTA 时也会显示。修复：用“当前 `state.proxyDisplay` 与该节点 `proxyUrl` 的打码比较”或持久化 `appliedNodeId` 判定“已生效”来源。
- **P2（初始 tab/自动切换由缓存订阅驱动）**：`initialMode` 用 `catalog.subscription != null`（UI:60-67），`LaunchedEffect` 在 `!modeTouched` 时强制切到机场（UI:76-78）。用户当前用“手动代理”但本地有旧订阅时，进入页面会落在“机场订阅”而不是当前来源。建议以 `state.settings.proxy` 与订阅关系决定初始模式，只有用户点击才切换。
- **P2（错误重试语义错误）**：`ProxyErrorBlock(onRetry = onIdentify)`（UI:395）；`refresh` 失败时 `linkDraft` 通常为空，点“重新识别”会得到 `InvalidLink`，无法直接重试刷新。建议区分“识别失败→重新识别 / 刷新失败→重试刷新”。
- **P2（全部不支持时不显示节点列表）**：`UnsupportedOnly` 只显示徽标 + 总数（UI:400-406），不渲染节点行与“暂不支持”标签；与 `PROXY.md:106` “节点行灰化 + 暂不支持标签，点击提示”不一致，用户看不到具体被跳过哪些协议/节点。建议同 Airport 一样列出（禁用行）。
- **P2（移除订阅位置与设计不一致）**：`PROXY.md:62` 要求“放页面内列表下方，不占底部主位”；实现放在底部操作区（UI:604-608）。功能可用，属 UI 规格偏差，需产品/视觉确认。
- **P2（手动表单草稿与当前设置不同步）**：`manualDraft` 只在首次组合时取 `state.settings.proxy`（UI:68）；机场节点生效后切到手动模式，输入框仍是旧草稿，“保存并启用”可能用旧值覆盖刚应用的节点。建议进入手动模式时用当前设置刷新（或提供“载入当前端点”）。
- **P2（CTA/保存失败不可见）**：`applyProxyNode` 的返回值被丢弃（UI:213）；`saveSettings` 的失败/成功只写 `Workbench` 全局 message（Workbench.kt:371-386、633-655），而该 message 渲染在 `ZakoCoderApp` 的 Scaffold 内（ZakoCoderApp.kt:177），**被全屏 Dialog（ZakoCoderApp.kt:100）遮住** → 本页看不到任何失败/成功反馈（手动保存的 `validateSettings` 异常有就地提示，但落盘/Keystore 失败没有）。建议接返回值 + 页内错误/成功提示，成功后关闭页面或更新状态卡。
- **P3（TalkBack 选中语义）**：节点行用 `Modifier.clickable(role = Role.RadioButton)`（UI:484-514）而非 `selectable`，未向读屏暴露“已选中”；内部 `RadioButton(onClick = null)` 可能产生重复焦点。模式分段已正确用 `selectable(role = RadioButton)`（UI:287-311）。建议行改用 `selectable(selected = ..., role = Role.RadioButton)` 并合并语义。
- **P3（“已生效”恒显/复制提示）**：状态卡“已生效”无条件显示（UI:260），未配置时也显示；未配置时仍显示“点按复制打码端点”（UI:279-281，复制是 no-op）。建议按状态显示。
- **P3（错误 + NotSubscription 双重提示）**：`UnsupportedContent` 同时设置 error 与 `probe=NotSubscription`（Catalog:107），UI 同时渲染错误块与“无法识别”徽标（UI:395-410），信息重复。
- **P3（130%/暗色/窄屏未经真机验证）**：模式分段固定 `14.sp` + `maxLines=1`（UI:299-307），底部固定区/列表嵌套滚动的实际手感、TalkBack 顺序均未在设备验证——属 P0-1 流程门禁的一部分。

### 3.5 集成一致性（结论：核心一致；1 个 P2 限制 + 文档偏差）

- **保存后生效**：`applyProxyNode` → `saveSettings` → `SettingsRepository.save`（凭据抽离）→ `configureSocksAuthentication(proxyForRuntime())`（SettingsRepository:88）→ `runtime.configure`（如已安装）→ 状态 `settings/proxyDisplay` 更新（Workbench:633-655）；AI 请求经 `proxy = { settingsRepository.proxyForRuntime() }`（Workbench:179 附近）自动获得 SOCKS5 凭据；rootfs 下载与 guest `ALL_PROXY` 同样由 `proxyForRuntime`/`withCredentials` 注入（LinuxRuntime:123-127、283-284）。**节点凭据仅经现有 SettingsRepository，未发现旁路。**
- **busy 语义**：页面按钮用 `state.busy || catalog.busy` 禁用（UI:211、351、378），与 Workbench `run()` 的 busy 守卫（Workbench:371-372）一致；`applyProxyNode` 自己也检查 busy/loading（Workbench:662-667）。**但失败反馈不可见（P1-5）与返回值被忽略使“busy 竞态”表现为静默 no-op。**
- **入口唯一**：设置页“网络代理”行改为 `onProxy()` 进独立页（SettingsPage:237、ZakoCoderApp:174）；旧 proxy sheet 分支仍保留但无任何写入 `editing="proxy"` 的路径（已 grep 确认），为死代码（P3，保留是为避免删除既有 strings 触发 UnusedResources；可在后续清理）。
- **打开页面不联网**：页面唯一副作用是 `LaunchedEffect(Unit) { loadCached() }`（UI:75，只读加密偏好）；识别/刷新/粘贴/复制均需显式点击；无自动重试/自动更新。
- **`-x :app:prepareRuntime` 未污染仓库**：`git grep prepareRuntime` 仅命中 `app/build.gradle.kts`（任务定义、preBuild 依赖）、`native-runtime/README.md` 与代码调用；**docs/README 无 `-x` 跳过说明**，构建配置未改。跳过仅发生在审计者本地命令，CI/Linux 不受影响。
- **P2（订阅下载不走应用代理）**：`HttpProxySubscriptionFetcher` 使用独立 OkHttpClient，未使用设置里的 HTTP/SOCKS5 代理（Fetcher:41-122）。在“必须先经代理才能联网”的环境中无法导入/刷新订阅；docs/RUNTIME:109 未说明。建议在文档标注该限制（或后续支持“用当前代理拉订阅”）。
- **文档与实现一致性**：
  - README:39、PRODUCT:24、RUNTIME:109 与实现基本一致（HTTPS-only、2 MiB、512 上限、仅 HTTP/SOCKS5 可启用、Keystore、显式点击、不自动更新/测速、节点写回立即生效）。
  - **PROXY.md 偏差**：移除订阅位置（PROXY:62 vs 实现底部区）、自动选中首个可用节点（PROXY §3.6/§3.7 暗示需用户选择；实现 `retainSelection` 自动选中，Catalog:265-267，证据-ui §7.4 自认需产品确认）、全部不支持时的节点行展示（PROXY:106）、截图证据（PROXY:125 要求，未提供）。
  - **未说明行为**：`https://` 代理链接/TLS-to-proxy 不支持（11:28 修复后按暂不支持列出），订阅下载不走已配置代理，自动选中首节点。
  - `docs/PROXY.md` 未被 README 的文档索引链接（孤立设计文档，check_repository 只校验已有链接，不报错）；建议加入 README 开发文档列表。
- **P2（IDN 写回失败）**见 §3.2。

### 3.6 测试质量（结论：新增核心覆盖扎实；但缺关键层）

**优点**：`ParserTest`（13）、`ProbeClassificationTest`（7）、`CatalogTest`（17）全部走**真实 parser/classifier**，只把网络/存储替身为 Fake（TestDoubles）；断言用户可见结果（format、supported/supportedCount、proxyUrl、maskedSource、toString 不含 token/密码、错误枚举、store.saves、selection、stale-link 丢弃、import 前置条件、refresh 失败保留缓存）；`SocksProxyTest`（既有 12）用本地 SOCKS5/HTTP fixture 验证真实 socket 路径与全局 Authenticator 绑定；11:28 新增的 in-flight 测试用 `beforeFetch` 钩子真实并发（Default 调度器）验证，质量好。

**缺口（P1-7）**：

- **无 `HttpProxySubscriptionFetcher` 测试**：HTTPS-only、重定向逐跳 HTTPS/降级拒绝、3 跳上限、2 MiB（chunked 与 Content-Length）、超时、取消、非 2xx、空 body、UA 均未测；`okhttp-mock`（MockWebServer）仅加了 `androidTestImplementation`（app/build.gradle.kts:87），本可用 JVM 测但未用。
- **无 `EncryptedProxySubscriptionStore` 测试**：alias/AAD/schema/损坏密文/Key 失效/`clear` 修复等仅靠代码阅读；`docs/DEVELOPMENT.md:55` 明确“Keystore 使用仪器测试”，当前无仪器测试（且无任何设备验证）。
- **无 Catalog 并发/取消测试**：`Mutex.tryLock` 忙时 no-op、取消时 busy 复位、`clear` 与在途请求、`resetBusyAfterCancellation` 竞态均未覆盖（11:28 只新增了“改链接”用例）。
- **无 `applyProxyNode`→`SettingsRepository` 写回一致性测试**：节点含凭据 → endpoint/Keystore/`proxyForRuntime` 的链路（Android Context 依赖，需 instrumented 或重构可测）。
- **无 UI 测试/截图**：Compose 层无 instrumentation；设计要求的 360dp/130%/亮暗/键盘/TalkBack 全部未验证。
- 其它：`ProxySubscriptionParserTest` 未覆盖 BOM、Clash `proxies: [...]` inline 整列、Clash `type: https`、unsupported 链接带 `/path`；`CatalogTest` 的 refresh 失败用例未断言 `selectedId` 保持；`Store.decodeOrNull` 部分损坏丢整份未测。

### 3.7 证据真实性核对（`evidence-proxy-airport-core.md` / `-ui.md`）

| 证据中的说法 | 核对结果 |
|---|---|
| `check_repository` OK / assembleDebug+lintDebug 通过 / 全量单测 65 tests 0 failures | **方向上真实**；冻结树实测 exit 0、lint 0 error/0 warning；但**测试数已变为 66**（11:28 增量 +1），证据未更新。 |
| “BUILD SUCCESSFUL in 4m 21s；APK 21,522,964 B（10:54:44）” | 大小接近（最终构建 21,521,997 B @11:30:42，差 967 B，属重新打包波动）；**时间戳不可复现**（此后至少 2 次构建：11:01:40、11:30:42），说明证据写完后又有构建，证据不是“最终代码的最后一次构建”。 |
| Fetcher “UA 为 `ZakoCoder/0.1.0-dev`” | **不准确**：代码默认 `DEFAULT_USER_AGENT = "ZakoCoder"`（Fetcher:107）；证据自身 §7.2 又说“默认 ZakoCoder/0.1.0-dev”，前后矛盾。 |
| “`docs/PROXY.md` 的措辞是超限拒绝” | **不准确**：`PROXY.md:118` 写的是“最多展示 512 个节点（超出按输入顺序截断…）”，与实现一致；证据 §7.3 的判断基于旧版文档。 |
| “UI 可能把 applyProxyNode false 显示成暂不支持” | **不准确**：UI 直接丢弃返回值（ProxySettingsPage.kt:213），没有 false→文案映射；这与 P1-5 相关。 |
| “lint 在最终代码上 0 error/0 warning” | evidence-ui 记录的 lint 修复（StateFlow.value）真实存在；但其 lint 报告时间 10:59 早于最后一处源码编辑 11:01:04（Fetcher），严格说当时未覆盖最终文件。**审计者在冻结树上 fresh lint（11:33）确认 0 error/0 warning**，结论成立但证据时点有瑕疵。 |
| “WSL 自编译 runtime 制品、未伪造 hash” | **真实**：`runtime-artifacts` 两个 .so 的 sha256 与 `runtime/artifacts.json` 完全一致（proot `e7daf3d5…`、loader `32ac7e2a…`），且与 `native-runtime/build/build-record.json` 的 fingerprint `3b28a2c0…7e003e`/artifacts 一致；构建前后哈希未变；`-x :app:prepareRuntime` 未写入仓库。native-runtime 需 Linux `fcntl` 与 linux-x86_64 工具链，Windows 跳过属合理变通。 |
| “未做真机/模拟器验证” | **如实声明**（evidence-ui §7.1）；但仓库合并要求实际截图（CONTRIBUTING:79-80、96），构成门禁（P0-1）。 |
| “未改 settings/docs/依赖” | UI 证据称未改 docs，但本次 WIP 实际含 README/PRODUCT/RUNTIME/PROXY 改动（应由其他人完成）；证据文档没有覆盖这些文档的验证归属。 |

**结论**：两份证据无“系统性造假”，核心命令/数字可复现（除上述版本漂移）；但**已落后于 11:28 修复批次**，且个别表述/数字不准确，需在 PR 前更新。

### 3.8 11:28 修复批次复核结论（Lead 要求单独复核）

| 修复 | 代码 | 测试 | 判定 |
|---|---|---|---|
| `https://` 代理链接与 Clash `type: https` → `Other`(unsupported) | Parser:41-56、148-159、323-327 | ParserTest:50-59（`Other`/unsupported/`proxyUrl=""`/host 保留）；Clash https **无专门用例**（P3） | **成立**：语义正确（app 不支持 TLS-to-proxy，避免静默降级成 http://）；类注释未同步（P3）。 |
| probe 在途链接变化丢弃结果 | Catalog:98-104 | CatalogTest:297-322（真实并发、断言 probe/error/busy/store.import） | **成立**：状态一致、不落盘；残留 UI 无提示/输入仍可编辑（P3）。 |
| `ProxyCatalogState.toString` 脱敏 | Catalog:26-31 | 间接（现有“异常文本不入 state”用例） | **成立**：不再打印订阅链接；`link` 仍作为属性存在（UI 需要，属内存态）。 |

---

## 4. 问题清单（按严重级别）

### P0（阻塞 PR 合并；均非“代码崩溃”，Lead 已知悉）

- **P0-1 UI 无真机截图/交互验证**：新页面属 UI 新增，`CONTRIBUTING.md:79-80`（必须附日/夜截图，说明设备/主题/数据来源，覆盖窄屏/字体放大/安全区/键盘）与 `:96`（缺截图不进合并）、`DEVELOPMENT.md:79/83`、`DESIGN.md:73`、`PROXY.md:125` 均要求；证据-ui §7.1 自认未做。**处置**：Lead 计划提交后补模拟器截图并附 PR；补齐后本项解除。
- **P0-2 Keystore/网络边界无仪器测试**：`DEVELOPMENT.md:55` 明确 Keystore 用仪器测试；`CONTRIBUTING.md:96` 将“缺乏关键验证”列为阻塞。当前 `EncryptedProxySubscriptionStore` 与 `HttpProxySubscriptionFetcher` 无任何自动化测试。**处置**：PR 中如实标注（Lead 已同意）并补最小 instrumented test（Store 一条、Fetcher MockWebServer 若干）后可解除。

> 审计未发现会导致崩溃/数据丢失/凭据外泄的**纯代码 P0**。

### P1（高；建议 PR 合并前修复）

1. **私密草稿进可恢复状态 + 密码保存后仍明文显示** — ProxySettingsPage.kt:68,70,162-180,332-340；DESIGN.md:57。
2. **状态卡把草稿选择误报为“已生效机场节点”** — ProxySettingsPage.kt:60-64,76-78,110-118。
3. **`select()` 只认已导入订阅 → probe-only/导入失败时节点不可选、CTA 死锁** — ProxyCatalog.kt:228-233 + ProxySettingsPage.kt:106-110,211。
4. **主线程 Keystore 解密与 2 MiB 解析** — ProxyCatalog.kt:105,212-225；ProxySettingsPage.kt:75。
5. **CTA/保存失败无页内反馈**（applyProxyNode 返回值被丢弃；message 被 Dialog 遮挡）— ProxySettingsPage.kt:213；Workbench.kt:371-386,662-667；ZakoCoderApp.kt:100,177。
6. **SOCKS5 只有 password 时被标 supported 但丢弃密码** — ProxySubscription.kt:31-35,38-47；Parser.kt:314-316。
7. **关键测试缺口**（Fetcher/Store/并发取消/applyProxyNode 写回）— 见 §3.6。

### P2（中；建议后续 PR/豁免单）

8. 自动选中首个可用节点与 PROXY.md 设计（需用户选择）不一致 — Catalog:265-267；evidence-ui §7.4。
9. 初始 tab/自动切换由缓存订阅而非当前来源驱动 — ProxySettingsPage.kt:60-67,76-78。
10. 错误重试统一“重新识别”，刷新失败无法直接重试 — ProxySettingsPage.kt:395。
11. UnsupportedOnly 不渲染节点列表 — ProxySettingsPage.kt:400-406；PROXY.md:106。
12. 移除订阅位置在底部操作区 vs PROXY.md:62。
13. 订阅下载不走已配置代理（无代理联网环境无法导入）— Fetcher:41-122；RUNTIME:109 未说明。
14. `https://` 代理链接（TLS-to-proxy）不支持未在公开文档说明 — PROXY/PRODUCT/RUNTIME 建议补一句。
15. IDN/非 ASCII host 节点标 supported 但写回失败 — Parser:229-232；ProxySubscription.kt:38-47；NetworkProxy.kt:95-99。
16. BOM 开头订阅解析失败 — Parser:60-70,252-253,495+。
17. unsupported 链接带 `/path` 时 host/port/名称丢失 — Parser:185-191,148-181。
18. Store 任一节点非法则整份订阅静默丢弃 — Store:114-149。
19. 移除按钮 busy 时仍可点 → clear 与在途请求竞态 — UI:604-608；Catalog:236-244。
20. `resetBusyAfterCancellation` 在 unlock 后执行 — Catalog:112-115,261-263。
21. probe 未捕获 parser/classify 意外异常 — Catalog:105-111。
22. refresh 失败保留 `probed`，随后 import 可用新时间戳重写旧结果 — Catalog:119-143,154-…。
23. 手动草稿与当前设置不同步（机场生效后切手动可能旧值覆盖）— UI:68。
24. 错误块 + NotSubscription 双重提示 — Catalog:107；UI:395-410。
25. `proxy_error_not_found` 文案与使用场景不符 — Catalog:154-161；UI:657。

### P3（低/建议）

26. Parser 类注释仍称 `https` 可用 — Parser:16-18。
27. Clash `type: https → Other` 无测试；Clash 其他子集外输入（flow 整列/anchor）未测/未文档化。
28. socks4 等未列协议静默丢弃 — Parser:41-56。
29. 旧 proxy sheet 死代码保留 — SettingsPage:284-337（无入口）。
30. `docs/PROXY.md` 未进 README 文档索引。
31. 状态卡“已生效”恒显、未配置仍显示复制提示 — UI:260,279-281。
32. TalkBack 节点行缺 `selected` 语义 — UI:484-514。
33. 130%/暗色/窄屏/键盘/TalkBack 未真机验证（与 P0-1 同源）。
34. 证据文档落后：65→66 tests、https 语义、stale-link 修复、toString 脱敏均未记录；UA/512/“false→暂不支持”表述不准确；APK 时间戳/大小不可精确复现。
35. `ProxyCatalogState` 仍携带完整 `link`（toString 已脱敏，仅内存态/UI 输入需要）；`SubscriptionFormat` 未展示（无影响）。

---

## 5. 未覆盖风险 / 审计限制

1. **无 Android 设备/模拟器**：Keystore 实际行为（首次建钥耗时、锁屏/生物识别变更导致的 Key 失效、备份恢复后的密文）、IME/安全区、TalkBack、130%、暗色、Activity 重建/进程死亡恢复均未实测；仅静态阅读 + JVM 层验证。
2. **无真实机场订阅样本**：解析覆盖仓库测试 fixture 与手写样例；真实 Clash/Mihomo 全量配置、URL-safe base64 变体、非常规元数据可能仍有差异。
3. **无真实网络**：Fetcher 的 TLS/重定向/DNS/代理交互未在设备验证；且订阅下载不经过应用代理。
4. **UI 视觉与交互未验证**：嵌套滚动（页面 + 节点 LazyColumn）、底部固定区与 IME 组合、长名称/超长链接换行均只做代码审查。
5. **代理与订阅组合场景**：guest `ALL_PROXY` 凭据可见（已在 PRODUCT:24/RUNTIME:105 记录）未在本功能中改变；应用自身 SOCKS5 ATYP=3 行为依赖既有机制（既有 SocksProxyTest 覆盖）。
6. **CI/发布**：本次只验证 Windows 本地 `-x :app:prepareRuntime`；CI（Linux）会真实构建 native 制品，未在 CI 复跑；`native-runtime` 相关不属于本 feature 变更。
7. **Freeze 期间的并发编辑**：审计期间发生一次修复批次（11:28），已重新验证冻结快照；若 11:30 后还有未告知的改动，本报告不覆盖。

---

## 6. 给 Lead 的结论与建议顺序

**可提交/推送 feature 分支：是。** 冻结快照上 `check_repository`、`assembleDebug`、`lintDebug`（warningsAsErrors、fresh 分析）、`settings.*` 单测（66/66，含 `--rerun-tasks`）全部通过；运行时制品与 manifest/build-record 一致；工作树变更符合预期；未发现纯代码 P0。

**PR 合并前必须处理/确认：**

1. **P0-1**：补日/夜、360dp、130%、键盘、TalkBack 的实际截图（至少覆盖识别中/机场成功/单节点/错误/不支持/空订阅），并按 CONTRIBUTING 说明设备/主题/数据来源；或由 Lead 在 PR 中给出明确例外并记录风险。
2. **P0-2**：补 `EncryptedProxySubscriptionStore` 仪器测试（可仅 1 条覆盖 alias/AAD/损坏返回 null）与 `HttpProxySubscriptionFetcher` MockWebServer 测试（HTTPS-only/重定向降级/2MiB/取消）；若本次豁免，PR 中如实标注为已知缺口。
3. **P1-1/P1-2/P1-3/P1-5/P1-6** 建议本 PR 修（均为小改动：草稿脱敏、状态判定、select 支持 probe、返回值接错误提示、supported 条件）；**P1-4**（主线程 work）建议同 PR 或紧随其后；**P1-7** 至少补 Fetcher/Store 关键用例。
4. **更新证据**：`evidence-proxy-airport-core.md` / `-ui.md` 需按冻结代码更新（66 tests、https unsupported、stale-link guard、toString 脱敏、UA 实际值、APK 数字、lint 时点），并把本次审计报告作为独立证据附 PR。
5. 文档小修：RUNTIME:109 措辞（“界面不显示完整链接/密码” → “持久化与状态卡不显示…”）、PROXY/PRODUCT 补“https:// 代理链接不支持”“订阅下载不经已配置代理”“自动选中首个可用节点”等实现事实；README 文档索引加 `docs/PROXY.md`；P2-8/11/12 与产品确认后再决定改代码或改设计稿。

---

## 7. 证据/日志索引（审计者本次产出，均在 `.local/`，git 忽略）

| 文件 | 内容 |
|---|---|
| `.local/logs/audit-check-repository.log` | 首次（旧快照）仓库检查 |
| `.local/logs/audit-build-lint.log`（149 s）/`audit-build-lint-rerun.log`（206.6 s） | 旧快照构建+lint（含一次 123/123 全量执行） |
| `.local/logs/audit-settings-tests.log` / `audit-settings-tests-rerun.log` | 旧快照 settings 单测（后者出现 1 个 11:28 竞态失败，已废弃） |
| `.local/logs/audit-final-check-repository.log` | **冻结快照**仓库检查 exit 0 |
| `.local/logs/audit-final-build-lint.log` | **冻结快照** BUILD SUCCESSFUL 2m22s / 123 tasks（15 executed） |
| `.local/logs/audit-final-settings-tests.log` | **冻结快照** 66/0 |
| `.local/logs/audit-final-settings-tests-rerun.log` | **冻结快照** `--rerun-tasks` 66/0，53/53 executed |
| `.local/logs/AuditProbe.java` | JDK17 只读探针：U+FEFF trim、IDN host=null、IPv6 bracket、大写 scheme |
| `app/build/reports/lint-results-debug.xml` | 冻结快照 fresh lint：14 Hint / 0 error / 0 warning（11:33:00） |
| `app/build/test-results/testDebugUnitTest/TEST-dev.zakocoder.app.settings.*.xml` | 66 tests 明细（11:33:40） |
| `app/build/outputs/apk/debug/app-debug.apk` | 21,521,997 B @11:30:42（1053 entries / 17 dex） |

审计者未修改仓库任何被跟踪文件；`git status --porcelain` 与 §1.1 预期一致；`.local/build.lock` 已释放。

---

# 8. Focused 复验：新冻结点 2026-11-03T11:42（P1/P2 修复后）

> 背景：Lead 解除 11:30 冻结，对 P1-1…P1-6 + 部分 P2 做最小修复，11:42 重新冻结并请求 focused 复验。
> 本节只复验冻结树与修复项；§1–§7 为 11:30 快照审计记录（保留历史，不覆盖）。

## 8.1 新冻结点快照（BEFORE == AFTER，含一次 123/123 强制全量重建）

| 文件 | mtime | bytes | sha256 前 16 | 相对 11:30 快照 |
|---|---|---|---|---|
| settings/ProxySubscription.kt | 11:37:42 | 7606 | 867b5d50b2731a82 | 改（P1-6） |
| settings/ProxySubscriptionParser.kt | 11:37:42 | 25118 | 1bb5c6017e4738ac | 改（https/socks4/BOM/path） |
| settings/ProxySubscriptionFetcher.kt | 11:01:04 | 5623 | 9e8765c0830b55bf | 未变 |
| settings/ProxySubscriptionStore.kt | 10:38:42 | 7883 | 3ca43c6f0169775b | 未变 |
| settings/ProxyCatalog.kt | 11:37:04 | 14305 | d04a10d4378201ed | 改（P1-3/P1-4/并发） |
| ui/ProxySettingsPage.kt | 11:38:36 | 37303 | 6c565517f9034aba | 改（P1-1/2/5 + P2 UI） |
| Workbench.kt | 10:39:16 | 49185 | 7c6995f3279b270d | 未变 |
| ui/SettingsPage.kt | 10:38:20 | 27718 | 58d1c8dc2dde1af8 | 未变 |
| ui/ZakoCoderApp.kt | 10:38:23 | 14322 | 9c07114a5aee0b60 | 未变 |
| res/values/strings_proxy.xml | 11:37:52 | 6508 | e8456a79cb489ca0 | 改（反馈/重试文案） |
| test/.../ProxySubscriptionParserTest.kt | 11:39:02 | 13815 | e1c031fb185489f7 | 改（+6 用例） |
| test/.../ProxyCatalogTest.kt | 11:39:02 | 14625 | 23d8f5cc6949bf35 | 改（+1 用例） |
| test/.../ProxyProbeClassificationTest.kt | 10:49:23 | 3560 | 78e9b4197a9e3b3b | 未变 |
| test/.../ProxyTestDoubles.kt | 11:28:33 | 1883 | 3dade887596fe5cc | 未变 |
| docs/PROXY.md | 11:39:10 | 10548 | d6fbcbed9ab5f46d | 改（8.4 所列语义） |
| README.md | 11:01:10 | 18364 | fcde651ad1d6e79b | 未变 |
| docs/PRODUCT.md | 11:01:20 | 18430 | b8fd0bd3b4d80f10 | 未变 |
| docs/RUNTIME.md | 11:39:10 | 17987 | 944d57e97d3a4c71 | 改（订阅段） |

复验开始（11:42:38）与结束（11:46:40，已包含 `--rerun-tasks` 全量重建+lint）两次快照逐字节一致；复验期间无并发编辑。

## 8.2 验证矩阵（新冻结快照，持 `.local/build.lock`）

| # | 命令 | 退出码 | 结果 | 耗时 |
|---|---|---|---|---|
| 1 | `PYTHONUTF8=1 python3 scripts/check_repository.py` | **0** | OK | 0.3 s |
| 2 | `.local\dev\build.ps1 :app:assembleDebug :app:lintDebug -x :app:prepareRuntime` | **0** | BUILD SUCCESSFUL（3 executed / 120 up-to-date：Lead 11:40 初验后输出已最新） | 3.2 s |
| 2b | 同上 `--rerun-tasks`（**审计者独立强制全量**） | **0** | BUILD SUCCESSFUL，`123 actionable tasks: 123 executed` | **171 s** |
| 3 | `.local\dev\build.ps1 :app:testDebugUnitTest --tests 'dev.zakocoder.app.settings.*' -x :app:prepareRuntime --rerun-tasks` | **0** | **73 tests / 0 failures / 0 errors**，`53 actionable tasks: 53 executed` | 34.1 s |

- **测试明细（XML 11:43:15）**：NetworkProxyTest 10、**ProxyCatalogTest 18**（17+1）、ProxyProbeClassificationTest 7、**ProxySubscriptionParserTest 19**（13+6）、SocksAuthBindingTest 7、SocksProxyTest 12 = **73**。Lead 预期 66+7=73 ✓。
- **Lint（强制重跑后 fresh，11:46:40）**：14 条 issue，全部 Hint / **0 error / 0 warning**（id 与 11:33 审计一致：OldTargetApi 1、GradleDependency 5、NewerVersionAvailable 6、AutoboxingStateCreation 1、NotShrinkingResources 1）；新 UI 未引入违规。
- **APK**：21,417,042 B @11:44:28，1053 entries / 17 dex；`lib/arm64-v8a/` 4 个 .so 与 `assets/runtime-artifacts.json`（2264 B）均在。（注：debug APK 在多次构建间出现 21.4–23.2 MB 波动，属增量 dex 分片差异；已核对 libs/assets/dex 内容完整，非缺失。）
- **runtime-artifacts**：构建前后 sha256 不变，且与 `runtime/artifacts.json`/`build-record.json` 一致（proot `e7daf3d5…`、loader `32ac7e2a…`）。
- **工作树**：`git status --porcelain` 18 项（6 modified + 12 untracked），与预期一致；`.local/build.lock` 已释放。

## 8.3 P1-1…P1-6 逐项结论

| 项 | 结论 | 代码/测试证据 | 残差 |
|---|---|---|---|
| **P1-1 草稿脱敏** | **通过** | `manualDraft`/`linkDraft` 改 `remember`（ProxySettingsPage.kt:75-79，注释引用 DESIGN.md:57）；保存成功清空 manualDraft（:108-110）；识别/导入成功清空 linkDraft（:125）；移除时清空（:279） | P3：保存后 Manual 模式输入框为空、仅状态卡显示端点（下次进入模式才回填），属可用性偏好 |
| **P1-2 状态卡来源** | **通过** | `appliedNode` 用「当前 settings endpoint == 订阅节点 proxyUrl endpoint」判定（:62-71）；`currentModeRes`/`selectedSourceName` 用 appliedNode（:142-148）；初始模式只看 settings（:72）；仅当 appliedNode 非空且未手动切换才自动进机场（:87-89） | P3：手动输入与某订阅节点 endpoint 相同时仍显示“机场节点”（endpoint 匹配的固有语义，可接受） |
| **P1-3 probe 节点可选** | **通过** | `select()` 改用 `currentNodes()`（Catalog:278-283、298-304）；新增测试 `selectWorksOnProbedNodesEvenBeforeImportPersists`（CatalogTest:324-344）覆盖“probe 后可选 + import 失败后仍可选” | — |
| **P1-4 主线程/并发** | **通过** | `loadCached()` 改 `suspend` + IO、`cacheLoaded` 每会话只解密一次（Catalog:67、260-275）；parse/classify 移到 `Dispatchers.Default` 并 catch→`UnsupportedContent`（Catalog:112-121、129、214-225、239）；finally 先清 busy 再 unlock（Catalog:130-134、171-175、250-253）；generation 作废在途 probe/import（clear:287，检查:107、123、147、155、213、233）；import 在途被清则 `store.clear()` 回滚（:155-159）；refresh 失败清 probed（:204-211） | R1/R3/R4（见 8.5）：probe 的 classify 异常点与个别失败分支仍缺 generation 复核 |
| **P1-5 页内反馈** | **通过** | `applyProxyNode` 返回值被使用（UI:256-264：saved/failed/unsupported）；底部条、手动、直连三处渲染反馈（UI:681-684、189-192、211-214）；错误块按上次动作显示“重新识别/重试刷新”（:239-240、453） | R2（见 8.5）：反馈在持久化完成前先宣称成功（异步失败仍不可见） |
| **P1-6 SOCKS5 凭据判定** | **通过** | `candidateProxyUrl` 先构造 URL，`supported` 用 `NetworkProxy.parse` 验证（ProxySubscription.kt:31-57）；仅 password/仅 username → unsupported；IDN host → unsupported；HTTP 带凭据 → unsupported；测试 `socks5NodeWithOnlyPasswordIsNeverAdvertisedAsUsable`、`idnHostIsNotAdvertisedAsSupported`（ParserTest:295-308） | P3：`supported` 每次访问都 parse（512 节点列表的多次调用为微性能开销） |

## 8.4 P2 清单核对（Lead 本轮声明）

| P2 项 | 结果 | 证据 |
|---|---|---|
| UnsupportedOnly 携带节点列表并在 UI 列出禁用行 | 通过 | `UnsupportedOnly(nodes)`（ProxySubscription.kt:106-108）；`ProxyUnsupportedResult` 列出禁用行（UI:519-540） |
| 移除订阅移到列表下方 | 通过 | UI:472-476（底部条不再有移除，UI:666-698） |
| 节点行改 `selectable` 语义 | 通过 | UI:568-573（可用行 `selectable(role=RadioButton)`；不支持行 clickable 提示） |
| 错误不再叠加“无法识别”徽标 | 通过 | 结果区仅 `catalog.error == null` 时渲染（UI:453-467） |
| BOM 订阅可解析 | 通过 | Parser:65 `trimStart('\uFEFF')` + 测试 280-284 |
| 带 path 的 unsupported 链接保留 host/port | 通过 | Parser:187-195 + 测试 286-293 |
| SOCKS4/https 明确暂不支持且不降级 | 通过 | Parser:44-46、54；测试 257-278（https link + Clash https） |
| 识别失败重试识别 / 刷新失败重试刷新 | 通过 | UI:117-134、239-240 + 文案 `proxy_airport_retry_refresh` |
| 文档 PROXY/RUNTIME 同步 | 通过 | PROXY:56（https/SOCKS4 不支持）、108（预选语义）、110（重试语义）、116-118（直连下载/IDN/不降级）、120；RUNTIME:109（https/SOCKS4/直连下载/预选） |
| 自动选中首节点语义 | 文档化为产品行为（PROXY:108/117），仍需产品最终确认（非阻塞） | — |

## 8.5 本轮新发现（均非阻塞，建议 Lead 决定是否顺手修）

- **R1（P2，并发边界）**：`ProxyCatalog.probe()` 在 `mutable.update { it.copy(..., probe = withContext(Dispatchers.Default) { parser.classify(...) }) }`（Catalog:129）里做挂起调用——`update` 是内联 CAS 循环，若状态在 classify 期间被并发修改，lambda 可能重跑（重复 classify），且 `clear()` 若发生在 generation 检查（:123）与 CAS 落盘之间，重试 lambda 可把旧 probe 写回已清空的状态（内存复活窗口，store 已清）。**建议**：先 `val classified = withContext(...) { ... }`，再复核 generation，最后做纯同步 `update`（refresh 已经是这种写法，Catalog:239）。
- **R2（P2，成功提示提前）**：手动/直连/机场保存后 UI 立即显示“已保存并生效”（UI:110、260），但 `saveSettings` 是异步 `run()`（Workbench:633-655、371-386）；Keystore/偏好持久化失败只会写 Workbench message，而该 message 渲染在 Dialog 之下（ZakoCoderApp:100 vs :177），本页看不到 → 极端情况下用户被错误告知成功。**建议**：用 `LaunchedEffect(state.busy + settings)` 在保存真正结束后再显示成功/失败。
- **R3（P3）**：`loadCached()` 无 generation 复核（Catalog:260-275）；若解密期间发生 `clear()`（需要页面已有订阅才能点移除，概率极低）可能回填已清除状态。
- **R4（P3）**：`refresh()` 的两个 fetch 失败分支直接写 error/probed，未复核 generation（Catalog:204-211）；clear 期间的迟到失败会显示过期错误。
- **R5（P3）**：UnsupportedOnly 的节点行为 `enabled=false`（UI:531），点击不会触发 `onUnsupported` 的一行说明；混合机场列表里的不支持行可触发（不一致）。
- **R6（P3）**：`ProxyNode.supported` 每次 getter 调用都执行 `NetworkProxy.parse`（含 URI 分配）；`supportedCount`/行渲染/`appliedNode` 匹配会在 512 节点时重复调用，属可接受的微开销，后续可缓存。
- **R7（P3，文档措辞）**：RUNTIME:109 仍写“界面与日志不显示完整链接和密码”，而输入框必然显示用户自己输入的订阅链接/密码（现已不进入 saved state）；建议改为“持久化、状态卡与日志不显示…”。
- **R8（P3，证据滞后）**：`evidence-proxy-airport-core.md`/`-ui.md` 仍描述 65 tests 与修复前语义（https→Http、无 generation、无 toString 脱敏），未覆盖 11:28/11:37 修复与 73 tests；PR 前需更新（Lead 已计划附本审计报告）。
- **R9（P3，可用性）**：保存后 manualDraft 清空且不自动回填，切模式才回填（UI:90-93）；反馈文案随模式切换清除（同处），无功能影响。

## 8.6 结论

- **focused 复验通过**：新冻结快照上 `check_repository`、`assembleDebug`、`lintDebug`（含独立 123/123 全量重跑）、`settings.*` 73/73（含 `--rerun-tasks`）全部通过；**P1-1…P1-6 六项均按声明修复**，P2 清单 7 项均已落实或文档化；**无新 blocker**。
- 建议 Lead 在最终提交前决定是否顺手修 R1/R2（均 10 行内、低风险）；若不修，PR 中作为“已知低概率边界”注明即可。
- P0-1（真机截图）提交后补、P0-2（Keystore/Fetcher 仪器测试）PR 如实标注 → 维持约定；本复验不把它们算作代码回归。
- 审计者未 commit、未改 `app/src/**`；`.local/build.lock` 已释放；`git status` 18 项符合预期。

### 8.7 复验日志索引（`.local/logs/`，git 忽略）

`reverify-check-repository.log`、`reverify-build-lint.log`（增量 3.2 s）、`reverify-build-lint-forced.log`（123/123，171 s）、`reverify-settings-tests.log`（73/0，34.1 s）。
