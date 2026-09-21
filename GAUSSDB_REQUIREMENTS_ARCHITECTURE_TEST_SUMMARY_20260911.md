# GaussDB 适配版 DBeaver：需求、架构、实现及测试总结

整理日期：2026-09-11。实现与验收基线：`feature/gaussdb-compatibility`，提交 `26688a344254a29677edfd06076345c0fc6d71fe`，DBeaver 26.2.0。

本文供需求评审、研发交接和客户交付说明使用。依据当前源码、2026-09-02/05/09 各轮测试报告及已归档证据整理；**本次是资料核对与文档编写，没有在 9 月 11 日重新执行全部测试**。表内“通过”均受环境和测试层次限定。

## 1. 原始需求与最终范围

### 1.1 原始七项

| 编号 | 原始需求 | 拆解后的验收点 |
| --- | --- | --- |
| R1 | 支持 Oracle/B/MySQL 兼容模式 | 识别模式；建库模式映射；目标数据库级功能控制；M 模式限制；不同模式不误用首次连接缓存 |
| R2 | 函数/存储过程断点调试 | 创建配置、绑定参数、启动暂停；F7 进入、F8 跳过、Shift+F7 退出、F9 继续、F10 终止 |
| R3 | 断点管理 | 设置/创建、启用、禁用、删除；断点窗格管理；重加、重复添加和服务端编号映射 |
| R4 | 变量监视 | 查看当前变量；手动修改；添加 Watch；失败改值、常量、不同栈帧变量的保护 |
| R5 | 调用堆栈 | 显示父子调用链；双击不同帧打开正确函数源码；跨 schema 同名对象不串位 |
| R6 | 调试后提交/回滚 | 正常完成后用户选择提交或回滚；选择与数据库真实数据一致；取消、终止及异常安全清理 |
| R7 | 编译包、错误定位、批量删除 | ALL/SPEC/BODY 编译；对象状态；多错误与源码部分归属；自动/双击定位；多选删除 |

### 1.2 后续追加需求

- 原生工具：在 DBeaver 内对接 `gsql/gs_dump/gs_restore/gs_dumpall`，关注备份脱敏不破坏业务数据。
- 兼容能力加强：M/未知模式、routine 语言、调试角色、目标 EXECUTE 权限、17 个 API 的精确签名与执行权限。
- 共享代码回归：PostgreSQL 原生调试不能因 GaussDB 改动回退。
- 边界验收：跨 schema、并发包编译、取消/断连、异步源码加载、关闭窗口后的迟到回调。
- 最终交付：客户明确为**麒麟 V10 服务器版**，CPU 不明，因此提供 ARM64 与 x86_64 两种客户端；Windows 后续明确不要求本次验证。

### 1.3 当前结论

七项均有代码实现，且在既有 GaussDB 507 环境完成相应协议、模型或 GUI 场景。麒麟双架构已生成可分发归档，完成核心 GUI、干净安装启动、保存连接恢复、重连、退出及归档校验。

“完成”不等于所有数据库版本、所有兼容模式的所有 SQL、所有菜单组合和客户物理硬件均已认证。主要约束：包仅在支持它的集中式 ORA 环境验证；M 不启用 PL 调试；Watch 仅变量名；Linux 使用容器用户空间，x86_64 为仿真执行。

## 2. DBeaver 架构与本次改造位置

### 2.1 分层结构

```text
用户：导航树 / SQL与对象编辑器 / Debug视图 / 包编译结果窗口
                         │
          Eclipse RCP + SWT/JFace（桌面UI、命令、视图、快捷键）
                         │
          DBeaver 通用模型 / 导航 / 编辑器 / debug.core + debug.ui
                    ┌────┴───────────────┐
                    │                    │
       GaussDB model + UI        GaussDB debug.core + debug.ui
       模式、元数据、DDL、包       能力探测、双会话、断点、栈、变量
                    │                    │
                    └──────── JDBC ──────┘
                               │
                    GaussDB catalog / SQL / DBE_PLDEBUGGER

原生备份恢复：DBeaver任务 → 本机可执行文件 gs_dump/gs_restore → 数据库
构建交付：Maven/Tycho → OSGi bundles/features → OS/CPU产品 → 原生启动器+SWT+JRE
```

这不是独立重写一个数据库客户端，而是在 DBeaver CE 的插件扩展点上增加 GaussDB 专用适配，并修复少量共享框架问题。

### 2.2 主要组件职责

| 组件 | 职责 | 本次如何使用 |
| --- | --- | --- |
| Eclipse Equinox / OSGi | 插件加载、依赖和扩展注册 | `MANIFEST.MF` 声明依赖；`plugin.xml` 注册 provider、对象、命令、调试面板 |
| SWT / JFace / Workbench | 原生桌面控件、菜单、编辑器、窗口生命周期 | 不自建断点/变量/栈整套窗口，复用现有视图；新增包编译结果交互 |
| DBeaver model / model.jdbc | 数据源、数据库对象、执行上下文、JDBC会话 | 数据库模式和对象能力放在模型层，SQL与连接管理不放进控件代码 |
| `org.jkiss.dbeaver.ext.postgresql` | PostgreSQL系元数据和通用方言基础 | GaussDB继承适用部分，不把服务端所有行为假定为原生PG |
| `org.jkiss.dbeaver.ext.gaussdb` | GaussDB模型和对象管理 | 模式、部署探测、函数/过程、包、编译器、原生工具映射 |
| `org.jkiss.dbeaver.ext.gaussdb.ui` | GaussDB专用UI | 建库、包声明/主体编辑器、编译命令、结果列表与定位 |
| `org.jkiss.dbeaver.debug.core/.ui` | 数据库调试抽象与Eclipse调试桥接 | 会话、栈帧、变量、断点、Watch和完成事务事件 |
| `org.jkiss.dbeaver.ext.gaussdb.debug.core/.ui` | GaussDB调试协议和UI适配 | 接入DBE_PLDEBUGGER；不复用PG的pldbgapi协议 |
| `test/` | JUnit/Mockito、协议程序、SWTBot测试 | 测试插件与客户产品隔离 |
| `features/`、`product/` | 组件选装、跨平台组装 | 构建Linux两架构，附匹配JRE与麒麟兼容原生库 |

