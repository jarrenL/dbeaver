# 跨 schema 与竞态补验（2026-09-09）

## 范围与结论

本轮使用现有 macOS SWTBot 客户端、GaussDB 507、PostgreSQL 16.15 + pldbgapi，
新增跨 schema 的实际调试与断点测试，以及并发编译/迟到导航回调测试。Windows 未测试。
修复了 GaussDB 跨 schema 源码解析缺口及关闭编辑器后的导航回调 NPE。
主要场景已复测通过，但下述 PostgreSQL 边界仍不能计入通过。

## 真实调试验证

证据目录 `/tmp/gaussdb-swtbot/queue/`，以下编号为 `NNN.cmd.result`。

| 场景 | 实际结果 | 证据 |
| --- | --- | --- |
| GaussDB 跨 schema 同名对象解析 | ui_acceptance.ui_child=205259，ui_cross_0909.ui_child=205287；按 OID 分别返回正确 schema，启动配置不变 | 984–985；模型/API 测试，不单独算 GUI |
| GaussDB 跨 schema 单步进入 | 从 ui_acceptance.ui_parent 进入 ui_cross_0909.ui_child，显示 `p_in + 102` 而不是另一 schema 的 `+2` 源码 | 1103、1108 前后控件记录 |
| GaussDB 子过程断点 | 在子过程第 5 行设置断点，点击 Continue 后命中第 5 行；父栈仍在第 5 行 | 1108；断点窗格明确显示 ui_cross_0909.ui_child |
| GaussDB 跨 schema 双击导航 | 父/子帧来回双击，分别显示父过程与 +102 的子过程源码 | 1110、1112 |
| GaussDB 完成/回滚 | 到达事务选择窗口，点击 Rollback；独立 SQL 查询审计表仍为 count=2、sum=24 | 1114–1116 + gsql |
| PostgreSQL 跨 schema 单步进入 | ui_regression.parent → ui_cross_0909.child，栈和 +102 的源码正确 | 912；重新打开干净编辑器后 1085 |
| PostgreSQL 跨 schema 双击 | 从子帧切回父帧，显示 ui_cross_0909.child 调用位置 | 919 前后控件记录 |
| PostgreSQL 子函数中间语句断点 | 设置第 4 行断点，Continue 后停在 child 第 4 行，父栈在 parent 第 5 行 | 1090；数据库日志确认为 `pldbg_set_breakpoint(1, 16432, 4)` 后 `pldbg_continue(1)` |

GaussDB 实际调试使用已安装修复后的插件。初版修复遍历 schema，实测会加载不相关的系统
routine 并产生类型警告；最终版本改为通过 pg_proc 的 OID 查询目标 namespace，再加载其专用
GaussDB procedure/function cache，避免按名字误配或遍历所有系统对象。

## 并发及生命周期竞态

### 同一包的两个并发编译请求

1. 专用会话持有 ui_pkg.pkg_ui 的编译锁并执行 pg_sleep(90)。
2. 在 UI 连续启动两个编译任务。进度窗格有两个任务，服务端一个 ALTER PACKAGE 正在等锁。
3. 取消第二个任务。它从进度窗格消失，第一个 ALTER PACKAGE 仍为 active、waiting=t。
4. 只终止本轮 application_name=dbeaver_acceptance_lock 的测试锁持有者。
5. 剩余任务正常成功，没有连带取消、连接错误或重复错误弹窗。

证据 967、969、970 和同期 pg_stat_activity。此为一个真实并发/取消交错，
不是多客户端高负载压力测试，也不证明所有任务顺序都安全。

### 确定性迟到回调重放

测试专用 `navigation-race` 命令捕获真实包编译结果窗口、实际 EntityEditor、当前导航请求号和
真实诊断对象。在 UI 线程关闭编辑器/对话框，或发出第二行的真实导航请求之后，再调用之前的
`positionWhenLoaded`，模拟定时回调恰好在状态变化后到达。不向生产代码加入延迟或测试开关。

