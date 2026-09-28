# 会话操作执行器：异常与资源关闭矩阵

对应历史清单1.6的执行器异常/关闭资源，以及连接监控中取消/终止请求的客户端故障处理。

## 新增18项

取消和终止分别覆盖以下8个故障位置，共16项：

| 故障位置 | 预期断言 |
| --- | --- |
| createStatement | 不执行SQL，不关闭未创建的语句或结果集 |
| executeQuery | 最多发送一次SQL，关闭语句，不关闭未创建结果集 |
| ResultSet.next | 传播原始SQLException，关闭结果集和语句 |
| getBoolean | 同上，不能视为已获得确认 |
| wasNull | 同上，不能视为已获得非NULL确认 |
| ResultSet.close | 即使已经读到true，资源关闭异常也不能被吞掉；不重发请求 |
| Statement.close | 保留关闭异常，不重发请求 |
| getBoolean失败且两次close也失败 | 读取异常为主cause，两条关闭异常按顺序保留为suppressed |

再补取消/终止各1项：服务端响应false，结果集和语句close均抛错。未确认操作的DBException及本地化消息保持为主异常，两个关闭异常保留为suppressed。

所有场景均调用生产PostgreSessionManager.alterSession；模拟JDBC故障，不发真实服务器操作。断言准确请求SQL和调用次数、原始异常身份、关闭次数以及调用者session不被关闭。模拟SQLSTATE统一使用08006，不把测试结果宣称为覆盖所有数据库错误码。

## 结果

新增18/18通过，PostgreSessionAcknowledgementTest累计26/26、零跳过，无生产修改。检查的是单次方法调用不自动重放；不保证用户手动再次点击、连接恢复或PID复用等跨操作场景。

73模块五个测试模块合计2723项：2548通过、174跳过、0失败、1错误（RestTest网络权限拒绝）。独立门禁拒绝整体验收；门禁自身7/7。仍无本轮真库/GUI/整包验收，未推送远程。

日志：`/tmp/session-failure-matrix-20260929.log`；[脱敏逐项证据](evidence/SESSION_FAILURE_MATRIX_REACTOR_20260929.json)。上述模型故障覆盖不能替代真实断网、取消后数据结果或GUI错误展示验证。