模型层不依赖 SWT；UI通过模型执行功能。Java目标为21，构建使用Maven/Tycho及P2依赖；GTK/SWT、启动器、JRE含本机代码，所以“Java跨平台”不等于任意Linux包直接能运行。

### 2.3 为什么不是拿数据库PID做gdb调试

gdb调试的是数据库进程的C/C++机器指令、线程和内存，通常需要系统级权限。这里要调试的是数据库内部执行的PL过程语句。

DBeaver通过JDBC调用数据库提供的调试API，由数据库服务端负责暂停、单步、断点和变量访问，客户端负责呈现与控制。数据库PID/会话号用于识别，不意味着附加gdb；无须客户提供数据库引擎的本地调试符号。

## 3. 每个小需求如何实现

### 3.1 R1：兼容模式与能力门控

| 小需求 | 具体实现 | 主要代码 |
| --- | --- | --- |
| 双表示法识别 | `DBCompatibilityEnum`将A/ORA、B/MYSQL、C/TD、PG、M归一化；结合`sql_compatibility`与数据库目录信息 | `DBCompatibilityEnum`、`GaussDBServerInfo`、`GaussDBDatabase` |
| 建库选模式 | 建库对话框收集模式；manager按明确部署输出`DBCOMPATIBILITY`；未知部署不静默套用另一套值 | `GaussDBDatabaseManager`及建库UI |
| 同实例不同库 | 能力绑定当前目标`GaussDBDatabase`，不沿用最初连接库模式判断所有对象 | `GaussDBDatabase`、`GaussDBDataSource` |
| M模式过程限制 | 过程树/创建能力与manager校验配合；调试资格先拒绝M，避免先访问该模式不支持的catalog表达式 | `GaussDBProcedureManager`、`GaussDBDebugCore` |
| 函数与过程分开 | SQL函数的存在不代表可PL调试；语言与目标对象资格独立检查 | `GaussDBFunctionManager`、调试对象adapter |
| 包开放条件 | 集中式ORA与包目录能力共同判断；不能仅见到`gs_package`就声称支持编译 | `GaussDBDatabase.isPackageSupported` |
| 其他模式差异 | ON CONFLICT、PL DDL形式、M类型映射按相关能力分支处理 | GaussDB方言、过程manager、`PostgreServerGaussDB` |

注意：M函数树可浏览不等于M过程可创建，也不等于所有M函数创建DDL已通过真库验收。

### 3.2 R2：调试入口、双会话与快捷键

入口首先按provider和GaussDB routine类型路由；UI资格控制与启动时服务端校验配合，不能只看函数名是否存在。

启动检查包括：目标库模式、持久化routine及语言、17个所需API的参数类型、API EXECUTE、目标routine EXECUTE，以及管理员身份或`gs_role_pldebugger`成员资格。普通用户可能看不到内置角色的目录行，因此用`pg_has_role`核实成员资格，不以“pg_roles查不到角色”直接拒绝。

```text
执行连接E：autoCommit=false → turn_on(routineOID) → 异步CALL/SELECT → 服务端暂停
                                      │ 返回调试端点
控制连接D：autoCommit=true  ────────────→ attach(node,port)
                                      → step/next/finish/continue、断点、变量、栈
执行完成：E保留事务 → UI选择Commit/Rollback → E提交/回滚 → 关闭/turn_off清理
```

必须两条独立连接：目标语句暂停时其JDBC调用仍在等待，不能用同一个忙碌连接再发送继续命令。`GaussDBDebugSession`串行化相关控制与快照更新，避免栈视图和Watch并发访问造成重复帧。

| 用户动作 | 服务端动作（DBE_PLDEBUGGER命名空间） | 客户端处理 |
| --- | --- | --- |
| 启动 | `turn_on(oid)`、`attach(text,integer)` | 参数化执行CALL/SELECT；D0011仅做有界重试，支持取消/目标提前结束 |
| F7进入 | `step()` | 刷新停止位置、栈和变量 |
| F8跳过 | `next()` | 不进入子routine，显示下一暂停位置 |
| Shift+F7退出 | `finish()` | 返回调用者，刷新调用链 |
| F9继续 | `continue()` | 运行到断点或结束，区分完成与异常标记 |
| F10终止 | `abort()`及会话清理 | 用户终止归为取消，不误弹执行失败；未提交事务回滚 |

主要代码：`GaussDBDebugSession`、`GaussDBDebugCapabilityDetector`、`GaussDBDebugController`、`GaussDBDebugPanelRoutine`、`GaussDBDebugContextActivator`及调试UI的`plugin.xml`。快捷键限定相应调试上下文，不全局抢占所有编辑器操作。

### 3.3 R3：断点管理

- Eclipse marker/断点窗格保存本地描述符，描述符包含数据源、routine OID、正文行号和启用状态。
- `info_code(oid)`的`canbreak`校验可断点行；`add_breakpoint`返回服务端编号，保存映射后才更新状态。
- 507实际支持`add_breakpoint(oid,integer)`；同时支持参考版本的`text,integer`重载，选取有EXECUTE权限的匹配签名。
- `serverId=-1`表示未注册，**0是合法编号**；删除后重加使用服务端新返回编号，不自行推算或复用旧号。
- 同一OID+行重复添加先删除已注册位置再新增，避免旧编号失效后残留本地映射。
- 初始化仅安装启用且全局开关允许的断点；初始禁用断点首次启用时补注册。未注册断点的disable/delete按幂等方式处理。
- `enable_breakpoint`、`disable_breakpoint`、`delete_breakpoint`与本地列表联动；修改跨函数断点时保留目标OID，不误用启动函数OID。

