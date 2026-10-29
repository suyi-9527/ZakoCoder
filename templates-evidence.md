# 模板创建与运行 / 写入失败回滚 —— 验证证据（task-8）

> 仓库：`D:\deepseek\ZakoCoder`，分支 `feat/proxy-project-templates`（基线 HEAD `4dc2228`）
> 执行人：teammate `template-writer`；日期：2026-10-29（Asia/Shanghai）
> 环境：`.local/dev/build-env.md` 的 JDK17 + Gradle 8.13 + SDK36；node v24.21.0；Python 3.14.6
> Gradle 全程按 `.local/build.lock` 独占协议串行（`CreateNew` / `try-finally` 释放），日志见 `.local/logs/task8-*.log`。

---

## 1. 改动范围

| 文件 | 状态 | 说明 |
|---|---|---|
| `app/src/main/java/dev/zakocoder/app/projects/ProjectTemplateWriter.kt` | 新增（未跟踪） | 纯 JVM 写盘器：路径校验 + canonical 越界检查 + 数量/大小上限 + UTF-8 写入 + 失败回滚 |
| `app/src/main/java/dev/zakocoder/app/projects/ProjectRepository.kt` | 修改 | `create()` 去掉内联写入与重复回滚，改为 `mkdirs` 后调用 writer；`import()` 等方法未动 |
| `app/src/test/java/dev/zakocoder/app/projects/ProjectTemplateWriterTest.kt` | 新增（未跟踪） | 10 个纯 JVM 单测（临时目录 + finally 清理） |
| `app/src/main/java/dev/zakocoder/app/projects/ProjectTemplates.kt` | **未改** | 仍是 `MAX_FILES`/`MAX_FILE_BYTES`/`isSafeRelativePath` 的唯一真源 |
| `app/src/test/java/dev/zakocoder/app/projects/ProjectTemplatesTest.kt` | **未改** | 既有 7 个测试原样通过 |
| `ui/ProjectLibraryPage.kt`、`res/`、`Workbench.kt`、README/docs/依赖/CI/lint | **未改** | 按要求不碰 |

`ProjectRepository.create` 关键片段（异常语义不变：失败后整个项目目录不存在）：

```kotlin
val root = File(base, project.id)
check(root.mkdirs())
// The writer validates, writes in UTF-8 and rolls back the whole root itself on failure.
if (templateFiles.isNotEmpty()) {
    try {
        ProjectTemplateWriter.write(root, templateFiles)
    } catch (invalid: IllegalArgumentException) {
        // Surface the user-facing scaffold error while keeping the writer's rollback intact.
        throw IllegalArgumentException(context.getString(R.string.project_template_invalid), invalid)
    }
}
return project
```

> 说明：这个 catch 同时让 `R.string.project_template_invalid`（HEAD 中已存在）重新被引用，
> 从而消除 `:app:lintDebug` 的 `UnusedResources` error；回滚仍只由 writer 执行，不会双重删除。

Writer 契约（`ProjectTemplateWriter.write(root, files)`）：

