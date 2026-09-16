# GaussDB 适配版 DBeaver：Windows 测试指南

日期：2026-09-16。目标代码：`jarrenL/dbeaver` 的 `feature/gaussdb-compatibility` 分支。

## 1. 测试范围和状态

本指南用于在 Windows 上复验兼容模式、PL调试、断点、变量/Watch、调用栈、事务收尾、包编译，以及原生工具。**这是待执行的Windows测试方案，不是Windows验收通过报告。** 既有macOS/Linux测试不能替代Windows原生UI、快捷键、路径和进程启动测试。

需要分开记录两个环境：

| 对象 | 推荐测试方式 | 注意 |
|---|---|---|
| DBeaver客户端 | Windows原生x86_64客户端，独立workspace | 不能拿Linux产品运行在WSL里当作Windows客户端通过 |
| GaussDB服务端 | 连接服务器上已部署、可正常提交事务的GaussDB | Windows不安装数据库、不需要Docker；客户端与服务器不要求同CPU架构 |
| 普通Windows Intel/AMD机器 | Windows原生x86_64客户端 | 可以通过JDBC连接ARM64或x86_64服务器 |
| 包编译 | 支持包的集中式ORA实例 | 分布式实例不支持包时记“环境不满足”，不是客户端通过或失败 |

目前无Windows实机执行证据；不要将本文的预期结果填成实测结果。

## 2. 获取源码和Windows产品

### 2.1 源码准备（PowerShell）

在独立工作目录执行，不覆盖已有仓库：

```powershell
git clone --branch feature/gaussdb-compatibility https://github.com/jarrenL/dbeaver.git
git clone https://github.com/dbeaver/dbeaver-common.git
git -C dbeaver-common checkout 2df03e58480c6325732f476e8355e375cec5405e
git -C dbeaver rev-parse HEAD
java -version
```

本机已有依赖仓库的基线为上述dbeaver-common提交，未修改其代码。两个仓库必须并列。记录实际代码SHA和JDK版本；不要只写“最新版”。构建至少遵循仓库的Java 21要求；若固定依赖明确要求更高构建JDK，以构建报错和依赖配置核实，不通过降低编译目标绕过。

### 2.2 构建入口

仓库提供 `tools/build.cmd`，它会克隆缺失的dbeaver-common并启用appstore等配置。为显式控制本轮构建，可在dbeaver根目录使用：

```powershell
..\dbeaver-common\mvnw.cmd package -f product\aggregate\pom.xml -Pproduct-dbeaver-ce,product-dbeaver-eclipse-ce -T 1C
if ($LASTEXITCODE -ne 0) { throw 'Build failed; do not test stale products' }
Get-ChildItem product\community\target\products -Recurse -Filter dbeaver.exe
```

这是依据现有Maven/Tycho入口整理的Windows命令，本轮未在Windows运行。完整构建需要能访问配置的Maven/P2源；首次不要使用离线模式。平台配置含win32/win32/x86_64和aarch64，选择与Windows/JRE匹配的产品；不要拷贝macOS或Linux的SWT、启动器、JRE到Windows。

也可使用同一代码SHA构建的Windows成品；须记录SHA/文件校验值，并在安装详情确认含以下四个bundle：

- org.jkiss.dbeaver.ext.gaussdb
- org.jkiss.dbeaver.ext.gaussdb.ui
- org.jkiss.dbeaver.ext.gaussdb.debug.core
- org.jkiss.dbeaver.ext.gaussdb.debug.ui

新建目录如 `C:\gaussdb-test\client` 和独立workspace：

```powershell
& 'C:\gaussdb-test\client\dbeaver.exe' -data 'C:\gaussdb-test\workspace'
```

该路径是放置产品后的示例，不是仓库自动生成位置。不要用日常workspace做取消/退出/错误恢复测试。

## 3. 先检查服务端，避免把数据库故障归到客户端

在Windows PowerShell检测真实目标端口：

```powershell
$dbHost = 'db-test.example.internal' # 替换为DBA提供的服务器地址
$dbPort = 5432                       # 替换为实际CN/集中式入口端口
Test-NetConnection -ComputerName $dbHost -Port $dbPort
```

