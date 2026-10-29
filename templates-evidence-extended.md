# task-12 证据：新建项目模板扩展 C++ / Go / Rust

日期：2026-10-29 · worktree `D:\deepseek\ZakoCoder-templates` · 分支 `feat/project-templates` · 基线 HEAD `5df5c1c`
执行者：teammate `project-templates` · Lead 统一提交推送，本任务**未 commit**。

## 1. 改动文件（仅本任务范围）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `app/src/main/java/dev/zakocoder/app/projects/ProjectTemplates.kt` | 修改 | 新增 `Cpp` / `Go` / `Rust` 三个模板枚举项与内容生成；新增 `escapeDoubleQuoted` 供 Python/C++/Go/Rust 复用 |
| `app/src/main/res/values/strings_templates.xml` | 修改 | 新增 6 条字符串（3 label + 3 description） |
| `app/src/test/java/dev/zakocoder/app/projects/ProjectTemplatesTest.kt` | 修改 | 扩展为 9 个用例：7 个模板顺序、文件集/数量/大小/路径校验、Cpp/Go/Rust 内容、C++/Go/Rust 双引号转义；既有用例语义保留 |
| `ProjectTemplateWriter.kt` / `ProjectRepository.kt` | **未改** | 已能处理嵌套路径（`src/main.cpp`、`src/main.rs`），无需改动 |

说明：worktree 中 `README.md`、`docs/PRODUCT.md` 的改动是 **Lead 统一改文档** 的并行修改，不由本任务产生，未触碰。

## 2. 模板清单（顺序：Web、Node、Python、Cpp、Go、Rust、Other）

| 模板 | 生成文件（相对路径） | 关键内容 |
| --- | --- | --- |
| Cpp（C++） | `CMakeLists.txt`、`src/main.cpp`、`README.md`、`.gitignore` | CMake ≥3.20、C++17、`add_executable(<identifier> src/main.cpp)`；`<iostream>` + `Hello from <项目名>`；忽略 `build/`、`.cache/` |
| Go | `go.mod`、`main.go`、`README.md`、`.gitignore` | `module <identifier>`、`go 1.22`；`package main` + `fmt.Println`；忽略 `/bin/`、`*.exe` |
| Rust | `Cargo.toml`、`src/main.rs`、`README.md`、`.gitignore` | `[package] name = "<identifier>"`、`edition 2021`；`fn main()` + `println!`；忽略 `/target/` |

- 每个模板 4 个文件，`≤ MAX_FILES(8)`、单文件 `≤ 16 KiB`，路径均通过 `isSafeRelativePath`（生成期 + writer 落盘双重校验）。
- 标识符统一用 `identifier()`：`Go Hello` → `go-hello`；README 标题用项目名原文（纯文本）。
- 内容无外部网络依赖；C++/Go/Rust 字符串转义复用 `escapeDoubleQuoted`（`\\` 与 `"`），单测覆盖带引号的项目名。
- 4 个模板 README 均给出构建/运行命令：`cmake -S . -B build && cmake --build build`、`go run .`、`cargo run`。
- UI 无需改动：创建弹窗自动遍历 `ProjectTemplates.entries`，新增枚举项即联动。

## 3. 真实运行证据：Go（本机已安装）

工具：`go => D:\go\bin\go.exe`，`go version go1.26.4 windows/amd64`。

用 **真实代码路径**（`ProjectTemplates.files` + `ProjectTemplateWriter.write`）生成到
`D:\deepseek\ZakoCoder\.local\logs\templates-extended\go-hello`（通过一个临时 JUnit harness 调用真实 writer，
生成后删除 harness 源文件；最终测试计数已确认不含 harness）。

生成的 `go.mod`：

```text
module go-hello

go 1.22
```

生成的 `main.go`（gofmt 风格 tab 缩进）：

```go
package main

import "fmt"

func main() {
	fmt.Println("Hello from Go Hello")
}
```

真实执行结果（原样输出）：

```text
$ cd D:\deepseek\ZakoCoder\.local\logs\templates-extended\go-hello
$ D:\go\bin\go.exe run .
Hello from Go Hello
GO_RUN_EXIT=0

$ D:\go\bin\go.exe vet ./...
GO_VET_EXIT=0

$ D:\go\bin\gofmt.exe -l .
(no files need formatting)
GOFMT_EXIT=0
```

结论：Go 模板从生成到 `go run .` 端到端真实跑通，输出 `Hello from Go Hello`；`go vet` 通过；生成源码 gofmt-clean。

## 4. C++ / Rust：本机工具链限制与替代验证

本机工具探测结果（原样）：

```text
go     => D:\go\bin\go.exe
g++    => NOT FOUND
cmake  => NOT FOUND
cargo  => NOT FOUND
rustc  => NOT FOUND
```

因此 **未在本机执行** `cmake` 构建或 `cargo build/run`；以下为替代验证，不能表述为“C++/Rust 已真实编译运行”。

### C++（超出最低要求的真实编译，但不运行）

本机无 `g++`/`cmake`，但 Android SDK NDK 自带 `clang++`（NDK 23.2.8568313）。用它针对 aarch64 Android 目标做了真实编译 + 链接：

```text
$ clang++ -std=c++17 --target=aarch64-linux-android21 --sysroot <NDK>/sysroot -c src/main.cpp -o cpp-build/main.o
CPP_COMPILE_EXIT=0
$ clang++ -std=c++17 --target=aarch64-linux-android21 --sysroot <NDK>/sysroot src/main.cpp -o cpp-build/main-android
CPP_LINK_EXIT=0
$ 产物 main-android 前 4 字节 = 7f 45 4c 46 (ELF)
```

