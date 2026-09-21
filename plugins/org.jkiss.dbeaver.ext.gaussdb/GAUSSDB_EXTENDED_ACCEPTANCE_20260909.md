# 本地补充验收（2026-09-09）

## 结论与范围

本轮继续真实 macOS 客户端验收，排除 Windows。发现并修复包编译取消、断连诊断、
原生 PostgreSQL 子函数源码解析及正常结束误报。修复后重新执行相应真实场景。
本报告补充而不替代此前 SWTBot、包编译和包边界报告；不宣称所有客户版本、所有异常组合已验收。

环境：macOS ARM64；临时产品 `/tmp/gaussdb-swtbot/DBeaver.app`；
GaussDB 507 集中式 `gaussdb-507-ha-lab:55452/package_lab`（普通用户 package_tester）；
原 GaussDB 507 测试库 `gaussdb-507:5432/dbeaver_ui_0909`；
原生 PostgreSQL 16.15 + pldbgapi `dbeaver-pg-debug-review-20260909:55439/postgres`。
所有新增数据、权限变更均限定专用测试对象/用户，未操作业务表。

## 实测结果

主证据目录 `/tmp/gaussdb-swtbot/queue/`，编号对应 `NNN.cmd.result`。
不是以动作返回 OK 作为验收标准，而是同时核对后续控件、源码位置和数据库结果。

| 补验项 | 本轮结果 | 证据与限制 |
| --- | --- | --- |
| 包编译等锁取消 | 通过，修复后 JDBC 真正退出等待，锁持有者仍在运行；无假成功弹窗 | 604–609；取消后目标连接 idle、waiting=f，锁持有者仍执行 pg_sleep |
| 取消后恢复 | 通过，释放测试锁后重新编译成功 | 611–612 |
| 编译连接被终止 | 通过，显示连接终止而不是 SPEC 第 1 行编译错误 | 804；此前失败样例 614–617 |
| 断连后重连恢复 | 通过，显式断开/重新连接后同包编译成功 | 824；不承诺断连后自动重试 SQL，未重连直接重试仍明确报连接已关闭 |
| PostgreSQL 新建连接 | 通过，GUI 下载 JDBC 42.7.13，连上 PostgreSQL 16.15 | 主队列 629–642 |
| PostgreSQL 变量与嵌套栈 | 通过，v=6 改为 20，Step Over 后 21，进入 child，父子帧分别显示自身源码 | 725–739 为首轮，修复后 768–775；真实 audit 值 23 |
| PostgreSQL 正常结束 | 通过，确认目标 SQL 成功后不再弹 pldbg_continue 错误 | 785–787；第二种服务端结束诊断补齐后，无 Problem Occurred，audit 增加未改变量的结果 9 |
| A/B/C/PG/M 模式识别/能力 | 通过，真实五个库、客户端生产模型加载 | 859；不是五种模式完整 SQL/DDL/调试端到端矩阵 |
| 调试角色撤销/恢复 | 通过，有角色通过，撤销后拒绝，恢复后通过 | 872–874；最终恢复原测试用户角色 |
| 目标函数 EXECUTE 权限 | 通过，无权限拒绝，授予通过，撤销再次拒绝 | 899、901–902；新增 admin 所有的 ext_privilege_probe |
| SQL 语言函数门控 | 通过，eligibility 明确拒绝 sql 语言 | 900；API 本身可执行不代表该 routine 可调试，二者是独立检查 |
| 混合有效/无效包连续批量编译 | 通过，四包两次编译，列表始终只有两个无效包，无跨包串错/重复累积 | 828、832；boundary_body/BODY/3、boundary_mixed/SPECIFICATION/2；独立 pg_object 状态核对 |
| 空白工作区默认 GaussDB 驱动 | 通过，下载、测试连接、保存连接及展开导航 | 见下节独立工作区证据 |
| 默认驱动包编译/关闭重开源码 | 通过，BODY 真错误定位第 3 行；关闭编辑器后从列表重开仍定位第 3 行 | 新工作区 050、053、055 |

模式模型实际输出：

| 数据库后缀 | getCompatibility | 存储过程能力 | 包能力 |
| --- | --- | --- | --- |
| a | ORACLE | true | true |
| b | MYSQL | true | false |
| c | TERADATA | true | false |
| pg | POSTGRES | true | false |
| m | M | false | false |

