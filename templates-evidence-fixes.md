# task-15 证据：修复 PR #20 审查意见（Rust 格式串/包名、CMake target 命名）

日期：2026-10-29 · worktree `D:\deepseek\ZakoCoder-templates2` · 分支 `feat/templates-extended` · 基线 HEAD `726902c`
执行者：teammate `project-templates` · 未 commit（Lead 统一提交推送）。

## 1. 改动文件（仅本任务范围）

| 文件 | 说明 |
| --- | --- |
| `app/src/main/java/dev/zakocoder/app/projects/ProjectTemplates.kt` | 三处修复：Rust 固定格式串 + `escapeRustString`；新增 `rustPackageName()`；新增 `cmakeTargetName()` 并同步 C++ README 可执行文件名 |
| `app/src/test/java/dev/zakocoder/app/projects/ProjectTemplatesTest.kt` | 新增 3 个回归用例（格式串/包名/CMake target），既有 9 个用例保留并随新行为更新 Rust 断言，共 12 个 |

未改 `res/values/strings_templates.xml`（无需新文案）、未改 writer/repository、未改依赖/CI/lint/README/docs。

## 2. 逐条回应审查意见（真实工具复现 + 修复验证）

本机初始无 cmake/cargo/g++/rustc。为做真实复现，按任务许可用镜像安装了工具（见 §5）：
- CMake 4.4.4（PyPI 阿里云镜像）+ Ninja 1.13.2；
- Rust/Cargo 1.99.1（rustup 清华镜像，minimal profile）。

### 2.1 Rust 项目名 `Demo {}`：名称被拼进格式串（已复现，已修复）

**修复前**（旧模板生成内容）：

```rust
fn main() {
    println!("Hello from Demo {}");
}
```

真实 `cargo check --offline`（cargo 1.99.1）输出：

```text
error: 1 positional argument in format string, but no arguments were given
 --> src\main.rs:2:31
  |
2 |     println!("Hello from Demo {}");
  |                               ^^
error: could not compile `demo` (bin "demo") due to 1 previous error
CARGO_CHECK_EXIT=101
```

**修复后**：`ProjectTemplates.kt` 的 `rustEntry()` 改为固定格式串 + 字符串参数，新增 `escapeRustString`（处理 `\\`、`"`、`\n`、`\r`、`\t`）：

```kotlin
println!("{}", "Hello from ${escapeRustString(projectName)}");
```

生成内容与真实验证：

```rust
fn main() {
    println!("{}", "Hello from Demo {}");
}
```

```text
$ cargo check --offline   # rust-demo-braces
Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.16s
CARGO_CHECK_EXIT=0
```

### 2.2 Rust 项目名 `123` / 关键字：Cargo 包名不合法（数字开头已复现，已修复）

**修复前**：Cargo.toml 直接用 `identifier("123") == "123"`：

```toml
[package]
name = "123"
```

真实 `cargo check --offline` 输出（cargo 1.99.1）：

```text
error: invalid character `1` in package name: `123`, the name cannot start with a digit
 --> Cargo.toml:2:8
  |
2 | name = "123"
  |        ^^^^^
CARGO_CHECK_EXIT=101
```

关于关键字：cargo 1.99.1 实测 `name = "fn"/"match"/"self"/"crate"/"Self"/"super"` 的 `cargo check` 均为 exit 0（本机工具链接受包名与关键字同名，仅 crate 标识符受限）。**该情形未能在本机复现为失败**，但按审查意见仍做保守加固。

**修复后**：新增 `ProjectTemplates.rustPackageName(identifier)`：

- 只保留 `[A-Za-z0-9_-]`；
- 必须以字母开头（数字开头加 `app-` 前缀）；
- 命中 Rust 关键字/保留字（`fn`、`match`、`self`、`Self`、`super`、`crate`、`async`、`try` 等，忽略大小写）加 `app-` 前缀；
- 长度 ≤ 64（`MAX_RUST_PACKAGE_LENGTH`），空值回退 `app`。

生成与真实验证：

```toml
# 项目名 "123"      -> name = "app-123"
# 项目名 "fn"       -> name = "app-fn"
# 项目名 "Demo {}"  -> name = "demo"
```

```text
$ cargo check --offline   # rust-123
Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.16s
CARGO_CHECK_EXIT=0

$ cargo check --offline   # rust-fn
Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.16s
CARGO_CHECK_EXIT=0
```

### 2.3 C++ 项目名 `all`：CMake target 使用保留名（已复现，已修复）

**修复前**：`add_executable(all src/main.cpp)`。真实 CMake 4.4.4 + NDK 28.2 clang 工具链配置输出：

```text
CMake Error at CMakeLists.txt:7 (add_executable):
  The target name "all" is reserved or not valid for certain CMake features,
  such as generator expressions, and may result in undefined behavior.
OLD_CONFIGURE_EXIT=1
```

**修复后**：新增 `ProjectTemplates.cmakeTargetName(identifier)`：