主要代码：`GaussDBDebugBreakpointDescriptor`、`GaussDBDebugSession`与共享断点adapter/model。

### 3.4 R4：变量与Watch

`info_locals(frameNo)`返回指定帧变量；`GaussDBDebugVariable`转换为DBeaver变量模型。修改通过`set_var(name,value)`，检查布尔结果，成功后重新读取服务器值，失败保留原值。常量及非当前执行帧变量只读，因为该服务端改值API没有帧号参数。

Watch使用共享`DatabaseWatchExpressionDelegate`：去除输入两端空白后，在当前选中栈帧的变量列表中按名称匹配并显示值。**不是任意表达式求值，也不是当前代码直接调用print_var。**“set_var可接受服务端表达式”与“Watch支持任意表达式”是不同能力。

会话结束后安全处理空session；找不到变量或没有合适暂停帧时给出错误/不可用状态，不伪造值。507的NULL显示可能为`<UNKNOWN>`，保留服务端语义展示。

### 3.5 R5：调用栈与源码定位

`backtrace()`构建`GaussDBDebugStackFrame`。每一帧携带真实routine OID、行号等信息，双击时按该帧OID解析对象而非沿用启动对象。

跨schema解析先以`pg_proc`的OID找到namespace，再读取GaussDB专用函数/过程cache；不单凭名称匹配，也不遍历全部系统schema。源码编辑器使用调试正文，避免CREATE FUNCTION头部导致行号偏移。

共享`DatabaseLazyEditorInput`保留并转交输入属性，`NavigatorHandlerObjectOpen`向通用数据库编辑器输入应用属性，修复“重启恢复旧编辑器后没有调试正文属性”的问题。

### 3.6 R6：事务选择与异常清理

执行连接禁用自动提交。正常完成置“待事务处理”状态，由共享`DebugUIEventListener`弹出Commit/Rollback选择，`GaussDBDebugSession.completeTransaction`在**原执行连接**提交或回滚。控制连接的事务不是业务调试事务。

取消/关闭选择、F10或会话清理优先回滚未提交变更。507运行期错误标记`[EXECUTION HAS ERROR OCCURRED!]`出现后，控制API可能连abort也拒绝；关闭目标连接释放等待并回滚，目标任务仍运行时不盲目发送turn_off。

限制：过程自行提交、自治事务及调用外部系统的副作用不保证被最后一次Rollback撤销。

### 3.7 R7：包编译、诊断与批量删除

| 小需求 | 实现 |
| --- | --- |
| 三种编译 | `GaussDBPackageCompileTarget`区分ALL/SPECIFICATION/BODY；编译器生成完整限定名SQL；三种命令均有菜单入口 |
| 源码与编译分离 | 保存执行CREATE OR REPLACE，显式编译执行ALTER PACKAGE；不把编译包等同于调用包内业务函数 |
| 对象状态 | 查询`pg_catalog.pg_object`，声明与主体分别显示Normal/Invalid/Unknown；不能因缺目录而伪报Normal |
| 错误读取 | 按包OID和schema OID查询`DBE_PLDEVELOPER.GS_ERRORS`，保存type/line/src及所属包；优先服务端真实行号 |
| SQL失败诊断 | 保留SQLException，必要时用元数据会话读错误；缺少可选目录可降级解析line，无法解析时仅有回退位置 |
| 错误分类 | 连接/认证/权限/服务终止类SQLSTATE抛出基础设施错误，不伪装成SPEC第1行源码错误 |
| 多包与跨页定位 | 非模态结果表保存“包+SPEC/BODY+行+错误”；自动定位首条，双击/打开源码定位其他条目 |
| 异步加载 | 打开对应编辑器，切换声明/主体页，等待真实源码加载；新请求使旧回调失效，关闭窗口/编辑器后停止 |
| 脏编辑器保护 | 编译前检查相关已打开编辑器，提示先保存；不覆盖未保存源码 |
| 取消 | handler使用可取消Job，中断当前JDBC blocking object；取消后不继续查询错误、不弹全部成功 |
| 批量删除 | 复用导航多选和确认对话框，通过包manager发出各对象DROP SQL；结果以数据库目录再次核对 |

服务端执行示意（不是客户端本地编译）：

```sql
ALTER PACKAGE "ui_pkg"."pkg_ui" COMPILE;
ALTER PACKAGE "ui_pkg"."pkg_ui" COMPILE SPECIFICATION;
ALTER PACKAGE "ui_pkg"."pkg_ui" COMPILE BODY;
-- 客户端随后查询 GS_ERRORS 和 pg_object，刷新诊断与状态。
```

主要代码：`GaussDBPackage`、`GaussDBPackageCompiler`、`GaussDBPackageCompileHandler`、`GaussDBPackageCompileResultsDialog`、`GaussDBPackageDeclareViewEditor`、`GaussDBPackageBodyViewEditor`。GS_ERRORS提供行号，不承诺可靠的精确列号；历史UI显示第1列可能是客户端默认定位列。

### 3.8 追加：原生备份恢复与Linux交付

原生工具复用PG任务框架，由`PostgreServerGaussDB`将工具映射为gsql/gs_dump/gs_restore/gs_dumpall并配置本地client home、环境和文件参数。它是**客户端启动外部进程**，不是通过JDBC把整个备份文件读回来。

全库/角色备份脱敏使用流式SQL词法处理，仅替换CREATE/ALTER ROLE/USER中的PASSWORD值，保护COPY数据、字符串、美元引用函数体、注释、编码和换行；截断输入不能覆盖原文件。原生工具二进制未随Linux客户端分发，本次麒麟验收未执行其全流程。