模式和权限验证通过测试 bundle 调用已加载的生产模型、生产 eligibility 和 capability detector，
使用真实 JDBC 会话，不是 mock；但不能据此声称所有菜单显示/隐藏路径都完成 GUI 验收。

## 空白工作区验证

新工作区 `/tmp/dbeaver-fresh-acceptance.oc3oBx/workspace`；证据为同目录 `queue/NNN.cmd.result`。
没有复制旧连接配置，使用产品注册的默认 GaussDB 驱动，从 JDBC URL 新建连接。

- 031：出现默认 gsjdbc4-1.1.jar 下载确认。
- 033：下载完成，测试连接成功，服务器显示 GaussDB Kernel 507.0.0 build d791c80a。
- 036、038：保存 package_lab 连接并成功展开。
- 050：编译 boundary_body，错误 BODY/3，首次打开源码 caretLine=3。
- 053：关闭包编辑器，保留非模态错误列表。
- 055：从列表再次打开源码，正确 BODY 页、caretLine=3、selection={93,94}。

另外实测原生驱动入口在未提供 JAR 时无法实例化 com.huawei.gaussdb.jdbc.Driver（018），
符合该入口要求手工提供版本匹配 gaussdbjdbc.jar 的配置设计。默认驱动成功不等于原生/M 驱动
零配置即用，也不等于操作系统全新用户、无共享缓存或所有 macOS 版本验收。

## 修复内容

1. GaussDBPackageCompileHandler 使用可取消的 AbstractJob。旧进度服务在 JDBC 阻塞时只设置
   canceled 标记，不中断 SQL；现在取消当前 JDBC blocking object。编译器在取消前后直接退出，
   不继续查询 GS_ERRORS，不弹“编译成功”。取消可能已经发生在部分包完成之后，不承诺回滚已完成 DDL。
2. 包编译器将 57P01/57P02/57P03、连接类、认证类以及 42501 当作基础设施/权限错误抛出，
   不伪装成源码错误。57P01 做了真库终止连接复测；其他新增分类有单测，并非都做了故障注入。
3. PostgreResolver 根据当前栈帧 OID 解析源码，而不是固定用启动函数 OID；跨 schema 提供数据库
   routine 查询兜底（跨 schema 本轮是单测，GUI 为同 schema 父子函数）。断点 SQL 同样使用描述符的目标 OID。
4. PostgreSQL 本地调试正常完成可能以 08006 + `select() failed waiting for target` 或
   `debugger connection terminated` 结束控制会话。只有匹配诊断且目标 JDBC 执行已成功时才忽略；
   等待目标完成最多 2 秒。其他提供者默认不忽略，远程 attach 不走此放行逻辑。
5. 测试专用 SWTBot 增加 dropdown/view/model/modes，不加入产品 feature。

## 构建与自动回归

- 完整产品构建成功：`/tmp/gaussdb-swtbot/extended-build4.log`。
- 71 模块最终 verify 成功：`/tmp/gaussdb-swtbot/extended-final-tests.log`（此前 extended-tests2.log 同样通过）。
- 总计 597 项：594 通过、3 跳过、0 失败、0 错误。
  platform=448（3 跳过）、GaussDB model=67、GaussDB debug=39、PostgreSQL=43。
- 新增取消、基础设施错误、栈帧 OID/跨 schema 解析、PG 完成诊断分类回归。

## 仍不能扩大的结论

- Windows 按用户要求不测试；客户不同内核版本、真实 CN+DN 集群不在当前通过范围。
- 五种模式的完整 DDL、数据类型及全套调试矩阵仍未逐个执行；本轮补齐的是模式识别/能力模型。
- 权限检查未对每一个 DBE_PLDEBUGGER API 逐一撤权（避免修改共享系统 API 权限），签名缺失/
  不匹配组合由单测覆盖；完整菜单动态刷新仍需单独逐入口验收。
- PG 跨 schema 双击和子函数断点 GUI、远程 attach、失败目标执行与断网组合仍需补测。
  PG Step Return 在此环境原本禁用，不将其计为通过。
- 已覆盖首次加载及关闭重开；人为长时间源码加载、关闭瞬间竞态、并发多编译任务仍不是压力测试。
- 单次默认驱动下载/连接成功不能代替客户安装介质及签名、公证/系统兼容性交付验收。

测试容器、专用五模式库和 fixtures 保留以便复测；原测试用户调试角色已恢复，
新函数 ext_privilege_probe 最终维持不授予执行权限的状态。没有删除业务数据。
