# GaussDB review 修复与回归（2026-09-05）

## 范围与结果

本次修复覆盖 review 的四项问题，以及扩大真库测试时发现的三个调试问题。未提交 Git commit；保留工作区已有改动。

| 问题 | 修复 |
|---|---|
| 全库备份脱敏误改业务数据 | 使用流式 SQL 词法识别，只替换 CREATE/ALTER ROLE/USER 的 PASSWORD 值；保留 COPY 数据、注释、字符串、美元引用函数体、换行和字节编码；截断输入不覆盖原文件 |
| 断点 JDBC 参数不匹配 | 从具有 EXECUTE 权限的重载选择 oid/text，显式转换第一个参数，行号绑定 integer |
| 启动时禁用的断点无法启用 | 首次 enable 时补注册；未注册的 disable/delete 幂等；全局禁用时新增断点不注册到服务端 |
| 修改变量失败仍更新本地值 | 检查 set_var 布尔结果，失败保留旧值，成功重新读取服务端值，正确显示表达式计算结果 |
| attach 早于目标进入等待 | 对真库确认的 D0011 状态做有界重试，支持取消和目标提前结束；其他错误不重试；控制连接显式自动提交 |
| 编辑调用者栈帧误改当前帧同名变量 | 非当前栈帧变量只读；服务端 set_var 无 frame 参数，仅能修改当前执行帧 |
| 运行时异常后调试会话卡住 | 识别 `[EXECUTION HAS ERROR OCCURRED!]` 并终止会话。507 此状态拒绝包括 abort 在内的控制命令，关闭目标连接才能释放错误等待并回滚；目标任务仍运行时不发送 turn_off |

## 构建与自动化测试

- JDK 25，项目目标 JavaSE-21；Maven/Tycho 离线 verify。
- 71 个 reactor 模块构建成功，覆盖 GaussDB、debug、UI、PostgreSQL 和公共平台依赖。
- 共 576 个测试：573 通过，0 failure，0 error，3 个仓库原有 skipped。

| 测试模块 | Tests run | Skipped |
|---|---:|---:|
| test.platform | 448 | 3 |
| ext.gaussdb.test | 62 | 0 |
| ext.gaussdb.debug.test | 25 | 0 |
| ext.postgresql.test | 41 | 0 |

原有跳过项是两个 SVG 测试（#26434）和 SQLCompletionAnalyzerTest.testQuotedNamesCompletion（#12159）。本次未新增跳过项。

新增回归覆盖备份 COPY/INSERT 文本、CRLF、多行/转义密码、嵌套注释、quoted identifier、函数体、角色设置、截断文件；调试覆盖重载选择、首次 enable、ID 0、重复注册、无效断点、变量失败/表达式/NULL/常量、调用者栈帧只读、attach 重试/提前结束及异常结束事件。

本次构建日志：`/tmp/dbeaver-fix-20260905-build.log`。reactor 模块列表：`/tmp/dbeaver-gaussdb-fix-tests/reactor-modules.txt`。在仓库根目录设置对应 JAVA_HOME 后，可使用该列表作为 `mvn -o verify -pl` 参数复跑。

## GaussDB 507 真库

环境：Docker `gaussdb-507`，GaussDB Kernel 507.0.0 build d791c80a，分布式部署。使用独立临时账号及 ORA、MYSQL、PG 三个独立数据库；华为 JDBC 506.0.0.b058。

### JDBC 协议：204 项断言通过

已将可复跑 Java 程序及使用说明保存到 `test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/`。它们验证真实 JDBC/服务端协议；DBeaver 会话代码由上述单元测试验证，不能等同于完整 UI 验收。

- 168 项：三种模式 × commit/rollback/abort。覆盖 attach、变量读取、非法整数拒绝且原值不变、表达式求值、带引号文本、NULL、常量拒绝、源码可断点行、类型明确的断点调用、重复断点拒绝、禁用/启用/删除/重新注册、step/next/continue、backtrace、独立连接验证事务结果。
- 21 项：三种模式的嵌套过程调用、双栈帧、同名变量隔离、finish 返回调用者、最终完成。
- 15 项：三种模式的 SELECT function 参数绑定和返回值，以及执行时除零异常、错误等待释放、异常前写入回滚。
- 507 对 SQL NULL 的 info_locals 展示为 `<UNKNOWN>`；客户端保留服务端展示值，不推断为字符串 NULL。

最终复跑日志位于 `/tmp/dbeaver-gaussdb-fix-tests/LiveDebug-final.log`、`LiveNested-final.log` 和 `LiveFunctionAndError-final.log`，三组程序退出码均为 0。

### 原生备份与真实恢复

ORA、MYSQL、PG 三种模式各验证 COPY 和 INSERT 两种 gs_dump 输出，共六组：

1. 创建含 `PASSWORD`、伪 CREATE ROLE 文本、中文、换行、美元标记及注释标记的业务数据。
2. 使用真实 gs_dump 导出 fix schema。
3. 调用编译后的生产脱敏类，确认无角色密码的原生 dump **逐字节不变**。
4. 在独立测试库删除测试 schema，用处理后的 dump 真实恢复。
5. 按 UTF-8 字节比较恢复前后各行，全部一致。

另使用真实 gs_dumpall globals 输出验证角色密码哈希均被删除；仅恢复临时测试角色的脱敏 CREATE ROLE 语句，验证密码为禁用状态。没有恢复其他角色或修改原有账户。含原始密码哈希的临时 dump 已删除。

日志：`/tmp/dbeaver-gaussdb-fix-tests/backup.log`。

### 元数据与权限

- 三种模式的表/视图增删改查通过。
- 直接运行生产 `GaussDBTablePartition.LOAD_PARTITIONS_SQL`，正确读取 p0/pmax 分区；pg_get_tabledef 包含分区定义。
- M 模式使用已有数据库做只读兼容模式检查；M 调试拒绝路径由单元测试覆盖。
- 临时账号的 gs_role_pldebugger 授予/撤销分别使调试角色门控通过/拒绝，最后恢复后清理。

日志：`/tmp/dbeaver-gaussdb-fix-tests/metadata.log`。

## 未覆盖与环境限制

- 上游 PostgreSQL JDBC 42.7.10 在认证阶段报 `Invalid SCRAM client initialization`，未完成该驱动的真库调试链路。仅对临时测试账号尝试会话级密码编码设置，服务端拒绝；没有更改实例认证配置。华为 JDBC 已通过全部上述协议测试。
- 分布式 ORA 创建 Package 返回 `Un-support:DN does not support compiling package.`。集中式 ORA 的 Package 编译、错误行定位、状态刷新仍需目标环境验收。
- 实例只能有一个 M 模式数据库，未新建第二个 M 库；没有修改已有 M 库。
- 未进行完整 DBeaver UI 人工/自动点击验收（快捷键、断点 gutter、Watch、事务弹窗、Package 编辑器）。UI bundle 编译通过不代表这些交互已端到端验证。
- 未覆盖断网故障注入、长时间并发压力、超大备份吞吐及其他 GaussDB 版本。

## 清理

三个人工创建的临时数据库、临时账号、临时恢复角色和凭据文件均已清理；查询确认剩余临时数据库/账号数量均为 0。已有业务数据库未作为恢复目标。保留不含凭据的测试代码、测试库数据样本和执行日志供检查。
