# 包编译：部分诊断读取失败验证

## 场景与步骤

调用生产 `GaussDBPackageCompiler.readCompilationDiagnostics`，使用模拟 JDBC 会话控制故障时刻。先返回一条 BODY 诊断（第7行），再分别模拟：

| 场景 | 故障注入位置 | 预期与实际结果 |
| --- | --- | --- |
| 下一行读取失败 | 第二次 ResultSet.next | 保留第一条诊断，原始 SQLException 作为 DBException cause 传播，通过 |
| 后续字段读取失败 | 第二条诊断的 src 字段 | 不发布半条诊断，不伪造源码错误，保留第一条，通过 |
| 结果集关闭失败 | ResultSet.close | 不能将本次诊断读取视为正常完成，保留已有诊断及原始异常，通过 |
| 语句关闭失败 | PreparedStatement.close | 同上，通过 |

四项同时断言：诊断消息、BODY归属与行号不变；包OID=42、schema OID=99分别绑定；结果集和语句各关闭一次；不关闭调用者拥有的session，不主动刷新包状态。

## 结果与边界

- 新增4项全部通过；GaussDBPackageCompilerTest合计21/21，零跳过。未修改生产代码。
- 73模块诊断回归中五个测试模块合计2660项：2486通过、173跳过、0失败、1错误。计数包含上游测试，不是历史用例迁移数量。
- 唯一错误为RestTest.restClientServerTest的网络权限限制；独立门禁拒绝整体通过，门禁自身测试7/7通过。构建允许继续收集后续模块，其SUCCESS不等于验收通过。
- Docker API访问仍被拒绝。本轮没有新增GaussDB真库、Linux GUI或最终安装包验收；这里验证的是客户端诊断读取边界，不是服务端真实编译结果，也不覆盖公共compile入口的全部生命周期。

执行日志：`/tmp/package-partial-diagnostics-20260929.log`。可持久查看的脱敏逐项结果：[回归证据](evidence/PACKAGE_PARTIAL_DIAGNOSTICS_REACTOR_20260929.json)。
