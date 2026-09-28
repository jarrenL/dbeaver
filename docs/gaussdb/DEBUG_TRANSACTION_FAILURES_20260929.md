# 调试事务收尾：异常路径回归

## 需求对应

原始需求“调试完成后选择提交或回滚”，以及历史调试用例的提交/回滚异常处理。目标是避免把已确认成功误报为失败并重复提交，或把未确认事务当成完成。

## 本轮新增8项

| 场景 | 注入方式 | 必须验证的结果 |
| --- | --- | --- |
| COMMIT/ROLLBACK成功，恢复网络超时失败（2项） | JDBC事务方法正常返回，恢复原超时抛SQLException | 收尾正常返回，pending清除；重复完成通知不再次执行事务；JDBCSession关闭 |
| COMMIT/ROLLBACK失败，恢复超时也失败（2项） | 两处分别抛出不同SQLException | DBGException保留事务原始异常；pending保留；后续COMMIT被拒绝，不发第二次提交；JDBCSession关闭 |
| 原网络超时20秒，COMMIT/ROLLBACK（2项） | getNetworkTimeout返回20000 | 顺序为设置10秒上限→执行事务→恢复20秒→关闭会话；不误执行相反动作 |
| 读取/设置网络超时失败（2项） | 在事务命令前抛SQLException | 原始错误传播，commit/rollback都不执行；pending保留，会话关闭 |

直接调用生产GaussDBDebugSession.completeTransaction，使用Mockito JDBC会话注入异常；不模拟该方法的返回结果。既有并发关闭、等待目标完成、取消和提交失败不自动重试测试一并复验。

## 验证与范围

GaussDBDebugSessionTest **42/42通过**；原有DebugTransactionCompletionTest **3/3通过**。本轮新增8项全部通过，**没有因此修改生产事务实现**。这两类测试加入必跑类清单，门禁自身7项测试通过。

完整逐项结果见[回归证据](evidence/DEBUG_TRANSACTION_FAILURES_REACTOR_20260929.json)。日志：`/tmp/debug-transaction-errors-20260929.log`。诊断构建允许错误后继续收集后续模块结果，但最终验收门禁不忽略错误。

73模块、五个测试模块合计**2603项：2429通过、173跳过、0失败、1错误**；唯一错误仍为RestTest本地网络权限拒绝。独立门禁拒绝整体通过；跳过项不算通过，不与之前重复回归的数量累加。

这些是生产方法级的异常注入测试，不等于真实网络断线、提交已到服务器但应答丢失、数据库持久化结果或客户端对话框验收。尤其失败后的ROLLBACK不能证明之前的COMMIT没有生效；仍需独立连接核验真实结果。未对上述未验场景作通过结论。
