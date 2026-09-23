# 多对象删除失败与重试：JDBC 验证

对应历史清单 9.6 原子执行失败/回滚/重试，以及 3.2/3.4 对象删除依赖。

测试入口：`GaussDBHistoricalJdbcLiveTest.multiObjectDropFailureRollsBackEarlierDdlAndAllowsCorrectedRetry`。

在随机隔离 schema 中建立三张各含 id=7 的表，以及依赖第二张表的视图。手工事务先删除第一张表，删除第二张表预期因依赖报 2BP01，再尝试删除第三张表预期因事务已失败报 25P02。rollback 后独立 JDBC 连接读取三张表和视图，都应保留唯一值7。

同一连接开启新事务，先删依赖视图，再删除前两张表并 commit。独立连接通过元数据确认这些对象消失，第三张表仍保留原始数据。

边界：此测试执行明确的 SQL/JDBC 事务，不调用批量删除 UI，不能据此声称 UI 默认启用原子执行，也不等于所有对象种类和部署形态都已通过。失败回滚和成功提交分开断言，不将第一步删除成功误算为批量成功。

执行结果：507分布式 run-51Q0m0 通过上述全部断言，最新回归1544项、1521通过、23跳过、0失败/错误。报告 `test-results-batch-drop-rollback-20260924.json`。随机schema由测试finally清理，权限测试运行器也报告本轮临时角色已删除。
