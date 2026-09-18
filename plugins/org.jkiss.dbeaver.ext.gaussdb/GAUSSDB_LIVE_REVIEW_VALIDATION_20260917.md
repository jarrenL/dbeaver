# 09-17 审查修复：真实数据库验证

> 本文为第一轮历史记录。后续针对未闭环项又跑了真实资源事件、原厂工具失败路径和麒麟 SWT，发现三个未解决问题，见 [第二轮补测报告](GAUSSDB_REMAINING_VALIDATION_20260917.md)。不能将本文的全绿结果理解为当前全部验收通过。

## 结论

本轮不是仅重跑 mock：实际连接 GaussDB 507，执行了调试协议、当前生产断点/诊断代码、原厂 gs_dump/gsql 和分布式 CN 事务验证。**发现并修复了包编译错误行号相对 CREATE 头偏移的问题。**

不能据此宣布所有客户端功能全部验收：本轮没有 SWT 窗口/快捷键验证；普通账号的原生工具密码传递尝试没有通过；全库角色密码脱敏失败路径仍是上一轮的注入测试，不是本轮实际 gs_dumpall 验收。

## 环境与安全范围

- 集中式：已有 `gaussdb-507-ha-lab`，内核 `507.0.0 build d791c80a`，端口 55452。本轮恢复其原有数据目录，没有重建实例。
- 分布式：运行中的 `gaussdb-507-cn-lab`，同内核，通过宿主机 55432 的 CN 访问 `distributed_acceptance`，未直接向 DN 写入。
- 集中式使用新建的独立账号和三个随机后缀测试库，兼容模式 A、B、PG。这个集中式内核接受 A/B 建库标识，ORA/MYSQL 别名被拒绝；不是静默降级。
- 宿主机旧 55452 映射未恢复，因此短期使用仅绑定 `127.0.0.1:55453` 的转发容器，并增加仅允许测试账号、三个测试库和转发 IP `/32` 的 sha256 认证规则。没有新加 trust 或开放公网。
- JDBC 为现有华为驱动包的 `gsjdbc4.jar` / `org.postgresql.Driver`，不是 PostgreSQL 社区 JDBC；CN 冒烟使用既有 `gsjdbc200.jar`。
- 测试只创建/删除独立测试对象，不替换旧验收 schema，不修改业务数据。密码只在受限临时 properties/进程环境中使用，不写入本报告或命令参数。

## 场景与结果

| 场景 | 实际测试方法 | 结果与边界 |
|---|---|---|
| 三模式断点、变量、提交/回滚/终止 | `LiveDebug` 对三个测试库执行真实 target/controller 双连接，独立连接查事务结果 | 168 个断言通过；底层协议，不代表客户端 UI |
| 嵌套调用、栈、frame 变量、返回 | `LiveNested` 实际调用内外两层过程并读写变量 | 21 个断言通过 |
| 函数调用及运行时错误收尾 | `LiveFunctionAndError` 验证 SELECT 调用、错误状态、关闭目标后的回滚 | 15 个断言通过；合计上述协议 204 个断言 |
| 当前生产默认参数校验 | `GaussDBSessionLiveTest` 用真实目录查询执行 `validateDefaultInvocation`；先单一默认签名，再增加重载 | 无歧义通过；新增重载被拒绝。取消/超时配置另有单测，没有做网络故障注入 |
| 当前生产断点增删去重 | 真库 turn_on/attach 后调用当前 `GaussDBDebugSession.add/disable/enable/removeBreakpoint`，读 `info_breakpoints()` | 重复添加后只有 1 个，删除后 0 个 |
| 数据库身份与删除回调 | 当前 controller 拒绝伪造的其他库/无库名 marker；将 delta 交给当前 `DatabaseDebugTarget.breakpointRemoved`，读服务器断点数 | 拒绝错误身份；delta 删除有效且不读取已删除 marker。模型/会话工厂为测试桥接，不是两个 GUI 窗口的跨库操作 |
| 包 ALL/SPEC/BODY | 在集中式 A 库创建实际包，对三个 target 使用当前 `getCompileSQL`，实际执行 ALTER，再调用生产诊断读取器 | 三种有效包编译通过；不是只检查 SQL 字符串 |
| 包 SPEC/BODY 错误定位 | 强制保存引用不存在类型的无效包部件，编译后读取 GS_ERRORS 和 GS_SOURCE，断言最终行实际含失败声明 | 首轮逐行断言失败，修复后 BODY 指向第 3 行、SPEC 指向第 2 行，均为失败声明所在行 |
| 诊断目录读取失败 | 仅在测试会话桥接中把诊断表名换成不存在的测试表，获得真实 SQLSTATE 42P01，调用生产诊断错误处理 | 保留 SQL 异常原因、无伪源码第 1 行诊断；没有删除系统目录。42501 仍为之前单测，不冒充真库权限测试 |
| 原厂备份/恢复及大 stdout | gs_dump 只备份独立 schema；删除该 schema 后用 gsql 执行实际 dump，并额外输出约 4 MiB；ProcessBuilder 使用当前生产重定向配置 | 使用已有 OS-owner 本地认证通过；45 秒界限内结束，独立 JDBC 读回 3 行、sum=6。此路径不证明普通账号密码认证通过 |
| 分布式 CN 事务 | CN 创建随机临时 schema 和 HASH 分布表；写入两行 COMMIT，再写一行 ROLLBACK，另一连接聚合校验 | count=2、sum=3；通过后删除临时 schema |
| 普通账号原生工具认证 | 尝试给 docker exec 转发 PGPASSWORD，使用测试账号运行原厂 gs_dump，含 `-w` 变体 | **未通过**：提示密码或非零退出。不能据此宣布 DBeaver 内普通账号原生备份可交付；原厂工具认证适配需单独闭环。没有把密码直接塞进进程命令行来绕过问题 |