由DBA提供地址、端口、验收数据库名、账号、兼容模式、部署类型及原厂JDBC驱动；确认服务器防火墙和数据库访问规则允许测试机的实际出口IP。使用内网/VPN或经批准的SSH隧道，不直接开放公网，不用trust规则绕过认证。服务器要求TLS时按DBA要求配置证书及校验，不关闭校验来掩盖连接问题。

在DBeaver新建GaussDB连接，填写上述信息，在驱动设置中配置匹配的JDBC jar，再执行“测试连接”。Windows无需安装服务器组件、Docker或WSL。网络端口可达只证明TCP通，不代表数据库认证和权限正确。

用原厂gsql/JDBC先确认：版本、连接成功、简单建表/INSERT/COMMIT/ROLLBACK成功。分布式环境必须经CN执行，不能直连DN成功就算集群成功。取证SQL：

```sql
SELECT version();
SELECT current_database(),current_user;
SELECT name,setting FROM pg_settings
WHERE name IN ('sql_compatibility','enable_gtm_free','gtm_option',
              'gtm_host','gtm_port','gtm_host1','gtm_port1',
              'synchronous_commit','synchronous_standby_names');
```

提交卡住或连接中断时，记录发生时间、SQLSTATE和专用测试SQL，请DBA检查服务器对应日志。不要在Windows验收过程中自行调整服务器GTM、系统表或一致性配置。集中式实例不要求存在CN/DN/GTM拓扑；服务端信息按实际部署收集。

## 4. 测试账号和数据准备

使用独立可删除的验收数据库和测试用户，由DBA按目标版本创建/授权；不要使用生产schema。调试用户需满足gs_role_pldebugger及目标routine/调试API执行权限。另准备一个无调试权限用户验证拒绝路径；不要对业务账号撤权。

数据库矩阵：ORA/A、MYSQL/B、PG，条件允许增加TD/C和M；枚举取值应按实际部署支持值创建。包测试另使用集中式ORA实例，确认CREATE PACKAGE及dbe_pldeveloper.gs_errors可用。未知模式/不支持模式应按能力门控拒绝，不计为成功调试。

调试fixture位于：

- [嵌套过程](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/fixtures.sql)
- [跨schema同名对象](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/cross-schema-gaussdb.sql)
- [模式fixture](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/extended-mode-fixtures.sql)
- [包错误fixture](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/package-boundary-fixtures.sql)
- [修复包声明](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/package-boundary-spec-repaired.sql)
- [修复包主体](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/package-boundary-body-repaired.sql)

**执行前阅读各脚本。** 它们含替换对象、故意无效对象或gsql元命令，不可整段不加区分地粘到DBeaver执行。gsql的`\set`不是服务器SQL；PL块的`/`应按客户端脚本分隔配置处理。

嵌套过程fixture的前置对象（在新验收库、以测试用户执行一次）：

```sql
CREATE SCHEMA ui_acceptance;
CREATE TABLE ui_acceptance.ui_audit(value integer);
```

再加载fixtures.sql。它定义ui_parent和ui_child。以p_in=10调试、不修改变量时，成功提交预期audit含11、13；回滚应无本次新增行。每轮记录初始行集，不在共享业务表上TRUNCATE。包fixture需要预先创建测试用户拥有的ui_pkg schema。

## 5. Windows原生UI验收清单

每项记录：实际结果、PASS/FAIL/BLOCKED/NOT RUN、截图、代码SHA、数据库build及模式。以下均为**预期**。