| 交错 | 修复前 | 修复后/最终证据 |
| --- | --- | --- |
| 新请求先到、旧回调后到 | 通过，旧请求不抢回焦点 | 1001 |
| 对话框先关闭、旧回调后到 | 通过，不访问已释放窗口 | 1005；1057 再次通过 |
| 编辑器先关闭、旧回调后到 | 1003 复现 NPE：editor.getSite().getPage() 已为 null | 补空值生命周期防护后 1039、1043、1047、1051、1055 连续 5 次通过；不重新打开已关闭编辑器、不改变活动编辑器 |

第一次修复复测误用了尚未更新的临时运行 JAR（1022 前后仍复现旧代码 NPE），随后用 javap
核对运行 JAR 中确有新增检查，再重启客户端重放。仅最后五次记为修复通过。
上述测试控制的是迟到回调的交错点，不声称模拟了网络任意延迟、所有 SWT 事件排列或长期压力。

## 尚未闭环的 PostgreSQL 边界

后续状态（同日 Linux 补验）：下列第 1、2 项已修复，并在麒麟 ARM64 真实 PostgreSQL 客户端完成首语句断点命中、禁用跳过、变量修改与重启恢复后的正文定位验证。详见仓库根目录 [Linux 验收报告](../../GAUSSDB_KYLIN_LINUX_ACCEPTANCE_20260909.md) 的 `0303/0316/0325` 记录。以下保留本轮发现时的原始描述，不再代表最新待办。

1. **子函数第一条可执行语句的普通行号断点**：本机 pldbgapi 将第一条语句检查位置转换为 -1。
   实际发送 child OID=16432、line=3，下一次调用未命中；源码跳转和单步暂停不等于这个断点通过。
   改用第二条语句 line=4 后才真实命中。本轮未修改 PG 首语句行号映射。
2. **恢复旧编辑器后的源码形式/定位**：一次重启恢复的 child 编辑器显示完整 CREATE FUNCTION DDL，
   光标停在第 1 行，而不是 debugger body 的行号（948）。关闭旧父/子编辑器后重新调试，
   正确显示纯 body 并命中第 4 行。此恢复路径仍需进一步定位，不将其算作通过。
3. 本轮没有遍历“跨 schema 断点禁用/重新启用/删除 × 每种兼容模式 × 远程 attach”的完整组合。
   GaussDB M 按已有能力控制不启用 PL/SQL 调试。

## 重放与回归

- 生产改动：GaussDBDebugCore 按 OID 定位跨 schema routine；包编译结果窗口补关闭 editor/page 防护。
- 单测：GaussDBCrossSchemaTest 校验跨 schema OID、查询参数、启动配置不变、原 schema 回退及未知 OID 拒绝。
- SWTBot 新增 `resolve-frame TREE_ITEM_ID OID EXPECTED_SCHEMA` 和
  `navigation-race SHELL_ID stale-request|closed-editor|closed-dialog`，仅用于专用测试工作区。
- SQL fixtures 见测试目录 `cross-schema-gaussdb.sql`、`cross-schema-postgresql.sql`。
- 完整产品构建：`/tmp/gaussdb-swtbot/cross-product-final.log`，BUILD SUCCESS。
- 最终 verify：`/tmp/gaussdb-swtbot/cross-final-tests.log`，BUILD SUCCESS。
  共 598 项，595 通过、3 跳过、0 失败/错误：platform 448（3 跳过）、GaussDB model 67、
  GaussDB debug 40、PostgreSQL 43。

测试数据变更：保留 ui_cross_0909 及专用测试父过程的跨 schema 调用，方便复测；GaussDB 调试选择
回滚，没有新增审计行。PostgreSQL 测试 audit 保留各次运行结果。诊断期间临时启用的 postgres
角色 log_statement 已 RESET；测试锁已释放，未修改其他 Docker 业务服务。
