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

更新专用GUI副本的model及ui.editors.sql插件，重跑同一INSERT，确认故障后不重试、没有残留数据并可显式恢复。SQL函数副作用、提交已成功但响应丢失、手动事务上下文恢复和其他语句类型仍需继续覆盖。当前不能标记写操作断连GUI已验收通过。