| ID | 场景与操作 | 预期/取证 |
|---|---|---|
| W01 | 干净启动；选择GaussDB专用连接；测试连接；关闭重开 | 无bundle缺失；连接保存、重连正常；不以Generic连接代替GaussDB适配 |
| W02 | 连不同兼容模式库；切换目标库后浏览类型、函数、过程；建测试库选择模式 | 模式识别正确，不沿用首次连接缓存；DDL使用目标模式 |
| W03 | M模式和不支持语言routine查看Debug入口；无权限用户启动 | PL调试不应错误开放；明确拒绝原因，不挂死；SQL函数浏览不等于可PL调试 |
| W04 | 对ui_parent建调试配置，p_in=10，启动并暂停 | 绑定正确对象与参数，源码和当前行匹配 |
| W05 | 英文输入法、焦点在调试界面，依次F7/F8/Shift+F7/F9 | 进入子过程/跳过/退回调用者/继续与按钮行为一致；笔记本必要时Fn组合 |
| W06 | 单独一次运行按F10 | 确实终止、释放会话；不留下未提交写入或下一次attach失败 |
| W07 | 断点窗格增/禁/启/删/重加，同一行重复toggle | 启用断点停住，禁用不停止；无重复孤儿断点；0号服务端断点正常 |
| W08 | 暂停后看变量；修改普通变量；非法值、常量、其他帧写值 | 合法值读回；非法修改有反馈且不伪造成功；常量等受保护 |
| W09 | 添加变量名Watch；继续、结束、重启会话 | 当前帧变量正确，结束后无陈旧值；任意表达式不属于当前支持承诺 |
| W10 | 嵌套暂停，双击父/子栈帧，再测试跨schema同名过程 | 打开正确OID/schema源码和行；不得串到另一个同名对象 |
| W11 | 正常运行结束选择提交，再用另一连接查audit | p_in=10无改值时本轮提交产生11和13；不是仅凭弹窗判断 |
| W12 | 相同过程选择回滚；另测取消、异常、断连 | 数据无本轮新增写入；会话清理；可以再次调试 |
| W13 | 集中式ORA创建有效包，执行ALL/SPEC/BODY编译入口 | 菜单/编辑器入口可发现，动作针对正确部分，状态刷新 |
| W14 | 加载故意错误包；看列表、双击多条错误，再修复编译 | 正确区分声明/主体和服务端错误行；列号仅在服务端提供时验证；修复后清除错误/无效状态 |
| W15 | 多选两个本轮测试包删除，并独立查询目录 | 只删除选中测试对象；取消删除不改变数据库 |
| W16 | 编译/源码加载中关闭对话框、切换编辑器、取消，再重开 | 无迟到回调打开错误对象；不锁死UI；记录是否有残留后台任务 |
| W17 | 高DPI、中文路径、带空格路径、中文/英文界面 | 对话框可操作，错误行定位正确；路径解析不截断 |
| W18 | 保存连接退出，重新启动，重复一次调试及包编译 | 无依赖旧会话的偶然通过，数据与对象状态正确 |

快捷键被系统/其他软件抢占时，分别记录按钮是否正常、键绑定冲突及修复后结果；不要把点按钮成功当作指定快捷键已通过。

### 2026-09-16 审查修复后的追加场景（Windows仍待执行）

- 参数表“输入方式”分别选择输入值、SQL NULL、使用默认值。空字符串和字符串 `NULL` 不应被客户端自动转换为数据库 NULL；保存/重开调试配置后保持选择。默认值只允许省略连续末尾参数，缺少默认值元数据或同 schema 同名重载时明确拒绝。
- 暂停期间快速重复增删/启禁断点；步进期间刷新 Watch、点击栈帧并终止。检查无重复注册、无已删除断点再次命中；忙碌状态不能伪装成有效旧变量。
- 通过DBA批准的故障注入测试提交失败、响应延迟与取消。未收到成功结果时不能自动终止并宣称成功，不能自动重试 COMMIT；结果未知时独立查库确认。
- 多选至少三个测试包：第一个产生源码诊断，第二个模拟基础设施错误或取消。前一个包的诊断仍可定位，明确报告处理进度，剩余包不得被标为编译成功。

本轮代码与自动化结果见 [审查修复验证记录](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_REVIEW_FIX_VALIDATION_20260916.md)，不替代本节Windows实机操作。

## 6. 原生备份恢复的Windows专项

此项为原始七项之外的扩展测试，不影响仅通过JDBC连接服务器。若需从Windows DBeaver启动原生备份恢复，另准备**Windows可执行的、与服务端兼容的**gsql/gs_dump/gs_restore等客户端工具和依赖DLL。服务器上的Linux程序不能直接作为Windows本地工具运行；没有相应Windows工具时记录本项BLOCKED，不要求为此安装Docker或WSL。

检查客户端工具路径含空格、中文目录、输出文件、取消任务、错误退出码。只从独立测试库导出，恢复到新的空测试库；对比schema/对象、行数和关键数据。日志不得泄露密码；确认环境变量、工作目录及DLL搜索路径正确。备份脱敏不能误改业务文本。

## 7. 可选JDBC协议回归（不是UI验收）

