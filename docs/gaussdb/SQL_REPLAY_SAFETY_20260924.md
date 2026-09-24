# SQL编辑器断连后的自动重放边界

关联1.1/1.6/9.7执行异常和连接恢复。只读等待查询断连后自动重试已在QUERY_INFLIGHT_DISCONNECT_GUI_20260924.md记录。本轮进一步验证写操作，不以SELECT结果推断写操作安全。

## GUI发现

在确认不存在同名schema后，创建专用dbv_gui_write_recovery_20260924.probe(id)表并将所有者设为测试账号。1077–1078同一SQL编辑器执行：

```sql
INSERT INTO dbv_gui_write_recovery_20260924.probe(id)
SELECT 7 FROM pg_sleep(120) /* dbv_gui_write_disconnect_20260924 */;
```

独立pg_stat_activity确认目标为测试库、账号、SQLEditor <Script-2.sql>、active及唯一标记后，按这些字段和pid联合筛选终止，返回t。随后1079–1080仍存在同一INSERT查询，backend_start和query_start更新至01:55:51，说明发生了重连重放；表计数当时为0，因为仍在等待。1081点击Cancel后，独立active计数和表行数均为0，再删除专用表/schema。未对业务数据做写入。

这是修复前的实际失败证据：INSERT不应静默自动重放。当前代码旧策略只排除解析成Insert/Delete/Update或SELECT INTO的语句，其他类型及解析不成功默认返回true。运行副本确实包含旧shouldRecoverQuery方法；本轮未采集该次解析AST，不能断言具体是超时还是语法解析失败。

## 修复

新增生产SQLQueryRecoveryPolicy，由SQLQueryJob唯一自动恢复入口调用。仅当成功解析为PlainSelect且无INTO/WITH才允许现有重试；非SELECT、解析失败、MERGE、CALL、事务语句、DDL及匿名块不自动重放。取消原先“未匹配写类型便允许”的默认行为。

这会收紧共享SQL编辑器行为（不是仅GaussDB）：只读WITH、UNION等不再自动重试，用户可确认状态后手动执行。普通SELECT保留已有恢复。此处仅结构门控，**不能证明SELECT函数无副作用**，不得宣传所有SELECT均可安全重复；函数副作用仍需要后续单独策略与验证。

## 测试

SQLQueryRecoveryPolicyTest新增19项：16项禁止重放，包括普通INSERT、INSERT SELECT pg_sleep、ON DUPLICATE KEY、UPDATE、DELETE、MERGE、CALL、匿名块、DDL、COMMIT/ROLLBACK、SELECT INTO、修改/只读WITH、错误SQL及空SQL；3项普通SELECT保留恢复。

run-I3dSGg完整1601项，1578通过、23跳过、0失败错误。结果见test-results-sql-replay-policy-20260924.json。本轮19项为生产策略单测，不是GUI故障注入后的修复复验。

## 待完成

### 首次GUI修复复验仍失败，发现外层重放

1082–1087正常退出后更新model和ui.editors.sql至202609240158并重启。1088执行相同INSERT，确认active后终止专用会话；1089–1090依旧出现新会话中的相同INSERT，说明单独内层门控不足。1091取消后独立active/行数均0，已删除专用表和schema。

进一步检查发现ResultSetJobDataRead用tryExecuteRecover包裹整个dataContainer.readData，内层SQLQueryJob即使不重试，外层仍会重试。新增DBExecUtils.preventAutomaticRecovery，在不允许重放的SQL执行前标记当前线程恢复链，外层看到该状态后不再恢复；最外层结束后原有finally清理ThreadLocal，防止影响后续独立请求。对纯SQL脚本无外层恢复的情况该调用无副作用。

DBExecRecoveryBoundaryTest补3项：内层禁止后外层不调用错误分类/恢复；嵌套成功返回后后续取数失败仍不能重放；最外层结束或外部调用后不污染下一独立请求。首轮run-UAO3kx因测试私有接口无法被Mockito模拟报3个测试构造错误，已改为公开测试接口，不算产品红测。

run-Rjg8sa完整回归1604项、1581通过、23跳过、0失败错误，新增3项恢复边界测试通过；安全结果test-results-sql-replay-boundary-20260924.json。

### 外层保护修复后的GUI复验通过

1092–1097正常退出、更新model和ui.editors.sql至202609240207并重启；重建独立空表。1098执行相同INSERT SELECT pg_sleep，服务端确认active及query_start=02:12:04.328829，再按pid/库/账号/application/唯一SQL标记终止目标连接。

- 1099–1100：活动测试INSERT计数0、表计数0；执行日志记录该INSERT失败，源码旁错误面板可见57P01及FATAL terminating connection due to administrator command，没有重新出现等待查询。
- 1101–1102：同一SQL编辑器执行新的中文SELECT，成功1行，文本结果实际为恢复验证中文/42；再次独立核对原INSERT active及表计数仍为0。
- 1103–1105：显式提交新的INSERT VALUES(9)，GUI执行日志成功、更新1行；独立SELECT仅返回9，证明后续独立写操作可用，没有错误地重放原值7。
- 删除专用表/schema并确认schema目录计数0。只清理本轮专用对象，无业务对象变化。

本次INSERT在执行中断连后不自动重放、无残留数据、后续读写可恢复的GUI场景通过。它不涵盖提交已经成功但响应丢失，也不能推广为所有SQL恰好执行一次。SQL函数副作用、手动事务上下文恢复和其他语句类型仍需继续覆盖。