- 避开 CMake 保留目标：`all`、`clean`、`help`、`install`、`test`、`package`、`package_source`、`edit_cache`、`rebuild_cache`、`list_install_components`、`run_tests`、`nightly`、`continuous`、`experimental`（忽略大小写）；
- 数字开头加 `app-` 前缀；
- 只保留 `[A-Za-z0-9_-]`，长度 ≤ 64（`MAX_CMAKE_TARGET_LENGTH`）；
- `project(<target>)`、`add_executable(<target> src/main.cpp)` 与 README 运行命令 `./build/<target>` 使用**同一个**安全名。

生成内容（项目名 `all`）：

```cmake
cmake_minimum_required(VERSION 3.20)
project(app-all LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(app-all src/main.cpp)
```

README 运行命令同步为 `./build/app-all`。

真实 CMake + NDK clang 配置与构建（NDK 28.2.13676358，aarch64 Android 目标）：

```text
$ cmake -S . -B build -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE=D:\Android\Sdk\ndk\28.2.13676358\build\cmake\android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 -DCMAKE_MAKE_PROGRAM=<ninja> --fresh
-- The CXX compiler identification is Clang 19.0.1
-- Check for working CXX compiler: .../clang++.exe - skipped
-- Configuring done (1.2s)
-- Generating done (0.0s)
CMAKE_CONFIGURE_EXIT=0

$ cmake --build build
[1/2] Building CXX object CMakeFiles/app-all.dir/src/main.cpp.o
[2/2] Linking CXX executable app-all
CMAKE_BUILD_EXIT=0
产物 build/app-all，5,791,472 bytes（约 5.8 MB），ELF_MAGIC=7f 45 4c 46
```

（验证目标为 Android aarch64 ELF，无法在 Windows 宿主机运行；也未使用 NDK 交叉编译运行该产物。）

## 3. 新增回归测试（ProjectTemplatesTest 12 个）

| 用例 | 覆盖 |
| --- | --- |
| `rustFormatStringKeepsProjectNameOutOfTheTemplate` | `Demo {}`：main.rs 必须含 `println!("{}", "Hello from Demo {}")`，禁止 `println!("Hello from` 形式；`{{}}{{}}` 也不得注入占位符；Cargo.toml name 合法 |
| `rustPackageNameAvoidsNumericAndKeywordNames` | `123/fn/match/self/Self/my-project` 直接调用与 Cargo.toml 双向断言：字母开头、仅合法字符、非关键字、≤64；并验证 `rustPackageName(identifier(name))` 与生成值一致 |
| `cmakeTargetAvoidsReservedTargetsAndMatchesReadme` | 全部保留目标均映射为 `app-*`；数字开头/空值；从生成的 CMakeLists 提取 target，断言非保留、`project()` 与 `add_executable()` 一致、README `./build/<target>` 一致且不含 `./build/all` |
| 既有 9 个用例 | 全部保留；其中 Rust 断言随固定格式串行为更新为 `println!("{}", "Hello from ...")`，未削弱覆盖面 |

测试计数（Gradle 报告 XML）：

```text
dev.zakocoder.app.projects.ProjectTemplatesTest:      tests=12 failures=0 errors=0 skipped=0
dev.zakocoder.app.projects.ProjectTemplateWriterTest: tests=12 failures=0 errors=0 skipped=0
合计 24 / 24 通过
```

## 4. 工程验证（worktree 内直接 gradlew，抢 `D:\deepseek\ZakoCoder\.local\build.lock`）