Linux交付遇到原启动器/SWT要求更高glibc的问题：在麒麟glibc2.28用户空间分别重建匹配版本SWT JNI与Equinox启动器，保留Java bundle对应版本；内置Temurin21两架构JRE。没有替换系统glibc。另修复关闭AI后Chat视图恢复错误及GaussDB特殊类型目录枚举告警。脚本在`product/community/kylin/`。

## 4. 测试方法、环境和证据规则

### 4.1 四层验证，不混淆结果

| 层次 | 如何测试 | 能证明什么 / 不能证明什么 |
| --- | --- | --- |
| 单元/Mockito | 构造模式、catalog结果、错误码、会话返回值，断言SQL/状态/异常 | 验证代码分支；不证明真库支持或UI能操作 |
| 真库协议/模型 | Java JDBC程序或测试bundle调用真实生产模型，连接独立测试库 | 验证API、权限、模式、事务；不代表菜单/快捷键已经验收 |
| 真实GUI | SWTBot操作产品副本中的真实控件，读取源码、栈、变量、对话框；必要时X11按键 | 验证用户交互；动作OK本身不是通过，需核对后续状态 |
| 干净产品/归档 | 不装SWTBot的新用户启动、连库、退出重启；解压比对、校验和、依赖检查 | 验证交付包可运行且与测试候选一致；不等于客户硬件认证 |

### 4.2 已用环境

| 代号 | 环境 | 用途 |
| --- | --- | --- |
| G | 原Docker `gaussdb-507`，Kernel507，早期报告称分布式部署；不是完整CN+DN集群认证 | ORA/MYSQL/PG协议、调试、元数据；M已有库只读/拒绝路径 |
| C | `gaussdb-507-ha-lab:55452/package_lab`，BusinessCentralized507，A模式，普通用户package_tester | 包创建、三种编译、错误定位和状态 |
| P | 原生PostgreSQL16.15 + pldbgapi，专用55439容器 | 共享调试框架回归；不是用GaussDB冒充PG |
| MAC | macOS ARM64真实客户端副本+SWTBot | 首轮GUI、包边界、权限、跨schema和竞态 |
| LA/LX | 麒麟V10服务器版Lance/glibc2.28/GTK3.24.21容器，ARM64/x86_64 | 双架构GUI及干净产品交付；x86_64在ARM宿主仿真 |

普通测试账号使用调试角色，不用管理员身份替代用户验收。对象限定`ui_acceptance`、`ui_cross_0909`、`ui_pkg`等专用fixture。协议程序会替换测试routine及清空其专用审计表，重放必须使用隔离库。

### 4.3 证据代号

- **A:n / X:n**：交付证据目录`evidence/arm64/n.cmd.result`、`evidence/x86_64/n.cmd.result`，保留四位编号。
- **M:n**：历史macOS队列`/tmp/gaussdb-swtbot/queue/n.cmd.result`；对应报告见第9节。临时原件若已清理，以报告作为历史记录，不冒充持久原件。
- **J**：9月5日协议报告及`LiveDebug/LiveNested/LiveFunctionAndError`日志。
- **L**：Linux交付报告、构建日志、clean-r4截图/运行日志、native-ldd。

统一证据根目录：`deliverables/dbeaver-gaussdb-kylin-v10-20260909`（维护者本地归档，未随源码上传）。9月11日编写时重新核对了源码和现存报告；表中数据库值是相应测试当时的断言，不描述数据库在任意未来时刻的状态。

## 5. 测试场景汇总：步骤、结果、证据

以下按需求去重归纳场景，不把多轮重跑次数或600个单测全部当成600个GUI用例。每行均写明操作和实际检查对象。

### 5.1 模式、权限、元数据与协议边界

| ID | 关联 | 怎么测试 | 实测结果 / 证据 |
| --- | --- | --- | --- |
| C01 | R1 | 在C实例建立a/b/c/pg/m五个专用库；通过客户端生产模型逐库读取compatibility、过程和包能力 | 两端均A=ORACLE、B=MYSQL、C=TERADATA、PG=POSTGRES、M=M；仅A包=true，M过程=false。A/X:0239，A:0333；模型级通过 |
| C02 | R1/R2 | M模式先走资格检查，再观察是否访问routine语言/API catalog | M被提前拒绝；避免`proargtypes::text`在507 M报Unsupport type。9/5报告+单测；不等于M调试成功 |
| C03 | R2 | 授予、撤销、恢复专用用户gs_role_pldebugger；每步重新调用生产检查 | 通过→拒绝→通过，最终恢复。M:872–874 |
| C04 | R2 | 对admin拥有的专用函数撤销/授予/再撤销EXECUTE，逐步检测 | 无权限拒绝、有权限通过、撤销再拒绝。M:899/901/902 |
| C05 | R2 | 对SQL语言函数调用生产eligibility，保持API可用 | 因语言不可调试而拒绝，不能被API存在性绕过。M:900 |
| C06 | R2/R3 | 查询17个API签名/EXECUTE，测试oid/text重载选择、缺失签名和权限缺失模拟 | 507 oid,integer匹配；缺失/错签名/无执行权分支单测拒绝。未逐一撤销共享系统API权限 |
| C07 | R2 | 三种模式分别执行turn_on、异步目标、attach；观察进入等待的先后顺序 | 协议成功；D0011有界重试、取消和目标提前结束有回归，不对其他错误无限重试。J |
| C08 | R3 | 添加首断点、删除后重加、同OID/行重复、无效行及空编号；核对真实编号/本地状态 | 0合法、新编号不推算；重复与无效返回由客户端明确处理。J+断点单测 |
| C09 | R4 | 依次写非法整数、表达式、引号文本、NULL、常量；写后独立读变量 | 非法/常量拒绝且旧值保留；成功读回服务端结果；NULL展示保留<UNKNOWN>。J |
| C10 | R4/R5 | 构造父子同名变量；读取两个帧并尝试修改调用者帧 | 帧值隔离；非当前帧只读，避免set_var误改当前同名变量。J+单测 |
| C11 | R2/R6 | 三模式分别SELECT函数并断言返回值；另一目标先写审计再除零 | 正常函数返回正确；错误等待释放，异常前写入回滚。LiveFunctionAndError 15项 |
| C12 | R1 | 三模式表/视图CRUD，运行生产分区查询与pg_get_tabledef | 元数据操作通过，读取p0/pmax；不是全部类型矩阵。9/5 metadata记录 |
| C13 | R1 | 单测枚举两套值、未知部署、建库SQL及M manager拒绝 | SQL生成/代码门控通过；不把golden test计为每种建库UI真库通过 |

