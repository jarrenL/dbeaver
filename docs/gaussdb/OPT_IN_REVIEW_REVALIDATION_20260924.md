# 真库可选用例启用复核

## 为什么重新启用

上一轮1517项结果中23项跳过，不等于23项都无法验证。本轮核对环境变量和前置条件，给集中式507隔离库提供`GAUSSDB_REVIEW_CONNECTION`和`GAUSSDB_REVIEW_JDBC`，启用2项包编译、4项调试会话测试。原生恢复选项仍启用，原历史集中式/分布式测试仍运行。

## 六项用例的实际范围

| 测试 | 核验 |
|---|---|
| realPackageCompileTargetsAndBodyDiagnosticLine | 真实包SPEC/BODY/ALL编译，强制保存错误源后，生产编译器从服务端目录读取SPEC/BODY错误并定位到含失败声明的源码行 |
| realMissingDiagnosticsDoesNotBecomeFakeSourceError | 将查询表名定向到不存在的测试表，真实42P01传播；不伪造源码编译错误 |
| lostCommitReplyIsUnknownAndNeverRetriedAlthoughDatabaseCommitted | 本地代理只丢弃COMMIT后的响应；独立连接确认数据已提交，客户端报告结果未确认且禁止再次提交 |
| blackholedCommitWithoutReadTimeoutBoundsCancellationAndClose | 无socket读取超时时，提交响应丢失后取消并关闭有界结束，不重复发送COMMIT |
| defaultOverloadValidationUsesRealCatalogAndRejectsAmbiguity | 真实目录中的默认参数函数可验证；存在同名其他签名后生产校验拒绝歧义 |
| productionBreakpointCommandsAndDeletedMarkerOperateOnRealDebugger | 生产断点增删启禁、并发调用、删除marker后的清理，真实服务端断点数量核验 |

这些是模型/协议与真实数据库组合测试，不替代按钮、快捷键、事务对话框及编译错误双击跳转GUI验收。

## 前置权限差异

首次run-Lz8QyA中两项包测试和三项会话测试通过，断点用例在DBE_PLDEBUGGER.turn_on被拒，错误为需要系统管理员。核对发现数据库存在专用`gs_role_pldebugger`角色，但隔离账号无成员授权。

本轮仅临时授予隔离测试账号专用调试角色，不授SYSADMIN；测试结束撤销本轮授权。因此未授权失败不能记为已通过，授权后的结果也不能证明任意普通用户都能调试。

## 最终结果

run-FkGkUy：1517项、1500通过、17跳过、0失败/错误，六项全部实际执行通过。结果见`test-results-opt-in-review-20260924.json`。独立gsql确认review前缀测试schema为0、专用调试角色成员授权为0，分布式测试grantee亦已删除。

本轮未修改生产实现，只补齐可选测试的执行证据。剩余17项仍需按环境/能力分别处理，未算通过；格式及Checkstyle检查未执行。