先读 [协议测试README](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/README.md)。现有三个GaussDB程序固定使用127.0.0.1:5432及dbeaver_fix_0905_ora/mysql/pg数据库；**下列命令不是远程服务器开箱即用的测试**。先由开发人员审查修改测试程序的连接地址、端口及数据库名，确认全部指向DBA提供的专用验收库，再编译运行；只改properties中的账号密码不会改变目标地址。不修改程序时跳过本节，使用第5节远程连接手工验收，不需要部署本地数据库。

JDK21+，在仓库根目录的PowerShell执行编译：

```powershell
New-Item -ItemType Directory -Force C:\gaussdb-test\classes | Out-Null
$src = Get-ChildItem test\org.jkiss.dbeaver.ext.gaussdb.debug.test\integration\*.java
javac -d C:\gaussdb-test\classes $src.FullName
if ($LASTEXITCODE -ne 0) { throw 'Compile failed' }
$env:GAUSSDB_JDBC_JAR = 'C:\gaussdb-test\driver\gsjdbc4.jar' # 按测试程序加载的驱动类选择实际jar
java -cp "C:\gaussdb-test\classes;$env:GAUSSDB_JDBC_JAR" LiveDebug C:\gaussdb-test\private\connection.properties
if ($LASTEXITCODE -ne 0) { throw 'LiveDebug failed' }
java -cp "C:\gaussdb-test\classes;$env:GAUSSDB_JDBC_JAR" LiveNested C:\gaussdb-test\private\connection.properties
if ($LASTEXITCODE -ne 0) { throw 'LiveNested failed' }
java -cp "C:\gaussdb-test\classes;$env:GAUSSDB_JDBC_JAR" LiveFunctionAndError C:\gaussdb-test\private\connection.properties
if ($LASTEXITCODE -ne 0) { throw 'LiveFunctionAndError failed' }
```

Windows classpath分隔符是分号，不是冒号。properties仅存测试user/password，放仓库外，使用Windows ACL限制当前用户读取，不依赖Linux chmod语义。程序会替换routine并清理测试表，不能指向业务库。

SWTBot参考 [测试驱动说明](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/README.md)。当前安装脚本以macOS产品为前提，不是现成Windows一键安装器。Windows自动化应单独适配测试产品拷贝、bundle装配、路径和键盘事件；未完成前用第5节手工GUI验收，禁止将SWTBot放入客户正式产品。

## 8. 故障证据与验收记录

Windows客户端收集：独立workspace下`.metadata/.log`、Error Log视图、错误截图、具体操作顺序、驱动jar版本、Java/DBeaver/Windows版本、CPU架构、DPI及输入法。路径根据实际-data目录定位。

数据库收集：CN/DN/GTM完整build、SQLSTATE、同时间段日志、模式/权限结果。输出中去除密码、令牌、私有连接字符串及敏感业务数据，不提交连接配置文件。

建议报告模板：

```text
测试日期/人员：
Windows版本/CPU/DPI：
DBeaver代码SHA/产品校验值/JRE：
数据库部署类型/架构/build/模式：
原厂JDBC版本：
用例ID：
前置条件/实际步骤：
预期结果：
实际UI结果/独立数据库结果：
PASS / FAIL / BLOCKED / NOT RUN：
证据文件/SQLSTATE：
清理结果/遗留问题：
```

通过标准：原始七项在适用服务器环境分别有Windows实测证据；负向门控、异常清理、快捷键、数据提交/回滚都验证；所有阻塞项明确记录。缺少支持包的集中式ORA环境时，不能宣布七项全部通过。原生备份恢复单独报告，不将其与七项验收混为一谈。

## 9. 关联资料

- [需求与架构及历史测试汇总](GAUSSDB_REQUIREMENTS_ARCHITECTURE_TEST_SUMMARY_20260911.md)
- [GaussDB兼容说明](plugins/org.jkiss.dbeaver.ext.gaussdb/README_GAUSSDB_COMPATIBILITY.md)
- [Linux历史验收](GAUSSDB_KYLIN_LINUX_ACCEPTANCE_20260909.md)
- [包编译边界历史测试](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_PACKAGE_BOUNDARY_ACCEPTANCE_20260909.md)

历史通过结果只适用于各报告注明的环境。本文编写及源码推送不构成一次Windows测试执行。
