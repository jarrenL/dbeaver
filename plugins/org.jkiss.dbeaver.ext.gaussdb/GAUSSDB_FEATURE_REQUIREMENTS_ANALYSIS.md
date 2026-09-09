# DBeaver GaussDB 功能需求分析与改造清单

> 文档状态：当前分支实现报告 / 待真实 GaussDB 与跨平台客户包验收
>
> 核对日期：2026-09-02
>
> 仓库：DBeaver Community 源码的 GaussDB 适配分支（`origin` 为 `dbeaver/dbeaver`，开发 fork 为 `jarrenL/dbeaver`）
>
> 分支：`feature/gaussdb-compatibility`
>
> 基线提交：`9ecaaa75f5907efa76c6e4f0419eb8d6e7a82a9b`
>
> 相关实现记录：[README_GAUSSDB_COMPATIBILITY.md](README_GAUSSDB_COMPATIBILITY.md)
>
> 507 分布式真库报告：[GAUSSDB_REALDB_VALIDATION_20260902.md](GAUSSDB_REALDB_VALIDATION_20260902.md)

## 1. 目的和结论

本文把以下客户需求转换为可实施的 DBeaver 改造项、验收条件和测试范围：

1. Oracle、B/MySQL 等 GaussDB 兼容模式识别、建库和功能门控。
2. 函数/存储过程断点调试、断点管理、变量监视与修改、调用堆栈、调试后提交/回滚。
3. Package 编译、编译错误定位和批量删除。
4. 在 DBeaver 内调用 `gs_dump`、`gs_restore` 完成原生备份恢复。
5. 从源码交付可运行的 macOS 和 Windows 客户端。

当前分支已经补齐本需求的代码主链路和自动化测试基线。下面的“已实现”表示源码、扩展注册和
Tycho 构建已完成；涉及服务端会话、权限、事务和桌面交互的功能，在目标 GaussDB 真库与最终
macOS/Windows 客户包验收前仍不能对外声明为“生产验收通过”。

| 需求 | 当前状态 | 本期判断 |
|---|---|---|
| A/ORA、B/MYSQL、C/TD、PG、M 模式识别及建库映射 | 已实现 | 数据库级模式门控；M 模式禁建过程；未知部署不再静默映射；保留明确的服务端值 |
| 断点调试、Step Into/Over/Return、Continue、Terminate | 主链路已通过 507 分布式真库验证 | GaussDB Debug core/UI 使用双连接协议；新增模式/语言/精确签名/权限门控已完成 507 catalog 回归，待完整 UI 冒烟 |
| 断点创建、启禁、删除和 Breakpoints 窗格 | 已通过 507 分布式真库验证 | 按 OID/行号维护映射；支持 0 起始编号并对重复位置先删后加 |
| 变量显示、修改和监视 | 已通过 507 分布式真库验证 | `info_locals`、`set_var` 已接入；watch 第一期仅支持变量名，不支持任意表达式 |
| 调用堆栈和跨函数导航 | 已通过 507 分布式真库验证 | `backtrace`、源码 OID/行号模型及编辑器 source lookup 已接入 |
| 调试结束提交/回滚 | 已通过 507 分布式真库验证 | 执行连接保持非自动提交；完成时选择提交/回滚，取消、终止和异常默认回滚 |
| Package 创建、源码读取、编辑 | 已实现 | 已补完整限定名、spec/body 状态及刷新 |
| Package 显式编译 | 已实现，待真库验收 | 支持 ALL/SPECIFICATION/BODY，执行 `ALTER PACKAGE ... COMPILE` 并刷新状态 |
| Package 编译错误自动定位 | 已实现，待真库验收 | 查询 `DBE_PLDEVELOPER.GS_ERRORS`，并解析直接 SQL 异常行号；提供行级定位 |
| Package 批量删除 | 已实现 | 复用通用多选删除，使用完整限定名和 `DROP PACKAGE [BODY] IF EXISTS` |
| `gs_dump` / `gs_restore` | 基础链路已接入 | 需补 Windows 发现/DLL 环境、参数能力、实机和多格式验收 |
| macOS / Windows 源码构建 | Tycho 目标平台已声明 | 尚缺随包 JRE、正式安装/归档、跨平台验收和发布签名流程 |

## 2. 范围边界

### 2.1 本期范围

- DBeaver Community 代码内的 GaussDB model、UI、Debug、原生工具和产品构建适配。
- 集中式、分布式两类部署；具体开放能力以运行时探测和客户声明的服务端版本矩阵为准。
- 兼容模式采用统一展示名，兼容服务端的两套 catalog 值：
  - 集中式：`A`、`B`、`C`、`PG`、`M`。
  - 分布式：`ORA`、`MYSQL`、`TD`、`PG`、`M`。