1. `files.size <= ProjectTemplates.MAX_FILES`；
2. 每个路径过 `ProjectTemplates.isSafeRelativePath`（绝对路径、`\`、`:`、ISO 控制字符、`.`/`..`/空段、长度上限）；
3. 每个文件 UTF-8 字节数 `<= ProjectTemplates.MAX_FILE_BYTES`；
4. 逐段解析，对**已存在**前缀调用 `toRealPath()`（Windows 上 `File.canonicalFile` 不解析目录符号链接，实测见 §5），
   任一前缀离开 root 或最终解析回 root 本身即拒绝；
5. `Files.createDirectories` + UTF-8 `Files.write`；**任意异常 → `root.deleteRecursively()` 后原样重抛**，
   删除失败/残留时把 `IOException` 作为 `addSuppressed` 附加到原异常。

---

## 2. 测试命令与结果

命令（持 `.local/build.lock`，经 `.local/dev/build.ps1`）：

```powershell
powershell -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:testDebugUnitTest --tests 'dev.zakocoder.app.projects.*'
powershell -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:assembleDebug
powershell -ExecutionPolicy Bypass -File .local\dev\build.ps1 :app:lintDebug
```

结果（2026-10-29 本次最终一轮，完整日志 `.local/logs/task8-projects-tests.log` / `task8-assemble.log` / `task8-lint.log`）：

| 任务 | 结果 | 说明 |
|---|---|---|
| `:app:testDebugUnitTest --tests 'dev.zakocoder.app.projects.*'` | **BUILD SUCCESSFUL in 4s** | 2 个测试类共 **17 tests / 0 failures** |
| `:app:assembleDebug` | **BUILD SUCCESSFUL in 1s** | APK 重新打包成功 |
| `:app:lintDebug` | **BUILD SUCCESSFUL in 1m** | `warningsAsErrors=true` 下 0 error（此前因 `project_template_invalid` 未引用报 1 error，已修复） |

测试计数（直接读 `app/build/test-results/testDebugUnitTest/TEST-*.xml`，不依赖控制台摘要）：

```
dev.zakocoder.app.projects.ProjectTemplatesTest:      tests=7   failures=0
dev.zakocoder.app.projects.ProjectTemplateWriterTest: tests=10  failures=0
TOTAL                                                 17 tests / 0 failures
```

新增 `ProjectTemplateWriterTest` 的 10 个用例：

| # | 用例 | 断言要点 |
|---|---|---|
| 1 | `webTemplateWritesEveryFileAsUtf8` | 4 个文件落盘、内容与 `ProjectTemplates.files(Web)` 逐字节一致（含中文 README） |
| 2 | `nodeTemplateWritesDotFiles` | `.gitignore` 点文件存在、`package.json` name 为清理后的 identifier |
| 3 | `pythonTemplateWritesEntryAndManifest` | `main.py` 含 identifier、`requirements.txt`/`.gitignore` 存在 |
| 4 | `otherTemplateWritesNothing` | 空 map 不写文件、空项目目录保留 |
| 5 | `exactlyAtTheLimitsIsAccepted` | 恰好 `MAX_FILES` 个文件且单文件恰好 `MAX_FILE_BYTES` 字节时成功（边界含等号） |
| 6 | `tooManyFilesIsRejectedAndRolledBack` | `MAX_FILES+1` → 抛异常且 root 不存在 |
| 7 | `oversizedFileIsRejectedAndRolledBack` | `MAX_FILE_BYTES+1` → 抛异常且 root 不存在 |
| 8 | `pathAttacksAreRejectedAndRolledBack` | 12 种攻击路径（绝对、`..`、`\`、`:`、控制字符、空段、`.`、末尾 `/`）全部拒绝且 root 被删 |
| 9 | `writeFailureRollsBackTheEntireRoot` | 写入中断（父路径被普通文件占用）→ root 整体删除（含预先写入的 marker 文件） |
| 10 | `symlinkedDirectoryEscapeIsRejectedAndRolledBack` | root 内目录符号链接指向外部 → 拒绝，外部未产生文件（平台不支持符号链接时 Assume 跳过；本机实际执行通过） |

---

## 3. 模板创建与运行（真实 writer 产物 + 真实解释器执行）

方法：用编译后的**生产类**（`app/build/tmp/kotlin-classes/debug` 的 `ProjectTemplateWriter` / `ProjectTemplates`）
运行临时探针 `.local/dev/TemplateWriterProbe.java`（未提交），在 `%TEMP%` 下创建三个真实项目；
探针同时校验每个落盘文件与模板内容的 **UTF-8 字节完全一致**，然后对产物执行 `node` / `python3` / `node --check`。
完整输出：`.local/logs/task8-template-run.log`。

生成（模板由真实 `ProjectTemplateWriter.write` 写入临时目录）：

```text
[create] template=Web name="Demo Web" files=4
         README.md (278 B, UTF-8 bytes match)
         app.js (231 B, UTF-8 bytes match)
         index.html (487 B, UTF-8 bytes match)
         styles.css (406 B, UTF-8 bytes match)
[create] template=Node name="Demo Node" files=4
         .gitignore (33 B, UTF-8 bytes match)
         README.md (303 B, UTF-8 bytes match)
         index.js (106 B, UTF-8 bytes match)
         package.json (188 B, UTF-8 bytes match)
[create] template=Python name="Demo Python" files=4
         .gitignore (29 B, UTF-8 bytes match)
         README.md (315 B, UTF-8 bytes match)
         main.py (157 B, UTF-8 bytes match)
         requirements.txt (49 B, UTF-8 bytes match)
[create] template=Other files=0 (empty project folder preserved)
```

### 3.1 Web 模板

```text
=== Web: node --check app.js + index.html references resolve ===
node --version -> v24.21.0
node --check app.js exit code = 0
node --check index.html refs:
  styles.css -> exists=True
  app.js -> exists=True