## 本轮新增生产修复

实际证据：`GS_ERRORS.line=2`，但 `GS_SOURCE` 由编辑器展示为：

```sql
CREATE OR REPLACE PACKAGE BODY review_live.review_pkg AS
 FUNCTION value_of RETURN INTEGER AS
 v review_live.missing_dependency.id%TYPE;
```

旧代码把第 2 行直接交给编辑器，落在 FUNCTION 头而不是第 3 行的失败声明。

- `GaussDBPackageCompiler.logErrors` 同时读取对应 GS_SOURCE，转换内容行号为展示源码行号。
- 新增 `GaussDBPackageSourceLines`，识别 AS/IS 头，跳过引号和注释，计算头部行偏移。
- 增加单测覆盖 SPEC/BODY、同行头、多行头、带 AS/IS 的引号名/注释，以及缺失源信息。
- 真库分别校验 SPEC 与 BODY 的目标行文本；不能将这一结果外推为全部版本、全部 DDL 排版或实际双击 GUI 验收。
- GS_SOURCE 查询不可用时仍按“诊断不可读取”处理，不伪装编译成功。没有 GS_ERRORS、仅 SQLException 消息的后备定位不是本轮真库逐行验收范围。

## 最终测试结果

最终 reactor 执行于 2026-09-17 18:31:41 完成，71 模块 `BUILD SUCCESS`：

| 模块 | 总数 | 失败/错误 | 跳过 |
|---|---:|---:|---:|
| platform | 448 | 0/0 | 3（原有） |
| GaussDB model | 86 | 0/0 | 0 |
| GaussDB debug | 69 | 0/0 | 0 |
| PostgreSQL | 45 | 0/0 | 0 |
| 合计 | 648 | 0/0 | 3 |

即 645 通过、3 跳过。包含 5 个显式启用的真库/原生工具测试方法；前述 204 个独立 Java 协议断言单独统计，不重复算作 JUnit 用例。Checkstyle/Spotless 仍由构建参数跳过；`git diff --check` 通过。

历史失败保留在记录中：fixture 的 Mockito 嵌套初始化、缺 schema 与缺表 SQLSTATE 区别、CASCADE 不能代替保存无效包体、docker cp 改变文件属主、gsql 不支持 gs_dump 的 `-w` 参数。这些测试夹具问题已修正；普通账号原生认证未通过则仍是明确边界，不因切换本地认证而撤销。

原始日志目录：`/tmp/gaussdb-live-review-20260917.YM4ZVu/`，主要文件 `protocol.log`、`nested.log`、`function.log`、`cn-smoke.log`、`live-native-final.log`。日志目录是本机临时证据，本报告和 opt-in 测试源码为仓库内长期记录。复跑参数见 integration/README.md；测试前重新创建独立环境，不复用已清理的凭据。

## 未覆盖项

- SWT 的参数格失焦/Tab/Enter、实际 marker 生命周期、快捷键和双击定位。
- Windows/客户麒麟实机、新制品打包及普通账号 native 密码链路。
- 真正 gs_dumpall 失败/取消后的角色密码清理，网络断开/未知 COMMIT 结果及完整 GUI 跨库并发。

## 环境清理

已删除本轮三个随机测试库及其账号，管理员查询残留数量均为 0；密码文件已删除。测试数据是合成数据，可由夹具重建，没有删除原有验收库或业务对象。CN 的随机临时 schema 已删除，运行中的 CN 实例未停止。

`gs_hba.conf` 恢复后 SHA-256 与运行前一致：`e7bf4026e9e594463b92e6fc644b5aa311ff0bb2149c176e7dad8c26bc8a0060`。临时转发容器已停止并自动移除。集中式服务经 gs_ctl 确认正常关闭，容器恢复到运行前的停止状态；容器 PID 1 为 sleep，其最终退出码不代表数据库异常关机。

代码尚未 commit/push，也未替换客户安装包。测试结果不能自动转化为旧候选包的验收结果。