- macOS x86_64/aarch64 和 Windows x86_64/aarch64 构建目标。

### 2.2 非目标及限制

- 不把 GaussDB JDBC 驱动、`gsql`、`gs_dump`、`gs_restore` 二进制直接提交到 DBeaver 源码仓库。是否随产品分发须单独完成许可、版本和 CPU 架构评审。
- 调试器的“监视表达式”第一期只支持变量名，不承诺任意 PL/SQL 表达式求值；服务端 `print_var` 没有等价的安全通用表达式 API。
- Package 错误定位第一期只保证编辑器行号。`GS_ERRORS` 没有列号，且官方说明部分错误可能不记录或行号不精确。
- 调试期间被调过程内部主动提交、自治事务或外部副作用无法由 DBeaver 的最终“回滚”撤销。
- 无签名 macOS 应用可用于内部测试，但 Gatekeeper 可能要求用户手动放行；面向客户的无干预安装不把“无签名”作为完成标准。

## 3. 总体设计

改造仍采用“复用 PostgreSQL 通用能力 + GaussDB 专用能力层”，但不能把 GaussDB 服务端版本当作 PostgreSQL 版本比较，也不能直接复用 PostgreSQL 的 `pldbg_*` 调试协议。

```text
GaussDB Navigator / Editors
        |
        +-- 兼容模式能力层 --------> GaussDB model/edit/UI
        |
        +-- Package 编译层 --------> ALTER PACKAGE + GS_ERRORS
        |
        +-- DBeaver Debug UI ------> GaussDB Debug core
                                      |-- 执行连接 E：turn_on + CALL/SELECT + commit/rollback
                                      `-- 控制连接 D：attach + step/breakpoint/variable/stack
        |
        `-- Native Tool Tasks -----> gs_dump / gs_restore / gs_dumpall
```

能力判断优先级必须统一为：

1. 当前数据库的实际兼容模式。
2. 当前连接的部署形态。
3. catalog、函数和 API 签名的运行时探测。
4. 版本矩阵只用于提示和回退，不能替代能力探测。

## 4. 兼容模式需求

### 4.1 功能要求

| 编号 | 要求 | 验收结果 |
|---|---|---|
| COMP-01 | 连接后显示当前数据库的兼容模式和部署形态 | 与 `pg_database.datcompatibility` 及部署探针一致 |
| COMP-02 | 建库界面按部署输出正确的 `DBCOMPATIBILITY` 值 | 集中式输出 A/B/C/PG/M；分布式输出 ORA/MYSQL/TD/PG/M |
| COMP-03 | 未知或云原生部署不能静默按分布式值生成 DDL | 要求用户明确选择部署，或保留 UI 已选的原始服务端值 |
| COMP-04 | 浏览一个实例中的不同兼容模式数据库时按目标数据库门控能力 | 不能继续使用首次连接数据库的缓存值代替目标数据库值 |
| COMP-05 | M 模式不显示“创建存储过程”，函数能力独立判断 | UI 禁用且 manager 拒绝创建过程；函数保留浏览与现有创建入口，M 模式向导生成 DDL 尚未真库验收，不承诺创建兼容性 |
| COMP-06 | Package 只在服务端实际支持时显示 | 当前基线保持“集中式 Oracle 模式 + `gs_package` 能力” |
| COMP-07 | 建库模式列表按部署、版本和实测能力过滤 | 不支持的组合不显示，或显示为禁用并给出原因 |