```

说明：`app.js` 通过 Node 语法检查；`index.html` 中引用的 `styles.css` / `app.js` 均实际存在。
本机无可用的无头浏览器（Chrome headless 未安装），因此**不声称做过浏览器渲染验证**；
页面结构（`<link rel="stylesheet" href="styles.css">`、`<script src="app.js">`）与文件存在性已由上面检查覆盖。

### 3.2 Node 模板

```text
=== Node: node index.js ===
package.json name=demo-node main=index.js start=node index.js
Hello from Demo Node
node index.js exit code = 0
```

### 3.3 Python 模板

```text
=== Python: python3 main.py ===
python3 --version -> Python 3.14.6
Hello from Demo Python
python3 main.py exit code = 0
```

### 3.4 清理

```text
=== cleanup ===
temporary directory removed: True
TEMPLATE RUN EVIDENCE OK
```

探针的临时目录位于 `%TEMP%\zakocoder-template-evidence-*`，运行结束后整个删除（脚本 `finally` 兜底），
仓库内不产生任何模板产物；探针与脚本均在 `.local/`（git 忽略），`git status` 中不可见。

---

## 4. 写入失败回滚证据

### 4.1 单测（JVM，跨平台）

`writeFailureRollsBackTheEntireRoot`：在 root 中预置普通文件 `blocker` 与 `already-written.txt`，然后请求写
`blocker/nested.txt` → `Files.createDirectories` 必然失败 → 断言抛出异常且 **`root.exists() == false`**，
即整个项目目录（含预置文件）被删除，而不是只删失败的那个文件。
`pathAttacksAreRejectedAndRolledBack` / 数量 / 大小上限用例同样断言 `root` 不存在。
`ProjectTemplateWriterTest` 于本机全部 10/10 通过（见 §2 XML 计数）。

### 4.2 真实 writer 运行回滚演示

```text
[rollback] exception=java.nio.file.FileAlreadyExistsException: C:\...\rollback\blocker
[rollback] root exists after failed write = false (whole project directory removed)
```

即：真实写盘路径上发生异常后，`ProjectRepository.create` 语义保持的“失败后整个项目目录不存在”成立；
且回滚只发生一次（writer 内 `try/catch` 删除 + 重抛，`create` 不再重复 `deleteRecursively`）。

### 4.3 为什么用 `toRealPath` 而不是 `File.canonicalFile`

本机实测（`.local/dev/CanonicalProbe.java`，JDK17/Windows 11）：

```text
link canonical   = C:\...\root\link          <- 未解析符号链接（lexical）
link realpath    = C:\...\outside            <- 正确解析
target canonical = C:\...\root\link\pwned.txt <- 未解析
target parent realpath = C:\...\outside
```

Windows 上 `File.getCanonicalPath()` 不解析目录符号链接，因此 writer 改为对**已存在的路径前缀**
调用 `toRealPath()` 做越界检查；E2E 证据是 §2 的第 10 个单测（真实符号链接逃逸被拒绝）。

---

## 5. 可直接粘贴到 PR 的证据文字

```markdown
### 模板创建与运行（真实执行）

模板写入已抽成纯 JVM 的 `ProjectTemplateWriter`（路径逐段校验、canonical 越界检查、文件数/单文件大小上限、
UTF-8 写入、失败回滚），`ProjectRepository.create` 只负责建目录并调用它。

用生产代码实际生成三个模板项目并运行（Windows 11 / node v24.21.0 / Python 3.14.6）：

- Web：`node --check app.js` 退出码 0；`index.html` 引用的 `styles.css`、`app.js` 均存在（未做无头浏览器渲染验证）。
- Node：`node index.js` → `Hello from Demo Node`，退出码 0；`package.json` 的 `main`/`start` 指向 `index.js`。
- Python：`python3 main.py` → `Hello from Demo Python`，退出码 0。
- Other：生成空项目目录，不写任何文件。
落盘文件与模板内容逐字节一致（UTF-8），临时目录在验证后删除。

### 写入失败回滚（真实与单测）

- 单测 `ProjectTemplateWriterTest`（10 个用例）：`root` 内预置普通文件占住父路径使写入中途失败 →
  断言抛出异常且 `root.exists() == false`（整个项目目录连同预置文件被删除）。
- 路径攻击（绝对路径、`..`、反斜杠、`:`、控制字符、空段、`.`）、`MAX_FILES+1`、`MAX_FILE_BYTES+1` 均抛异常且 root 被回滚删除。
- 目录符号链接逃逸到 root 外 → 拒绝（用 `toRealPath` 对已存在前缀做 canonical 校验；Windows 上 `File.canonicalFile` 不解析目录链接）。
- 真实 writer 探针同样输出 `root exists after failed write = false`。

### 测试与构建

- `:app:testDebugUnitTest --tests 'dev.zakocoder.app.projects.*'`：**17 tests / 0 failures**
  （既有 `ProjectTemplatesTest` 7 个未改动 + 新增 `ProjectTemplateWriterTest` 10 个）。
- `:app:assembleDebug`：BUILD SUCCESSFUL。
- `:app:lintDebug`（`warningsAsErrors=true`）：BUILD SUCCESSFUL。
```

---

## 6. 复现命令

```powershell
cd D:\deepseek\ZakoCoder
# 1) 测试 + 构建 + lint（自动抢 .local/build.lock，串行）
powershell -ExecutionPolicy Bypass -File .local\dev\task8-gradle.ps1
# 2) 模板创建与运行证据（真实 writer → node/python → 清理）
powershell -ExecutionPolicy Bypass -File .local\dev\task8-template-run.ps1
```

产物日志：`.local/logs/task8-projects-tests.log`、`task8-assemble.log`、`task8-lint.log`、`task8-template-run.log`。
