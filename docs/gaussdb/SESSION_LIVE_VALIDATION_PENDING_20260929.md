# 当前会话标识：真库验收用例与执行阻断

## 用例内容

新增GaussDBHistoricalJdbcLiveTest.realOwnSessionPreservesBackendIdentityAndDatabase，仅使用独立测试连接查询自身：

1. SELECT pg_backend_pid()::text,current_database()取得数据库返回的完整编号和库名。
2. 使用生产PostgreSessionManager.generateSessionReadQuery（显示空闲）生成查询，增加只看当前连接的WHERE sa.pid=pg_backend_pid()，不读取其他用户会话。
3. 直接用真实JDBC ResultSet构造生产PostgreSession，核对getSessionId、long getPid及库名，并要求自身恰好一行。
4. 关闭语句/结果集后再次SELECT 42，核对连接可继续读取。

这条用例不执行DDL/DML，不发送取消或终止请求。不强制断言服务器一定生成超过32位的编号；若返回小编号，只能证明该实际编号的读取，不能宣称验证过真库大编号。

## 专项入口与门禁

辅助测试仓脚本run-live-plan-focused.mjs新增--sessions。它显式编译当前PostgreSession、PostgreSessionManager及集成测试源码，读取已有测试平台依赖，不依赖旧GUI安装目录。--sessions与--set-queries互斥；会话专项只读，不要求ALLOW_DDL。

必填环境变量：GAUSSDB_HISTORY_CONNECTION（本地受保护的连接属性文件）、GAUSSDB_HISTORY_JDBC（厂商驱动jar）；按驱动设置GAUSSDB_HISTORY_DRIVER_CLASS。运行：

```sh
node scripts/run-live-plan-focused.mjs --sessions
```

门禁要求准确的1条会话用例身份、新鲜报告、零跳过/失败/错误，不能用其他用例的相同数量冒充。新增选择门禁自测，连同既有门禁共21/21通过；这不是数据库功能通过数。

## 2026-09-29实际结果

专项已编译并真正启动测试，建立厂商JDBC连接时抛java.net.SocketException: Operation not permitted。1项失败、0项通过、0项跳过；数据库查询和模型真实映射均尚未执行，因此真库验收未通过。运行目录：`/tmp/live-plan-focused-KPqPlZ`，日志：`/tmp/session-live-attempt-20260929.log`。Docker API也被权限拒绝，不能以容器内执行补验。

常规73模块回归未提供真库环境变量，新用例按设计跳过。该回归2695项：2520通过、174跳过、0失败、1错误（RestTest网络权限拒绝）。独立门禁拒绝整体验收；[逐项证据](evidence/SESSION_LIVE_REGISTRATION_REACTOR_20260929.json)。专项失败与常规跳过是两次不同执行，不能合并成通过。

辅助只读审核调用同样在初始化日志时遭Operation not permitted，未产生审核报告。未发布新包、未推送远程；后续必须在允许访问数据库的运行环境执行上述专项，并继续GUI和实际大编号验收。