GaussDB M 模式的存储过程限制需以客户目标版本为准；当前代码和既有 507 实测已经出现创建过程语法错误，官方迁移说明也明确列出 M 模式不支持存储过程，因此在完成反向实测前默认关闭。[GaussDB M 模式迁移限制](https://support.huaweicloud.com/intl/en-us/usermanual-ugo/ugo_conv_01_0080.html)

### 4.2 已修复问题

1. `GaussDBProcedureManager.canCreateObject` 已改为读取目标数据库能力，M 模式拒绝创建过程。
2. `DBCompatibilityEnum.getValue` 只接受明确的集中式/分布式部署；未知和云原生部署不再静默按分布式输出。
3. 表级 `ON CONFLICT`、函数/过程和 M 模式类型能力已按目标 `GaussDBDatabase` 判断，不再只依赖初始连接数据库。
4. 建库 manager 会保留已经明确选择的有效服务端兼容值，并拒绝无法确认的展示值映射。

### 4.3 需要修改的代码

| 文件/模块 | 改造内容 |
|---|---|
| `model/DBCompatibilityEnum.java` | 明确处理 `UNKNOWN/CLOUD_NATIVE`；禁止隐式落到分布式值；提供显式 deployment/value 校验 API |
| `model/GaussDBServerInfo.java` | 拆分“服务器级能力”和“数据库级兼容模式”；不要让初始数据库值成为全局唯一值 |
| `model/GaussDBDatabase.java` | 以该数据库的 `datcompatibility` 返回规范模式和数据库级 capability |
| `model/GaussDBDataSource.java` | 为目标数据库提供模式解析/刷新入口；数据库切换后清理相应缓存 |
| `model/PostgreServerGaussDB.java` | 将函数和存储过程能力拆开；全局能力只表示服务器是否具备，不替代数据库级门控 |
| `edit/GaussDBProcedureManager.java` | 使用目标 `GaussDBDatabase` 的存储过程 capability；M 模式返回 false |
| `edit/GaussDBDatabaseManager.java` | 使用 dialog 已验证的原始兼容值，未知部署时阻止生成而非重映射 |
| `ui/GaussDBCreateDatabaseDialog.java` | 增加部署明确选择、模式过滤和不支持原因；提交前校验最终服务端值 |
| `GaussDBProcedureManagerTest` 等测试 | 修正 M 模式断言，覆盖两套值、未知部署、混合模式多库和建库 SQL |

建议新增 `GaussDBCompatibilityCapabilities`，把 `supportsFunctionCreate`、`supportsProcedureCreate`、`supportsPackage` 等结果绑定到 `GaussDBDatabase`，避免继续向服务器级类堆叠兼容模式条件。

### 4.4 验收测试

- 枚举单测：五种展示名、集中式/分布式值、大小写、空值、未知值。
- 建库 SQL golden test：两类部署 × 五种模式；`UNKNOWN/CLOUD_NATIVE` 必须产生明确校验错误。
- 多数据库测试：同一 datasource 下分别加载 ORA、MYSQL、PG、M 数据库，菜单和 manager 能力随目标数据库改变。
- M 模式：函数创建可按能力开启；过程创建按钮隐藏、manager 拒绝，真库返回不再由 UI 触发。
- 真库矩阵：最低支持版本、507、当前客户最高版本 × 集中式/分布式 × 计划声明支持的模式。

## 5. 函数/存储过程调试需求

### 5.1 实现判断

当前分支已新增 GaussDB Debug core/UI/test 三个 bundle。会话没有复用 PostgreSQL 的
`pldbg_*` 协议，而是复用 DBeaver 通用 Debug 模型、视图和命令，单独实现
`DBE_PLDEBUGGER` 协议；PostgreSQL adapter 也已按 provider ID 与 GaussDB 隔离。

这些需求在服务端提供对应 `DBE_PLDEBUGGER` API 且用户具备权限时可以开发实现。官方集中式和分布式接口分别见 [集中式 DBE_PLDEBUGGER](https://support.huaweicloud.com/intl/en-us/centralized-ref-v10-gaussdb/gaussdb-38-1880.html) 与 [分布式 DBE_PLDEBUGGER](https://support.huaweicloud.com/distributed-ref-v10-gaussdb/gaussdb-08-1673.html)。实现必须运行时核对 API 签名，不能只按文档版本假设。

### 5.2 新增 bundle

#### `plugins/org.jkiss.dbeaver.ext.gaussdb.debug.core`

负责能力探测、双连接控制、服务端 API 调用、状态机、源码/栈/变量模型和事务完成。

建议主要类：

- `GaussDBDebugConstants`
- `GaussDBDebugAdapterFactory`
- `GaussDBBreakpointAdapterFactory`
- `GaussDBResolver`
- `GaussDBDebugControllerFactory`
- `GaussDBDebugController`
- `GaussDBDebugSession`
- `GaussDBDebugSessionInfo`
- `GaussDBDebugBreakpointDescriptor`
- `GaussDBDebugStackFrame`
- `GaussDBDebugVariable`
- `GaussDBDebugStopLocation`
- `GaussDBDebugCapabilityDetector`
- `GaussDBDebugTransactionState`

同时新增 `META-INF/MANIFEST.MF`、`plugin.xml`、`pom.xml`、`build.properties` 和 NLS 资源。

#### `plugins/org.jkiss.dbeaver.ext.gaussdb.debug.ui`

负责调试入口、参数输入、编辑器断点、快捷键、错误提示、源代码导航以及调试结束的提交/回滚选择。

建议主要类：

- `GaussDBDebugPanelRoutine`
- `GaussDBDebugUIAdapterFactory`
- `GaussDBDebugObjectAdapterFactory`
- `GaussDBSourceEditorAdvisor`
- `GaussDBDebugCompletionHandler` / `GaussDBDebugCompletionDialog`
- 可选的变量名 watch delegate

`plugin.xml` 需要将 configuration panel 绑定到 datasource `gaussdb`，并只在 GaussDB source editor 和 GaussDB debug context 中激活相关 adapter 与快捷键。

#### `test/org.jkiss.dbeaver.ext.gaussdb.debug.test`

用于纯单元和 mock JDBC 测试；真实数据库测试应使用独立 integration profile，避免把环境依赖混入默认测试。

### 5.3 服务端动作映射

| 用户功能 | DBeaver 动作 | GaussDB API/处理 |
|---|---|---|
| 启动调试 | Debug routine | 执行连接调用 `turn_on(oid)`，同一物理连接异步执行 `CALL`/`SELECT`；控制连接执行 `attach(node,port)` |
| 单步跳过 F8 | Step Over | `next()` |
| 单步进入 F7 | Step Into | `step()` |
| 单步退出 Shift+F7 | Step Return | `finish()` |
| 继续 F9 | Resume | `continue()` |
| 终止 F10 | Terminate | `abort()`，随后默认回滚执行连接 |
| 源码与可断点行 | Source | `info_code(funcoid)`，使用 `canbreak` 校验行 |
| 新建断点 | Add breakpoint | `add_breakpoint(funcoid,lineno)`，保存返回的 `breakpointno` |
| 删除断点 | Delete breakpoint | `delete_breakpoint(breakpointno)` |
| 启用/禁用 | Toggle breakpoint | `enable_breakpoint` / `disable_breakpoint` |
| 断点窗格同步 | Breakpoints view | `info_breakpoints()` 与 Eclipse marker 双向同步 |
| 变量列表 | Variables view | `info_locals([frameno])` |
| 变量名监视 | Watch | `print_var(var_name)` |
| 修改变量 | Set value | `set_var(var_name,value)`；仅顶层、非常量变量 |
| 调用堆栈 | Stack frames | `backtrace()`；按 frame 的 `funcoid` 打开正确源码 |
| 错误现场 | Error suspended | `error_info_locals`、`error_backtrace`、`error_end` |

### 5.4 双连接和事务状态机

1. 执行连接 E 关闭 auto-commit，调用 `turn_on(oid)`，并在同一物理 session 上异步执行 routine。
2. 控制连接 D 根据返回的 node/port 调用 `attach`；所有 step、断点、变量和 stack 命令在 D 上串行执行。
3. routine 正常完成后不立即释放 E，状态转为 `PENDING_COMPLETION`。
4. UI 提供“提交调试产生的更改”和“回滚”两个选择；默认、取消和关闭窗口均为回滚。
5. F10、attach 失败、超时、网络断开和 DBeaver 退出时，依次尝试 `abort/error_end`、rollback、`turn_off`，然后关闭 D/E；清理操作必须幂等。
6. 分布式部署还必须保证连接到同一数据库和同一 CN，连接池不能在 `turn_on` 与 routine 调用之间替换物理连接。

### 5.5 通用 Debug 框架必须修改

| 文件/接口 | 必要改造 |
|---|---|
| `org.jkiss.dbeaver.debug.core/.../DBGSession.java` | 增加事务完成和断点 enable/disable 的兼容默认接口 |
| `DatabaseDebugTarget.java` | 结束时先进入 pending completion，再 commit/rollback 和 disconnect；异常默认 rollback |
| `DatabaseVariable.java` | 实现 `setValue` 并正确报告是否可修改，失败不得伪成功 |
| `DatabaseStackFrame.java` | 修复 `stepReturn()` 误调用 `canStepReturn()` 的现有缺陷 |
| `DebugUIEventListener.java` | TERMINATE 事件先处理 pending transaction，不直接关闭相关视图和连接 |
| PG Debug adapter factories | 按 provider ID/server type 精确排除 GaussDB，避免因继承 PostgreSQL 类型而同时命中两套 adapter |
| `features/org.jkiss.dbeaver.debug.feature/feature.xml` | 加入两个 GaussDB Debug bundle |
| `plugins/pom.xml`、`test/pom.xml` | 注册新增的三个 module |

快捷键必须绑定 Eclipse 标准 Debug 命令，并限制在 GaussDB 调试上下文，防止 F7 等按键覆盖结果集或普通 SQL 编辑器已有功能。

### 5.6 调试器验收标准

- 服务端 API 不完整、签名不匹配或权限不足时，在启动前禁用 Debug 并显示缺失项，不在执行中途才失败。
- 断点可创建、启用、禁用、删除；退出并重新 attach 后服务端编号重新建立，不能持久化旧 `breakpointno`。
- F7/F8/Shift+F7/F9/F10 与 toolbar 行为一致，非调试上下文不抢占快捷键。
- Variables 显示名称、类型、值、package、常量标志；只允许服务端支持的顶层非常量变量修改。
- Stack 双击嵌套函数或 Package 例程时，按 OID 打开正确对象并定位到正确行。
- routine 正常结束可以选择提交或回滚；强制终止、异常、关闭窗口和断线默认回滚。
- 所有退出路径无悬挂 CALL、遗留 `turn_on`、连接泄漏和长期锁。
- 日志不得输出 routine 参数、敏感变量、密码或未经脱敏的服务端返回值。

### 5.7 服务端限制必须展示给用户

- 仅支持直接调用 routine；trigger 间接进入、自治事务 routine 不支持。
- 运行状态没有独立的服务端 Suspend API；只提供 step、continue、finish 和 abort。
- `set_var` 只支持服务端允许的栈层和变量，同名变量、常量和复杂类型受服务端限制。
- routine 内显式 COMMIT 和数据库外部副作用无法由最终 rollback 撤销。
- 调试可能持有业务锁；需要命令超时、取消和清理提示。
- 调试返回源码和变量可能包含敏感数据，权限校验和日志脱敏属于强制要求。

## 6. Package 管理需求

### 6.1 当前状态

`GaussDBPackage` 已能读取声明/Body 源码并在可选 catalog 不可用时降级；当前分支又补充了
完整限定对象、spec/body 状态、`ALTER PACKAGE` 编译、`GS_ERRORS` 查询、编辑器编译命令和
行级错误定位。通用 Navigator 多选删除继续复用，删除 SQL 已使用 `IF EXISTS` 和完整限定名。

### 6.2 功能要求

| 编号 | 要求 | 验收结果 |
|---|---|---|
| PKG-01 | 编译整个 Package | 执行 `ALTER PACKAGE <schema>.<package> COMPILE` 并刷新状态 |
| PKG-02 | 分别编译声明或 Body | 支持 `COMPILE SPECIFICATION` 和 `COMPILE BODY` |
| PKG-03 | 编译错误列表 | 显示对象部分、行号、消息和执行结果 |
| PKG-04 | 错误自动定位 | `package` 打开声明编辑器，`package body` 打开 Body 编辑器并定位行；列固定为 1 |
| PKG-05 | 编辑器保存行为清晰 | 保存仍执行 `CREATE OR REPLACE`；完成后只查询状态/错误，不重复执行一次 `ALTER PACKAGE` |
| PKG-06 | 批量删除 | 支持多选、确认、按对象执行、失败汇总；SQL 使用 schema 完整限定名 |
| PKG-07 | 状态展示 | 声明和 Body 状态分别显示 VALID/INVALID/UNKNOWN；可选元数据无权限时降级为 UNKNOWN |

GaussDB 提供 `ALTER PACKAGE ... COMPILE [SPECIFICATION|BODY]`，见 [ALTER PACKAGE 官方说明](https://support.huaweicloud.com/intl/en-us/centralized-ref-v10-gaussdb/gaussdb-38-0200.html)。编译错误可从 [GS_ERRORS](https://support.huaweicloud.com/centralized-devg-v8-gaussdb/gaussdb-42-1577.html) 获取，但其行号精度限制必须保留在 UI 提示中。

### 6.3 Model 改造

- `GaussDBPackage` 实现 `DBPQualifiedObject`，保证 `DBUtils.getObjectFullName(..., DDL)` 生成 `schema.package`。
- 实现或组合 `DBPStatefulObject`、`DBPRefreshableObject`，增加 spec/body 的 `VALID`、`INVALID`、`UNKNOWN` 状态。
- Package cache 从 `pg_object` 的 S/B 对象状态读取有效性；可选 catalog 缺失、无权限时只降级状态，不吞掉认证或连接错误。
- 新增：
  - `GaussDBPackageCompileTarget { ALL, SPECIFICATION, BODY }`
  - `GaussDBPackageCompileError { sourcePart, line, message }`
  - `GaussDBPackageCompiler`：生成/执行 SQL、查询错误、刷新对象状态。

错误查询建议使用 prepared statement，以 Package OID 和 namespace OID 精确关联：

```sql
SELECT type, line, src
FROM DBE_PLDEVELOPER.GS_ERRORS
WHERE id = ?
  AND nspid = ?
  AND lower(type) IN ('package', 'package body')
ORDER BY CASE lower(type) WHEN 'package' THEN 0 ELSE 1 END, line
```

删除 SQL：

```sql
DROP PACKAGE IF EXISTS <quoted_schema>.<quoted_package>
```

### 6.4 UI 改造

- 在 `org.jkiss.dbeaver.ext.gaussdb.ui/plugin.xml` 注册：
  - `org.jkiss.dbeaver.ext.gaussdb.package.compile` 命令。
  - ALL/SPECIFICATION/BODY 参数。
  - Navigator 和两个 Package 编辑器菜单/工具栏 handler。
- 新增 `GaussDBPackageCompileHandler`：
  1. 检查编辑器 dirty 状态并要求保存或取消。
  2. 后台执行编译，不阻塞 UI。
  3. 刷新 Package 状态与编译日志。
  4. 双击错误时路由到 `gaussdb.package.declaration` 或 `gaussdb.package.body`。
- 两个 Package editor 覆盖 compiler command/log 接口，使通用 `ObjectCompilerLogViewer` 展示 `DBCCompileError`。
- 增加中英文 NLS，至少包括命令、目标、成功/失败、无权限、元数据不可用和定位精度提示。
- 批量删除继续使用 Navigator 通用多选流程，但 handler 必须返回逐对象成功/失败结果；本期定义为“顺序执行、非原子”，不承诺全部回滚。

### 6.5 需要修改/新增的文件

| 文件/模块 | 改造内容 |
|---|---|
| `model/GaussDBPackage.java` | 完整限定名、状态、刷新及错误关联所需 OID/nspid |
| `model/GaussDBSchemaCache.java` | Package spec/body 状态查询和可选元数据降级 |
| `edit/GaussDBPackageManager.java` | `IF EXISTS`、完整限定删除；编译 SQL 可委托新 compiler |
| 新建 `model/GaussDBPackageCompiler.java` 等 | 编译目标、执行、错误映射和状态刷新 |
| `ui/editors/GaussDBPackageDeclareViewEditor.java` | 编译入口、日志、声明错误导航 |
| `ui/editors/GaussDBPackageBodyViewEditor.java` | 编译入口、日志、Body 错误导航 |
| 新建 UI compile handler | 菜单/后台任务/错误双击定位 |
| model/UI `plugin.xml`、MANIFEST、NLS | 注册服务、命令和用户文案 |
| GaussDB test bundle | SQL、状态、错误映射、跨 schema 批删和失败汇总测试 |

### 6.6 验收测试

- quoted schema/package、大小写和特殊字符的所有编译/删除 SQL。
- ALL/SPECIFICATION/BODY 三类命令与结果状态。
- 声明、Body、多条错误、空错误、无权限、catalog 缺失的解析和降级。
- 双击 `package` 错误打开声明，双击 `package body` 错误打开 Body，准确定位行。
- 多 schema 多选删除、取消确认、单项失败后继续、最终失败汇总。
- 集中式 Oracle 模式真库执行；其他部署/模式只在能力探测通过且纳入客户版本矩阵后开放。

## 7. `gs_dump` / `gs_restore` 需求

### 7.1 当前已实现

- DBeaver PostgreSQL 工具名已映射为 `gsql`、`gs_dump`、`gs_restore`、`gs_dumpall`。
- 已声明支持 native client，并能发现部分本地客户端。
- Linux/macOS 会把 client home 下的 `lib` 加入动态库搜索路径。
- 本地/虚拟文件可通过临时文件中转。
- backup-all 参数及密码脱敏已有基础处理。

GaussDB 的备份恢复参数和格式仍需按实际客户端版本核对，不能假定与 PostgreSQL 完全一致。服务端工具语义参考 [gs_dump](https://support.huaweicloud.com/intl/en-us/tg-gaussdb-cent-v8/gaussdb-38-0012.html) 和 [gs_restore](https://support.huaweicloud.com/intl/en-us/centralized-ref-v10-gaussdb/gaussdb-38-0942.html)。

### 7.2 剩余改造

| 文件/模块 | 改造内容 |
|---|---|
| `GaussDBDataSourceProvider.java` | Windows 常见安装目录、注册信息或 PATH 发现；识别 `.exe`；读取版本；缓存可刷新 |
| `PostgreServerGaussDB.java` | 完善 GaussDB 格式/参数 capability、可执行文件和环境变量生成 |
| `PostgreNativeToolHandler` | Windows 将 client `bin/lib` 安全加入子进程 `PATH`；路径空格、中文和特殊字符处理 |
| Backup/Restore/BackupAll handlers | 对 Gauss 不支持或语义不同的 PG 参数做显式过滤；处理取消、warning/exit code 和临时文件清理 |
| 可选 GaussDB 专属 task adapter | 当共享 PostgreSQL handler 条件持续增加时，将 Gauss 参数策略隔离，避免影响 PostgreSQL |
| `plugin.xml` / NLS | 客户端缺失、版本不匹配、格式不支持和手工选择提示 |

### 7.3 客户端策略

- 默认策略：要求用户在 DBeaver Native Client 设置中选择与服务端匹配的 GaussDB client home。
- 启动前校验 `gsql`/`gs_dump`/`gs_restore` 是否存在、可执行，并读取版本；不满足时禁用任务并给出精确原因。
- 密码不得出现在命令行和日志中，沿用安全环境变量/密码文件策略；临时凭据必须限制权限并在 finally 删除。
- macOS 使用匹配架构客户端并设置其动态库环境；Windows 同时处理 `bin`、`lib` 和 DLL 搜索 `PATH`。
- 若未来随应用打包原生客户端，必须对每个平台/架构准备独立包，并完成 GaussDB 许可和客户端-服务端版本兼容评审。

### 7.4 验收矩阵

- 平台：macOS x86_64/aarch64、Windows x86_64/aarch64；Linux x86_64 作为基线。
- 格式：plain、custom、directory、tar，仅对该版本工具实际支持的格式开放。
- 范围：全库、schema、table、schema-only、data-only。
- 选项：clean/create/no-owner、编码、压缩、SSL、口令。
- 文件：本地、虚拟文件系统、路径含空格和中文。
- 异常：用户取消、磁盘满、权限不足、错误口令、版本不匹配、warning exit code、应用异常退出。
- 恢复后用独立连接验证对象、数据、序列/权限等目标内容，而不是只检查进程 exit code。

## 8. macOS / Windows 客户端交付需求

### 8.1 当前判断

根 `pom.xml` 已声明 Windows 和 macOS 的 x86_64/aarch64 target，GaussDB model/UI 也已进入相应 target 内容，因此源码层可以生成两个平台的 Eclipse 产品。但“能编译”不等于“可直接交付客户”：

- 当前产品没有稳定的 per-arch JRE 注入流程，运行基线为 Java 21。
- macOS launcher 配置引用相对 JRE 路径；无随包 JRE 时客户机器必须自行准备正确 Java。
- 现有产物缺少正式 DMG/MSI 或可验证 portable ZIP 流程。
- 当前 macOS bundle 未通过有效 codesign/Gatekeeper 验证，也没有 notarization。
- Windows 尚无 Authenticode 和安装器验收。

### 8.2 需要修改的构建文件

| 文件/模块 | 改造内容 |
|---|---|
| 根 `pom.xml` | 明确四个平台架构产物、JRE 输入和产品 profile；固定可复现的 artifact 名称 |
| `product/community/DBeaver.product` | 校正各平台 JVM 路径、launcher 与启动参数 |
| `product/aggregate/pom.xml` | 聚合最终 app/zip，而非只提供 p2 repository 内容 |
| `tools/build.sh` / `tools/build.cmd` | 下载或注入受许可的 Java 21 runtime、校验 checksum、生成 per-platform archive |
| 新增发布脚本/CI | macOS codesign/notarize/staple；Windows Authenticode；SBOM、checksum、冷启动 smoke |
| Debug feature/product 配置 | 客户产品显式包含并启用 GaussDB Debug bundle；只编译插件但未进 product 不算交付 |

### 8.3 签名边界

- macOS 不签名并非“绝对不能运行”：内部用户可以手工放行，或在受控环境使用 ad-hoc 签名。
- 正式客户交付要求推荐 Developer ID、hardened runtime、notarization 和 staple，目标是双击可运行且 Gatekeeper 不阻断。
- Windows portable ZIP 可以不做安装器，但正式外部分发建议 Authenticode；是否提供 MSI/EXE 安装器由交付形态决定。
- 签名是发布需求，不影响 Java 代码逻辑；JRE 缺失则会直接影响启动，优先级高于签名。

### 8.4 交付验收

- 四个目标产物在没有系统 Java 的干净机器上冷启动。
- GaussDB plugin、UI、Debug feature 和驱动配置存在，能创建连接并完成 smoke query。
- Native client 未随包时，首次备份明确引导选择 client home；随包时验证架构、版本和许可清单。
- macOS 验证 codesign、Gatekeeper、notarization；Windows 验证签名、SmartScreen 行为和 portable/installer 升级卸载。
- 离线环境、非管理员用户、中文用户名/路径均完成测试。

## 9. 文件级改造总表

| 工作包 | 新增 | 修改 |
|---|---|---|
| 兼容模式 | 可选 `GaussDBCompatibilityCapabilities` | `DBCompatibilityEnum`、`GaussDBServerInfo`、`GaussDBDatabase`、`GaussDBDataSource`、`PostgreServerGaussDB`、Procedure/Database manager、Create Database dialog |
| 调试 core | 新 GaussDB debug core bundle 和约 12 个核心类 | 通用 `DBGSession`、Debug target/stack/variable，PG adapter 隔离，feature/pom |
| 调试 UI | 新 GaussDB debug UI bundle、launch panel、completion dialog、advisor | 通用 Debug UI event、feature/product 配置、NLS |
| 调试测试 | 新 `org.jkiss.dbeaver.ext.gaussdb.debug.test` | `test/pom.xml` |
| Package | compiler、target enum、error DTO、compile handler | Package/model/cache/manager、两个 editor、plugin.xml、MANIFEST、NLS |
| 原生工具 | 可选 Gauss 专属 task adapter | provider/server 和共享 backup/restore/native handlers |
| 跨平台交付 | CI/发布/签名脚本、可能的 installer 项目 | root/product/aggregate poms、product 文件、build scripts |

## 10. 测试计划

### 10.1 自动化层级

1. **单元测试**：SQL 生成、兼容模式映射、capability、状态机、结果集解析、路径/参数和脱敏。
2. **OSGi/Tycho 测试**：bundle resolve、extension 注册、adapter 隔离、NLS 和产品 feature 完整性。
3. **PDE/UI 测试**：菜单 enablement、快捷键上下文、断点 marker、错误导航、提交/回滚对话框。
4. **GaussDB 真库集成**：每个承诺版本/部署/模式上的 DDL、调试 API、Package 和 dump/restore。
5. **平台 E2E**：四个目标包在干净 OS 中启动、连接、调试 smoke、备份恢复和签名校验。

### 10.2 当前测试结果（2026-09-02）

- GaussDB model 与新增 Debug protocol/eligibility 测试共 69 个，0 failure、0 error。
- Debug Feature 完整依赖闭包共 62 个 Maven/Tycho 模块构建成功；包含测试基础设施的 64 模块 `verify` 也已通过。新 core/UI bundle 已生成 JAR，class、`plugin.xml` 和 NLS 均已进入产物。
- XML 和 `git diff --check` 通过。
- GaussDB 507 分布式真库已经完成调试、断点、变量、调用堆栈和事务完成链路验证，详见独立真库报告。
- 当前分布式环境拒绝 `CREATE PACKAGE` 且没有 `DBE_PLDEVELOPER.GS_ERRORS`；Package 编译仍需集中式 ORA 真库验收。当前也没有 Windows 主机，因此 Windows 客户包仍属于待验收项。

### 10.3 必过门禁

- 当前 HEAD 全量 GaussDB unit test 重新执行通过。
- 新增功能有失败路径测试，不能只覆盖成功命令生成。
- `mvn verify`/Tycho 对新增 bundle resolve 通过。
- 至少一套集中式和一套分布式真库完成已声明范围；未覆盖组合在 UI 中不得宣称支持。
- macOS/Windows 最终交付包分别在干净机执行 smoke，不用开发机目录或缓存兜底。

## 11. 实施优先级和依赖

### P0：本轮已完成的代码基线

1. 修复 M 模式存储过程门控、未知部署映射和数据库级能力判断。
2. 补齐 Package 编译、状态、错误定位和完整限定批量删除。
3. 补齐 GaussDB Debug core/UI/test、通用 Debug 事务/变量/Step Return 能力及 PG adapter 隔离。
4. 新 bundle 注册进 Debug Feature，并完成依赖闭包构建和 69 个自动化测试。

### P1：下一阶段真库和 UI 验收

1. Debug：在客户目标集中式/分布式版本验证 attach、快捷键、断点、变量修改、stack、提交/回滚和异常清理。
2. Package：在支持 Package 的集中式 ORA 环境验证 ALL/SPEC/BODY、状态刷新和错误行跳转。
3. Native tools：补 Windows 客户端发现/DLL PATH，并完成主要格式和全库/schema/table 的真实备份恢复。

### P2：补齐完整体验

1. Debug：finish、断点启禁、变量修改、commit/rollback 选择、错误现场 API、变量名 watch、完整快捷键。
2. Package：跨 schema 批删的完整失败汇总、更多部署/模式能力验证。
3. Native tools：虚拟文件、多选项、异常矩阵和客户版本兼容表。
4. macOS/Windows 正式签名、公证、安装器/portable 发布流程。

依赖关系：Debug 和 Package 的 UI 可以并行开发；两者都依赖真库、权限和版本矩阵。跨平台产品流水线应尽早建立，否则后期无法证明新增 bundle 已进入客户产物。

## 12. 完成定义（Definition of Done）

只有同时满足以下条件，需求表中的对应项才能从“部分支持/未支持”改为“支持”：

- 代码、extension、feature 和 product 四层均已注册，客户产物中实际包含功能。
- UI 能力与目标数据库实际模式/API/权限一致，不支持时提前禁用并说明原因。
- 成功、失败、取消、断线、无权限和清理路径均有自动化或真库证据。
- 兼容模式、部署、GaussDB 版本、JDBC 驱动和 native client 版本矩阵有明确记录。
- macOS 和 Windows 客户包在干净机器完成启动、连接和核心 smoke。
- 用户文档明确 native client 配置、调试限制、事务风险、Package 错误定位精度和签名/安装方式。

在上述条件完成前，面向客户的准确表述应为：

> DBeaver GaussDB 插件已完成本需求的兼容模式、PL/SQL 调试、Package 编译/错误定位代码主链路；前 6 项已通过 GaussDB 507 分布式真库验证。Package 编译仍待集中式 ORA 真库验收，Windows 原生工具与 macOS/Windows 正式客户安装包仍待实机发布验收。