### 5.2 调试、断点、变量、栈：真实客户端

| ID | 关联 | 怎么测试 | 实测结果 / 证据 |
| --- | --- | --- | --- |
| D01 | R2 | 新建Debug配置，选连接/routine、输入参数，普通用户启动 | MAC参数10暂停；LA/LX参数6暂停父第4行。M:141；A:0115；X:0195/0197 |
| D02 | R2 | 父第4行按F8，核对下一行及变量 | 到第5行，Linux v_local=7。A:0119，X:0204；MAC=11，M:141–144 |
| D03 | R4 | Variables执行Change Value，再检查Variables和Expressions | LA改12、LX改13，Watch均读回；MAC改20。A:0122/0124，X:0207/0209，M:147–150 |
| D04 | R2/R5 | 父调用行按F7，核对子源代码和两层栈 | 进入ui_cross_0909.ui_child第4行，正文是+102，不是同名+2。A:0126，X:0211 |
| D05 | R5 | 双击父/子栈帧来回切换 | 父调用第5行与正确子正文切换。A:0128，X:0213；M:1110/1112 |
| D06 | R3 | 在子第5行设置断点，从父继续，不使用单步到达冒充命中 | F9真实命中子第5行。A:0134/0136，X:0217/0218 |
| D07 | R3 | Breakpoints取消勾选后重新运行，再勾选再次运行 | MAC禁用后直接完成、启用再命中；Linux窗格false/true同步。M:229–231/258–263；A:0138/0140，X:0220/0222 |
| D08 | R3 | 删除断点检查列表；同一行重加并再次继续 | Linux删除后条目消失；MAC重加再次命中。A:0150，X:0229；M:276–283 |
| D09 | R2/R5 | 停在子函数按Shift+F7，检查栈深和停止行 | 返回父第6行；不是仅点击按钮成功。A:0142，X:0223；M:178–179 |
| D10 | R2/R6 | 暂停后按F10，检查terminated、错误弹窗和独立审计查询 | 正常终止，无用户abort误报，不新增审计数据。A:0159/0160，X:0237；M:264–265/284–285 |
| D11 | R5 | 首次暂停同时由栈/Watch取快照，再按F8 | 修复后仅一个初始parent帧，无重复追加；M:271–273及并发快照单测 |
| D12 | R4 | 调试结束后保留Watch视图，再访问变量 | 空session安全处理，不崩溃；结束后不可用不误显示成有效当前值。SWTBot报告+回归 |

### 5.3 事务：界面与数据库联合断言

共同方法：先记录`SELECT count(*),sum(value)`，完成调试选择事务动作，再从独立连接查询。不能只凭弹窗关闭判断提交/回滚成功。

| ID | 怎么测试 | 实测结果 / 证据 |
| --- | --- | --- |
| T01 | MAC运行父子过程，正常完成选Commit | 新增11、13，count=2,sum=24。M:180–183 |
| T02 | MAC再次运行，分别正常回滚/终止后查询 | 始终2/24。M:198/211/232、284–285 |
| T03 | LA有改变量/跨schema的运行完成选Rollback | 保持2/24，树terminated。A:0144–0146 |
| T04 | LA下一轮无断点正常运行选Commit | 变为4/140，新增子109+父7。A:0154/0155 |
| T05 | LA再运行F10 | 保持4/140。A:0160 |
| T06 | LX调试结束选Rollback | 保持4/140。X:0224/0225 |
| T07 | LX下一轮正常运行选Commit | 变为6/256，再增109+7。X:0232/0233 |
| T08 | LX再次F10 | 保持6/256。X:0237 |
| T09 | 三模式协议分别commit/rollback/abort，独立连接检查 | 纳入LiveDebug的168项断言全部通过；不代替上述GUI证据 |

### 5.4 包编译、诊断、修复与删除

均使用支持包的C实例、普通包所有者。错误fixture只在自己的会话设置force-create等参数，不修改全局服务配置。