说明：这是**真实编译器语法/链接验证**，目标为 Android aarch64 ELF，**无法在 Windows 宿主机运行**，且未使用 `CMakeLists.txt`（本机无 cmake）。未安装 `g++/cmake` 的结论不变。

### Rust（仅静态校验）

无 `cargo`/`rustc`，未编译、未运行。做了：
- `Cargo.toml` 用 Python 3 `tomllib` 解析通过（合法 TOML），`package.name == "rust-hello"`、`edition == "2021"`；
- `src/main.rs` 含 `fn main()` 与 `println!("Hello from Rust Hello")`，括号/花括号配对；
- 文件集、UTF-8 编码、README 标题、`.gitignore` 内容检查。

### 静态校验脚本

`D:\deepseek\ZakoCoder\.local\logs\templates-extended\static_check.py`（真实生成物上运行）：

```text
TOTAL=26 FAILURES=0
STATIC_CHECK_EXIT=0
```

覆盖：Cpp/Go/Rust 文件集与 UTF-8、CMake 指令与 C++17、`add_executable`、C++ 源码标记与括号配对、C++ ELF 魔数、
Cargo.toml TOML 解析与字段、Rust 源码标记、Go module/go 版本/main 标记、三份 `.gitignore`、README 标题原文。

Web/Node/Python 的既有证据见 `.local/dev/project-templates.md`（task-5），本次不重复。

## 5. Gradle / 仓库验证（worktree 内直接 gradlew，抢 `.local/build.lock`）

环境：`JAVA_HOME=D:\deepseek\ZakoCoder\.local\tools\jdk-17.0.20.1+1`、`ANDROID_HOME/ANDROID_SDK_ROOT=D:\Android\Sdk`。

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :app:assembleDebug --console=plain` | **BUILD SUCCESSFUL**（FINAL_EXIT=0） |
| `.\gradlew.bat :app:testDebugUnitTest --tests 'dev.zakocoder.app.projects.*' --console=plain` | **BUILD SUCCESSFUL**；`ProjectTemplatesTest: tests=9 failures=0 errors=0 skipped=0`、`ProjectTemplateWriterTest: tests=10 failures=0 errors=0 skipped=0`（合计 19 全过） |
| `.\gradlew.bat :app:lintDebug --console=plain` | **BUILD SUCCESSFUL**（2m12s，lintAnalyzeDebug 实际重跑；warningsAsErrors 未报错） |
| `$env:PYTHONUTF8='1'; python3 scripts/check_repository.py` | `Repository structure, XML, local documentation links and ignore rules: OK`（PY_EXIT=0） |

未运行整包 `:app:testDebugUnitTest`（已知 Windows 7 个平台失败既有基线，不属本任务修复范围）；无真机/模拟器，未做 UI 截图验证（UI 仅新增枚举项，创建弹窗自动联动）。

## 6. 可直接粘贴 PR 的段落（中文）

> ### 新建项目模板扩展 C++ / Go / Rust
>
> 在“新建项目”的初始化模板中新增 **C++**、**Go**、**Rust** 三类脚手架（顺序：静态网页、Node.js、Python、C++、Go、Rust、其他），仍保留“其他 = 只建空文件夹”。
>
> - C++：`CMakeLists.txt`（CMake ≥3.20、C++17、`add_executable`）、`src/main.cpp`、`README.md`、`.gitignore`。
> - Go：`go.mod`（module 名与 `go 1.22`）、`main.go`、`README.md`、`.gitignore`。
> - Rust：`Cargo.toml`（包名、edition 2021）、`src/main.rs`、`README.md`、`.gitignore`。
>
> 模板内容继续走纯逻辑 `ProjectTemplates.files()` + 事务化 `ProjectTemplateWriter`：路径逐段校验、canonical 越界检查、UTF-8 写入、任一步失败回滚整个项目目录；标识符复用 `identifier()` 清洗，README 标题使用项目名原文，C 系字符串按 `\\`/`"` 转义。
>
> 验证：`:app:assembleDebug` 成功；`dev.zakocoder.app.projects.*` 单测 19 个全过（ProjectTemplatesTest 9 + ProjectTemplateWriterTest 10）；`:app:lintDebug` 通过；`scripts/check_repository.py` OK。Go 模板已在本机用真实 `go run .` 跑通（输出 `Hello from Go Hello`，`go vet`/`gofmt` 通过）；C++ 用 NDK clang++ 对 aarch64 Android 目标真实编译+链接通过（本机无 g++/cmake，无法宿主机运行）；Rust 以合法 TOML 解析 + 静态结构校验为准（本机无 cargo/rustc）。

## 7. 复现命令

```powershell
# 1) 证据生成（临时 harness 已删除；如需重现，可临时添加等价 JUnit 调用）：
#    ProjectTemplateWriter.write(root, ProjectTemplates.files(ProjectTemplate.Go, "Go Hello"))
# 2) Go 运行
cd D:\deepseek\ZakoCoder\.local\logs\templates-extended\go-hello
D:\go\bin\go.exe run .
D:\go\bin\go.exe vet ./...
D:\go\bin\gofmt.exe -l .
# 3) 静态校验
$env:PYTHONUTF8='1'; python3 D:\deepseek\ZakoCoder\.local\logs\templates-extended\static_check.py
# 4) 工程验证（抢锁后）
cd D:\deepseek\ZakoCoder-templates
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.zakocoder.app.projects.*' --console=plain
.\gradlew.bat :app:lintDebug --console=plain
```

证据目录：`D:\deepseek\ZakoCoder\.local\logs\templates-extended\`（生成模板、`static_check.py`、`cpp-build/` 编译产物）。
