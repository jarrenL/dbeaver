# 会话标识读取失败：拒绝伪造编号0

## 复现与修复

历史清单连接监控/会话列表的异常路径新增4项：先读取正常PID=42的行，再在第二行读取PID时分别抛08006、42501、22003，或返回SQL NULL。旧实现使用safeGetLong，异常被吞掉并返回0；SQL NULL也变成0。四项均未抛出预期异常，红测4失败。

PostgreSession现改为直接getLong并立即检查wasNull，读取SQLException原样传播，NULL以SQLSTATE 22004拒绝。PostgreSessionManager已有异常包装和资源关闭，现可向上报告失败，不返回包含伪造会话的成功快照。其他展示字段仍采用原有容错读取，未改为全部严格读取。

## 验证步骤与结果

每项调用生产getSessions入口，模拟JDBC记录和故障，断言：

1. 返回DBException，三种读取异常保留原始cause；NULL场景cause的SQLSTATE为22004。
2. ResultSet和Statement均关闭；调用者session不关闭，不发取消/终止操作。
3. 将下一次读取替身恢复为PID=43，再次显式调用getSessions，只返回新快照，不保留上次不完整结果。

4/4通过，GaussDBSessionManagerTest共10/10；此前64位标识的PostgreSessionIdentityTest仍6/6通过。恢复步骤是模拟后续读取，不代表真实失效连接可直接复用。

构造器现在声明SQLException，源码调用者需处理；生产唯一构造路径的manager已有捕获。本轮执行所选73模块clean verify，避免旧字节码掩盖源码编译影响。

## 回归范围

五个测试模块合计2685项：2511通过、173跳过、0失败、1错误（RestTest网络权限拒绝）。独立门禁拒绝整体通过，门禁自身7/7。没有追加真库、GUI或新安装包验收，未推送远程。

红测日志：`/tmp/session-pid-read-red-20260929.log`；修复后日志：`/tmp/session-pid-read-clean-20260929.log`；[逐项脱敏证据](evidence/SESSION_PID_READ_REACTOR_20260929.json)。