| ID | 怎么测试 | 实测结果 / 证据 |
| --- | --- | --- |
| P01 | 首次连库展开ui_pkg，观察包节点 | 修复初始化时能力缓存过早为false后节点正常。M:316/329–331 |
| P02 | 分别从右键点ALL/SPEC/BODY；独立调用plus_one(41) | 三种编译均成功，函数返回42。M:355–360；A/X:0037/0039/0041 |
| P03 | 创建缺失类型的无效BODY，执行BODY编译 | 真实错误报告BODY第3行；不以伪造报错测试替代。M:413/414；A/X:0043 |
| P04 | 无额外点击观察首错自动打开；手动移到第1行再双击错误 | 回到正确BODY第3行并选中错误源码。M:414–416/582–583；A/X:0045 |
| P05 | 多选分别有BODY/SPEC错误的包，ALL编译，逐条打开结果 | 包名、源码页与行归属不串：BODY/3、SPEC/2。M:497/499/501/558/560 |
| P06 | 已打开主体后手动切到声明，再点击BODY错误 | 自动切回主体第3行。M:505/507 |
| P07 | 源码尚未打开时直接点错误；关闭编辑器再点结果 | 等待真实加载后定位；无首次第1行偏移。M:499；独立新工作区050/053/055 |
| P08 | 修改对应BODY类型为INTEGER，保存、检查SQL预览、执行，再编译 | value_of返回预期1或42（不同fixture）；GS_ERRORS清空、旧日志消失。M:421–427/510–516 |
| P09 | 打开Invalid包属性，保存修复再编译，不关闭编辑器 | 状态由Invalid变Normal，其他包错误不被清掉。M:531/533/560/564–568 |
| P10 | 保留包编辑器未保存修改，从导航树执行编译 | 显示Save package保护，不越过未保存修改执行编译。M:457 |
| P11 | 混合四个有效/无效包连续批量编译两次 | 列表始终仅两个无效包，无重复累积/跨包串错。M:828/832 |
| P12 | 多选两个专用包，核对删除确认名单，确认后独立查目录 | MAC delete_a/b消失且其他包保留；LA/LX各自两包均删除，四名称最终0行。M:417–420；A/X:0053–0055 |
| P13 | 在G环境尝试创建包并检查错误目录 | 服务端拒绝CREATE PACKAGE，GS_ERRORS不存在；该部署不计包功能通过。后续改用C环境，不伪造服务端能力 |
| P14 | 模拟缺失可选目录、混合SPEC/BODY错误、不同SQLSTATE | 42P01降级、错误过滤、基础设施错误分类单测通过；同包同时保留两组错误在该真库不可构造，仅单测覆盖 |

### 5.5 取消、断连、并发与生命周期

| ID | 怎么测试 | 实测结果 / 证据 |
| --- | --- | --- |
| E01 | 专用会话持有包编译锁；UI启动编译；取消；查pg_stat_activity | 目标退出等待至idle/waiting=f，锁持有者仍运行，无假成功窗口。M:604–609 |
| E02 | 释放上述测试锁，重新编译同包 | 编译成功。M:611–612 |
| E03 | 编译时终止本轮目标连接 | 显示连接终止，不伪装为SPEC第1行源码错误。M:804；修复前614–617 |
| E04 | 断连后显式断开/重连，再编译 | 恢复成功；未重连直接重试仍明确报关闭，不承诺自动重试DDL。M:824 |
| E05 | 同包连续启动两个等锁编译；取消第二个；只释放专用锁持有者 | 第二任务取消、第一仍等待，释放后第一成功，无连带取消。M:967/969/970 |
| E06 | 捕获真实导航回调；先发新导航请求，再重放旧回调 | 旧回调不抢回焦点。M:1001 |
| E07 | 先关闭结果对话框，再重放迟到回调 | 不访问已释放窗口。M:1005/1057 |
| E08 | 先关闭编辑器，再重放迟到回调 | 修复前NPE；修复后连续5次通过，不重开编辑器、不改变活动编辑器。M:1039/1043/1047/1051/1055 |

E05是一个真实并发交错，E06–E08是确定性回调重放，不是长期高负载压力测试，也不证明所有线程/网络时序安全。

### 5.6 PostgreSQL共享框架回归

| ID | 怎么测试 | 实测结果 / 证据 |
| --- | --- | --- |
| PG01 | 用独立原生PG+pldbgapi运行LivePostgreSQL | 11项协议断言通过，实际返回23；42.7.10连接此PG成功，不等于其连接GaussDB认证问题已修复 |
| PG02 | GUI新建原生PG连接，加载42.7.13，启动父子函数调试并修改变量 | MAC变量6→20→21，进入子帧，最终23；正常完成不误报pldbg_continue错误。M:629–642/768–775/785–787 |
| PG03 | 跨schema进入子函数；双击父帧；中间语句第4行设断点后继续 | 正确源码/调用行及第4行命中。M:912/919/1090 |
| PG04 | LA父第4行Step Over到第5行，Step Into到子第3行 | 子正文含PERFORM 1，父子两帧正确。A:0289/0291/0293 |
| PG05 | 在子第一条语句第3行设断点；重新启动后直接Continue | 映射服务端行号-1后真实命中第3行。修复前普通3未命中；A:0294/0303 |
| PG06 | 子变量p从7改8，添加Watch，继续完成 | Watch=8，audit从7/577到8/687（新增110）。A:0307/0308/0310/0312 |
| PG07 | 禁用首语句断点再运行 | 不停在子帧，直接完成，audit=9/796。A:0316 |
| PG08 | 重新启用断点，正常退出，重启恢复旧编辑器再继续调试 | 旧完整DDL输入切换为正文，命中子第3行、caretLine=3；终止后audit仍9/796。A:0321/0325/0327 |

PG Step Return仍为`canStepReturn=false`，不计为通过；GaussDB的Shift+F7支持有独立证据。正常结束诊断放行只在特定PG错误信息且目标SQL实际成功时成立，不普遍忽略08006连接错误。

### 5.7 原生备份恢复、Linux安装与发布

