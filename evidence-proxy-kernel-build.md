# 证据：本地代理内核（Xray-core）构建与端到端验证

> 分支 `feat/proxy-kernel`（基于 PR #48 的 `1efabc8`）。日期 2026-11-03。所有命令本机真实执行。

## 1. 内核选择与自编译

- 上游：<https://github.com/XTLS/Xray-core>，Go 模块 `github.com/xtls/xray-core`，固定版本 **v1.260327.0**（MPL-2.0）。
- 工具链：Go 1.26.4（`native-kernel/go.version`）。
- 构建：`GOOS=linux CGO_ENABLED=0 go build -trimpath -ldflags "-s -w"`，目标 arm64 / amd64；产物以 `libzakocoder_xray.so` 放进 `runtime-artifacts/jniLibs/<abi>/`（gitignored），由 `scripts/prepare_proxy_kernel.py` 按 `native-kernel/artifacts.lock.json` 校验版本/工具链/SHA-256，并可重建；CI 通过 `actions/setup-go` + 同一脚本自编译，不下载预编译制品。
- 产物 hash（arm64 / x86_64）记录在 `native-kernel/artifacts.lock.json`；`python3 scripts/prepare_proxy_kernel.py` 在已有产物上输出 `Verified arm64-v8a/...`、`Verified x86_64/...`（exit 0），删除后重建 hash 一致。

## 2. VMess 端到端（WSL2 Ubuntu-22.04，真实进程）

配置（测试用临时 UUID，非真实凭据）：

- server：`vmess` inbound `127.0.0.1:10086`，`alterId=0`，`security=auto`，`network=tcp`，outbound freedom。
- client：`socks` inbound `127.0.0.1:1080`（noauth，udp=false），outbound vmess → `127.0.0.1:10086`。
- 本地 `python3 -m http.server 8888` 作为被访问目标。

命令与结果：

```
./xray run -c server.json &
./xray run -c client.json &
python3 -m http.server 8888 --bind 127.0.0.1 &
curl -sS --max-time 10 --socks5-hostname 127.0.0.1:1080 http://127.0.0.1:8888/ -o /tmp/via-vmess.html -w "http=%{http_code} bytes=%{size_download}\n"
→ http=200 bytes=555
```

`xray version` → `Xray 26.3.27 (Xray, Penetrates Everything.) Custom (go1.26.4 linux/amd64)`。

具体脚本与日志未入库（`.local/dev`），本仓库不可重放到真实机场服务器；因此该验证只证明“VMess 协议 + SOCKS 转发链路”在自编译内核上可用，**不证明**任何第三方机场节点可用。

## 3. 应用内集成

- `settings/ProxyKernel.kt` 从 `applicationInfo.nativeLibraryDir` 执行 `libzakocoder_xray.so`（与既有 PRoot loader 相同的可执行 jniLib 机制），配置写 `cacheDir/proxy-kernel/`，`stop()` 销毁进程并删配置；本地 `127.0.0.1:<随机空闲端口>` SOCKS5 inbound，应用代理写回 `socks5://127.0.0.1:<port>`，复用既有 `SettingsRepository`/AI/镜像下载/guest `ALL_PROXY` 链路。
- 节点参数：vmess base64 JSON / ss SIP002 / trojan URL / Clash YAML；`ProxyKernelConfig` 生成最小 xray JSON。
- **Android 执行验证**：见下方 §4（模拟器 `nativeLibraryDir` 执行与 UI 启用流程）。

## 4. 模拟器端到端（x86_64, Android 17/API 37, 非 ARM64 真机）— 通过

- 设备：`emulator-5554`，`sdk_gphone64_x86_64`，API 37；APK 含 `lib/x86_64/libzakocoder_xray.so`（46,586,211 B，双 ABI debug 构建，`-PcompatibilityAbis=arm64-v8a,x86_64 -x :app:prepareRuntime`）。
- 夹具：Windows 主机上真实运行自编译 `xray.exe`（同版本，VMess inbound `0.0.0.0:10086`、UUID 为测试值、`alterId=0/security=auto/tcp`）+ `python -m http.server 8888`（目标内容 `kernel-e2e-ok`）。
- 仪器测试：`app/src/androidTest/.../ProxyKernelDeviceTest.kt`（opt-in：`-e proxyKernelE2E true`），断言：
  1) `applicationInfo.nativeLibraryDir/libzakocoder_xray.so` 存在（验证 jniLib 打包与 Android exec 路径）；
  2) `ProxyKernel.start(node)` 返回 `Running`；
  3) OkHttp 经 `socks5://127.0.0.1:<port>` 请求 `http://127.0.0.1:8888/`（由 VMess 服务端解析）→ HTTP 200 且 body 非空；
  4) `stop()` 后状态为 `Stopped`。
- 命令：`adb shell am instrument -w -e class dev.zakocoder.app.settings.ProxyKernelDeviceTest -e proxyKernelE2E true -e proxyKernelHost 10.0.2.2 -e proxyKernelPort 10086 -e proxyKernelHttpPort 8888 -e proxyKernelUuid 11111111-2222-3333-4444-555555555555 dev.zakocoder.app.test/androidx.test.runner.AndroidJUnitRunner`
- 结果：**`OK (1 test)`**。内核日志（cache/proxy-kernel/kernel.log）显示 `Xray 26.3.27 started` 与 `accepted tcp:127.0.0.1:... -> tcp:127.0.0.1:8888 [socks-in >> proxy]`。
- 说明：这是**自建本地 VMess 服务端**的协议/进程/代理链路验证，不是第三方机场节点、也不是 ARM64 真机；CI 不运行 connected test（测试以 instrumentation 参数 opt-in，未指定时 skip）。

## 5. 已知限制

- 非 ARM64 真机验证；内核进程随应用进程存活，系统回收后不保证接管；没有真实机场订阅服务器可用。
- VLESS/TUIC/Hysteria 未支持；无延迟测速/自动切换；APK 体积增加（arm64 内核约 29MB 未压缩）。
- Xray MPL-2.0：源码可从固定标签获取，许可证随 APK `assets/licenses/Xray-MPL-2.0.txt`。
