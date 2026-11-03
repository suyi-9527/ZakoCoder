# 证据：网络代理与机场订阅（最终实现）

> 分支 `feat/proxy-airport`，基线 main `7c359c8`（v0.1.0）。设计规格见 `docs/PROXY.md`。
> 本文件替代此前 `evidence-proxy-airport-core.md` / `evidence-proxy-airport-ui.md` 中已过时的数字，作为 PR 证据。

## 1. 功能

- 「设置 → 网络代理」由单字段弹层改为独立页面：状态卡（直连/手动/机场节点、打码端点、来源）、三模式分段（直连/手动代理/机场订阅）、固定底部唯一主 CTA。
- 机场订阅：粘贴/输入 HTTPS 链接 →「识别链接」→ 结果徽标（机场订阅/单节点/暂不支持/无法识别）+ 节点列表（协议短标、host:port、可用单选、暂不支持灰化）→ 选节点 →「保存并启用」写回现有代理设置（SOCKS5 凭据沿用现有 Keystore 链路）。
- 支持解析：纯文本链接列表、Base64 链接列表、Clash/Mihomo YAML `proxies:` 子集（inline/block）。可启用协议仅 HTTP（无凭据）与 SOCKS5（可带 RFC1929 凭据）。
- 安全/边界：仅 HTTPS；显式点击才联网（打开页面只读加密缓存）；订阅与节点凭据用 Android Keystore AES/GCM 加密保存、独立 alias/AAD/schemaVersion；界面与日志不显示完整链接/密码；响应 ≤2 MiB、最多 512 节点；`https://`(TLS-to-proxy)、SOCKS4、VMess/SS/Trojan/VLESS/Hysteria/TUIC 标注暂不支持且不降级；IDN 主机不可启用；单节点不支持仍列出但不提供保存。

## 2. 审查后修复（P1/P2）

- 认证/代理层（上一轮合并前）：`https` 节点不再降级为 `http://`；识别在途修改链接会丢弃旧结果；`ProxyCatalogState.toString` 脱敏。
- P1-1：`manualDraft`/`linkDraft` 不再进 `rememberSaveable`，保存/导入成功后清空草稿（避免明文密码/订阅 token 进 saved state）。
- P1-2：状态卡用“当前设置 endpoint 与订阅节点 endpoint 匹配”判定机场来源与“已生效”，不再把草稿选择当作已生效；初始模式不因本地旧订阅误判。
- P1-3：`select()` 支持最近 probe 的节点，导入失败也不会出现不可选/CTA 死锁。
- P1-4：`loadCached()` 改为 `suspend` + `Dispatchers.IO`（每会话只解密一次）；解析/分类移入 `Dispatchers.Default` 并捕获异常→`UnsupportedContent`。
- P1-5：`applyProxyNode` 返回值用于页内反馈（已保存/无法保存/协议不支持）；错误重试区分“重新识别/重试刷新”。
- P1-6：SOCKS5 仅 password（或仅 username）不再 supported；`supported/proxyUrl` 统一经 `NetworkProxy.parse` 验证，IDN 主机不可启用。
- P2：BOM 订阅可解析；带 path 的 unsupported 链接保留 host/port；重复/未知协议处理；`clear()` 用 generation 作废在途 probe/import 并回滚 store；`finally` 先清 busy 再 unlock；UnsupportedOnly 列出节点；移除订阅移到列表下方；节点行 `selectable` 语义。

## 3. 验证矩阵（最终树，Lead 初验）

| 检查 | 结果 |
| --- | --- |
| `PYTHONUTF8=1 python3 scripts/check_repository.py` | exit 0，OK |
| `:app:assembleDebug :app:lintDebug -x :app:prepareRuntime` | BUILD SUCCESSFUL（2m36s，123 tasks；lint warningsAsErrors 通过） |
| `:app:testDebugUnitTest --tests 'dev.zakocoder.app.settings.*' -x :app:prepareRuntime` | **73 tests / 0 failures / 0 errors**（NetworkProxyTest 10 + ProxyCatalogTest 18 + ProxyProbeClassificationTest 7 + ProxySubscriptionParserTest 19 + SocksAuthBindingTest 7 + SocksProxyTest 12） |
| APK | `app/build/outputs/apk/debug/app-debug.apk` 23,249,206 B |
| 全量单测 | 见 PR（Windows 环境既有失败；CI Ubuntu 为准） |
| 独立审计 | `audit-proxy-airport.md`（build-env，未参与实现；含冻结哈希与复验章节） |

## 4. 构建环境说明（Windows）

main 自 v0.1.0 起要求 Linux 自编译 PRoot（`native-runtime/build.py` 使用 `fcntl` 且 `toolchain.lock.json` host=linux-x86_64）。本机在 WSL2 Ubuntu-22.04 内用官方 Linux NDK r28c 执行：

```
python3 scripts/prepare_runtime.py --ndk ~/ndk-extract/android-ndk-r28c --abi arm64-v8a
→ Self-built PRoot and loader: verified and prepared
```

产物 sha256 与 `runtime/artifacts.json`、`native-runtime/build/build-record.json`（fingerprint `3b28a2c0…7e003e`）一致；Windows 侧验证使用 `-x :app:prepareRuntime` 复用已校验制品。未修改构建/校验脚本，未伪造 hash；Linux/CI 不走该变通。

## 5. 已知缺口（如实）

- 无 ARM64 真机/仪器测试；UI 截图与 TalkBack/130%/窄屏交互由提交后的模拟器取证补充（x86_64、临时 ABI、非目标真机）。
- Keystore 存储与 OkHttp Fetcher 无 JVM/仪器测试（Keystore 无法在 JVM 测；Fetcher 需 MockWebServer 属 androidTest 依赖，未新增依赖）；由 FakeFetcher/FakeStore 覆盖 Catalog 状态机，代码审查覆盖其余。
- 订阅下载不使用已配置代理（避免循环依赖）；不自动更新/测速；已知限制均写入 `docs/RUNTIME.md` 与 `docs/PROXY.md`。