| ID | 怎么测试 | 实测结果 / 证据 |
| --- | --- | --- |
| B01 | ORA/MYSQL/PG各用COPY与INSERT导出含伪PASSWORD/CREATE ROLE、中文、换行、美元标记的数据；调用生产脱敏器 | 六组无角色密码dump处理前后逐字节不变。9/5 backup报告 |
| B02 | 在独立测试库删除专用schema后用上述dump恢复，按UTF-8字节比对行 | 六组恢复内容完全一致，不以文本替换看起来正常代替真实恢复 |
| B03 | gs_dumpall导出globals；脱敏；只恢复临时角色CREATE语句 | 角色密码哈希移除，恢复后密码禁用；不恢复其他角色。原始敏感dump已清理 |
| B04 | 单测截断、多行/转义密码、嵌套注释、函数体、CRLF、COPY/INSERT文本 | 脱敏边界回归通过；不是麒麟原生工具二进制认证 |
| L01 | 原始Linux包在麒麟glibc2.28启动；重建匹配SWT/Equinox后重试 | 初始因GLIBC版本失败；重建后两架构启动成功，未替换系统库。L |
| L02 | 两架构客户向导手工加Native JDBC，Test Connection并展开 | Kernel507连接成功。A:0014，X:0016；干净用户另测252ms/446ms，不作性能基准 |
| L03 | 独立deliverycheck用户、全新工作区，不装SWTBot；首次向导关闭AI、启用debugger | 主窗口正常；解决缺WebKit与关闭AI后Chat恢复错误。clean-r4截图/日志 |
| L04 | 保存连接，正常退出，加载最终候选重启，再展开连接，最后退出 | 两端重连成功，日志含连接关闭和Platform shutdown completed，无旧类型告警。clean-r4-launch.log |
| L05 | 实际运行内置JRE；对启动器和七个JNI做ldd，包含内置JRE查找路径 | 两端Temurin21.0.12.1+1可运行，无缺失依赖。native-ldd.txt；裸ldd的libjawt由JRE提供 |
| L06 | 故意向ARM组装脚本输入x86原生库；核对JRE release架构 | 明确拒绝错误架构且不创建目标输出目录；正向组装两端成功 |
| L07 | 新枚举识别o/F、s/H、b/L、b/M、u/W目录类型；重连检查 | 单测通过且两端旧告警消失；未声称新增所有集合/内部类型值编辑 |
| L08 | 重新加载modes测试工具，读五库后正常退出，查Main/Metadata关闭日志 | 临时连接成对立即关闭，无测试工具残留上下文。A:0333、modes-cleanup.log |
| L09 | 客户归档扫描测试插件/工作区配置；检查已知测试密码；保留来源许可 | 客户产品不含SWTBot/测试工作区/保存凭据；已知密码扫描未发现，不代替通用安全审计 |
| L10 | 两架构tar.gz重新解压，与已测candidate-r4逐文件diff；全目录SHA256验证 | 文件一致，清单全通过；源码固定26688a3442。L/DELIVERY/BUILD-MANIFEST |

## 6. 发现问题—修复—复测闭环摘要

| 问题 | 修复方式 | 对应复测 |
| --- | --- | --- |
| 只按provider/API名字开放调试 | 模式、语言、签名、API/目标权限和角色联合检查 | C02–C06 |
| 断点编号、重载、重复注册/初始禁用处理错误 | OID+行映射、0合法、权限可用重载、首次enable补注册 | C08、D06–D08 |
| 修改变量失败仍显示新值/调用者变量被误改 | 检查set_var返回、成功读回、非当前帧只读 | C09–C10、D03 |
| attach抢跑、64位PID溢出、异常会话卡住 | 有界重试、getLong、错误标记与目标连接清理 | C07、C11、D01、T09 |
| 并发读栈产生重复帧、结束Watch空session | 同步快照替换、生命周期检查 | D11–D12 |
| 跨schema打开启动函数/同名错误对象 | 按帧OID查namespace和专用cache | D04–D05、PG03 |
| 首次连接无包节点、编译菜单无动作 | 晚到能力快照、从导航适配DBSObject后检查包 | P01–P02 |
| 编译错误绕过诊断、跨页/多包串错、首次只到第1行 | 保留SQL异常；错误关联包/源码页；真实加载后定位 | P03–P07 |
| 脏源码覆盖、旧错误/状态不刷新 | 编译前保存保护，刷新未修改编辑器/对应日志 | P08–P11 |
| 取消仅改标记、断连伪装编译错 | 中断blocking JDBC；SQLSTATE分类 | E01–E04 |
| 编辑器关闭后迟到回调NPE | 检查editor/site/page与请求号生命周期 | E06–E08 |
| PG正常结束误报、首语句断点不命中、恢复输入丢属性 | 精确完成判别、首语句-1映射、lazy输入属性转交 | PG02、PG05、PG08 |
| 麒麟原生库glibc不兼容、Chat错误占位 | 目标用户空间重建；尊重AI配置并清理不可用视图引用 | L01、L03–L05 |

## 7. 自动化统计与重放指南

### 7.1 最新统计（不能把历史轮次相加）

| 测试模块 | 总数 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: |
| platform | 448 | 3 | 0 |
| GaussDB model | 67 | 0 | 0 |
| GaussDB debug | 40 | 0 | 0 |
| PostgreSQL | 45 | 0 | 0 |
| 合计 | 600 | 3 | 0 |

实际通过597。3项为仓库原有SVG相关两个测试及quoted names completion测试，未为本次适配增加跳过。日志：交付目录`evidence/catalog-tests.log`，71模块verify成功；完整产品构建`evidence/catalog-product.log`成功。

独立统计：GaussDB三模式协议204项断言=168+21+15；原生PG协议11项。它们不是上述600个JUnit测试的同一计数口径，也不是GUI用例数。

### 7.2 如何复跑