环境：`JAVA_HOME=D:\deepseek\ZakoCoder\.local\tools\jdk-17.0.20.1+1`、`ANDROID_HOME/ANDROID_SDK_ROOT=D:\Android\Sdk`。

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :app:assembleDebug --console=plain` | **BUILD SUCCESSFUL** |
| `.\gradlew.bat :app:testDebugUnitTest --tests 'dev.zakocoder.app.projects.*' --console=plain` | **BUILD SUCCESSFUL**（12+12=24 全过） |
| `.\gradlew.bat :app:lintDebug --console=plain` | **BUILD SUCCESSFUL**（1m16s，lintAnalyzeDebug 实际重跑，warningsAsErrors 无 error） |
| `$env:PYTHONUTF8='1'; python3 scripts/check_repository.py` | `Repository structure, XML, local documentation links and ignore rules: OK`（FINAL_EXIT=0） |

## 5. 工具安装记录与限制（如实）

- CMake/Ninja：`python3 -m pip install --user cmake ninja -i https://mirrors.aliyun.com/pypi/simple/` → 成功（cmake 4.4.4、ninja 1.13.2，安装在用户 Scripts 目录，未改 PATH 全局）。CMake 为 PyPI 版、非 Android SDK 内置，但 target 命名语义与 CMake 行为一致。
- Rust：`rustup-init.exe` 从清华镜像下载（`https://mirrors.tuna.tsinghua.edu.cn/rustup/...`），以 `RUSTUP_DIST_SERVER=https://mirrors.tuna.tsinghua.edu.cn/rustup`、独立 `CARGO_HOME/RUSTUP_HOME`（`.local/logs/templates-fixes/rustup`）安装 stable minimal，成功（rustc/cargo 1.99.1）。
- **`cargo build` 无法完成链接**：本机无 MSVC `link.exe`（`error: linker link.exe not found`，exit 101），因此 Rust 侧真实验证使用 `cargo check --offline`（编译 + 格式串/包名校验，不链接）。这是本机限制，不是模板问题。
- `g++` 仍不可用；C++ 用 NDK 28.2 clang++（经 CMake Android toolchain）完成真实配置+编译+链接。
- 未做真机/模拟器 UI 验证；本次仅改模板生成逻辑与测试，UI 无改动。
- 复现/验证输出目录：`D:\deepseek\ZakoCoder\.local\logs\templates-fixes\`（`cpp-all`、`rust-demo-braces`、`rust-123`、`rust-fn`、`repro-*`、`rustup/`）。临时 JUnit harness 已删除，最终测试计数不含它。

## 6. 可直接粘贴 PR 的回复段落（逐条回应）

> 感谢审查，三个问题均已修复并用真实工具链复现/验证：
>
> 1. **Rust `Demo {}` 格式串注入**：`rustEntry` 已改为固定格式串 + 参数 `println!("{}", "Hello from <escaped>")`，项目名只出现在字符串参数里，并新增 `escapeRustString`（`\\`、`"`、`\n`、`\r`、`\t`）。修复前 `cargo check --offline` 报 `1 positional argument in format string, but no arguments were given`（exit 101）；修复后同一项目 exit 0。
> 2. **Cargo 包名边界**：新增 `rustPackageName()`——数字开头、Rust 关键字/保留名（`fn`/`match`/`self`/`super`/`crate` 等）统一加 `app-` 前缀，仅允许 `[A-Za-z0-9_-]`、字母开头、≤64，空值回退 `app`。修复前 `name = "123"` 报 `invalid character '1' in package name ... cannot start with a digit`（exit 101）；修复后生成的 `app-123`、`app-fn`、`demo` 均 `cargo check --offline` 通过。说明：本机 cargo 1.99.1 实测接受关键字包名（未复现失败），此条为按审查要求做的保守加固。
> 3. **CMake 保留 target**：新增 `cmakeTargetName()`，避开 `all`/`clean`/`help`/`install`/`test`/`package` 等保留目标、数字开头加前缀，并让 `project()`、`add_executable()` 与 README 的 `./build/<target>` 使用同一名称。修复前 `add_executable(all ...)` 在真实 CMake 中报 `The target name "all" is reserved...`（exit 1）；修复后 `cmake -S . -B build` + `cmake --build build`（CMake 4.4.4 + NDK 28.2 clang，aarch64）配置/构建均 exit 0，产物 ELF 生成。
>
> 回归：`ProjectTemplatesTest` 12 个 + `ProjectTemplateWriterTest` 12 个全部通过；`:app:assembleDebug`、`:app:lintDebug` 成功，`scripts/check_repository.py` OK。本机无 MSVC linker，Rust 代码用 `cargo check --offline` 验证而非链接运行；C++ 已用 NDK clang 真实编译链接。

## 7. 复现命令

```powershell
# 生成模板证据（临时 JUnit 调用真实 ProjectTemplates.files + ProjectTemplateWriter.write）
# Rust 校验
$env:CARGO_HOME='D:\deepseek\ZakoCoder\.local\logs\templates-fixes\rustup\cargo'
$env:RUSTUP_HOME='D:\deepseek\ZakoCoder\.local\logs\templates-fixes\rustup\rustup-home'
$env:Path="$env:CARGO_HOME\bin;$env:Path"
cd D:\deepseek\ZakoCoder\.local\logs\templates-fixes\rust-demo-braces; cargo check --offline
cd ..\rust-123; cargo check --offline
cd ..\rust-fn; cargo check --offline
# C++ 配置/构建
cd ..\cpp-all
C:\Users\suyi\AppData\Roaming\Python\Python314\Scripts\cmake.exe -S . -B build -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE=D:\Android\Sdk\ndk\28.2.13676358\build\cmake\android.toolchain.cmake `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 `
  -DCMAKE_MAKE_PROGRAM=C:\Users\suyi\AppData\Roaming\Python\Python314\Scripts\ninja.exe --fresh
C:\Users\suyi\AppData\Roaming\Python\Python314\Scripts\cmake.exe --build build
# 工程验证（worktree 内，抢锁后）
cd D:\deepseek\ZakoCoder-templates2
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.zakocoder.app.projects.*' --console=plain
.\gradlew.bat :app:lintDebug --console=plain
$env:PYTHONUTF8='1'; python3 scripts/check_repository.py
```