1. **隔离环境**：依据integration README创建专用库/用户，授最小所需权限；密码放权限0600文件，不放聊天、报告或仓库。不要在业务库运行会替换过程/清空fixture的程序。
2. **单测与构建**：配置P2依赖和合适JDK，在仓库运行Maven/Tycho。历史离线verify使用`/tmp/dbeaver-gaussdb-fix-tests/reactor-modules.txt`中的71模块，离线模式要求依赖已缓存；该临时列表不存在时按项目依赖重建reactor，不能把文件缺失当产品失败。
3. **协议程序**：编译`integration/*.java`，设置对应JDBC classpath，依次执行LiveDebug、LiveNested、LiveFunctionAndError；以退出码、断言计数和独立数据库结果判定。具体参数见第9节integration说明。
4. **GUI程序**：复制产品到临时目录，安装test-only SWTBot，指定独立`-data`及`-Dgaussdb.swtbot.queue=...`。Linux应保留`--launcher.appendVmargs`，避免测试VM参数覆盖产品ini配置。
5. **准备fixture**：按场景使用`package-boundary-fixtures.sql`、修复脚本、`extended-mode-fixtures.sql`、`cross-schema-gaussdb.sql`、`cross-schema-postgresql.sql`；名称、OID和行号以实际创建结果为准。
6. **驱动与断言**：先dump读取当前控件ID，再提交tab分隔命令。每次dump后ID可能变化，不重放历史ID；虚拟树未展开的旧文字不算证据。截图空白/命令OK也不算功能通过。
7. **按键**：窗口管理器和焦点必须正确；x86仿真SWT Display.post未可靠送达时改用X11系统按键，仍以数据库暂停行/状态变化判断。
8. **独立结果核对**：事务查询审计表；包查GS_ERRORS/pg_object并调用测试函数；删除查询目录；取消查pg_stat_activity。不要只看成功弹窗。
9. **归档验收**：用不带测试插件的新用户运行实际包，完成连接、退出、重启；保存日志和截图；校验源代码版本、许可证、JRE架构、SHA256及解压内容。

用于本次产品构建的命令形式：

```sh
mvn -o package -f product/aggregate/pom.xml -T4 -Pproduct-dbeaver-ce \
  -DskipTests -Dskip-checkstyle=true -Dspotless.check.skip=true
# 上述产品组装命令跳过测试；测试通过依据是另行执行的 verify，不是这条命令。
```

## 8. 仍需明确的边界

| 边界 | 当前事实 |
| --- | --- |
| 客户麒麟整机 | 测的是容器用户空间，非客户内核、显卡、远程桌面或麒麟官方认证；需现场安装冒烟 |
| Windows | 按后续要求不做本次运行验收；历史构建成功不是Windows GUI通过 |
| 全模式/全版本 | 五模式识别和能力有实测；协议主要ORA/MYSQL/PG；不是所有DDL/类型/菜单组合矩阵 |
| 包部署 | 集中式ORA已通过；分布式环境真实拒绝不能靠客户端模拟支持；完整CN+DN不是当前验收环境 |
| 错误定位 | 已验证真实行与SPEC/BODY；不保证服务端未提供的精确列或所有错误均入GS_ERRORS |
| Watch | 变量名匹配；任意表达式/任意对象展开不在当前承诺 |
| 事务 | 普通事务变更可提交/回滚；主动提交、自治事务、外部副作用另论 |
| 异常与并发 | 指定取消/断连/回调交错通过；未穷尽网络故障、远程attach、多客户端长压 |
| PG | 首语句与恢复输入问题已闭环；PG Step Return仍不支持，不能把Gauss能力套给PG |
| 备份恢复 | 已有真工具六组导出/恢复与脱敏测试；麒麟双架构gs_dump/gs_restore任务未验收，工具和JDBC需另供 |

因此向客户推荐的表述是：“本分支已实现七项适配，并在报告所列GaussDB507、macOS及麒麟双架构环境完成对应场景验证；具体部署、模式和硬件边界见本报告。”不应表述为“所有GaussDB和Linux环境全部功能均已认证”。

## 9. 源码、报告与交付索引

### 9.1 主要源码入口

- [GaussDB模型及对象管理](plugins/org.jkiss.dbeaver.ext.gaussdb/src/org/jkiss/dbeaver/ext/gaussdb)
- [GaussDB调试会话](plugins/org.jkiss.dbeaver.ext.gaussdb.debug.core/src/org/jkiss/dbeaver/ext/gaussdb/debug/core/internal/GaussDBDebugSession.java)
- [调试能力/签名/权限探测](plugins/org.jkiss.dbeaver.ext.gaussdb.debug.core/src/org/jkiss/dbeaver/ext/gaussdb/debug/core/internal/GaussDBDebugCapabilityDetector.java)
- [包编译器](plugins/org.jkiss.dbeaver.ext.gaussdb/src/org/jkiss/dbeaver/ext/gaussdb/model/GaussDBPackageCompiler.java)
- [包编译UI及结果定位](plugins/org.jkiss.dbeaver.ext.gaussdb.ui/src/org/jkiss/dbeaver/ext/gaussdb/ui/actions)
- [Watch实现](plugins/org.jkiss.dbeaver.debug.core/src/org/jkiss/dbeaver/debug/core/model/DatabaseWatchExpressionDelegate.java)
- [JDBC协议程序与重放说明](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/README.md)
- [SWTBot驱动与fixture说明](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/README.md)
- [麒麟构建/组装脚本](product/community/kylin)

### 9.2 原始报告（按时间理解，不把旧待办当最新结论）

- [需求分析基线](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_FEATURE_REQUIREMENTS_ANALYSIS.md)
- [9月2日真库与9月5日门控补验](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_REALDB_VALIDATION_20260902.md)
- [9月5日协议、备份恢复和修复回归](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_REVIEW_FIX_VALIDATION_20260905.md)
- [macOS调试GUI](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_SWTBOT_ACCEPTANCE_20260909.md)
- [包GUI首轮](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_PACKAGE_UI_ACCEPTANCE_20260909.md)
- [包边界与状态刷新](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_PACKAGE_BOUNDARY_ACCEPTANCE_20260909.md)
- [取消、断连、权限和PG补验](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_EXTENDED_ACCEPTANCE_20260909.md)
- [跨schema和竞态](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_CROSS_SCHEMA_RACE_ACCEPTANCE_20260909.md)
- [Linux完整历史记录及最终证据索引](GAUSSDB_KYLIN_LINUX_ACCEPTANCE_20260909.md)
- 最终交付说明：维护者本地归档中的 `DELIVERY.md`（未随源码上传）

本文为新增汇总文档，不修改已经生成的9月9日交付包或其SHA256清单。
